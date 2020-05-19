/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.carapaceproxy.server.http2;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.concurrent.Future;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.EndpointStats;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.client.impl.ConnectionsManagerImpl;
import org.carapaceproxy.client.impl.EndpointConnectionImpl;
import org.carapaceproxy.server.RequestHandler;
import org.carapaceproxy.utils.PrometheusUtils;

/**
 * An HTTP2 client that allows you to send HTTP2 frames to a server using the newer HTTP2
 * approach (via {@link io.netty.handler.codec.http2.Http2FrameCodec}).
 * When run from the command-line, sends a single HEADERS frame (with prior knowledge) to
 * the server configured at host:port/path.
 * You should include {@link io.netty.handler.codec.http2.Http2ClientUpgradeCodec} if the
 * HTTP/2 server you are hitting doesn't support h2c/prior knowledge.
 */
public final class HTTP2EndpointConnectionImpl {

    private static final Logger LOG = Logger.getLogger(EndpointConnectionImpl.class.getName());

    private static final AtomicLong IDGENERATOR = new AtomicLong();

    private static final String METRIC_LABEL_RESULT = "result";
    private static final String METRIC_LABEL_HOST = "host";
    private static final String METRIC_LABEL_RESULT_SUCCESS = "success";
    private static final String METRIC_LABEL_RESULT_FAILURE = "failure";

    private final long id = IDGENERATOR.incrementAndGet();
    private final ConnectionsManagerImpl parent;
    private final EndpointKey key;
    private final EndpointStats endpointstats;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean();

    private final Channel channelToEndpoint;

