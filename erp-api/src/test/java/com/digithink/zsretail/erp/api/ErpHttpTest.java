package com.digithink.zsretail.erp.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Boot 4 migration, step 1.7 (decision 5): what the shared client of the connectors does, against a server of this JVM on
 * 127.0.0.1. Not the NTLM proof: that one is a live GET and POST on ZEN's test NAV, at step 2.7. Here, that the client
 * answers an NTLM challenge at all (HttpClient 5 does not by default), sends the login only where it is meant to, and that
 * a slow answer ends at the read timeout.
 */
class ErpHttpTest {

	/** The start of every NTLM negotiate message (type 1), "NTLMSSP\0" and 1, in base64. */
	private static final String NTLM_TYPE_1 = "NTLM TlRMTVNTUAAB";

	private final List<String> authorizations = new CopyOnWriteArrayList<>();
	private HttpServer server;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	/** Without an Authorization header: 401 asking for NTLM. With one: 200. Every header received is kept. */
	private String ntlmServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String authorization = exchange.getRequestHeaders().getFirst("Authorization");
			authorizations.add(String.valueOf(authorization));
			if (authorization == null) {
				exchange.getResponseHeaders().add("WWW-Authenticate", "NTLM");
				answer(exchange, 401, "");
			} else {
				answer(exchange, 200, "ok");
			}
		});
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
	}

	private static void answer(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			exchange.getResponseBody().write(bytes);
		}
		exchange.close();
	}

	@Test
	@DisplayName("Asked for NTLM, the client answers with an NTLM negotiate message")
	void answersAnNtlmChallenge() throws IOException {
		String url = ntlmServer();
		RestTemplate template = new RestTemplate(ErpHttp.requestFactory(ErpHttp.ntlm("user", "secret", "DOMAIN"), 5, 5));
		assertEquals("ok", template.getForObject(url, String.class));
		assertEquals(2, authorizations.size(), authorizations.toString());
		assertEquals("null", authorizations.get(0));
		assertTrue(authorizations.get(1).startsWith(NTLM_TYPE_1), authorizations.get(1));
	}

	@Test
	@DisplayName("The user name alone, without a domain, as ZEN's test NAV wants it")
	void withoutDomain() throws IOException {
		String url = ntlmServer();
		RestTemplate template = new RestTemplate(ErpHttp.requestFactory(ErpHttp.ntlm("user", "secret", null), 5, 5));
		assertEquals("ok", template.getForObject(url, String.class));
		assertTrue(authorizations.get(1).startsWith(NTLM_TYPE_1), authorizations.get(1));
	}

	@Test
	@DisplayName("A login meant for another host is not sent: the ERP's 401 comes back")
	void onlyForItsHost() throws IOException {
		String url = ntlmServer();
		RestTemplate template = new RestTemplate(
				ErpHttp.requestFactory(ErpHttp.ntlm("user", "secret", "DOMAIN").onlyFor("erp.example", -1), 5, 5));
		assertThrows(org.springframework.web.client.HttpClientErrorException.Unauthorized.class,
				() -> template.getForObject(url, String.class));
		assertEquals(List.of("null"), authorizations);
	}

	@Test
	@DisplayName("Without a login, a call goes out without one (ZEN's products address)")
	void noLogin() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
			answer(exchange, 200, "ok");
		});
		server.start();
		RestTemplate template = new RestTemplate(ErpHttp.requestFactory(null, 5, 5));
		assertEquals("ok", template.getForObject("http://127.0.0.1:" + server.getAddress().getPort() + "/", String.class));
		assertEquals(List.of("null"), authorizations);
	}

	@Test
	@DisplayName("An answer slower than the read timeout fails on a read timeout, it does not hold the call")
	void readTimeout() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			try {
				Thread.sleep(4000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			answer(exchange, 200, "late");
		});
		server.start();
		RestTemplate template = new RestTemplate(ErpHttp.requestFactory(null, 5, 1));
		long start = System.nanoTime();
		ResourceAccessException failure = assertThrows(ResourceAccessException.class,
				() -> template.getForObject("http://127.0.0.1:" + server.getAddress().getPort() + "/", String.class));
		long millis = (System.nanoTime() - start) / 1_000_000;
		assertInstanceOf(SocketTimeoutException.class, NestedExceptionUtils.getMostSpecificCause(failure), failure.toString());
		assertTrue(millis < 3500, "ended after " + millis + " ms, the read timeout is 1 s");
	}
}
