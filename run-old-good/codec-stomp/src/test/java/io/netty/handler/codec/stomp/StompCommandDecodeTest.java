/*
 * Copyright 2020 The Netty Project
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
package io.netty.handler.codec.stomp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Arrays;
import java.util.Collection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.netty.util.CharsetUtil.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StompCommandDecodeTest {

    private EmbeddedChannel channel;

    @BeforeEach
    public void setUp() {
        channel = new EmbeddedChannel(new StompSubframeDecoder(true));
    }

    @AfterEach
    public void tearDown() {
        assertFalse(channel.finish());
    }

    @ParameterizedTest(name = "{index}: testDecodeCommand({0}) = {1}")
    @MethodSource("stompCommands")
    public void testDecodeCommand(String rawCommand, StompCommand expectedCommand, Boolean valid) {
        byte[] frameContent = String.format("%s\n\n\0", rawCommand).getBytes(UTF_8);
        ByteBuf incoming = Unpooled.wrappedBuffer(frameContent);
        assertTrue(channel.writeInbound(incoming));

        StompHeadersSubframe frame = channel.readInbound();

        assertNotNull(frame);
        assertEquals(expectedCommand, frame.command());

        if (valid) {
            assertTrue(frame.decoderResult().isSuccess());

            StompContentSubframe content = channel.readInbound();
            assertSame(LastStompContentSubframe.EMPTY_LAST_CONTENT, content);
            content.release();
        } else {
            assertTrue(frame.decoderResult().isFailure());
            assertNull(channel.readInbound());
        }
    }

    public static Collection<Object[]> stompCommands() {
        return Arrays.asList(new Object[][] {
                { "STOMP", StompCommand.STOMP, true },
                { "CONNECT", StompCommand.CONNECT, true },
                { "SEND", StompCommand.SEND, true },
                { "SUBSCRIBE", StompCommand.SUBSCRIBE, true },
                { "UNSUBSCRIBE", StompCommand.UNSUBSCRIBE, true },
                { "ACK", StompCommand.ACK, true },
                { "NACK", StompCommand.NACK, true },
                { "BEGIN", StompCommand.BEGIN, true },
                { "ABORT", StompCommand.ABORT, true },
                { "COMMIT", StompCommand.COMMIT, true },
                { "DISCONNECT", StompCommand.DISCONNECT, true },

                // invalid commands
                { "INVALID", StompCommand.UNKNOWN, false },
                { "disconnect", StompCommand.UNKNOWN , false }
        });
    }
}
