/*
 * Copyright 2013 The Netty Project
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

package io.netty.handler.codec.socks;

public enum SocksAuthStatus {
    SUCCESS((byte) 0x00),
    FAILURE((byte) 0xff);

    private final byte b;

    SocksAuthStatus(byte b) {
        this.b = b;
    }

    /**
     * @deprecated Use {@link #valueOf(byte)} instead.
     */
    @Deprecated
    public static SocksAuthStatus fromByte(byte b) {
        return valueOf(b);
    }

    public static SocksAuthStatus valueOf(byte b) {
        for (SocksAuthStatus code : values()) {
            if (code.b == b) {
                return code;
            }
        }
        return FAILURE;
    }

    public byte byteValue() {
        return b;
    }
}

