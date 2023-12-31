/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.amqp.proxy;

import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.streamnative.pulsar.handlers.amqp.AmqpBrokerDecoder;
import io.streamnative.pulsar.handlers.amqp.AmqpConnection;
import io.streamnative.pulsar.handlers.amqp.AmqpProtocolHandler;
import io.streamnative.pulsar.handlers.amqp.AopVersion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.TopicDomain;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.qpid.server.QpidException;
import org.apache.qpid.server.bytebuffer.QpidByteBuffer;
import org.apache.qpid.server.common.ServerPropertyNames;
import org.apache.qpid.server.protocol.ErrorCodes;
import org.apache.qpid.server.protocol.ProtocolVersion;
import org.apache.qpid.server.protocol.v0_8.AMQShortString;
import org.apache.qpid.server.protocol.v0_8.FieldTable;
import org.apache.qpid.server.protocol.v0_8.transport.AMQDataBlock;
import org.apache.qpid.server.protocol.v0_8.transport.AMQFrame;
import org.apache.qpid.server.protocol.v0_8.transport.AMQMethodBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionCloseBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionTuneBody;
import org.apache.qpid.server.protocol.v0_8.transport.MethodRegistry;
import org.apache.qpid.server.protocol.v0_8.transport.ProtocolInitiation;
import org.apache.qpid.server.protocol.v0_8.transport.ServerChannelMethodProcessor;
import org.apache.qpid.server.protocol.v0_8.transport.ServerMethodProcessor;

/**
 * Proxy connection.
 */
