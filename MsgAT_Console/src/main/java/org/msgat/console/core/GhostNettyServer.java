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

    // Starts the Netty server, sets up dynamic TLS, configures the channel pipeline, and binds to the specified port.
    public void start() throws Exception {
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

                            p.addLast(sslCtx.newHandler(ch.alloc()));
                            p.addLast(new GhostPacketCodec());
                            p.addLast(new AuthChallengeHandler(routingTable));
                            p.addLast(new RelayHandler(routingTable));
                        }
                    });

            ChannelFuture f = b.bind(port).sync();
            log.info("Server started on port: {}", port);
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}