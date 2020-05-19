/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.carapaceproxy.server.http2;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;


/**
 * Handles HTTP/2 stream frame responses.
 */
public final class EndpointConnectionHandler extends SimpleChannelInboundHandler<Http2StreamFrame> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Http2StreamFrame msg) throws Exception {
        System.out.println("Received HTTP/2 'stream' frame: " + msg);

        if (msg instanceof Http2DataFrame && ((Http2DataFrame) msg).isEndStream()) {

        } else if (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream()) {

        }
    }

}
