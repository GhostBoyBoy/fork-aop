/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.amqp.proxy;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.streamnative.pulsar.handlers.amqp.AmqpEncoder;

/**
 * Proxy service channel initializer.
 */
public class ServiceChannelInitializer extends ChannelInitializer<SocketChannel> {

    private ProxyService proxyService;

    public ServiceChannelInitializer(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline().addLast("consolidation", new FlushConsolidationHandler(
                proxyService.getProxyConfig().getAmqpExplicitFlushAfterFlushes(), true));
        ch.pipeline().addLast("frameEncoder", new AmqpEncoder());
        ch.pipeline().addLast("handler", new ProxyConnection(proxyService));
    }

}
