package com.digithink.zsretail.core.support;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 3.3.0, communication step 8b: an SMS gateway that catches every request on 127.0.0.1 and sends nothing, for the tests
 * of the provider http and the screen tests (nothing installed, no SMS leaves the PC). It answers as its mode says:
 * accepted, key refused (401: the key given is nowhere in the request), too many requests (429, with Retry-After when
 * set), server error (503), no answer (the request kept, never answered: the client's read timeout), 200 with an error
 * inside; a number refused (400) whatever the mode; any status asked. Under a path holding "/text" it answers plain text
 * ("OK CATCH-0001", "ERR ..."), otherwise JSON ({"status":"OK","messageId":"CATCH-0001"}, {"status":"ERROR","error":
 * "..."}). Step 8c: under a path ending with /messages it answers as Meta's WhatsApp Cloud API (accepted with a wamid,
 * the token refused #190, the throughput reached #130429, a template refused #132001, a recipient who cannot receive
 * #131026, Meta unavailable #2, no answer); a delay before each answer (a slow gateway). Each request kept in
 * {@link #requests()}. From the command line, one block per request on the console (the key shown as ***), and the mode
 * changed by GET /_catch/mode/&lt;MODE&gt;, /_catch/refuse/&lt;digits&gt;, /_catch/refuse-template/&lt;name&gt;,
 * /_catch/delay/&lt;ms&gt;, /_catch/retry-after/&lt;seconds&gt;, /_catch/accept-all:
 * {@code java -cp <test classes> com.digithink.zsretail.core.support.CatchHttpProvider <port> [key]}.
 */
public final class CatchHttpProvider implements Closeable {

	public enum Mode {
		ACCEPTED, KEY_REFUSED, TOO_MANY, SERVER_ERROR, NO_ANSWER, ERROR_INSIDE
	}

	/** One request caught. */
	public static final class Request {
		private final String method;
		private final String path;
		private final String query;
		private final Map<String, List<String>> headers;
		private final String body;
		private final int status;

		Request(String method, String path, String query, Map<String, List<String>> headers, String body, int status) {
			this.method = method;
			this.path = path;
			this.query = query;
			this.headers = headers;
			this.body = body;
			this.status = status;
		}

		public String getMethod() {
			return method;
		}

		public String getPath() {
			return path;
		}

		/** The query as it came (encoded), null when none. */
		public String getQuery() {
			return query;
		}

		public String header(String name) {
			for (Map.Entry<String, List<String>> e : headers.entrySet()) {
				if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
					return e.getValue().isEmpty() ? null : e.getValue().get(0);
				}
			}
			return null;
		}

		public String getBody() {
			return body;
		}

		/** The status answered (0: none, the no-answer mode). */
		public int getStatus() {
			return status;
		}

		/** The query's and a form body's fields, decoded (UTF-8). */
		public Map<String, String> params() {
			Map<String, String> params = new LinkedHashMap<>();
			decode(query, params);
			String type = header("Content-Type");
			if (type != null && type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
				decode(body, params);
			}
			return params;
		}

		private static void decode(String raw, Map<String, String> into) {
			if (raw == null || raw.isEmpty()) {
				return;
			}
			for (String pair : raw.split("&")) {
				int eq = pair.indexOf('=');
				try {
					into.put(URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8"),
							eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
				} catch (UnsupportedEncodingException e) {
					throw new IllegalStateException(e);
				}
			}
		}
	}

	private final HttpServer server;
	private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "catch-http");
		t.setDaemon(true);
		return t;
	});
	private final List<Request> requests = new CopyOnWriteArrayList<>();
	private final AtomicInteger accepted = new AtomicInteger();
	private final Set<String> refused = Collections.synchronizedSet(new HashSet<>());
	private final Set<String> refusedTemplates = Collections.synchronizedSet(new HashSet<>());
	private volatile long delayMillis;
	private final CountDownLatch closing = new CountDownLatch(1);
	private volatile String key;
	private volatile Mode mode = Mode.ACCEPTED;
	private volatile Integer retryAfter;
	private volatile Integer status;
	private volatile String location;
	private volatile boolean verbose;

	/** On 127.0.0.1, port 0 for any free port; key null: none asked. */
	public CatchHttpProvider(int port, String key) throws IOException {
		this.key = key;
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
		server.setExecutor(executor);
		server.createContext("/", this::handle);
		server.start();
	}

	public int getPort() {
		return server.getAddress().getPort();
	}

	public List<Request> requests() {
		return new ArrayList<>(requests);
	}

	public CatchHttpProvider mode(Mode mode) {
		this.mode = mode;
		return this;
	}

	/** The key a request must carry somewhere (null: none). */
	public CatchHttpProvider key(String key) {
		this.key = key;
		return this;
	}

	/** A number answered 400 "Invalid destination number" (its digits, without +). */
	public CatchHttpProvider refuse(String digits) {
		refused.add(digits);
		return this;
	}

	/** Step 8c: a WhatsApp template name answered #132001 (does not exist in that language or not approved). */
	public CatchHttpProvider refuseTemplate(String name) {
		refusedTemplates.add(name);
		return this;
	}

	/** Step 8c: a wait before each answer (a slow gateway), 0: none. */
	public CatchHttpProvider delay(long millis) {
		this.delayMillis = millis;
		return this;
	}

	/** The Retry-After of the 429 (and 503) answers, null: none. */
	public CatchHttpProvider retryAfter(Integer seconds) {
		this.retryAfter = seconds;
		return this;
	}

	/** Every request answered this status (null: as the mode says), with a Location for a 3xx. */
	public CatchHttpProvider status(Integer status, String location) {
		this.status = status;
		this.location = location;
		return this;
	}

	@Override
	public void close() {
		closing.countDown();
		server.stop(0);
		executor.shutdownNow();
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		if (path.startsWith("/_catch/")) {
			control(exchange, path.substring("/_catch/".length()));
			return;
		}
		String query = exchange.getRequestURI().getRawQuery();
		String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
		Map<String, List<String>> headers = new LinkedHashMap<>(exchange.getRequestHeaders());
		boolean text = path.contains("/text");
		String all = (query == null ? "" : decoded(query)) + " " + headers + " " + body + " " + decoded(body);
		int code;
		String answer;
		if (mode == Mode.NO_ANSWER) {
			keep(exchange, path, query, headers, body, 0);
			try {
				closing.await(120, TimeUnit.SECONDS); // never answered: the client's read timeout
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
			return;
		}
		if (delayMillis > 0) {
			try {
				Thread.sleep(delayMillis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		String refusedNumber = refused.stream().filter(all::contains).findFirst().orElse(null);
		if (path.endsWith("/messages") && status == null) {
			meta(exchange, path, query, headers, body, all, refusedNumber);
			return;
		}
		if (status != null) {
			code = status;
			answer = text ? "ERR status " + code : "{\"status\":\"ERROR\",\"error\":\"status " + code + "\"}";
			if (location != null) {
				exchange.getResponseHeaders().add("Location", location);
			}
		} else if (mode == Mode.KEY_REFUSED || key != null && !all.contains(key)) {
			code = 401;
			answer = text ? "ERR invalid API key" : "{\"status\":\"ERROR\",\"error\":\"invalid API key\"}";
		} else if (mode == Mode.TOO_MANY) {
			code = 429;
			answer = text ? "ERR too many requests" : "{\"status\":\"ERROR\",\"error\":\"too many requests\"}";
			if (retryAfter != null) {
				exchange.getResponseHeaders().add("Retry-After", String.valueOf(retryAfter));
			}
		} else if (mode == Mode.SERVER_ERROR) {
			code = 503;
			answer = text ? "ERR service unavailable" : "{\"status\":\"ERROR\",\"error\":\"service unavailable\"}";
			if (retryAfter != null) {
				exchange.getResponseHeaders().add("Retry-After", String.valueOf(retryAfter));
			}
		} else if (refusedNumber != null) {
			code = 400;
			answer = text ? "ERR Invalid destination number " + refusedNumber
					: "{\"status\":\"ERROR\",\"error\":\"Invalid destination number " + refusedNumber + "\"}";
		} else if (mode == Mode.ERROR_INSIDE) {
			code = 200;
			answer = text ? "ERR Sender name not allowed" : "{\"status\":\"ERROR\",\"error\":\"Sender name not allowed\"}";
		} else {
			code = 200;
			String id = String.format("CATCH-%04d", accepted.incrementAndGet());
			answer = text ? "OK " + id : "{\"status\":\"OK\",\"messageId\":\"" + id + "\"}";
		}
		keep(exchange, path, query, headers, body, code);
		byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", text ? "text/plain; charset=utf-8" : "application/json; charset=utf-8");
		exchange.sendResponseHeaders(code, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
		note("    answered " + code + ": " + answer);
	}

	/** Step 8c: Meta's WhatsApp Cloud API answers. */
	private void meta(HttpExchange exchange, String path, String query, Map<String, List<String>> headers, String body,
			String all, String refusedNumber) throws IOException {
		String template = refusedTemplates.stream().filter(name -> body.contains("\"name\":\"" + name + "\""))
				.findFirst().orElse(null);
		int code;
		String answer;
		if (mode == Mode.KEY_REFUSED || key != null && !all.contains(key)) {
			code = 401;
			answer = metaError(190, "Error validating access token: Session has expired on Friday, 21-Nov-26 10:00:00 PST.",
					null);
		} else if (mode == Mode.TOO_MANY) {
			code = 400;
			answer = metaError(130429, "(#130429) Rate limit hit", "Cloud API message throughput has been reached.");
		} else if (mode == Mode.SERVER_ERROR) {
			code = 503;
			answer = metaError(2, "(#2) Service temporarily unavailable", null);
		} else if (template != null || mode == Mode.ERROR_INSIDE) {
			code = 400;
			answer = metaError(132001, "(#132001) Template name does not exist in the translation", "template name ("
					+ (template == null ? "?" : template) + ") does not exist in the language");
		} else if (refusedNumber != null) {
			code = 400;
			answer = metaError(131026, "(#131026) Message undeliverable", "Unable to deliver message to " + refusedNumber
					+ ": not a WhatsApp number or old version");
		} else {
			code = 200;
			String id = String.format("wamid.CATCH-%04d", accepted.incrementAndGet());
			java.util.regex.Matcher to = java.util.regex.Pattern.compile("\"to\":\"([^\"]*)\"").matcher(body);
			String input = to.find() ? to.group(1) : "";
			answer = "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"input\":\"" + input + "\",\"wa_id\":\""
					+ input.replace("+", "") + "\"}],\"messages\":[{\"id\":\"" + id + "\"}]}";
		}
		keep(exchange, path, query, headers, body, code);
		byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(code, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
		note("    answered " + code + ": " + answer);
	}

	private static String metaError(int code, String message, String details) {
		return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"OAuthException\",\"code\":" + code
				+ (details == null ? "" : ",\"error_data\":{\"messaging_product\":\"whatsapp\",\"details\":\"" + details
						+ "\"}")
				+ ",\"fbtrace_id\":\"ACATCH" + code + "\"}}";
	}

	private void keep(HttpExchange exchange, String path, String query, Map<String, List<String>> headers, String body,
			int code) {
		Request request = new Request(exchange.getRequestMethod(), path, query, headers, body, code);
		requests.add(request);
		if (verbose) {
			StringBuilder out = new StringBuilder();
			out.append(LocalTime.now().withNano(0)).append(" request ").append(requests.size()).append(": ")
					.append(request.getMethod()).append(' ').append(hide(path + (query == null ? "" : "?" + query)));
			headers.forEach((name, values) -> {
				if (name != null && !name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Connection")
						&& !name.equalsIgnoreCase("Accept-Encoding")) {
					out.append("\n    ").append(name).append(": ").append(hide(String.join(", ", values)));
				}
			});
			if (!body.isEmpty()) {
				out.append("\n    body: ").append(hide(body));
			}
			Map<String, String> params = request.params();
			if (!params.isEmpty()) {
				out.append("\n    fields decoded: ").append(hide(params.toString()));
			}
			System.out.println(out);
		}
	}

	private String hide(String text) {
		return key == null || key.isEmpty() ? text : text.replace(key, "***");
	}

	private void note(String text) {
		if (verbose) {
			System.out.println(text);
		}
	}

	private void control(HttpExchange exchange, String command) throws IOException {
		String[] parts = command.split("/");
		String answer = "ok";
		switch (parts[0]) {
			case "mode":
				mode = Mode.valueOf(parts[1].toUpperCase(Locale.ROOT).replace('-', '_'));
				break;
			case "refuse":
				refused.add(parts[1]);
				break;
			case "retry-after":
				retryAfter = Integer.valueOf(parts[1]);
				break;
			case "refuse-template":
				refusedTemplates.add(parts[1]);
				break;
			case "delay":
				delayMillis = Long.parseLong(parts[1]);
				break;
			case "accept-all":
				mode = Mode.ACCEPTED;
				refused.clear();
				refusedTemplates.clear();
				delayMillis = 0;
				retryAfter = null;
				status = null;
				break;
			default:
				answer = "unknown";
		}
		note(LocalTime.now().withNano(0) + " control: " + command + " -> " + answer + " (mode " + mode + ", refused "
				+ refused + ")");
		byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static String decoded(String raw) {
		try {
			return raw == null ? "" : URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
		} catch (UnsupportedEncodingException | IllegalArgumentException e) {
			return raw;
		}
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int n;
		while ((n = in.read(buffer)) > 0) {
			out.write(buffer, 0, n);
		}
		return out.toByteArray();
	}

	/** Port, optional key; runs until stopped. */
	public static void main(String[] args) throws Exception {
		CatchHttpProvider provider = new CatchHttpProvider(Integer.parseInt(args[0]), args.length > 1 ? args[1] : null);
		provider.verbose = true;
		System.out.println("Catch HTTP provider on 127.0.0.1:" + provider.getPort() + (args.length > 1 ? ", a key asked"
				: "") + "; control: /_catch/mode/<ACCEPTED|KEY_REFUSED|TOO_MANY|SERVER_ERROR|NO_ANSWER|ERROR_INSIDE>,"
				+ " /_catch/refuse/<digits>, /_catch/refuse-template/<name>, /_catch/delay/<ms>, /_catch/retry-after/<s>,"
				+ " /_catch/accept-all");
		Thread.currentThread().join();
	}
}