    private AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.IDLE);
    private volatile boolean forcedInvalid = false;
    private volatile RequestHandler clientSidePeerHandler;

    // stats
    private static final Summary CONNECTION_STATS_SUMMARY = PrometheusUtils.createSummary("backends", "connection_time_ns",
            "backend connections", METRIC_LABEL_HOST, METRIC_LABEL_RESULT).register();
    private static final Gauge OPEN_CONNECTIONS_GAUGE = PrometheusUtils.createGauge("backends", "open_connections",
            "currently open backend connections", METRIC_LABEL_HOST).register();
    private static final Gauge ACTIVE_CONNECTIONS_GAUGE = PrometheusUtils.createGauge("backends", "active_connections",
            "currently active backend connections", METRIC_LABEL_HOST).register();
    private static final Counter TOTAL_REQUESTS_COUNTER = PrometheusUtils.createCounter("backends", "sent_requests_total",
            "sent requests", METRIC_LABEL_HOST).register();

    private final Gauge.Child openConnectionsStats;
    private final Gauge.Child activeConnectionsStats;
    private final Counter.Child requestsStats;

    private static enum ConnectionState {
        IDLE,
        REQUEST_SENT,
        RELEASABLE,
        DELAYED_RELEASE
    }

    /**
     * Creates and return always a "CONNECTED" connection. This constructor will block until the backend is connected
     *
     * @param key
     * @param parent
     * @param endpointstats
     * @throws IOException
     */
    public HTTP2EndpointConnectionImpl(EndpointKey key, ConnectionsManagerImpl parent, EndpointStats endpointstats) throws IOException {
        this.key = key;
        this.parent = parent;
        this.forcedInvalid = false;
        this.endpointstats = endpointstats;
        activityDone();

        EventLoopGroup eventLoopForOutboundConnections = parent.getEventLoopForOutboundConnections();
        if (eventLoopForOutboundConnections.isShuttingDown()) {
            throw new IOException("eventLoopForOutboundConnections "
                    + eventLoopForOutboundConnections + " has been shutdown, cannot connect to " + key);
        }
        String labelHost = key.getHost() + "_" + key.getPort();
        this.openConnectionsStats = OPEN_CONNECTIONS_GAUGE.labels(labelHost);
        this.activeConnectionsStats = ACTIVE_CONNECTIONS_GAUGE.labels(labelHost);
        this.requestsStats = TOTAL_REQUESTS_COUNTER.labels(labelHost);

        final long startTime = System.nanoTime();
        Bootstrap b = new Bootstrap();

        b.group(eventLoopForOutboundConnections)
                .channel(Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, parent.getConnectTimeout())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast("readTimeoutHandler", new ReadTimeoutHandler(parent.getIdleTimeout() / 2));
                        ch.pipeline().addLast("writeTimeoutHandler", new WriteTimeoutHandler(parent.getIdleTimeout() / 2));
                        ch.pipeline().addLast("client-codec", new HttpClientCodec());
                        ch.pipeline().addLast(new EndpointConnectionInitializer());
                    }
                });

            final Channel channel = b.connect(key.getHost(), key.getPort()).syncUninterruptibly().channel();
            final EndpointConnectionHandler streamFrameResponseHandler = new EndpointConnectionHandler();
            final Http2StreamChannelBootstrap streamChannelBootstrap = new Http2StreamChannelBootstrap(channel);
            final Http2StreamChannel streamChannel = streamChannelBootstrap.open().syncUninterruptibly().getNow();
            streamChannel.pipeline().addLast(streamFrameResponseHandler);




        channel.addListener((Future<Void> future) -> {
            if (future.isSuccess()) {
                endpointstats.getTotalConnections().incrementAndGet();
                endpointstats.getOpenConnections().incrementAndGet();
                openConnectionsStats.inc();
                CONNECTION_STATS_SUMMARY.labels(labelHost, METRIC_LABEL_RESULT_SUCCESS)
                        .observe(System.nanoTime() - startTime);
            } else {
                CONNECTION_STATS_SUMMARY.labels(labelHost, METRIC_LABEL_RESULT_FAILURE)
                        .observe(System.nanoTime() - startTime);
                LOG.log(Level.INFO, "connect failed to " + key, future.cause());
                parent.backendHealthManager.reportBackendUnreachable(key.getHostPort(),
                        System.currentTimeMillis(), "connection failed");
            }
        });
        try {
            connectFuture.get(parent.getConnectTimeout(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw new IOException(err);
        } catch (ExecutionException err) {
            LOG.log(Level.INFO, "cannot create a valid connection to " + key, err.getCause());
            if (err.getCause() instanceof IOException) {
                throw (IOException) err.getCause();
            } else {
                throw new IOException(err.getCause());
            }
        } catch (TimeoutException err) {
            LOG.log(Level.INFO, "timed out creating a valid connection to " + key, err);
            throw new IOException(err);
        }
        if (forcedInvalid) {
            throw new IOException("Cannot connect to " + key+" (already invalidated)");
        }

        channelToEndpoint = connectFuture.channel();
        channelToEndpoint
                .closeFuture()
                .addListener((Future<? super Void> future) -> {
                    LOG.log(Level.FINE, "channel closed to {0}", key);
                    endpointstats.getOpenConnections().decrementAndGet();
                    openConnectionsStats.dec();
                });

    }





    public static void main(String[] args) throws Exception {
        final EventLoopGroup clientWorkerGroup = new NioEventLoopGroup();

        try {
            final Bootstrap b = new Bootstrap();
            b.group(clientWorkerGroup);
            b.channel(NioSocketChannel.class);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.remoteAddress(HOST, PORT);
            b.handler(new EndpointConnectionInitializer(sslCtx));

            // Start the client.
            final Channel channel = b.connect().syncUninterruptibly().channel();
            System.out.println("Connected to [" + HOST + ':' + PORT + ']');

            final EndpointConnectionHandler streamFrameResponseHandler =
                    new EndpointConnectionHandler();

            final Http2StreamChannelBootstrap streamChannelBootstrap = new Http2StreamChannelBootstrap(channel);
            final Http2StreamChannel streamChannel = streamChannelBootstrap.open().syncUninterruptibly().getNow();
            streamChannel.pipeline().addLast(streamFrameResponseHandler);

            // Send request (a HTTP/2 HEADERS frame - with ':method = GET' in this case)
            final DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.method("GET");
            headers.path(PATH);
            headers.scheme(SSL? "https" : "http");
            final Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(headers);
            streamChannel.writeAndFlush(headersFrame);
            System.out.println("Sent HTTP/2 GET request to " + PATH);

            // Wait for the responses (or for the latch to expire), then clean up the connections
            if (!streamFrameResponseHandler.responseSuccessfullyCompleted()) {
                System.err.println("Did not get HTTP/2 response in expected time.");
            }

            System.out.println("Finished HTTP/2 request, will close the connection.");

            // Wait until the connection is closed.
            channel.close().syncUninterruptibly();
        } finally {
            clientWorkerGroup.shutdownGracefully();
        }
    }

    private void activityDone() {
        endpointstats.getLastActivity().set(System.currentTimeMillis());
    }

    private void invalidate() {
        forcedInvalid = true;
    }

    @Override
    public EndpointKey getKey() {
        return key;
    }

}
