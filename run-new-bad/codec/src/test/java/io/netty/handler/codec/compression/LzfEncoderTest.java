/*
 * Copyright 2014 The Netty Project
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
package io.netty.handler.codec.compression;

import com.ning.compress.lzf.LZFDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

public class LzfEncoderTest extends AbstractEncoderTest {

    @Override
    protected EmbeddedChannel createChannel() {
        return new EmbeddedChannel(new LzfEncoder());
    }

    @Override
    protected ByteBuf decompress(ByteBuf compressed, int originalLength) throws Exception {
        byte[] compressedArray = new byte[compressed.readableBytes()];
        compressed.readBytes(compressedArray);
        compressed.release();

        byte[] decompressed = LZFDecoder.decode(compressedArray);
        return Unpooled.wrappedBuffer(decompressed);
    }
}
