package org.msgat.console.core;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.msgat.console.routing.BlindRoutingTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record GhostNettyServer(int port, BlindRoutingTable routingTable) {

    private static final Logger log = LoggerFactory.getLogger(GhostNettyServer.class);

    public void start() throws Exception {
        // 【核心修改】：每次启动动态生成自签名证书，实现“零配置部署”
        // 这里的 "ghost-node.local" 只是一个虚构的假域名，用来欺骗 TLS 握手
        SelfSignedCertificate ssc = new SelfSignedCertificate("ghost-node.local");
        final SslContext sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();

                            // 1. 最外层防御：动态 TLS 加密 (抗 DPI 深度包检测)
                            p.addLast(sslCtx.newHandler(ch.alloc()));

                            // 2. 流量混淆层：500KB 定长包装甲
                            p.addLast(new GhostPacketCodec());

                            // 3. 身份核验层：Ed25519 挑战-响应
                            p.addLast(new AuthChallengeHandler(routingTable));

                            // 4. 核心业务层：盲中转、延迟、暂存与自毁
                            p.addLast(new RelayHandler(routingTable));
                        }
                    });

            ChannelFuture f = b.bind(port).sync();
            log.info("[NETWORK] MsgAT启动完毕!");
            log.info("[NETWORK] 当前监听端口: {}", port);
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}