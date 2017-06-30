package com.datastax.simulacron.common.cluster;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Represents a cluster, which contains {@link DataCenter}s which contains {@link Node}s. */
public class Cluster extends AbstractNodeProperties implements Closeable {

  // json managed reference is used to indicate a two way linking between the 'parent' (cluster) and 'children'
  // (datacenters) in a json tree.  This tells the jackson mapping to tie child DCs to this cluster on deserialization.
  @JsonManagedReference
  @JsonProperty("data_centers")
  private final Collection<DataCenter> dataCenters = new ConcurrentSkipListSet<>();

  @JsonIgnore private final transient AtomicLong dcCounter = new AtomicLong(0);

  @JsonIgnore private final transient ActivityLog activityLog = new ActivityLog();

  Cluster() {
    // Default constructor for jackson deserialization.
    this(null, null, null, null, Collections.emptyMap());
  }

  public Cluster(
      String name,
      Long id,
      String cassandraVersion,
      String dseVersion,
      Map<String, Object> peerInfo) {
    super(name, id, cassandraVersion, dseVersion, peerInfo);
  }

  @Override
  public Long getActiveConnections() {
    return dataCenters.stream().mapToLong(NodeProperties::getActiveConnections).sum();
  }

  @Override
  @JsonIgnore
  public Cluster getCluster() {
    return this;
  }

  /** @return The {@link DataCenter}s belonging to this cluster. */
  public Collection<DataCenter> getDataCenters() {
    return dataCenters;
  }

  /**
   * @return All nodes belonging to {@link DataCenter}s in this cluster. Note that this method
   *     builds a new collection on every invocation, so it is recommended to not call this
   *     repeatedly without a good reason.
   */
  @JsonIgnore
  public List<Node> getNodes() {
    return dataCenters.stream().flatMap(dc -> dc.getNodes().stream()).collect(Collectors.toList());
  }

  /**
   * Convenience method to find the DataCenter with the given id.
   *
   * @param id id of the data center.
   * @return the data center if found or null.
   */
  public DataCenter dc(long id) {
    return dataCenters.stream().filter(dc -> dc.getId() == id).findFirst().orElse(null);
  }

  /**
   * Convenience method to find the Node with the given DataCenter id and node id.
   *
   * @param dcId id of the data center.
   * @param nodeId id of the node.
   * @return the node if found or null
   */
  public Node node(long dcId, long nodeId) {
    DataCenter dc = dc(dcId);
    if (dc != null) {
      return dc.node(nodeId);
    } else {
      return null;
    }
  }

  /**
   * Convenience method to find the Node in DataCenter 0 with the given id. This is a shortcut for
   * <code>node(0, X)</code> as it is common for clusters to only have 1 dc.
   *
   * @param nodeId id of the node.
   * @return the node if found in dc 0 or null
   */
  public Node node(long nodeId) {
    return node(0, nodeId);
  }

  /**
   * Intended to be called in {@link DataCenter} construction to add the {@link DataCenter} to this
   * cluster.
   *
   * @param dataCenter The data center to tie to this cluster.
   */
  void addDataCenter(DataCenter dataCenter) {
    assert dataCenter.getParent().orElse(null) == this;
    this.dataCenters.add(dataCenter);
  }

  /**
   * Constructs a builder for a {@link DataCenter} that will be added to this cluster. On
   * construction the created {@link DataCenter} will be added to this cluster.
   *
   * @return a Builder to create a {@link DataCenter} in this cluster.
   */
  public DataCenter.Builder addDataCenter() {
    return new DataCenter.Builder(this, dcCounter.getAndIncrement());
  }

  /** @return A builder for making a Cluster. */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String toString() {
    return toStringWith(
        ", dataCenters="
            + dataCenters.stream().map(d -> d.getId().toString()).collect(Collectors.joining(",")));
  }

  @Override
  @JsonIgnore
  public Optional<NodeProperties> getParent() {
    return Optional.empty();
  }

  @Override
  public void close() throws IOException {
    // by default does nothing, the expectation is implementations that are bound to a Server
    // will unregister the Cluster when done with it.
  }

  public static class Builder extends ClusterBuilderBase<Builder, Cluster> {

    Builder() {
      super(Builder.class);
    }

    @Override
    public Cluster baseCluster(
        String name,
        Long id,
        String cassandraVersion,
        String dseVersion,
        Map<String, Object> peerInfo) {
      return new Cluster(name, id, cassandraVersion, dseVersion, peerInfo);
    }
  }

  @JsonIgnore
  public ActivityLog getActivityLog() {
    return this.activityLog;
  }
}
