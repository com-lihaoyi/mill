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
package io.netty.handler.codec.http.websocketx.extensions;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebSocketExtensionUtil {

    private static final String EXTENSION_SEPARATOR = ",";
    private static final String PARAMETER_SEPARATOR = ";";
    private static final char PARAMETER_EQUAL = '=';

    private static final Pattern PARAMETER = Pattern.compile("^([^=]+)(=[\\\"]?([^\\\"]+)[\\\"]?)?$");

    static boolean isWebsocketUpgrade(HttpHeaders headers) {
        //this contains check does not allocate an iterator, and most requests are not upgrades
        //so we do the contains check first before checking for specific values
        return headers.contains(HttpHeaderNames.UPGRADE) &&
                headers.containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true) &&
                headers.contains(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true);
    }

    public static List<WebSocketExtensionData> extractExtensions(String extensionHeader) {
        String[] rawExtensions = extensionHeader.split(EXTENSION_SEPARATOR);
        if (rawExtensions.length > 0) {
            List<WebSocketExtensionData> extensions = new ArrayList<WebSocketExtensionData>(rawExtensions.length);
            for (String rawExtension : rawExtensions) {
                String[] extensionParameters = rawExtension.split(PARAMETER_SEPARATOR);
                String name = extensionParameters[0].trim();
                Map<String, String> parameters;
                if (extensionParameters.length > 1) {
                    parameters = new HashMap<String, String>(extensionParameters.length - 1);
                    for (int i = 1; i < extensionParameters.length; i++) {
                        String parameter = extensionParameters[i].trim();
                        Matcher parameterMatcher = PARAMETER.matcher(parameter);
                        if (parameterMatcher.matches() && parameterMatcher.group(1) != null) {
                            parameters.put(parameterMatcher.group(1), parameterMatcher.group(3));
                        }
                    }
                } else {
                    parameters = Collections.emptyMap();
                }
                extensions.add(new WebSocketExtensionData(name, parameters));
            }
            return extensions;
        } else {
            return Collections.emptyList();
        }
    }

    static String computeMergeExtensionsHeaderValue(String userDefinedHeaderValue,
                                                    List<WebSocketExtensionData> extraExtensions) {
        List<WebSocketExtensionData> userDefinedExtensions =
          userDefinedHeaderValue != null ?
            extractExtensions(userDefinedHeaderValue) :
            Collections.<WebSocketExtensionData>emptyList();

        for (WebSocketExtensionData userDefined: userDefinedExtensions) {
            WebSocketExtensionData matchingExtra = null;
            int i;
            for (i = 0; i < extraExtensions.size(); i ++) {
                WebSocketExtensionData extra = extraExtensions.get(i);
                if (extra.name().equals(userDefined.name())) {
                    matchingExtra = extra;
                    break;
                }
            }
            if (matchingExtra == null) {
                extraExtensions.add(userDefined);
            } else {
                // merge with higher precedence to user defined parameters
                Map<String, String> mergedParameters = new HashMap<String, String>(matchingExtra.parameters());
                mergedParameters.putAll(userDefined.parameters());
                extraExtensions.set(i, new WebSocketExtensionData(matchingExtra.name(), mergedParameters));
            }
        }

        StringBuilder sb = new StringBuilder(150);

        for (WebSocketExtensionData data: extraExtensions) {
            sb.append(data.name());
            for (Entry<String, String> parameter : data.parameters().entrySet()) {
                sb.append(PARAMETER_SEPARATOR);
                sb.append(parameter.getKey());
                if (parameter.getValue() != null) {
                    sb.append(PARAMETER_EQUAL);
                    sb.append(parameter.getValue());
                }
            }
            sb.append(EXTENSION_SEPARATOR);
        }

        if (!extraExtensions.isEmpty()) {
            sb.setLength(sb.length() - EXTENSION_SEPARATOR.length());
        }

        return sb.toString();
    }

    private WebSocketExtensionUtil() {
        // Unused
    }
}