@Slf4j
public class ProxyConnection extends ChannelInboundHandlerAdapter implements
        ServerMethodProcessor<ServerChannelMethodProcessor>, FutureListener<Void> {

    private ProxyService proxyService;
    private ProxyConfiguration proxyConfig;
    @Getter
    private ChannelHandlerContext cnx;
    private State state;
    private ProxyHandler proxyHandler;

    protected AmqpBrokerDecoder brokerDecoder;
    @Getter
    private MethodRegistry methodRegistry;
    private ProtocolVersion protocolVersion;
    private LookupHandler lookupHandler;
    private AMQShortString virtualHost;
    private String vhost;
    private String tenant;

    private List<Object> connectMsgList = new ArrayList<>();

    private volatile int currentClassId;
    private volatile int currentMethodId;

    private enum State {
        Init,
        RedirectLookup,
        RedirectToBroker,
        Closed
    }
    private static final Map<String, String> USERS = new HashMap<>() {
        {
            // dc-chain
            put("dc_chain/HsA2s3#s3", "pro-chain");
            put("root/Ds4Y3#s1", "pro-chain");

            // abm-dt
            put("dc_user/Jsdxxs3#s3", "abm-dt");
            put("root/RrdY3#s2", "abm-dt");

            // pay
            put("dc_pay/Laocksu3#s3", "pro-pay");
            put("root/RrxY3#s2", "pro-pay");

            // mall
            put("dc_shop/Tc7aga3#s3", "pro-mall");
            put("root/RroY3#s2", "pro-mall");

            // base
            put("dc_base/Niucksu6#s9", "pro-base");
            put("dc_user/RidY3#s1", "pro-base");
            put("root/Rs4Y3#s2", "pro-base");
        }
    };

    public ProxyConnection(ProxyService proxyService) throws PulsarClientException {
        log.info("ProxyConnection init ...");
        this.proxyService = proxyService;
        this.proxyConfig = proxyService.getProxyConfig();
        this.tenant = proxyConfig.getAmqpTenant();
        brokerDecoder = new AmqpBrokerDecoder(this);
        protocolVersion = ProtocolVersion.v0_91;
        methodRegistry = new MethodRegistry(protocolVersion);
        lookupHandler = proxyService.getLookupHandler();
        state = State.Init;
    }

    @Override
    public void channelActive(ChannelHandlerContext cnx) throws Exception {
        super.channelActive(cnx);
        this.cnx = cnx;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        this.close();
    }

    @Override
    public void operationComplete(Future future) throws Exception {
        // This is invoked when the write operation on the paired connection is
        // completed
        if (future.isSuccess()) {
            cnx.read();
        } else {
            log.warn("Error in writing to inbound channel. Closing", future.cause());
            proxyHandler.getBrokerChannel().close();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        switch (state) {
            case Init, RedirectLookup -> {
                log.info("ProxyConnection [channelRead] - RedirectLookup");
                connectMsgList.add(msg);

                // Get a buffer that contains the full frame
                ByteBuf buffer = (ByteBuf) msg;
                io.netty.channel.Channel nettyChannel = ctx.channel();
                checkState(nettyChannel.equals(this.cnx.channel()));
                try {
                    brokerDecoder.decodeBuffer(QpidByteBuffer.wrap(buffer.nioBuffer()));
                } catch (Throwable e) {
                    log.error("error while handle command:", e);
                    close();
                }
            }
            case RedirectToBroker -> {
                if (log.isDebugEnabled()) {
                    log.debug("ProxyConnection [channelRead] - RedirectToBroker");
                }
                if (proxyHandler != null) {
                    proxyHandler.getBrokerChannel().writeAndFlush(msg);
                }
            }
            case Closed -> log.info("ProxyConnection [channelRead] - closed");
            default -> log.error("ProxyConnection [channelRead] - invalid state");
        }
    }

    // step 1
    @Override
    public void receiveProtocolHeader(ProtocolInitiation protocolInitiation) {
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection - [receiveProtocolHeader] Protocol Header [{}]", protocolInitiation);
        }
        brokerDecoder.setExpectProtocolInitiation(false);
        try {
            ProtocolVersion pv = protocolInitiation.checkVersion(); // Fails if not correct
            // TODO serverProperties mechanis
            AMQMethodBody responseBody = this.methodRegistry.createConnectionStartBody(
                    (short) protocolVersion.getMajorVersion(),
                    (short) pv.getActualMinorVersion(),
                    FieldTable.convertToFieldTable(new HashMap<>(2) {
                        {
                            put(ServerPropertyNames.VERSION, AopVersion.getVersion());
                        }
                    }),
                    // TODO temporary modification
                    "PLAIN token".getBytes(US_ASCII),
                    "en_US".getBytes(US_ASCII));
            writeFrame(responseBody.generateFrame(0));
        } catch (QpidException e) {
            log.error("Received unsupported protocol initiation for protocol version: {} ", getProtocolVersion(), e);
        }
    }

    // step 2
    @Override
    public void receiveConnectionStartOk(FieldTable clientProperties, AMQShortString mechanism, byte[] response,
                                         AMQShortString locale) {
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection - [receiveConnectionStartOk] clientProperties: {}, mechanism: {}, locale: {}",
                    clientProperties, mechanism, locale);
        }
        if (mechanism != null && mechanism.length() != 0) {
            if ("PLAIN".equals(String.valueOf(mechanism))) {
                int authzidNullPosition = findNullPosition(response, 0);
                if (authzidNullPosition >= 0) {
                    int authcidNullPosition = findNullPosition(response, authzidNullPosition + 1);
                    if (authcidNullPosition >= 0) {
                        String username = new String(response, authzidNullPosition + 1,
                                authcidNullPosition - authzidNullPosition - 1, UTF_8);
                        int passwordLen = response.length - authcidNullPosition - 1;
                        String password = new String(response, authcidNullPosition + 1, passwordLen, UTF_8);
                        String tenant = USERS.get(username + "/" + password);
                        if (tenant != null) {
                            this.tenant = tenant;
                        }
                    }
                }
            }
        }
        // TODO AUTH
        ConnectionTuneBody tuneBody =
                methodRegistry.createConnectionTuneBody(proxyConfig.getAmqpMaxNoOfChannels(),
                        proxyConfig.getAmqpMaxFrameSize(), proxyConfig.getAmqpHeartBeat());
        writeFrame(tuneBody.generateFrame(0));
    }

    private int findNullPosition(byte[] response, int startPosition) {
        int position = startPosition;
        while (position < response.length) {
            if (response[position] == (byte) 0) {
                return position;
            }
            position++;
        }
        return -1;
    }

    // step 3
    @Override
    public void receiveConnectionSecureOk(byte[] response) {
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection - [receiveConnectionSecureOk] response: {}", new String(response, UTF_8));
        }
        ConnectionTuneBody tuneBody =
                methodRegistry.createConnectionTuneBody(proxyConfig.getAmqpMaxNoOfChannels(),
                        proxyConfig.getAmqpMaxFrameSize(), proxyConfig.getAmqpHeartBeat());
        writeFrame(tuneBody.generateFrame(0));
    }

    // step 4
    @Override
    public void receiveConnectionTuneOk(int i, long l, int i1) {
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection - [receiveConnectionTuneOk]");
        }
    }

    // step 5
    @Override
    public void receiveConnectionOpen(AMQShortString virtualHost, AMQShortString capabilities, boolean insist) {
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection - [receiveConnectionOpen] virtualHost: {} capabilities: {} insist: {}",
                    virtualHost, capabilities, insist);
        }
        this.virtualHost = virtualHost;
        state = State.RedirectLookup;
        String virtualHostStr = AMQShortString.toString(virtualHost);
        Pair<String, String> pair;
        if (virtualHostStr == null || (pair = validateVirtualHost(virtualHostStr)) == null) {
            sendConnectionClose(ErrorCodes.NOT_ALLOWED, String.format(
                    "The virtualHost [%s] configuration is incorrect. For example: tenant/namespace or namespace",
                    virtualHostStr));
            return;
        }
        tenant = pair.getLeft();
        vhost = pair.getRight();
        handleConnect(new AtomicInteger(5));
    }

    private Pair<String, String> validateVirtualHost(String virtualHostStr) {
        String virtualHost = virtualHostStr.trim();
        if ("/".equals(virtualHost)) {
            return Pair.of(this.tenant, AmqpConnection.DEFAULT_NAMESPACE);
        }
        StringTokenizer tokenizer = new StringTokenizer(virtualHost, "/", false);
        return switch (tokenizer.countTokens()) {
            case 1 -> Pair.of(this.tenant, tokenizer.nextToken());
            case 2 -> Pair.of(tokenizer.nextToken(), tokenizer.nextToken());
            default -> null;
        };
    }

    public void handleConnect(AtomicInteger retryTimes) {
        log.info("handle connect residue retryTimes: {}", retryTimes);
        if (retryTimes.get() == 0) {
            log.warn("Handle connect retryTimes is 0.");
            close();
            return;
        }
        try {
            NamespaceName namespaceName = NamespaceName.get(tenant, vhost);

            String topic = TopicName.get(TopicDomain.persistent.value(),
                    namespaceName, "__lookup__").toString();
            CompletableFuture<Pair<String, Integer>> lookupData = lookupHandler.findBroker(
                    TopicName.get(topic), AmqpProtocolHandler.PROTOCOL_NAME);
            lookupData.whenComplete((pair, throwable) -> {
                if (throwable != null) {
                    log.error("Lookup broker failed; may retry.", throwable);
                    retryTimes.decrementAndGet();
                    handleConnect(retryTimes);
                    return;
                }
                assert pair != null;
                handleConnectComplete(pair.getLeft(), pair.getRight(), retryTimes);
            });
        } catch (Exception e) {
            log.error("Lookup broker failed.", e);
            resetProxyHandler();
            close();
        }
    }

    private void handleConnectComplete(String aopBrokerHost, int aopBrokerPort, AtomicInteger retryTimes) {
        try {
            if (StringUtils.isEmpty(aopBrokerHost) || aopBrokerPort == 0) {
                throw new ProxyException();
            }

            AMQMethodBody responseBody = methodRegistry.createConnectionOpenOkBody(virtualHost);
            proxyHandler = new ProxyHandler(NamespaceName.get(tenant, vhost), proxyService,
                    this, aopBrokerHost, aopBrokerPort, connectMsgList, responseBody);
            state = State.RedirectToBroker;
            log.info("Handle connect complete. aopBrokerHost: {}, aopBrokerPort: {}", aopBrokerHost, aopBrokerPort);
        } catch (Exception e) {
            retryTimes.decrementAndGet();
            resetProxyHandler();
            String errorMSg = String.format("Lookup broker failed. aopBrokerHost: %S, aopBrokerPort: %S",
                    aopBrokerHost, aopBrokerPort);
            log.error(errorMSg, e);
            handleConnect(retryTimes);
        }
    }

    public void resetProxyHandler() {
        if (proxyHandler != null) {
            proxyHandler.close();
            proxyHandler = null;
        }
    }

    @Override
    public void receiveChannelOpen(int i) {
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection - [receiveChannelOpen]");
        }
    }

    @Override
    public ProtocolVersion getProtocolVersion() {
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection - [getProtocolVersion]");
        }
        return null;
    }

    @Override
    public ServerChannelMethodProcessor getChannelMethodProcessor(int i) {
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection - [getChannelMethodProcessor]");
        }
        return null;
    }

    @Override
    public void receiveConnectionClose(int i, AMQShortString amqShortString, int i1, int i2) {
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection - [receiveConnectionClose]");
        }
    }

    @Override
    public void receiveConnectionCloseOk() {
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection - [receiveConnectionCloseOk]");
        }
    }

    @Override
    public void receiveHeartbeat() {
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection - [receiveHeartbeat]");
        }
    }


    @Override
    public void setCurrentMethod(int classId, int methodId) {
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection - [setCurrentMethod] classId: {}, methodId: {}", classId, methodId);
        }
        currentClassId = classId;
        currentMethodId = methodId;
    }

    @Override
    public boolean ignoreAllButCloseOk() {
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection - [ignoreAllButCloseOk]");
        }
        return false;
    }

    public void sendConnectionClose(int errorCode, String message) {
        writeFrame(new AMQFrame(0, new ConnectionCloseBody(getProtocolVersion(),
                errorCode, AMQShortString.validValueOf(message), currentClassId, currentMethodId)));
    }

    public synchronized void writeFrame(AMQDataBlock frame) {
        if (log.isDebugEnabled()) {
            log.debug("send: " + frame);
        }
        cnx.writeAndFlush(frame);
    }

    public void close() {
        log.info("ProxyConnection close.");
        if (log.isDebugEnabled()) {
            log.debug("ProxyConnection close.");
        }

        if (proxyHandler != null) {
            resetProxyHandler();
        }
        if (cnx != null) {
            cnx.close();
        }
        state = State.Closed;
        connectMsgList.forEach(ReferenceCountUtil::safeRelease);
        connectMsgList.clear();
    }

}
