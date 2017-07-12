package com.datastax.simulacron.common.cluster;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class QueryLog {

  @JsonProperty("query")
  private String query;

  @JsonProperty("consistency_level")
  private String consistency;

  @JsonProperty("serial_consistency_level")
  private String serialConsistency;

  @JsonProperty("connection")
  private String connection;

  @JsonProperty("timestamp")
  private long timestamp;

  @JsonProperty("primed")
  private boolean primed;

  @JsonCreator
  public QueryLog(
      @JsonProperty("query") String query,
      @JsonProperty("consistency_level") String consistency,
      @JsonProperty("serial_consistency_level") String serialConsistency,
      @JsonProperty("connection") String connection,
      @JsonProperty("timestamp") long timestamp,
      @JsonProperty("primed") boolean primed) {
    this.query = query;
    this.consistency = consistency;
    this.serialConsistency = serialConsistency;
    this.connection = connection;
    this.timestamp = timestamp;
    this.primed = primed;
  }

  public String getQuery() {
    return query;
  }

  public String getConsistency() {
    return consistency;
  }

  public String getSerialConsistency() {
    return serialConsistency;
  }

  public String getConnection() {
    return connection;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public boolean isPrimed() {
    return primed;
  }
}
