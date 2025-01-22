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

import io.netty.handler.codec.DecoderResult;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

/**
 * The default {@link Socks5InitialResponse}.
 */
public class DefaultSocks5InitialResponse extends AbstractSocks5Message implements Socks5InitialResponse {

    private final Socks5AuthMethod authMethod;

    public DefaultSocks5InitialResponse(Socks5AuthMethod authMethod) {
        this.authMethod = ObjectUtil.checkNotNull(authMethod, "authMethod");
    }

    @Override
    public Socks5AuthMethod authMethod() {
        return authMethod;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(StringUtil.simpleClassName(this));

        DecoderResult decoderResult = decoderResult();
        if (!decoderResult.isSuccess()) {
            buf.append("(decoderResult: ");
            buf.append(decoderResult);
            buf.append(", authMethod: ");
        } else {
            buf.append("(authMethod: ");
        }
        buf.append(authMethod());
        buf.append(')');

        return buf.toString();
    }
}
