package org.carapaceproxy.server.filters;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static org.junit.Assert.assertTrue;

public class XTlsCipherFilterTest {
    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void TestXTlsProtocol() throws Exception {
        String certificate = TestUtils.deployResource("localhost.p12", tmpDir.getRoot());

        stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Protocol", equalTo("TLSv1.2"))
                .withHeader("X-Tls-Cipher", matching("TLS_.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Cipher", absent())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>absent</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.addCertificate(new SSLCertificateConfiguration("*", certificate, "testproxy", STATIC));
            server.addRequestFilter(new RequestFilterConfiguration(XTlsCipherRequestFilter.TYPE, Collections.emptyMap()));
            server.addRequestFilter(new RequestFilterConfiguration(XTlsProtocolRequestFilter.TYPE, Collections.emptyMap()));
            server.addListener(new NetworkListenerConfiguration("0.0.0.0", 0, true, null, "*", "TLSv1.2"));
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port, true, null, new String[]{"TLSv1.2"}, null, 10_000)) {
                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
            }
        }

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.addCertificate(new SSLCertificateConfiguration("*", certificate, "testproxy", STATIC));
            server.addRequestFilter(new RequestFilterConfiguration(XTlsProtocolRequestFilter.TYPE, Collections.emptyMap()));
            server.addListener(new NetworkListenerConfiguration("0.0.0.0", 0, true, null, "*", "TLSv1.2"));
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port, true, null, new String[]{"TLSv1.2"}, null, 10_000)) {
                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>absent</b> !!"));
            }
        }

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.addRequestFilter(new RequestFilterConfiguration(XTlsCipherRequestFilter.TYPE, Collections.emptyMap()));

            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>absent</b> !!"));
            }
        }
    }
}
