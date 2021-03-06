/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.api;

import java.io.IOException;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.bookkeeper.test.PortManager;
import org.apache.pulsar.client.api.MockBrokerServiceHooks.CommandAckHook;
import org.apache.pulsar.client.api.MockBrokerServiceHooks.CommandCloseConsumerHook;
import org.apache.pulsar.client.api.MockBrokerServiceHooks.CommandCloseProducerHook;
import org.apache.pulsar.client.api.MockBrokerServiceHooks.CommandConnectHook;
import org.apache.pulsar.client.api.MockBrokerServiceHooks.CommandFlowHook;
import org.apache.pulsar.client.api.MockBrokerServiceHooks.CommandPartitionLookupHook;
import org.apache.pulsar.client.api.MockBrokerServiceHooks.CommandProducerHook;
import org.apache.pulsar.client.api.MockBrokerServiceHooks.CommandSendHook;
import org.apache.pulsar.client.api.MockBrokerServiceHooks.CommandSubscribeHook;
import org.apache.pulsar.client.api.MockBrokerServiceHooks.CommandTopicLookupHook;
import org.apache.pulsar.client.api.MockBrokerServiceHooks.CommandUnsubscribeHook;
import org.apache.pulsar.common.api.Commands;
import org.apache.pulsar.common.api.PulsarDecoder;
import org.apache.pulsar.common.api.proto.PulsarApi;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandLookupTopic;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandLookupTopicResponse.LookupType;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandPartitionedTopicMetadata;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSend;
import org.apache.pulsar.common.lookup.data.LookupData;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.apache.pulsar.common.util.netty.EventLoopUtil;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 */
public class MockBrokerService {
    private class genericResponseHandler extends AbstractHandler {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final String lookupURI = "/lookup/v2/destination/persistent";
        private final String partitionMetadataURI = "/admin/persistent";
        private final LookupData lookupData = new LookupData("pulsar://127.0.0.1:" + brokerServicePort,
                "pulsar://127.0.0.1:" + brokerServicePortTls, "http://127.0.0.1:" + webServicePort, null);
        private final PartitionedTopicMetadata singlePartitionedTopicMetadata = new PartitionedTopicMetadata(1);
        private final PartitionedTopicMetadata multiPartitionedTopicMetadata = new PartitionedTopicMetadata(4);
        private final PartitionedTopicMetadata nonPartitionedTopicMetadata = new PartitionedTopicMetadata();
        // regex to find a partitioned topic
        private final Pattern singlePartPattern = Pattern.compile(".*/part-.*");
        private final Pattern multiPartPattern = Pattern.compile(".*/multi-part-.*");

        @Override
        public void handle(String s, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            String responseString;
            log.info("Received HTTP request {}", baseRequest.getRequestURI());
            if (baseRequest.getRequestURI().startsWith(lookupURI)) {
                response.setContentType("application/json;charset=utf-8");
                response.setStatus(HttpServletResponse.SC_OK);
                responseString = objectMapper.writeValueAsString(lookupData);
            } else if (baseRequest.getRequestURI().startsWith(partitionMetadataURI)) {
                response.setContentType("application/json;charset=utf-8");
                response.setStatus(HttpServletResponse.SC_OK);
                if (singlePartPattern.matcher(baseRequest.getRequestURI()).matches()) {
                    responseString = objectMapper.writeValueAsString(singlePartitionedTopicMetadata);
                } else if (multiPartPattern.matcher(baseRequest.getRequestURI()).matches()) {
                    responseString = objectMapper.writeValueAsString(multiPartitionedTopicMetadata);
                } else {
                    responseString = objectMapper.writeValueAsString(nonPartitionedTopicMetadata);
                }
            } else {
                response.setContentType("text/html;charset=utf-8");
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                responseString = "URI NOT DEFINED";
            }
            baseRequest.setHandled(true);
            response.getWriter().println(responseString);
            log.info("Sent response: {}", responseString);
        }
    }

    private class MockServerCnx extends PulsarDecoder {
        // local state
        ChannelHandlerContext ctx;
        long producerId = 0;

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            this.ctx = ctx;
        }

        @Override
        protected void messageReceived() {
        }

