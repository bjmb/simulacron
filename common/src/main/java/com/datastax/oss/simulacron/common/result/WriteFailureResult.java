/*
 * Copyright (C) 2017-2017 DataStax Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.simulacron.common.result;

import static com.datastax.oss.protocol.internal.ProtocolConstants.ErrorCode.WRITE_FAILURE;

import com.datastax.oss.protocol.internal.Message;
import com.datastax.oss.protocol.internal.response.error.WriteFailure;
import com.datastax.oss.simulacron.common.codec.ConsistencyLevel;
import com.datastax.oss.simulacron.common.codec.RequestFailureReason;
import com.datastax.oss.simulacron.common.codec.WriteType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.InetAddress;
import java.util.Map;

public class WriteFailureResult extends RequestFailureResult {

  @JsonProperty("write_type")
  private final WriteType writeType;

  public WriteFailureResult(
      ConsistencyLevel cl,
      int received,
      int blockFor,
      Map<InetAddress, RequestFailureReason> failureReasonByEndpoint,
      WriteType writeType) {
    this(cl, received, blockFor, failureReasonByEndpoint, writeType, 0, false);
  }

  @JsonCreator
  public WriteFailureResult(
      @JsonProperty(value = "consistency_level", required = true) ConsistencyLevel cl,
      @JsonProperty(value = "received", required = true) int received,
      @JsonProperty(value = "block_for", required = true) int blockFor,
      @JsonProperty(value = "failure_reasons", required = true)
          Map<InetAddress, RequestFailureReason> failureReasonByEndpoint,
      @JsonProperty(value = "write_type", required = true) WriteType writeType,
      @JsonProperty("delay_in_ms") long delayInMs,
      @JsonProperty("ignore_on_prepare") boolean ignoreOnPrepare) {
    super(
        WRITE_FAILURE, cl, received, blockFor, failureReasonByEndpoint, delayInMs, ignoreOnPrepare);
    this.writeType = writeType;
  }

  @Override
  public Message toMessage() {
    return new WriteFailure(
        errorMessage,
        cl.getCode(),
        received,
        blockFor,
        failureReasonByEndpoint.size(),
        toIntMap(failureReasonByEndpoint),
        writeType.name());
  }
}
