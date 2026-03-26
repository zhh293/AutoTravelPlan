package com.zhh.handsome.gaodemcptest.websocket.netty;

import com.zhh.handsome.gaodemcptest.websocket.TravelPlanWebSocketSender;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class NettyTravelPlanWebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final AttributeKey<String> TASK_ID_KEY = NettyWebSocketHttpHandler.TASK_ID_KEY;

    private final TravelPlanWebSocketSender sender;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt == WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            String taskId = ctx.channel().attr(TASK_ID_KEY).get();
            if (taskId == null || taskId.isBlank()) {
                ctx.close();
                return;
            }
            sender.registerChannel(taskId, ctx.channel());
            return;
        }
        if (evt instanceof IdleStateEvent idleEvent) {
            if (idleEvent.state() == IdleState.WRITER_IDLE) {
                ctx.writeAndFlush(new PingWebSocketFrame());
            }
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        sender.removeChannel(ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame) {
            ctx.close();
            return;
        }
        if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        if (frame instanceof TextWebSocketFrame) {
            return;
        }
        ctx.fireChannelRead(frame.retain());
    }
}