        @Override
        protected void handleConnect(PulsarApi.CommandConnect connect) {
            if (handleConnect != null) {
                handleConnect.apply(ctx, connect);
                return;
            }
            // default
            ctx.writeAndFlush(Commands.newConnected(connect.getProtocolVersion()));
        }

        @Override
        protected void handlePartitionMetadataRequest(CommandPartitionedTopicMetadata request) {
            if (handlePartitionlookup != null) {
                handlePartitionlookup.apply(ctx, request);
                return;
            }
            // default
            ctx.writeAndFlush(Commands.newPartitionMetadataResponse(0, request.getRequestId()));
        }

        @Override
        protected void handleLookup(CommandLookupTopic lookup) {
            if (handleTopiclookup != null) {
                handleTopiclookup.apply(ctx, lookup);
                return;
            }
            // default
            ctx.writeAndFlush(Commands.newLookupResponse("pulsar://127.0.0.1:" + brokerServicePort, null, true,
                    LookupType.Connect, lookup.getRequestId(), false));
        }

        @Override
        protected void handleSubscribe(PulsarApi.CommandSubscribe subscribe) {
            if (handleSubscribe != null) {
                handleSubscribe.apply(ctx, subscribe);
                return;
            }
            // default
            ctx.writeAndFlush(Commands.newSuccess(subscribe.getRequestId()));
        }

        @Override
        protected void handleProducer(PulsarApi.CommandProducer producer) {
            producerId = producer.getProducerId();
            if (handleProducer != null) {
                handleProducer.apply(ctx, producer);
                return;
            }
            // default
            ctx.writeAndFlush(Commands.newProducerSuccess(producer.getRequestId(), "default-producer"));
        }

        @Override
        protected void handleSend(CommandSend send, ByteBuf headersAndPayload) {
            if (handleSend != null) {
                handleSend.apply(ctx, send, headersAndPayload);
                return;
            }
            // default
            ctx.writeAndFlush(Commands.newSendReceipt(producerId, send.getSequenceId(), 0, 0));
        }

        @Override
        protected void handleAck(PulsarApi.CommandAck ack) {
            if (handleAck != null) {
                handleAck.apply(ctx, ack);
            }
            // default: do nothing
        }

        @Override
        protected void handleFlow(PulsarApi.CommandFlow flow) {
            if (handleFlow != null) {
                handleFlow.apply(ctx, flow);
            }
            // default: do nothing
        }

        @Override
        protected void handleUnsubscribe(PulsarApi.CommandUnsubscribe unsubscribe) {
            if (handleUnsubscribe != null) {
                handleUnsubscribe.apply(ctx, unsubscribe);
                return;
            }
            // default
            ctx.writeAndFlush(Commands.newSuccess(unsubscribe.getRequestId()));
        }

        @Override
        protected void handleCloseProducer(PulsarApi.CommandCloseProducer closeProducer) {
            if (handleCloseProducer != null) {
                handleCloseProducer.apply(ctx, closeProducer);
                return;
            }
            // default
            ctx.writeAndFlush(Commands.newSuccess(closeProducer.getRequestId()));
        }

