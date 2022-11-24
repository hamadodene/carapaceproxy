/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package org.carapaceproxy.server.config;

import java.util.Collections;
import java.util.Set;
import lombok.Data;

/**
 * Listens for connections on the network
 */
@Data
public class NetworkListenerConfiguration {

    public static final Set<String> DEFAULT_SSL_PROTOCOLS = Set.of("TLSv1.2", "TLSv1.3");

    private final String host;
    private final int port;
    private final boolean ssl;
    private final String sslCiphers;
    private final String defaultCertificate;
    private Set<String> sslProtocols = Collections.emptySet();

    public HostPort getKey() {
        return new HostPort(host, port);
    }

    public record HostPort(String host, int port) {}

    public NetworkListenerConfiguration(String host, int port) {
        this(host, port, false, null, null, Collections.emptySet());
    }

    public NetworkListenerConfiguration(String host,
                                        int port,
                                        boolean ssl,
                                        String sslCiphers,
                                        String defaultCertificate) {
        this(host, port, ssl, sslCiphers, defaultCertificate, DEFAULT_SSL_PROTOCOLS);
    }

    public NetworkListenerConfiguration(String host,
                                        int port,
                                        boolean ssl,
                                        String sslCiphers,
                                        String defaultCertificate,
                                        Set<String> sslProtocols) {
        this.host = host;
        this.port = port;
        this.ssl = ssl;
        this.sslCiphers = sslCiphers;
        this.defaultCertificate = defaultCertificate;
        if (ssl) {
            this.sslProtocols = sslProtocols;
        }
    }

}
