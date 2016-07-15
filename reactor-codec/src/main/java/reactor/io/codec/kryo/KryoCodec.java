/*
 * Copyright (c) 2011-2014 Pivotal Software, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package reactor.io.codec.kryo;

import java.util.function.Function;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.UnsafeMemoryInput;
import com.esotericsoftware.kryo.io.UnsafeMemoryOutput;
import reactor.core.Reactor;
import reactor.io.codec.SerializationCodec;

/**
 * @author Jon Brisbin
 */
public class KryoCodec<IN, OUT> extends SerializationCodec<Kryo, IN, OUT> {

	public KryoCodec() {
		super(new Kryo(), true);
	}

	public KryoCodec(Kryo engine, boolean lengthFieldFraming) {
		super(engine, lengthFieldFraming);
	}

	@Override
	protected Function<byte[], IN> deserializer(final Kryo engine,
	                                            final Class<IN> type) {
		return new Function<byte[], IN>() {
			@Override
			public IN apply(byte[] bytes) {
				return engine.readObject(new UnsafeMemoryInput(bytes), type);
			}
		};
	}

	@Override
	protected Function<OUT, byte[]> serializer(final Kryo engine) {
		return new Function<OUT, byte[]>() {
			@Override
			public byte[] apply(OUT o) {
				UnsafeMemoryOutput out = new UnsafeMemoryOutput(Reactor.SMALL_IO_BUFFER_SIZE, Reactor.SMALL_IO_BUFFER_SIZE);
				engine.writeObject(out, o);
				out.flush();
				return out.toBytes();
			}
		};
	}

}