        @Override
        protected void handleCloseConsumer(PulsarApi.CommandCloseConsumer closeConsumer) {
            if (handleCloseConsumer != null) {
                handleCloseConsumer.apply(ctx, closeConsumer);
                return;
            }
            // default
            ctx.writeAndFlush(Commands.newSuccess(closeConsumer.getRequestId()));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            log.warn("Got exception", cause);
            ctx.close();
        }
    }

    private final Server server;
    EventLoopGroup workerGroup;

    private final int webServicePort;
    private final int brokerServicePort;
    private final int brokerServicePortTls;

    private CommandConnectHook handleConnect = null;
    private CommandTopicLookupHook handleTopiclookup = null;
    private CommandPartitionLookupHook handlePartitionlookup = null;
    private CommandSubscribeHook handleSubscribe = null;
    private CommandProducerHook handleProducer = null;
    private CommandSendHook handleSend = null;
    private CommandAckHook handleAck = null;
    private CommandFlowHook handleFlow = null;
    private CommandUnsubscribeHook handleUnsubscribe = null;
    private CommandCloseProducerHook handleCloseProducer = null;
    private CommandCloseConsumerHook handleCloseConsumer = null;

    public MockBrokerService() {
        this(PortManager.nextFreePort(), PortManager.nextFreePort(), PortManager.nextFreePort(),
                PortManager.nextFreePort());
    }

    public MockBrokerService(int webServicePort, int webServicePortTls, int brokerServicePort,
            int brokerServicePortTls) {
        this.webServicePort = webServicePort;
        this.brokerServicePort = brokerServicePort;
        this.brokerServicePortTls = brokerServicePortTls;

        server = new Server(webServicePort);
        server.setHandler(new genericResponseHandler());
    }

    public void start() {
        try {
            server.start();
            log.info("Started web service on http://127.0.0.1:{}", webServicePort);

            startMockBrokerService();
            log.info("Started mock Pulsar service on http://127.0.0.1:{}", brokerServicePort);
        } catch (Exception e) {
            log.error("Error starting mock service", e);
        }
    }

    public void stop() {
        try {
            server.stop();
            workerGroup.shutdownGracefully();
        } catch (Exception e) {
            log.error("Error stopping mock service", e);
        }
    }

    public void startMockBrokerService() throws Exception {
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("mock-pulsar-%s").build();
        final int numThreads = 2;

        final int MaxMessageSize = 5 * 1024 * 1024;

        try {
            workerGroup = EventLoopUtil.newEventLoopGroup(numThreads, threadFactory);

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(workerGroup, workerGroup);
            bootstrap.channel(EventLoopUtil.getServerSocketChannelClass(workerGroup));
            bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast("frameDecoder", new LengthFieldBasedFrameDecoder(MaxMessageSize, 0, 4, 0, 4));
                    ch.pipeline().addLast("handler", new MockServerCnx());
                }
            });
            // Bind and start to accept incoming connections.
            bootstrap.bind(brokerServicePort).sync();
        } catch (Exception e) {
            throw e;
        }
    }

    public void setHandleConnect(CommandConnectHook hook) {
        handleConnect = hook;
    }

    public void resetHandleConnect() {
        handleConnect = null;
    }

    public void setHandlePartitionLookup(CommandPartitionLookupHook hook) {
        handlePartitionlookup = hook;
    }

    public void resetHandlePartitionLookup() {
        handlePartitionlookup = null;
    }

    public void setHandleLookup(CommandTopicLookupHook hook) {
        handleTopiclookup = hook;
    }

    public void resetHandleLookup() {
        handleTopiclookup = null;
    }

    public void setHandleSubscribe(CommandSubscribeHook hook) {
        handleSubscribe = hook;
    }

    public void resetHandleSubscribe() {
        handleSubscribe = null;
    }

    public void setHandleProducer(CommandProducerHook hook) {
        handleProducer = hook;
    }

    public void resetHandleProducer() {
        handleProducer = null;
    }

    public void setHandleSend(CommandSendHook hook) {
        handleSend = hook;
    }

    public void resetHandleSend() {
        handleSend = null;
    }

    public void setHandleAck(CommandAckHook hook) {
        handleAck = hook;
    }

    public void resetHandleAck() {
        handleAck = null;
    }

    public void setHandleFlow(CommandFlowHook hook) {
        handleFlow = hook;
    }

    public void resetHandleFlow() {
        handleFlow = null;
    }

    public void setHandleUnsubscribe(CommandUnsubscribeHook hook) {
        handleUnsubscribe = hook;
    }

    public void resetHandleUnsubscribe() {
        handleUnsubscribe = null;
    }

    public void setHandleCloseProducer(CommandCloseProducerHook hook) {
        handleCloseProducer = hook;
    }

    public void resetHandleCloseProducer() {
        handleCloseProducer = null;
    }

    public void setHandleCloseConsumer(CommandCloseConsumerHook hook) {
        handleCloseConsumer = hook;
    }

    public void resetHandleCloseConsumer() {
        handleCloseConsumer = null;
    }

    private static final Logger log = LoggerFactory.getLogger(MockBrokerService.class);
}
