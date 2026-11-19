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
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.msgat.console.routing.BlindRoutingTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GhostNettyServer {
    private final ServerConfiguration configuration;
    private final BlindRoutingTable routingTable;
    private final org.msgat.console.db.AttachmentStore attachments;
    private final RelayAdmission admission;
    private final AndroidPushRelay androidPush;
    private final Runnable wakeRevocationCleanup;
    private volatile Channel listener;
    private volatile boolean stopRequested;
    public GhostNettyServer(ServerConfiguration configuration, BlindRoutingTable routingTable,
                            org.msgat.console.db.AttachmentStore attachments, AndroidPushRelay androidPush,
                            Runnable wakeRevocationCleanup) {
        this.configuration = configuration;
        this.routingTable = routingTable;
        this.attachments = attachments;
        admission = new RelayAdmission(configuration.connectionLimits(), configuration.connectionTimeouts().authenticationSeconds());
        this.androidPush = androidPush;
        this.wakeRevocationCleanup = wakeRevocationCleanup;
    }
    public void requestStop() { stopRequested=true; var channel=listener; if(channel!=null) channel.close(); }
    private static final Logger log = LoggerFactory.getLogger(GhostNettyServer.class);

    // Starts the Netty server, sets up dynamic TLS, configures the channel pipeline, and binds to the specified port.
    @SuppressWarnings("deprecation") // Netty 4.2 retains ordered per-channel executor groups; keep DB work off I/O threads.
    public void start() throws Exception {
        if(stopRequested) return;
        final SslContext sslCtx = createSslContext();

        EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())), NioIoHandler.newFactory());

        var applicationGroup = new io.netty.util.concurrent.DefaultEventExecutorGroup(
                Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
                new io.netty.util.concurrent.DefaultThreadFactory("msgat-application"), 64,
                io.netty.util.concurrent.RejectedExecutionHandlers.reject());
        var transferGroup = new io.netty.util.concurrent.DefaultEventExecutorGroup(2,
                new io.netty.util.concurrent.DefaultThreadFactory("msgat-attachments"),16,
                io.netty.util.concurrent.RejectedExecutionHandlers.reject());
        var clients=new io.netty.channel.group.DefaultChannelGroup(io.netty.util.concurrent.GlobalEventExecutor.INSTANCE, true);

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.RCVBUF_ALLOCATOR, new AdaptiveRecvByteBufAllocator(1024, 16 * 1024, 64 * 1024))
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1024 * 1024, 4 * 1024 * 1024))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) {
                            RelayAdmission.Lease lease = stopRequested ? null : admission.acquire(ch);
                            if (lease == null) { ch.close(); return; }
                            clients.add(ch);
                            ChannelPipeline p = ch.pipeline();

                            var timeouts = configuration.connectionTimeouts();
                            p.addLast(new ReadTimeoutHandler(timeouts.readSeconds()));
                            p.addLast(new WriteTimeoutHandler(timeouts.writeSeconds()));
                            p.addLast(sslCtx.newHandler(ch.alloc()));
                            p.addLast(new GhostPacketCodec(configuration.maxInboundFrameBytes()));
                            p.addLast(new ApplicationQueueGuard());
                            // Both handlers share the same ordered application executor for this channel.
                            p.addLast(applicationGroup, "authentication", new AuthChallengeHandler(routingTable, lease, androidPush != null, wakeRevocationCleanup, channel -> {
                                channel.pipeline().addLast(transferGroup,"attachments",new AttachmentHandler(attachments));
                                channel.pipeline().remove("relay");
                            }));
                            p.addLast(applicationGroup, "relay", new RelayHandler(routingTable, androidPush));
                        }
                    });

            ChannelFuture f = b.bind(configuration.listener());
            listener = f.channel();
            if(stopRequested) listener.close();
            f.sync();
            log.info("Server listening on {}", f.channel().localAddress());
            f.channel().closeFuture().sync();
        } finally {
            if (listener != null) listener.close().awaitUninterruptibly();
            clients.close().awaitUninterruptibly();
            transferGroup.shutdownGracefully(0,5,java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
            applicationGroup.shutdownGracefully(0,5,java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
            workerGroup.shutdownGracefully(0,5,java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
            bossGroup.shutdownGracefully(0,5,java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @SuppressWarnings("deprecation") // Netty retains this helper only for explicit local-development certificates.
    private SslContext createSslContext() throws Exception {
        var tlsFiles = configuration.tlsFiles();
        if (tlsFiles != null) {
            return SslContextBuilder.forServer(tlsFiles.certificate().toFile(), tlsFiles.privateKey().toFile())
                    .protocols("TLSv1.3", "TLSv1.2").build();
        }
        log.warn("Using an ephemeral self-signed certificate. Set tlsCertificatePath and tlsPrivateKeyPath for deployment.");
        SelfSignedCertificate certificate = SelfSignedCertificate.builder()
                .fqdn("ghost-node.local")
                .build();
        try {
            return SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey())
                    .protocols("TLSv1.3", "TLSv1.2").build();
        } finally {
            certificate.delete();
        }
    }
}
