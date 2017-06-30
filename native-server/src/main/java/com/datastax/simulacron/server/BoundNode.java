package com.datastax.simulacron.server;

import com.datastax.oss.protocol.internal.Frame;
import com.datastax.oss.protocol.internal.Message;
import com.datastax.oss.protocol.internal.request.*;
import com.datastax.oss.protocol.internal.response.Ready;
import com.datastax.oss.protocol.internal.response.Supported;
import com.datastax.oss.protocol.internal.response.error.Unprepared;
import com.datastax.oss.protocol.internal.response.result.SetKeyspace;
import com.datastax.simulacron.common.cluster.DataCenter;
import com.datastax.simulacron.common.cluster.Node;
import com.datastax.simulacron.common.stubbing.*;
import com.datastax.simulacron.common.stubbing.PrimeDsl.PrimeBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.datastax.oss.protocol.internal.response.result.Void.INSTANCE;
import static com.datastax.simulacron.common.stubbing.DisconnectAction.Scope.CLUSTER;
import static com.datastax.simulacron.common.stubbing.DisconnectAction.Scope.NODE;
import static com.datastax.simulacron.common.stubbing.PrimeDsl.noRows;
import static com.datastax.simulacron.common.stubbing.PrimeDsl.when;
import static com.datastax.simulacron.common.utils.FrameUtils.wrapResponse;
import static com.datastax.simulacron.server.ChannelUtils.completable;

class BoundNode extends Node {

  private static Logger logger = LoggerFactory.getLogger(BoundNode.class);

  private static final Pattern useKeyspacePattern =
      Pattern.compile("\\s*use\\s+(.*)$", Pattern.CASE_INSENSITIVE);

  private final ServerBootstrap bootstrap;

  // TODO: Isn't really a good reason for this to be an AtomicReference as if binding fails we don't reset
  // the channel, but leaving it this way for now in case there is a future use case.
  final transient AtomicReference<Channel> channel;

  final transient ChannelGroup clientChannelGroup =
      new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

  // TODO: There could be a lot of concurrency issues around simultaneous calls to reject/accept, however
  // in the general case we don't expect it.   Leave this as AtomicReference in case we want to handle it better.
  private final AtomicReference<RejectState> rejectState = new AtomicReference<>(new RejectState());

  private final Timer timer;

  private final transient StubStore stubStore;

  private final boolean activityLogging;

  private final Server<?> server;

  private static class RejectState {
    private final RejectScope scope;
    private volatile int rejectAfter;
    private volatile boolean listeningForNewConnections;

    RejectState() {
      this(true, Integer.MIN_VALUE, null);
    }

    RejectState(boolean listeningForNewConnections, int rejectAfter, RejectScope scope) {
      this.listeningForNewConnections = listeningForNewConnections;
      this.rejectAfter = rejectAfter;
      this.scope = scope;
    }
  }

  BoundNode(
      SocketAddress address,
      String name,
      Long id,
      String cassandraVersion,
      String dseVersion,
      Map<String, Object> peerInfo,
      DataCenter parent,
      Server<?> server,
      Timer timer,
      Channel channel,
      StubStore stubStore,
      boolean activityLogging) {
    super(address, name, id, cassandraVersion, dseVersion, peerInfo, parent);
    this.server = server;
    // for test purposes server may be null.
    this.bootstrap = server != null ? server.serverBootstrap : null;
    this.timer = timer;
    this.channel = new AtomicReference<>(channel);
    this.stubStore = stubStore;
    this.activityLogging = activityLogging;
  }

  @Override
  public Long getActiveConnections() {
    // Filter only active channels as some may be in process of closing.
    return clientChannelGroup.stream().filter(Channel::isActive).count();
  }

  public ChannelGroup getClientChannelGroup() {
    return clientChannelGroup;
  }

  /**
   * Closes the listening channel for this node. Note that this does not close existing client
   * connections, this can be done using {@link #disconnectConnections()}. To stop listening and
   * close connections, use {@link #close()}.
   *
   * @return future that completes when listening channel is closed.
   */
  CompletableFuture<Node> unbind() {
    logger.debug("Unbinding listener on {}", channel);
    return completable(channel.get().close()).thenApply(v -> this);
  }

