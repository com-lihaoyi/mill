/*
 * Copyright 2021 The Netty Project
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

import io.netty.buffer.ByteBuf;

import java.net.InetSocketAddress;

/**
 * @deprecated use {@link io.netty.channel.unix.SegmentedDatagramPacket}.
 */
@Deprecated
public final class SegmentedDatagramPacket extends io.netty.channel.unix.SegmentedDatagramPacket {

    /**
     * Create a new instance.
     *
     * @param data          the {@link ByteBuf} which must be continguous.
     * @param segmentSize   the segment size.
     * @param recipient     the recipient.
     */
    public SegmentedDatagramPacket(ByteBuf data, int segmentSize, InetSocketAddress recipient) {
        super(data, segmentSize, recipient);
        checkIsSupported();
    }

    /**
     * Create a new instance.
     *
     * @param data          the {@link ByteBuf} which must be continguous.
     * @param segmentSize   the segment size.
     * @param recipient     the recipient.
     */
    public SegmentedDatagramPacket(ByteBuf data, int segmentSize,
                                   InetSocketAddress recipient, InetSocketAddress sender) {
        super(data, segmentSize, recipient, sender);
        checkIsSupported();
    }

    /**
     * Returns {@code true} if the underlying system supports GSO.
     */
    public static boolean isSupported() {
        return Epoll.isAvailable() &&
                // We only support it together with sendmmsg(...)
                Native.IS_SUPPORTING_SENDMMSG && Native.IS_SUPPORTING_UDP_SEGMENT;
    }

    @Override
    public SegmentedDatagramPacket copy() {
        return new SegmentedDatagramPacket(content().copy(), segmentSize(), recipient(), sender());
    }

    @Override
    public SegmentedDatagramPacket duplicate() {
        return new SegmentedDatagramPacket(content().duplicate(), segmentSize(), recipient(), sender());
    }

    @Override
    public SegmentedDatagramPacket retainedDuplicate() {
        return new SegmentedDatagramPacket(content().retainedDuplicate(), segmentSize(), recipient(), sender());
    }

    @Override
    public SegmentedDatagramPacket replace(ByteBuf content) {
        return new SegmentedDatagramPacket(content, segmentSize(), recipient(), sender());
    }

    @Override
    public SegmentedDatagramPacket retain() {
        super.retain();
        return this;
    }

    @Override
    public SegmentedDatagramPacket retain(int increment) {
        super.retain(increment);
        return this;
    }

    @Override
    public SegmentedDatagramPacket touch() {
        super.touch();
        return this;
    }

    @Override
    public SegmentedDatagramPacket touch(Object hint) {
        super.touch(hint);
        return this;
    }

    private static void checkIsSupported() {
        if (!isSupported()) {
            throw new IllegalStateException();
        }
    }
}
