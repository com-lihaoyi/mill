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
package io.netty.handler.ssl;

import java.util.List;

/**
 * Interface to support Application Protocol Negotiation.
 * <p>
 * Default implementations are provided for:
 * <ul>
 * <li><a href="https://technotes.googlecode.com/git/nextprotoneg.html">Next Protocol Negotiation</a></li>
 * <li><a href="https://tools.ietf.org/html/rfc7301">Application-Layer Protocol Negotiation</a></li>
 * </ul>
 *
 * @deprecated use {@link ApplicationProtocolConfig}
 */
@SuppressWarnings("deprecation")
public interface ApplicationProtocolNegotiator {
    /**
     * Get the collection of application protocols supported by this application (in preference order).
     */
    List<String> protocols();
}