  /**
   * Reopens the listening channel for this node. If the channel was already open, has no effect and
   * future completes immediately.
   *
   * @return future that completes when listening channel is reopened.
   */
  CompletableFuture<Node> rebind() {
    if (this.channel.get().isOpen()) {
      // already accepting...
      return CompletableFuture.completedFuture(this);
    }
    CompletableFuture<Node> future = new CompletableFuture<>();
    ChannelFuture bindFuture = bootstrap.bind(this.getAddress());
    bindFuture.addListener(
        (ChannelFutureListener)
            channelFuture -> {
              if (channelFuture.isSuccess()) {
                channelFuture.channel().attr(Server.HANDLER).set(this);
                logger.debug("Bound {} to {}", BoundNode.this, channelFuture.channel());
                future.complete(BoundNode.this);
                channel.set(channelFuture.channel());
              } else {
                // If failed, propagate it.
                future.completeExceptionally(
                    new BindNodeException(BoundNode.this, getAddress(), channelFuture.cause()));
              }
            });
    return future;
  }

  /**
   * Closes both the listening channel and all existing client channels.
   *
   * @return future that completes when listening channel and client channels are all closed.
   */
  CompletableFuture<Node> unbindAndClose() {
    return unbind().thenCombine(disconnectConnections(), (n0, n1) -> this);
  }

  /**
   * Disconnects all client channels. Does not close listening interface (see {@link #unbind()} for
   * that).
   *
   * @return future that completes when all client channels are disconnected.
   */
  CompletableFuture<Node> disconnectConnections() {
    return completable(clientChannelGroup.disconnect()).thenApply(v -> this);
  }

  /**
   * Indicates that the node should resume accepting connections.
   *
   * @return future that completes when node is listening again.
   */
  CompletableFuture<Node> acceptNewConnections() {
    logger.debug("Accepting New Connections");
    rejectState.set(new RejectState());
    // Reopen listening interface if not currently open.
    if (!channel.get().isOpen()) {
      return rebind();
    } else {
      return CompletableFuture.completedFuture(this);
    }
  }

  /**
   * Indicates that the node should stop accepting new connections.
   *
   * @param after If non-zero, after how many successful startup messages should stop accepting
   *     connections.
   * @param scope The scope to reject connections, either stop listening for connections, or accept
   *     connections but don't respond to startup requests.
   * @return future that completes when listening channel is unbound (if {@link RejectScope#UNBIND}
   *     was used) or immediately if {@link RejectScope#REJECT_STARTUP} was used or after > 0.
   */
  CompletableFuture<Node> rejectNewConnections(int after, RejectScope scope) {
    RejectState state;
    if (after <= 0) {
      logger.debug("Rejecting new connections with scope {}", scope);
      state = new RejectState(false, Integer.MIN_VALUE, scope);
    } else {
      logger.debug("Rejecting new connections after {} attempts with scope {}", after, scope);
      state = new RejectState(true, after, scope);
    }
    rejectState.set(state);
    if (after <= 0 && scope != RejectScope.REJECT_STARTUP) {
      CompletableFuture<Node> unbindFuture = unbind();
      // if scope is STOP, disconnect existing connections after unbinding.
      if (scope == RejectScope.STOP) {
        return unbindFuture.thenCompose(n -> disconnectConnections());
      } else {
        return unbindFuture;
      }
    } else {
      return CompletableFuture.completedFuture(this);
    }
  }

