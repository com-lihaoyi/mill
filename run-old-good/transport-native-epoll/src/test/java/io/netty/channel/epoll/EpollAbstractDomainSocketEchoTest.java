/*
 * Copyright 2015 The Netty Project
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
package io.netty.channel.epoll;

import io.netty.channel.unix.DomainSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

public class EpollAbstractDomainSocketEchoTest extends EpollDomainSocketEchoTest {
    @Override
    protected SocketAddress newSocketAddress() {
        // these don't actually show up in the file system so creating a temp file isn't reliable
        return new DomainSocketAddress("\0" + System.getProperty("java.io.tmpdir") + UUID.randomUUID());
    }
}
