/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.aeron.subscriber;

import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.aeron.Context;
import reactor.aeron.support.AeronInfra;
import reactor.aeron.support.SignalType;
import reactor.core.processor.BaseProcessor;
import reactor.core.support.ReactiveState;
import reactor.fn.Consumer;
import reactor.io.buffer.Buffer;


/**
 * @author Anatoly Kadyshev
 */
public class UnicastServiceMessageHandler implements ServiceMessageHandler, ReactiveState.LinkedDownstreams {

	private final static Logger logger = LoggerFactory.getLogger(UnicastServiceMessageHandler.class);

	private final BaseProcessor<Buffer, Buffer> processor;

	private final AeronInfra aeronInfra;

	private final Context context;

	/**
	 * Run when a terminal event is published into all established sessions
	 */
	private final Consumer<Void> onTerminalEventTask;

	private final SessionTracker<UnicastSession> sessionTracker;

	private final HeartbeatWatchdog heartbeatWatchdog;

	//TODO: Change to work with IPC as well
	private final Pattern SESSION_ID_PATTERN = Pattern.compile("(udp://.+:\\d+)/(\\d+)/(\\d+)");

	private class InnerSubscriber implements Subscriber<Buffer> {

		private final SignalSender signalSender;

		private final UnicastSession session;

		InnerSubscriber(UnicastSession session) {
			this.session = session;
			this.signalSender = createSignalSender();
		}

		@Override
		public void onSubscribe(Subscription s) {
			session.subscription = s;

			long demand = session.getAndResetDemad();
			if (demand > 0) {
				session.subscription.request(demand);
			}
		}

		@Override
		public void onNext(Buffer buffer) {
			signalSender.publishSignal(session.getSessionId(), session.getPublication(), buffer, SignalType.Next, true);
		}

		@Override
		public void onError(Throwable t) {
			session.setTerminal();

			Buffer buffer = Buffer.wrap(context.exceptionSerializer().serialize(t));
			signalSender.publishSignal(session.getSessionId(), session.getErrorPublication(), buffer,
					SignalType.Error, true);
		}

		@Override
		public void onComplete() {
			session.setTerminal();

			Buffer buffer = new Buffer(0, true);
			signalSender.publishSignal(session.getSessionId(), session.getPublication(), buffer,
					SignalType.Complete, true);
		}

	}

	public UnicastServiceMessageHandler(BaseProcessor<Buffer, Buffer> processor,
										AeronInfra aeronInfra,
										Context context,
										Consumer<Void> onTerminalEventTask) {
		this.processor = processor;
		this.aeronInfra = aeronInfra;
		this.context = context;
		this.onTerminalEventTask = onTerminalEventTask;
		this.sessionTracker = new BasicSessionTracker<>();
		this.heartbeatWatchdog = new HeartbeatWatchdog(context, this, sessionTracker);
	}

	protected SignalSender createSignalSender() {
		return new SignalSender(aeronInfra, context.errorConsumer());
	}

	@Override
	public void handleMore(String sessionId, long n) {
		UnicastSession session = getOrCreateSession(sessionId);

		session.requestMore(n);
		if (session.subscription != null) {
			long demand = session.getAndResetDemad();
			if (demand > 0) {
				session.subscription.request(demand);
			}
		}
	}

	@Override
	public void handleHeartbeat(String sessionId) {
		UnicastSession session = getOrCreateSession(sessionId);
		session.setLastHeartbeatTimeNs(System.nanoTime());
	}

	@Override
	public void handleCancel(String sessionId) {
		UnicastSession session = sessionTracker.remove(sessionId);
		if (session != null) {
			boolean isTerminal = session.isTerminal();

			cancel(session);

			if (isTerminal && sessionTracker.getSessionCounter() == 0) {
				onTerminalEventTask.accept(null);
			}

		} else {
			//TODO: Handle
		}
	}

	@Override
	public void start() {
		heartbeatWatchdog.start();
	}

	@Override
	public void shutdown() {
		heartbeatWatchdog.shutdown();

		sendCompleteIntoNonTerminalSessions();
		closeAllSessions();
	}

	private void sendCompleteIntoNonTerminalSessions() {
		SignalSender signalSender = new SignalSender(aeronInfra, context.errorConsumer());
		Buffer buffer = new Buffer(0, true);
		for (UnicastSession session: sessionTracker.getSessions()) {
			if (!session.isTerminal()) {
				session.setTerminal();
				signalSender.publishSignal(session.getSessionId(), session.getPublication(), buffer,
						SignalType.Complete, true);
			}
		}
	}

	private void closeAllSessions() {
		for (UnicastSession session: sessionTracker.getSessions()) {
			cancel(session);
		}
	}

	private UnicastSession createSession(String sessionId) {
		Matcher matcher = SESSION_ID_PATTERN.matcher(sessionId);
		int streamId;
		int errorStreamId;
		String receiverChannel;
		if (matcher.matches()) {
			receiverChannel = matcher.group(1);
			streamId = Integer.parseInt(matcher.group(2));
			errorStreamId = Integer.parseInt(matcher.group(3));
		} else {
			throw new IllegalArgumentException("Malformed unicast sessionId: " + sessionId);
		}

		return new UnicastSession(
				sessionId,
				aeronInfra.addPublication(receiverChannel, streamId),
				aeronInfra.addPublication(receiverChannel, errorStreamId));
	}

	private void cancel(UnicastSession session) {
		if (session.subscription != null) {
			session.subscription.cancel();
		} else {
			//TODO: Implement
		}

		aeronInfra.close(session.getPublication());
		aeronInfra.close(session.getErrorPublication());

		if (logger.isDebugEnabled()) {
			logger.debug("Closed session with sessionId: {}", session.getSessionId());
		}
	}

	public UnicastSession getOrCreateSession(String sessionId) {
		UnicastSession session = sessionTracker.get(sessionId);
		if (session == null) {
			session = createSession(sessionId);
			sessionTracker.put(sessionId, session);

			processor.subscribe(new InnerSubscriber(session));

			if (logger.isDebugEnabled()) {
				logger.debug("New session established with sessionId: {}", sessionId);
			}
		}
		return session;
	}

	@Override
	public Iterator<?> downstreams() {
		return sessionTracker.getSessions().iterator();
	}

	@Override
	public long downstreamsCount() {
		return sessionTracker.getSessionCounter();
	}

}
