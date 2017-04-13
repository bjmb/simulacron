package com.datastax.simulacron.common.codec;

import java.nio.ByteBuffer;
import java.util.function.Function;

public interface Decoder<T> extends Function<ByteBuffer, T> {}