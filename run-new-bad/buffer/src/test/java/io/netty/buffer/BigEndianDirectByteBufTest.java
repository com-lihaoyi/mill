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
package io.netty.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/**
 * Tests big-endian direct channel buffers
 */
public class BigEndianDirectByteBufTest extends AbstractByteBufTest {

    @Override
    protected ByteBuf newBuffer(int length, int maxCapacity) {
        ByteBuf buffer = newDirectBuffer(length, maxCapacity);
        assertSame(ByteOrder.BIG_ENDIAN, buffer.order());
        assertEquals(0, buffer.writerIndex());
        return buffer;
    }

    protected ByteBuf newDirectBuffer(int length, int maxCapacity) {
        return new UnpooledDirectByteBuf(UnpooledByteBufAllocator.DEFAULT, length, maxCapacity);
    }

    @Test
    public void testIsContiguous() {
        ByteBuf buf = newBuffer(4);
        assertTrue(buf.isContiguous());
        buf.release();
    }
}