  void handle(ChannelHandlerContext ctx, Frame frame) {
    logger.debug("Got request streamId: {} msg: {}", frame.streamId, frame.message);
    // On receiving a message, first check the stub store to see if there is handling logic for it.
    // If there is, handle each action.
    // Otherwise delegate to default behavior.
    Optional<StubMapping> stubOption = stubStore.find(this, frame);
    List<Action> actions = null;
    if (stubOption.isPresent()) {
      StubMapping stub = stubOption.get();
      actions = stub.getActions(this, frame);
    }

    //store the frame in history
    if (activityLogging) {
      getCluster()
          .getActivityLog()
          .addLog(
              this, frame, ctx.channel().remoteAddress(), stubOption, System.currentTimeMillis());
    }

    if (actions != null && !actions.isEmpty()) {
      // TODO: It might be useful to tie behavior to completion of actions but for now this isn't necessary.
      CompletableFuture<Void> future = new CompletableFuture<>();
      handleActions(actions.iterator(), ctx, frame, future);
    } else {
      // Future that if set defers sending the message until the future completes.
      CompletableFuture<?> deferFuture = null;
      Message response = null;
      if (frame.message instanceof Startup || frame.message instanceof Register) {
        RejectState state = rejectState.get();
        // We aren't listening for new connections, return immediately.
        if (!state.listeningForNewConnections) {
          return;
        } else if (state.rejectAfter > 0) {
          // Decrement rejectAfter indicating a new initialization attempt.
          state.rejectAfter--;
          if (state.rejectAfter == 0) {
            // If reject after is now 0, indicate that it's time to stop listening (but allow this one)
            state.rejectAfter = -1;
            state.listeningForNewConnections = false;
            deferFuture = rejectNewConnections(-1, state.scope);
          }
        }
        response = new Ready();
      } else if (frame.message instanceof Options) {
        // Maybe eventually we can set these depending on the version but so far it looks
        // like this.cassandraVersion and this.dseVersion are both null
        HashMap<String, List<String>> options = new HashMap<>();
        options.put("PROTOCOL_VERSIONS", Arrays.asList("3/v3", "4/v4", "5/v5-beta"));
        options.put("CQL_VERSION", Collections.singletonList("3.4.4"));
        options.put("COMPRESSION", Arrays.asList("snappy", "lz4"));

        response = new Supported(options);
      } else if (frame.message instanceof Query) {
        Query query = (Query) frame.message;
        String queryStr = query.query;
        if (queryStr.startsWith("USE") || queryStr.startsWith("use")) {
          Matcher matcher = useKeyspacePattern.matcher(queryStr);
          // should always match.
          assert matcher.matches();
          if (matcher.matches()) {
            String keyspace = matcher.group(1);
            response = new SetKeyspace(keyspace);
          }
        } else {
          response = INSTANCE;
        }
      } else if (frame.message instanceof Execute) {
        // Unprepared execute received, return an unprepared.
        Execute execute = (Execute) frame.message;
        String hex = new BigInteger(1, execute.queryId).toString(16);
        response = new Unprepared("No prepared statement with id: " + hex, execute.queryId);
      } else if (frame.message instanceof Prepare) {
        // fake up a prepared statement from the message and register an internal prime for it.
        Prepare prepare = (Prepare) frame.message;
        // TODO: Maybe attempt to identify bind parameters
        String query = prepare.cqlQuery;
        Prime prime =
            whenWithInferedParams(query).then(noRows()).forCluster(this.getCluster()).build();
        this.stubStore.registerInternal(prime);
        response = prime.toPrepared();
      }
      if (response != null) {
        if (deferFuture != null) {
          final Message fResponse = response;
          deferFuture.thenRun(() -> sendMessage(ctx, frame, fResponse));
        } else {
          sendMessage(ctx, frame, response);
        }
      }
    }
  }

  private void handleActions(
      Iterator<Action> nextActions,
      ChannelHandlerContext ctx,
      Frame frame,
      CompletableFuture<Void> doneFuture) {
    // If there are no more actions, complete the done future and return.
    if (!nextActions.hasNext()) {
      doneFuture.complete(null);
      return;
    }

    CompletableFuture<Void> future = new CompletableFuture<>();
    Action action = nextActions.next();
    ActionHandler handler = new ActionHandler(action, ctx, frame, future);
    if (action.delayInMs() > 0) {
      timer.newTimeout(handler, action.delayInMs(), TimeUnit.MILLISECONDS);
    } else {
      // process immediately when delay is 0.
      handler.run(null);
    }

    // proceed to next action when complete
    future.whenComplete(
        (v, ex) -> {
          if (ex != null) {
            doneFuture.completeExceptionally(ex);
          } else {
            handleActions(nextActions, ctx, frame, doneFuture);
          }
        });
  }

  private class ActionHandler implements TimerTask {

    private final Action action;
    private final ChannelHandlerContext ctx;
    private final Frame frame;
    private final CompletableFuture<Void> doneFuture;

    ActionHandler(
        Action action, ChannelHandlerContext ctx, Frame frame, CompletableFuture<Void> doneFuture) {
      this.action = action;
      this.ctx = ctx;
      this.frame = frame;
      this.doneFuture = doneFuture;
    }

