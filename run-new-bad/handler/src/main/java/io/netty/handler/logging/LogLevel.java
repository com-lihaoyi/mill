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
package io.netty.handler.logging;

import io.netty.util.internal.logging.InternalLogLevel;

/**
 * Maps the regular {@link LogLevel}s with the {@link InternalLogLevel} ones.
 */
public enum LogLevel {
    TRACE(InternalLogLevel.TRACE),
    DEBUG(InternalLogLevel.DEBUG),
    INFO(InternalLogLevel.INFO),
    WARN(InternalLogLevel.WARN),
    ERROR(InternalLogLevel.ERROR);

    private final InternalLogLevel internalLevel;

    LogLevel(InternalLogLevel internalLevel) {
        this.internalLevel = internalLevel;
    }

    /**
     * For internal use only.
     *
     * <p/>Converts the specified {@link LogLevel} to its {@link InternalLogLevel} variant.
     *
     * @return the converted level.
     */
    public InternalLogLevel toInternalLevel() {
        return internalLevel;
    }
}
