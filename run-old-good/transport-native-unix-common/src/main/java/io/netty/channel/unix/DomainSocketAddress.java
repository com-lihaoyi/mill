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
package io.netty.channel.unix;

import io.netty.util.internal.ObjectUtil;

import java.io.File;
import java.net.SocketAddress;

/**
 * A address for a
 * <a href="https://en.wikipedia.org/wiki/Unix_domain_socket">Unix Domain Socket</a>.
 */
public class DomainSocketAddress extends SocketAddress {
    private static final long serialVersionUID = -6934618000832236893L;
    private final String socketPath;

    public DomainSocketAddress(String socketPath) {
        this.socketPath = ObjectUtil.checkNotNull(socketPath, "socketPath");
    }

    public DomainSocketAddress(File file) {
        this(file.getPath());
    }

    /**
     * The path to the domain socket.
     */
    public String path() {
        return socketPath;
    }

    @Override
    public String toString() {
        return path();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DomainSocketAddress)) {
            return false;
        }

        return ((DomainSocketAddress) o).socketPath.equals(socketPath);
    }

    @Override
    public int hashCode() {
        return socketPath.hashCode();
    }
}