    @Override
    public void run(Timeout timeout) {
      CompletableFuture<Void> future;
      // TODO maybe delegate this logic elsewhere
      if (action instanceof MessageResponseAction) {
        MessageResponseAction mAction = (MessageResponseAction) action;
        future = completable(sendMessage(ctx, frame, mAction.getMessage()));
      } else if (action instanceof DisconnectAction) {
        DisconnectAction cAction = (DisconnectAction) action;
        switch (cAction.getScope()) {
          case CONNECTION:
            switch (cAction.getCloseType()) {
              case DISCONNECT:
                future = completable(ctx.disconnect());
                break;
              default:
                Function<SocketChannel, ChannelFuture> shutdownMethod =
                    cAction.getCloseType() == CloseType.SHUTDOWN_READ
                        ? SocketChannel::shutdownInput
                        : SocketChannel::shutdownOutput;

                Channel c = ctx.channel();
                if (c instanceof SocketChannel) {
                  future = completable(shutdownMethod.apply(((SocketChannel) c)));
                } else {
                  logger.warn(
                      "Got {} request for non-SocketChannel {}, disconnecting instead.",
                      cAction.getCloseType(),
                      c);
                  future = completable(ctx.disconnect());
                }
                break;
            }
            break;
          default:
            Stream<Node> nodes =
                cAction.getScope() == NODE
                    ? Stream.of(BoundNode.this)
                    : cAction.getScope() == CLUSTER
                        ? getCluster().getNodes().stream()
                        : getDataCenter().getNodes().stream();
            future = closeNodes(nodes, cAction.getCloseType());
            break;
        }
      } else if (action instanceof NoResponseAction) {
        future = new CompletableFuture<>();
        future.complete(null);
      } else {
        logger.warn("Got action {} that we don't know how to handle.", action);
        future = new CompletableFuture<>();
        future.complete(null);
      }

      future.whenComplete(
          (v, t) -> {
            if (t != null) {
              doneFuture.completeExceptionally(t);
            } else {
              doneFuture.complete(v);
            }
          });
    }
  }

  public static CompletableFuture<Void> closeChannelGroups(
      Stream<ChannelGroup> channels, CloseType closeType) {
    List<CompletableFuture<Void>> futures = null;
    switch (closeType) {
      case DISCONNECT:
        futures = channels.map(chs -> completable(chs.disconnect())).collect(Collectors.toList());
        break;
      case SHUTDOWN_READ:
      case SHUTDOWN_WRITE:
        futures =
            channels
                .flatMap(Collection::stream)
                .map(
                    c -> {
                      CompletableFuture<Void> f;
                      Function<SocketChannel, ChannelFuture> shutdownMethod =
                          closeType == CloseType.SHUTDOWN_READ
                              ? SocketChannel::shutdownInput
                              : SocketChannel::shutdownOutput;
                      if (c instanceof SocketChannel) {
                        f = completable(shutdownMethod.apply((SocketChannel) c));
                      } else {
                        logger.warn(
                            "Got {} request for non-SocketChannel {}, disconnecting instead.",
                            closeType,
                            c);
                        f = completable(c.disconnect());
                      }
                      return f;
                    })
                .collect(Collectors.toList());
        break;
    }
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[] {}));
  }

  public static CompletableFuture<Void> closeNodes(Stream<Node> nodes, CloseType closeType) {
    return closeChannelGroups(nodes.map(n -> ((BoundNode) n).getClientChannelGroup()), closeType);
  }

  private ChannelFuture sendMessage(
      ChannelHandlerContext ctx, Frame requestFrame, Message responseMessage) {
    Frame responseFrame = wrapResponse(requestFrame, responseMessage);
    logger.debug(
        "Sending response for streamId: {} with msg {}",
        responseFrame.streamId,
        responseFrame.message);
    return ctx.writeAndFlush(responseFrame);
  }

  @Override
  public void close() throws IOException {
    if (this.getCluster() != null) {
      this.getCluster().close();
    }
  }

  /**
   * Convenience fluent builder for constructing a prime with a query, where the parameters are
   * inferred by the query
   *
   * @param query The query string to match against.
   * @return builder for this prime.
   */
  private static PrimeBuilder whenWithInferedParams(String query) {
    long posParamCount = query.chars().filter(num -> num == '?').count();

    // Do basic param population for positional types
    HashMap<String, String> paramTypes = new HashMap<>();
    HashMap<String, Object> params = new HashMap<>();
    if (posParamCount > 0) {
      for (int i = 0; i < posParamCount; i++) {
        params.put(Integer.toString(i), "*");
        paramTypes.put(Integer.toString(i), "varchar");
      }
    }
    // Do basic param population for named types
    else {
      List<String> allMatches = new ArrayList<String>();
      Pattern p = Pattern.compile("([\\w']+)\\s=\\s:[\\w]+");
      Matcher m = p.matcher(query);
      while (m.find()) {
        allMatches.add(m.group(1));
      }
      for (String match : allMatches) {
        params.put(match, "*");
        paramTypes.put(match, "varchar");
      }
    }
    return when(
        new com.datastax.simulacron.common.request.Query(
            query, Collections.emptyList(), params, paramTypes));
  }
}
