/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.carapaceproxy.server.http2;

import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Settings;

import static io.netty.handler.logging.LogLevel.INFO;

public final class ClientConnectionHandlerBuilder
        extends AbstractHttp2ConnectionHandlerBuilder<ClientConnectionHandler, ClientConnectionHandlerBuilder> {

    private static final Http2FrameLogger logger = new Http2FrameLogger(INFO, ClientConnectionHandler.class);

    public ClientConnectionHandlerBuilder() {
        frameLogger(logger);
    }

    @Override
    public ClientConnectionHandler build() {
        return super.build();
    }

    @Override
    protected ClientConnectionHandler build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                           Http2Settings initialSettings) {
        ClientConnectionHandler handler = new ClientConnectionHandler(decoder, encoder, initialSettings);
        frameListener(handler);
        return handler;
    }
}
