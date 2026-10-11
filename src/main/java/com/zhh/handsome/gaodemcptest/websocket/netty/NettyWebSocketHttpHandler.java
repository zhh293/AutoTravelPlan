package com.zhh.handsome.gaodemcptest.websocket.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AttributeKey;

import java.util.List;
import java.util.Map;

public class NettyWebSocketHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    static final AttributeKey<String> TASK_ID_KEY = AttributeKey.valueOf("taskId");

    private final String path;

    public NettyWebSocketHttpHandler(String path) {
        this.path = path;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        if (!decoder.path().equals(path)) {
            ctx.fireChannelRead(request.retain());
            return;
        }
        Map<String, List<String>> params = decoder.parameters();
        List<String> taskIds = params.get("taskId");
        String taskId = (taskIds == null || taskIds.isEmpty()) ? null : taskIds.get(0);
        if (taskId == null || taskId.isBlank()) {
            DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
            resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(resp);
            ctx.close();
            return;
        }
        ctx.channel().attr(TASK_ID_KEY).set(taskId);
        ctx.fireChannelRead(request.retain());
    }
}
