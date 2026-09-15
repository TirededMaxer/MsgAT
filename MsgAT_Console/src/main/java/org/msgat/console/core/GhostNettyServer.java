package org.msgat.console.core;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.msgat.console.routing.BlindRoutingTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;

public record GhostNettyServer(int port, BlindRoutingTable routingTable) {

    private static final Logger log = LoggerFactory.getLogger(GhostNettyServer.class);

    // Starts the Netty server, sets up dynamic TLS, configures the channel pipeline, and binds to the specified port.
    public void start() throws Exception {
        final SslContext sslCtx = createSslContext();

        EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

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

                            // 5 minutes (300 seconds) read timeout to reap zombie connections.
                            p.addLast(new io.netty.handler.timeout.ReadTimeoutHandler(300));
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

    @SuppressWarnings("deprecation") // Netty retains this helper only for explicit local-development certificates.
    private SslContext createSslContext() throws Exception {
        String certificatePath = System.getenv("MSGAT_TLS_CERT");
        String privateKeyPath = System.getenv("MSGAT_TLS_KEY");
        if (certificatePath != null && !certificatePath.isBlank()
                && privateKeyPath != null && !privateKeyPath.isBlank()) {
            return SslContextBuilder.forServer(new File(certificatePath), new File(privateKeyPath)).build();
        }
        if ((certificatePath == null || certificatePath.isBlank())
                != (privateKeyPath == null || privateKeyPath.isBlank())) {
            throw new IllegalStateException("MSGAT_TLS_CERT and MSGAT_TLS_KEY must be configured together");
        }
        log.warn("Using an ephemeral self-signed certificate. Configure MSGAT_TLS_CERT and MSGAT_TLS_KEY for deployment.");
        SelfSignedCertificate certificate = SelfSignedCertificate.builder()
                .fqdn("ghost-node.local")
                .build();
        try {
            return SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).build();
        } finally {
            certificate.delete();
        }
    }
}
