/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.testsuite.transport.socket;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.testsuite.transport.AbstractComboTestsuiteTest;
import io.netty.testsuite.transport.TestsuitePermutation;
import io.netty.util.NetUtil;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

public abstract class AbstractSocketTest extends AbstractComboTestsuiteTest<ServerBootstrap, Bootstrap> {
    @Override
    protected List<TestsuitePermutation.BootstrapComboFactory<ServerBootstrap, Bootstrap>> newFactories() {
        return SocketTestPermutation.INSTANCE.socket();
    }

    @Override
    protected void configure(ServerBootstrap sb, Bootstrap cb, ByteBufAllocator allocator) {
        sb.localAddress(newSocketAddress());
        sb.option(ChannelOption.ALLOCATOR, allocator);
        sb.childOption(ChannelOption.ALLOCATOR, allocator);
        cb.option(ChannelOption.ALLOCATOR, allocator);
    }

    protected SocketAddress newSocketAddress() {
        return new InetSocketAddress(NetUtil.LOCALHOST, 0);
    }
}
