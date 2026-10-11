package com.zhh.handsome.gaodemcptest.websocket.netty;

import com.zhh.handsome.gaodemcptest.websocket.TravelPlanWebSocketSender;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class NettyWebSocketServer {

    private final TravelPlanWebSocketSender sender;

    @Value("${netty.websocket.port:8090}")
    private int port;

    @Value("${netty.websocket.path:/ws/travel-plan}")
    private String path;

    @Value("${netty.websocket.heartbeat-seconds:30}")
    private int heartbeatSeconds;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    @PostConstruct
    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(65536))
                                    .addLast(new IdleStateHandler(0, heartbeatSeconds, 0, TimeUnit.SECONDS))
                                    .addLast(new NettyWebSocketHttpHandler(path))
                                    .addLast(new WebSocketServerProtocolHandler(path, null, true))
                                    .addLast(new NettyTravelPlanWebSocketHandler(sender));
                        }
                    });
            ChannelFuture future = bootstrap.bind(port).sync();
            serverChannel = future.channel();
            log.info("Netty WebSocket started at port {}, path {}", port, path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Netty WebSocket start interrupted", e);
        } catch (Exception e) {
            log.error("Netty WebSocket start failed", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}
