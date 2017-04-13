package com.datastax.simulacron.server;

import com.datastax.oss.protocol.internal.Frame;
import com.datastax.oss.protocol.internal.Message;
import com.datastax.oss.protocol.internal.request.Options;
import com.datastax.oss.protocol.internal.request.Query;
import com.datastax.oss.protocol.internal.request.Register;
import com.datastax.oss.protocol.internal.request.Startup;
import com.datastax.oss.protocol.internal.response.Ready;
import com.datastax.oss.protocol.internal.response.Supported;
import com.datastax.oss.protocol.internal.response.result.SetKeyspace;
import com.datastax.oss.protocol.internal.response.result.Void;
import com.datastax.simulacron.common.cluster.DataCenter;
import com.datastax.simulacron.common.cluster.Node;
import com.datastax.simulacron.common.stubbing.Action;
import com.datastax.simulacron.common.stubbing.MessageResponseAction;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.datastax.simulacron.common.utils.FrameUtils.wrapResponse;

public class BoundNode extends Node {

  private static Logger logger = LoggerFactory.getLogger(BoundNode.class);

  private static final Pattern useKeyspacePattern =
      Pattern.compile("\\s*use\\s+(.*)$", Pattern.CASE_INSENSITIVE);

  final transient Channel channel;

  private final transient StubStore stubStore;

  BoundNode(
      SocketAddress address,
      String name,
      Long id,
      String cassandraVersion,
      Map<String, Object> peerInfo,
      DataCenter parent,
      Channel channel,
      StubStore stubStore) {
    super(address, name, id, cassandraVersion, peerInfo, parent);
    this.channel = channel;
    this.stubStore = stubStore;
  }

  void handle(ChannelHandlerContext ctx, Frame frame) {
    logger.debug("Got request streamId: {} msg: {}", frame.streamId, frame.message);

    // On receiving a message, first check the stub store to see if there is handling logic for it.
    // If there is, handle each action.
    // Otherwise delegate to default behavior.
    List<Action> actions = stubStore.handle(this, frame);
    if (actions.size() != 0) {
      for (Action action : actions) {
        // TODO handle delay
        // TODO maybe delegate this logic elsewhere
        if (action instanceof MessageResponseAction) {
          MessageResponseAction mAction = (MessageResponseAction) action;
          sendMessage(ctx, frame, mAction.getMessage());
        }
      }
    } else {
      Message response = null;
      if (frame.message instanceof Startup || frame.message instanceof Register) {
        response = new Ready();
      } else if (frame.message instanceof Options) {
        response = new Supported(new HashMap<>());
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
          response = Void.INSTANCE;
        }
      }
      if (response != null) {
        sendMessage(ctx, frame, response);
      }
    }
  }

  void sendMessage(ChannelHandlerContext ctx, Frame requestFrame, Message responseMessage) {
    Frame responseFrame = wrapResponse(requestFrame, responseMessage);
    logger.debug(
        "Sending response for streamId: {} with msg {}",
        responseFrame.streamId,
        responseFrame.message);
    ctx.writeAndFlush(responseFrame);
  }
}