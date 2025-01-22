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
package io.netty.handler.codec.socksx.v5;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;

final class Socks5CommonTestUtils {
    /**
     * A constructor to stop this class being constructed.
     */
    private Socks5CommonTestUtils() {
        //NOOP
    }

    public static void writeFromClientToServer(EmbeddedChannel embedder, Socks5Message msg) {
        embedder.writeInbound(encodeClient(msg));
    }

    public static void writeFromServerToClient(EmbeddedChannel embedder, Socks5Message msg) {
        embedder.writeInbound(encodeServer(msg));
    }

    public static ByteBuf encodeClient(Socks5Message msg) {
        EmbeddedChannel out = new EmbeddedChannel(Socks5ClientEncoder.DEFAULT);
        out.writeOutbound(msg);

        ByteBuf encoded = out.readOutbound();
        out.finish();

        return encoded;
    }

    public static ByteBuf encodeServer(Socks5Message msg) {
        EmbeddedChannel out = new EmbeddedChannel(Socks5ServerEncoder.DEFAULT);
        out.writeOutbound(msg);

        ByteBuf encoded = out.readOutbound();
        out.finish();

        return encoded;
    }
}
