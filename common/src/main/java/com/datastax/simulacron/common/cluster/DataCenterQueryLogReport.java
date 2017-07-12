package com.datastax.simulacron.common.cluster;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;

import java.util.Collection;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Represent a class that contains the QueryLogs of a particular datacenter. It's useful for
 * encoding the results with JSON.
 */
@JsonIgnoreProperties(value = {"name"})
public class DataCenterQueryLogReport extends QueryLogReport {
  @JsonManagedReference private final Collection<NodeQueryLogReport> nodes = new TreeSet<>();

  @JsonBackReference private final ClusterQueryLogReport parent;

  DataCenterQueryLogReport() {
    // Default constructor for jackson deserialization.
    this(null, null);
  }

  public DataCenterQueryLogReport(Long id, ClusterQueryLogReport clusterReport) {
    super(id);
    this.parent = clusterReport;
    if (parent != null) {
      parent.addDataCenter(this);
    }
  }

  void addNode(NodeQueryLogReport node) {
    this.nodes.add(node);
  }

  public Collection<NodeQueryLogReport> getNodes() {
    return nodes;
  }

  @Override
  public Optional<AbstractReport> getParent() {
    return Optional.ofNullable(parent);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    DataCenterQueryLogReport that = (DataCenterQueryLogReport) o;
    if (!(this.getId().equals(that.getId()))) {
      return false;
    }
    return parent.equals(that.parent);
  }

  @Override
  public int hashCode() {
    return nodes != null ? nodes.hashCode() : 0;
  }

  @Override
  public ClusterQueryLogReport getRootReport() {
    return parent;
  }
}
