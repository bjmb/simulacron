package com.datastax.simulacron.common.cluster;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Represents a {@link Node} which may belong to a {@link Cluster} (via a {@link DataCenter}
 * relationship) or it may be standalone to represent a 'single node' cluster.
 *
 * <p>A {@link Node} has an address which indicates what ip address and port the node is listening
 * on.
 */
public class Node extends AbstractNodeProperties implements Closeable {

  /** The address and port that this node should listen on. */
  @JsonProperty private final SocketAddress address;

  @JsonBackReference private final DataCenter parent;

  Node() {
    // Default constructor for jackson deserialization.
    this(null, null, null, null, null, Collections.emptyMap(), null);
  }

  public Node(
      SocketAddress address,
      String name,
      Long id,
      String cassandraVersion,
      String dseVersion,
      Map<String, Object> peerInfo,
      DataCenter parent) {
    super(name, id, cassandraVersion, dseVersion, peerInfo);
    this.address = address;
    this.parent = parent;
    if (this.parent != null) {
      parent.addNode(this);
    }
  }

  /** @return The address and port that this node should listen on. */
  public SocketAddress getAddress() {
    return address;
  }

  /**
   * Convenience method to get access {@link #getAddress()} as {@link InetSocketAddress} instance
   * which it almost always is (except in testing scenarios.
   *
   * @return address as an {@link InetSocketAddress}
   */
  public InetSocketAddress inetSocketAddress() {
    if (address instanceof InetSocketAddress) {
      return (InetSocketAddress) address;
    }
    return null;
  }

  /**
   * Convenience method to access the {@link InetAddress} part of {@link #getAddress()} if it is an
   * {@link InetSocketAddress}.
   *
   * @return address as an {@link InetAddress}
   */
  public InetAddress inet() {
    InetSocketAddress addr = inetSocketAddress();
    if (addr != null) {
      return addr.getAddress();
    }
    return null;
  }

  /**
   * Convenience method to access the port part of {@link #getAddress()} if it is an {@link
   * InetSocketAddress}.
   *
   * @return port
   */
  public int port() {
    InetSocketAddress addr = inetSocketAddress();
    if (addr != null) {
      return addr.getPort();
    }
    return -1;
  }

  /**
   * @return The {@link DataCenter} this node belongs to, otherwise null if it does not have one.
   */
  @JsonIgnore
  public DataCenter getDataCenter() {
    return parent;
  }

  /**
   * @return The {@link Cluster} associated this node belongs to, otherwise null if it does not
   *     belong to one.
   */
  @JsonIgnore
  @Override
  public Cluster getCluster() {
    return Optional.ofNullable(parent).map(DataCenter::getCluster).orElse(null);
  }

  @Override
  public String toString() {
    return toStringWith(", address=" + address);
  }

  /**
   * Constructs a {@link Builder} for defining a {@link Node} that has no parent {@link Cluster}.
   *
   * @return Builder for creating {@link Node}
   */
  public static Builder builder() {
    return new Builder(null, null);
  }

  @Override
  @JsonIgnore
  public Optional<NodeProperties> getParent() {
    return Optional.ofNullable(parent);
  }

  @Override
  public Long getActiveConnections() {
    // In the case of a concrete 'Node' instance, active connections will always be 0 since there is no actual
    // connection state here.  We expect specialized implementations of Node to override this.
    return 0L;
  }

  @Override
  public void close() throws IOException {
    // by default does nothing, the expectation is implementations that are bound to a Server
    // will unregister the Node when done with it.
  }

  public static class Builder extends NodePropertiesBuilder<Builder, DataCenter> {

    private SocketAddress address = null;

    Builder(DataCenter parent, Long id) {
      super(Builder.class, parent);
      this.id = id;
    }

    /**
     * Sets the address that the Node should be listening on.
     *
     * @param address address to listen on
     * @return this builder
     */
    public Builder withAddress(SocketAddress address) {
      this.address = address;
      return this;
    }

    /** @return Constructs a {@link Node} from this builder. Can be called multiple times. */
    public Node build() {
      return new Node(address, name, id, cassandraVersion, dseVersion, peerInfo, parent);
    }
  }
}
