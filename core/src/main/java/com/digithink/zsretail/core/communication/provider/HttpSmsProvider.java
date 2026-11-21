package com.digithink.zsretail.core.communication.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import javax.annotation.PreDestroy;
import javax.net.ssl.SSLException;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.DateUtils;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HttpCoreContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.service.CommunicationSettings;
import com.digithink.zsretail.core.communication.service.SmsParts;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 3.3.0, communication step 8b: the provider "http" of the SMS channel (communication.sms.provider=http), a gateway
 * plugged in by filling lines, no code: one HTTP call per SMS, everything from communication.sms.http.* (the address,
 * GET or POST, the fields in the address, a form or JSON, their names and fixed values, the headers, the key or login,
 * the timeouts, the phone's format, how to read the answer). Two worked examples in core's application.properties.
 * <p>
 * Placeholders in the address, the fields, the headers and the JSON template: {phone}, {text}, {sender}, {messageId}
 * (our reference, 32 characters, kept when the SMS is sent again: a gateway that knows it can refuse a double),
 * {unicode} (communication.sms.http.unicode-values: the first value when the text needs Unicode, Arabic, the second
 * otherwise; the same rule as the template's parts count, SmsParts), {key}, {username}, {password}, {basic} (Base64 of
 * username:password). An unknown placeholder, or {key} with no key, turns the channel off with a WARN at the start.
 * <p>
 * Its own HTTP client: connect and read timeouts, no automatic retry, no redirect followed, a new connection per SMS (a
 * dead pooled connection is never retried behind our back: an SMS costs). The answers:
 * <ul>
 * <li>the channel's problems (the channel pauses, the SMS waits with its attempts): no connection (unreachable, a
 * connect timeout, TLS), 401 / 403 / 407 (the key or login refused), 402, 404 / 405 / 415 (the address or method is not
 * the gateway's: never one number's fault), 3xx (a redirect), 5xx, 429 (with the wait the gateway gives, Retry-After), a
 * refusal whose words hold one of communication.sms.http.channel-error-texts (e.g. no credit left);</li>
 * <li>the SMS's own refusal, FAILED at once with the gateway's words: another 4xx (a number refused), or a status of
 * communication.sms.http.accepted-status whose answer fails the accepted rule (a 200 with an error inside);</li>
 * <li>408: tried again with the waits of every message;</li>
 * <li>sent and no answer (a read timeout, the connection cut once the request left): uncertain, the gateway may have
 * taken it: FAILED "no answer from the provider", sent again by hand only, the channel paused.</li>
 * </ul>
 * The key, the login and the password come from the outside file; they are never logged, never shown (the Canaux tab:
 * the host only, "set" / "not set"), and masked in any gateway's words kept on a message. See
 * docs/modules/communication.md, "Step 8b".
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
public class HttpSmsProvider implements CommunicationProvider {

	public static final String NAME = "http";
	public static final String PREFIX = "communication.sms.http.";
	public static final String URL_KEY = PREFIX + "url";
	public static final String METHOD_KEY = PREFIX + "method";
	public static final String BODY_KEY = PREFIX + "body";
	public static final String JSON_KEY = PREFIX + "json-template";
	public static final String KEY_KEY = PREFIX + "key";
	public static final String USERNAME_KEY = PREFIX + "username";
	public static final String PASSWORD_KEY = PREFIX + "password";
	public static final String SENDER_KEY = PREFIX + "sender";
	public static final String PHONE_FORMAT_KEY = PREFIX + "phone-format";
	public static final String UNICODE_VALUES_KEY = PREFIX + "unicode-values";
	public static final String CONNECT_TIMEOUT_KEY = PREFIX + "connect-timeout-seconds";
	public static final String READ_TIMEOUT_KEY = PREFIX + "read-timeout-seconds";
	public static final String ACCEPTED_STATUS_KEY = PREFIX + "accepted-status";
	public static final String ACCEPTED_FIELD_KEY = PREFIX + "accepted-field";
	public static final String ACCEPTED_VALUE_KEY = PREFIX + "accepted-value";
	public static final String ACCEPTED_TEXT_KEY = PREFIX + "accepted-text";
	public static final String ID_FIELD_KEY = PREFIX + "id-field";
	public static final String ID_REGEX_KEY = PREFIX + "id-regex";
	public static final String ERROR_FIELD_KEY = PREFIX + "error-field";
	public static final String CHANNEL_ERRORS_KEY = PREFIX + "channel-error-texts";
	/** Step 8c: true for a gateway that refuses doubles on {messageId}: a "no answer" is tried again, not FAILED. */
	public static final String RETRY_NO_ANSWER_KEY = PREFIX + "retry-no-answer";
	/** One line per field: communication.sms.http.field.&lt;name&gt;=&lt;value&gt;. */
	public static final String FIELD_PREFIX = PREFIX + "field.";
	/** One line per header: communication.sms.http.header.&lt;Name&gt;=&lt;value&gt;. */
	public static final String HEADER_PREFIX = PREFIX + "header.";

	/** The phone's formats: +21620123456, 21620123456, 0021620123456, 20123456. */
	public static final List<String> PHONE_FORMATS = Arrays.asList("with-plus", "without-plus", "with-00", "local");
	static final List<String> PLACEHOLDERS = Arrays.asList("phone", "text", "sender", "messageId", "unicode", "key",
			"username", "password", "basic");
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)\\}");
	static final int MAX_ANSWER = 64 * 1024;
	static final int MAX_WORDS = 300;
	/** Step 8c: a gateway's asked wait (Retry-After) never pauses the channel longer than an hour. */
	static final Duration MAX_WAIT = Duration.ofMinutes(60);

	private static final ObjectMapper JSON = new ObjectMapper();

	private final String url;
	private final String method;
	private final String body;
	private final String jsonTemplate;
	private final String key;
	private final String username;
	private final String password;
	private final String senderName;
	private final String phoneFormat;
	private final String unicodeYes;
	private final String unicodeNo;
	private final int connectSeconds;
	private final int readSeconds;
	private final List<int[]> acceptedStatus;
	private final String acceptedField;
	private final Set<String> acceptedValues;
	private final String acceptedText;
	private final String idField;
	private final Pattern idPattern;
	private final String errorField;
	private final List<String> channelErrors;
	private final boolean retryNoAnswer;
	private final String badRetryNoAnswer;
	private final Map<String, String> fields;
	private final Map<String, String> headers;
	private final String countryCode;
	private final String host;
	private final String unavailable;
	private final CloseableHttpClient client;

	@Autowired
	public HttpSmsProvider(Environment env) {
		url = text(env, URL_KEY);
		method = text(env, METHOD_KEY) == null ? "GET" : text(env, METHOD_KEY).toUpperCase(Locale.ROOT);
		body = text(env, BODY_KEY) == null ? "query" : text(env, BODY_KEY).toLowerCase(Locale.ROOT);
		jsonTemplate = text(env, JSON_KEY);
		key = secret(env, KEY_KEY);
		username = text(env, USERNAME_KEY);
		password = secret(env, PASSWORD_KEY);
		senderName = text(env, SENDER_KEY);
		phoneFormat = text(env, PHONE_FORMAT_KEY) == null ? "with-plus" : text(env, PHONE_FORMAT_KEY).toLowerCase(Locale.ROOT);
		String unicodeValues = text(env, UNICODE_VALUES_KEY) == null ? "true,false" : text(env, UNICODE_VALUES_KEY);
		int comma = unicodeValues.indexOf(',');
		unicodeYes = comma < 0 ? null : unicodeValues.substring(0, comma).trim();
		unicodeNo = comma < 0 ? null : unicodeValues.substring(comma + 1).trim();
		Integer connect = number(env, CONNECT_TIMEOUT_KEY, 10);
		Integer read = number(env, READ_TIMEOUT_KEY, 30);
		connectSeconds = connect == null ? 10 : connect;
		readSeconds = read == null ? 30 : read;
		acceptedStatus = statuses(text(env, ACCEPTED_STATUS_KEY) == null ? "200-299" : text(env, ACCEPTED_STATUS_KEY));
		acceptedField = text(env, ACCEPTED_FIELD_KEY);
		acceptedValues = list(text(env, ACCEPTED_VALUE_KEY)).stream().map(v -> v.toLowerCase(Locale.ROOT))
				.collect(Collectors.toCollection(LinkedHashSet::new));
		acceptedText = text(env, ACCEPTED_TEXT_KEY);
		idField = text(env, ID_FIELD_KEY);
		errorField = text(env, ERROR_FIELD_KEY);
		channelErrors = list(text(env, CHANNEL_ERRORS_KEY)).stream().map(v -> v.toLowerCase(Locale.ROOT))
				.collect(Collectors.toList());
		String retry = text(env, RETRY_NO_ANSWER_KEY);
		retryNoAnswer = "true".equalsIgnoreCase(retry);
		badRetryNoAnswer = retry == null || "true".equalsIgnoreCase(retry) || "false".equalsIgnoreCase(retry) ? null
				: RETRY_NO_ANSWER_KEY + " is not true or false";
		fields = withPrefix(env, FIELD_PREFIX);
		headers = withPrefix(env, HEADER_PREFIX);
		String code;
		try {
			code = CommunicationSettings.countryCode(env.getProperty(CommunicationSettings.COUNTRY_CODE_KEY));
		} catch (IllegalStateException e) {
			code = "216"; // the settings refuse the start on it anyway
		}
		countryCode = code;
		Pattern pattern = null;
		String problem = null;
		String idRegex = text(env, ID_REGEX_KEY);
		if (idRegex != null) {
			try {
				pattern = Pattern.compile(idRegex);
				if (pattern.matcher("").groupCount() < 1) {
					problem = ID_REGEX_KEY + " has no group ( ) around the id";
				}
			} catch (PatternSyntaxException e) {
				problem = ID_REGEX_KEY + " is not a regular expression";
			}
		}
		idPattern = pattern;
		host = hostOf(url);
		unavailable = problem != null ? problem
				: check(connect == null ? CONNECT_TIMEOUT_KEY : null, read == null ? READ_TIMEOUT_KEY : null);
		RequestConfig config = RequestConfig.custom().setConnectTimeout(connectSeconds * 1000)
				.setConnectionRequestTimeout(connectSeconds * 1000).setSocketTimeout(readSeconds * 1000)
				.setRedirectsEnabled(false).build();
		client = HttpClients.custom().setDefaultRequestConfig(config).disableAutomaticRetries().disableRedirectHandling()
				.disableCookieManagement().disableAuthCaching().setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE)
				.setUserAgent("ZS Retail").build();
	}

	/** Why the lines cannot send (the first problem found), null when they can. Never the url's or a secret's value. */
	private String check(String badConnect, String badRead) {
		if (url == null) {
			return URL_KEY + " is empty";
		}
		if (host == null) {
			return URL_KEY + " is not an http or https address";
		}
		if (!"GET".equals(method) && !"POST".equals(method)) {
			return METHOD_KEY + " is not GET or POST";
		}
		if (!Arrays.asList("query", "form", "json").contains(body)) {
			return BODY_KEY + " is not query, form or json";
		}
		if ("GET".equals(method) && !"query".equals(body)) {
			return "a GET sends its fields in the address: " + BODY_KEY + " must be query";
		}
		if (jsonTemplate != null && !"json".equals(body)) {
			return JSON_KEY + " is set, but " + BODY_KEY + " is not json";
		}
		if (!PHONE_FORMATS.contains(phoneFormat)) {
			return PHONE_FORMAT_KEY + " is not " + String.join(", ", PHONE_FORMATS);
		}
		if (unicodeYes == null) {
			return UNICODE_VALUES_KEY + " is not two values separated by a comma (Unicode, then not)";
		}
		if (badConnect != null || badRead != null) {
			return (badConnect != null ? badConnect : badRead) + " is not 1 to 300 seconds";
		}
		if (acceptedStatus == null) {
			return ACCEPTED_STATUS_KEY + " is not a list of statuses or ranges (e.g. 200-299 or 200,202)";
		}
		if (acceptedField != null && acceptedValues.isEmpty()) {
			return ACCEPTED_FIELD_KEY + " is set without " + ACCEPTED_VALUE_KEY;
		}
		Map<String, String> templates = new LinkedHashMap<>();
		templates.put(URL_KEY, url);
		fields.forEach((name, value) -> templates.put(FIELD_PREFIX + name, value));
		headers.forEach((name, value) -> templates.put(HEADER_PREFIX + name, value));
		if (jsonTemplate != null) {
			templates.put(JSON_KEY, jsonTemplate);
		}
		Set<String> used = new LinkedHashSet<>();
		for (Map.Entry<String, String> template : templates.entrySet()) {
			Matcher m = PLACEHOLDER.matcher(template.getValue());
			while (m.find()) {
				if (!PLACEHOLDERS.contains(m.group(1))) {
					return template.getKey() + " uses {" + m.group(1) + "}, not a placeholder ("
							+ PLACEHOLDERS.stream().map(p -> "{" + p + "}").collect(Collectors.joining(", ")) + ")";
				}
				used.add(m.group(1));
			}
		}
		if (!used.contains("phone") || !used.contains("text")) {
			return "no field carries " + (used.contains("phone") ? "{text}" : "{phone}") + " (" + FIELD_PREFIX + "<name>, "
					+ URL_KEY + " or " + JSON_KEY + ")";
		}
		if (badRetryNoAnswer != null) {
			return badRetryNoAnswer;
		}
		if (retryNoAnswer && !used.contains("messageId")) {
			return RETRY_NO_ANSWER_KEY + " is true, but no field carries {messageId}: the gateway could not refuse a double";
		}
		if (used.contains("key") && key == null) {
			return "{key} is used, but " + KEY_KEY + " is empty";
		}
		if ((used.contains("username") || used.contains("basic")) && username == null) {
			return "{" + (used.contains("basic") ? "basic" : "username") + "} is used, but " + USERNAME_KEY + " is empty";
		}
		if ((used.contains("password") || used.contains("basic")) && password == null) {
			return "{" + (used.contains("basic") ? "basic" : "password") + "} is used, but " + PASSWORD_KEY + " is empty";
		}
		return null;
	}

	@PreDestroy
	public void close() throws IOException {
		client.close();
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public boolean serves(String channel) {
		return CommunicationTypes.SMS.equals(channel);
	}

	@Override
	public String unavailableReason() {
		return unavailable;
	}

	@Override
	public String sender() {
		return senderName;
	}

	/** The host only (never the path, the fields or a key), the method, the body, the phone's format, the timeouts. */
	@Override
	public Map<String, Object> describe() {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("host", host);
		view.put("method", method);
		view.put("body", body);
		view.put("phoneFormat", phoneFormat);
		view.put("username", username);
		view.put("key", key == null ? "not set" : "set");
		view.put("password", password == null ? "not set" : "set");
		view.put("connectTimeoutSeconds", connectSeconds);
		view.put("readTimeoutSeconds", readSeconds);
		view.put("retryNoAnswer", retryNoAnswer);
		return view;
	}

	@Override
	public SendResult send(CommunicationMessage message) {
		if (unavailable != null) {
			return SendResult.channelProblem("http cannot send: " + unavailable);
		}
		String phone = phone(message.getAddress());
		if (phone == null) {
			return SendResult.permanentFailure("invalid phone '" + message.getAddress() + "': not sent");
		}
		Map<String, String> values = values(message, phone);
		HttpRequestBase request;
		try {
			request = request(values);
		} catch (IllegalArgumentException e) {
			return SendResult.channelProblem("SMS address not valid once filled (" + URL_KEY + ")");
		}
		HttpClientContext context = HttpClientContext.create();
		CloseableHttpResponse response;
		try {
			response = client.execute(request, context);
		} catch (IOException e) {
			// HTTP_REQ_SENT: set by the client once the request is written and flushed
			boolean sent = Boolean.TRUE.equals(context.getAttribute(HttpCoreContext.HTTP_REQ_SENT));
			return sent ? noAnswer(e) : notSent(e);
		}
		try {
			int status = response.getStatusLine().getStatusCode();
			String answer;
			try {
				answer = read(response.getEntity());
			} catch (IOException e) {
				return SendResult.uncertain("SMS: the answer was cut (HTTP " + status + ", " + mask(e.getMessage()) + ")");
			}
			return answer(status, response.getStatusLine().getReasonPhrase(), answer, response);
		} finally {
			try {
				response.close();
			} catch (IOException ignored) {
				// the answer is read already
			}
		}
	}

	/** The placeholders' values for one SMS. */
	Map<String, String> values(CommunicationMessage message, String phone) {
		String text = message.getBody() == null ? "" : message.getBody();
		Map<String, String> values = new LinkedHashMap<>();
		values.put("phone", phone);
		values.put("text", text);
		values.put("sender", senderName == null ? "" : senderName);
		values.put("messageId", message.getSendRef() != null ? message.getSendRef() : String.valueOf(message.getId()));
		values.put("unicode", SmsParts.UNICODE.equals(SmsParts.of(text).getEncoding()) ? unicodeYes : unicodeNo);
		values.put("key", key == null ? "" : key);
		values.put("username", username == null ? "" : username);
		values.put("password", password == null ? "" : password);
		values.put("basic", username == null ? "" : Base64.getEncoder().encodeToString(
				(username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8)));
		return values;
	}

	/** The request: the address filled, the fields where the lines say, the headers. */
	HttpRequestBase request(Map<String, String> values) {
		String address = fill(url, values, HttpSmsProvider::urlEncode);
		Map<String, String> filled = new TreeMap<>();
		fields.forEach((name, value) -> filled.put(name, fill(value, values, Function.identity())));
		if ("query".equals(body) && !filled.isEmpty()) {
			address += (address.contains("?") ? "&" : "?") + form(filled);
		}
		HttpRequestBase request;
		if ("GET".equals(method)) {
			request = new HttpGet(URI.create(address));
		} else {
			HttpPost post = new HttpPost(URI.create(address));
			if ("form".equals(body)) {
				post.setEntity(new StringEntity(form(filled), ContentType.APPLICATION_FORM_URLENCODED.withCharset(StandardCharsets.UTF_8)));
			} else if ("json".equals(body)) {
				post.setEntity(new StringEntity(json(filled, values), ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));
			}
			request = post;
		}
		request.setHeader("Accept", "application/json, text/plain, */*");
		headers.forEach((name, value) -> request.setHeader(name, fill(value, values, Function.identity())
				.replace("\r", "").replace("\n", "")));
		return request;
	}

	/** The JSON body: the template filled (each value escaped as in a JSON string), or one object of the fields. */
	private String json(Map<String, String> filled, Map<String, String> values) {
		if (jsonTemplate != null) {
			return fill(jsonTemplate, values, HttpSmsProvider::jsonEscape);
		}
		ObjectNode object = JSON.createObjectNode();
		filled.forEach(object::put);
		return object.toString();
	}

	/** Every {placeholder} replaced by its value as the place needs it. */
	static String fill(String template, Map<String, String> values, Function<String, String> escape) {
		Matcher m = PLACEHOLDER.matcher(template);
		StringBuffer out = new StringBuffer();
		while (m.find()) {
			String value = values.get(m.group(1));
			m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : escape.apply(value)));
		}
		m.appendTail(out);
		return out.toString();
	}

	private static String form(Map<String, String> filled) {
		return filled.entrySet().stream().map(e -> formEncode(e.getKey()) + "=" + formEncode(e.getValue()))
				.collect(Collectors.joining("&"));
	}

	private static String formEncode(String value) {
		try {
			return URLEncoder.encode(value, "UTF-8");
		} catch (java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	/** In the address: as in a form, a space as %20 (good in the path and in the query). */
	static String urlEncode(String value) {
		return formEncode(value).replace("+", "%20");
	}

	/** A value inside a JSON string (the template gives the quotes). */
	static String jsonEscape(String value) {
		String quoted = JSON.getNodeFactory().textNode(value).toString();
		return quoted.substring(1, quoted.length() - 1);
	}

	/** The member's phone (+21620123456) in the gateway's format; null when it is not a phone. */
	String phone(String address) {
		String value = address == null ? "" : address.trim().replaceAll("[\\s.-]", "");
		if (!value.matches("\\+?\\d{8,15}")) {
			return null;
		}
		String international = value.startsWith("+") ? value.substring(1)
				: value.length() == 8 ? countryCode + value : value;
		switch (phoneFormat) {
			case "without-plus":
				return international;
			case "with-00":
				return "00" + international;
			case "local":
				return international.startsWith(countryCode) && international.length() > countryCode.length() + 5
						? international.substring(countryCode.length()) : international;
			default:
				return "+" + international;
		}
	}

	// ─── The answer ──────────────────────────────────────────────────

	/** A status and an answer read: accepted, the SMS's refusal or the channel's problem. */
	SendResult answer(int status, String reasonPhrase, String answer, CloseableHttpResponse response) {
		JsonNode json = json(answer);
		if (accepted(status)) {
			String refused = acceptanceProblem(answer, json);
			if (refused == null) {
				return SendResult.accepted(id(answer, json, status));
			}
			String words = words(answer, json, reasonPhrase);
			return channelError(words) ? SendResult.channelProblem("SMS refused by the gateway (HTTP " + status + "): " + words)
					: SendResult.permanentFailure("SMS not accepted (HTTP " + status + "): " + words);
		}
		String words = words(answer, json, reasonPhrase);
		if (status >= 300 && status < 400) {
			Header location = response == null ? null : response.getFirstHeader("Location");
			return SendResult.channelProblem("SMS redirected (HTTP " + status + ") to " + (location == null ? "?"
					: String.valueOf(hostOf(location.getValue()))) + ": the address is not the gateway's own");
		}
		if (status == 401 || status == 403 || status == 407) {
			return SendResult.channelProblem("SMS login refused (HTTP " + status + "): " + words);
		}
		if (status == 402) {
			return SendResult.channelProblem("SMS refused by the gateway (HTTP 402): " + words);
		}
		if (status == 404 || status == 405 || status == 415) {
			return SendResult.channelProblem("SMS address or method refused (HTTP " + status + "): " + words);
		}
		if (status == 408) {
			return SendResult.temporaryFailure("SMS (HTTP 408): " + words);
		}
		// Step 8e: 502, 504, 524 with no error of the gateway inside: a proxy in front of it, which may have passed the
		// request on: the "no answer" rule (never sent again by itself; with retry-no-answer, after the pause)
		if (PROXY_STATUSES.contains(status) && (errorField == null || text(path(json, errorField)) == null)) {
			return noAnswer("SMS: no answer from the gateway (HTTP " + status + ")");
		}
		if (status == 429 || status >= 500) {
			Duration wait = retryAfter(response);
			String what = status == 429 ? "SMS too many requests (HTTP 429)" : "SMS server error (HTTP " + status + ")";
			return SendResult.channelProblem(what + (wait == null ? "" : ", wait " + wait.getSeconds() + " s") + ": " + words,
					wait);
		}
		return channelError(words) ? SendResult.channelProblem("SMS refused by the gateway (HTTP " + status + "): " + words)
				: SendResult.permanentFailure("SMS refused (HTTP " + status + "): " + words);
	}

	private boolean accepted(int status) {
		return acceptedStatus.stream().anyMatch(range -> status >= range[0] && status <= range[1]);
	}

	/** Why an answer with an accepted status is not an acceptance (accepted-field, accepted-text), null when it is. */
	private String acceptanceProblem(String answer, JsonNode json) {
		if (acceptedField != null) {
			String value = text(path(json, acceptedField));
			if (value == null || !acceptedValues.contains(value.trim().toLowerCase(Locale.ROOT))) {
				return acceptedField + " is " + (value == null ? "absent" : "'" + value + "'");
			}
		}
		if (acceptedText != null && !answer.toLowerCase(Locale.ROOT).contains(acceptedText.toLowerCase(Locale.ROOT))) {
			return "no '" + acceptedText + "' in the answer";
		}
		return null;
	}

	/** The gateway's id of the SMS (id-field, id-regex), else "HTTP &lt;status&gt;". */
	private String id(String answer, JsonNode json, int status) {
		String id = idField == null ? null : text(path(json, idField));
		if (id == null && idPattern != null) {
			Matcher m = idPattern.matcher(answer);
			id = m.find() ? m.group(1) : null;
		}
		id = id == null || id.trim().isEmpty() ? "HTTP " + status : mask(id.trim());
		return id.length() <= 100 ? id : id.substring(0, 100);
	}

	/** The gateway's words: error-field, else the answer's start, else the status's phrase; secrets masked. */
	private String words(String answer, JsonNode json, String reasonPhrase) {
		String words = errorField == null ? null : text(path(json, errorField));
		if (words == null || words.trim().isEmpty()) {
			words = answer;
		}
		words = words == null ? "" : words.replaceAll("\\s+", " ").trim();
		if (words.isEmpty()) {
			words = reasonPhrase == null ? "" : reasonPhrase;
		}
		words = mask(words);
		return words.length() <= MAX_WORDS ? words : words.substring(0, MAX_WORDS) + "...";
	}

	private boolean channelError(String words) {
		String lower = words.toLowerCase(Locale.ROOT);
		return channelErrors.stream().anyMatch(lower::contains);
	}

	/** Retry-After: seconds or a date; null when absent or not readable; at most an hour (step 8c), at least a second. */
	static Duration retryAfter(CloseableHttpResponse response) {
		Header header = response == null ? null : response.getFirstHeader("Retry-After");
		if (header == null || header.getValue() == null) {
			return null;
		}
		String value = header.getValue().trim();
		Duration wait = null;
		if (value.matches("\\d{1,9}")) {
			wait = Duration.ofSeconds(Long.parseLong(value));
		} else {
			Date date = DateUtils.parseDate(value);
			wait = date == null ? null : Duration.ofMillis(date.getTime() - System.currentTimeMillis());
		}
		if (wait == null) {
			return null;
		}
		return wait.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
	}

	/**
	 * The request left, no answer: uncertain. Step 8c, retry-no-answer (a gateway refusing doubles on {messageId}): the
	 * channel's problem instead, the SMS waits with its attempts and is tried again after the pause, with the same
	 * {messageId}.
	 */
	private SendResult noAnswer(IOException e) {
		return noAnswer(e instanceof SocketTimeoutException ? "SMS: no answer within " + readSeconds + " s"
				: "SMS: the connection was lost before the answer (" + mask(e.getMessage()) + ")");
	}

	/** Step 8e: the statuses of a proxy in front of a provider (bad gateway, gateway timeout, Cloudflare's timeout). */
	static final java.util.Set<Integer> PROXY_STATUSES = new java.util.HashSet<>(java.util.Arrays.asList(502, 504, 524));

	/** The "no answer" rule: uncertain; with retry-no-answer, the channel's problem (sent again after the pause). */
	private SendResult noAnswer(String why) {
		return retryNoAnswer ? SendResult.channelProblem(why + ", tried again (the gateway refuses doubles)")
				: SendResult.uncertain(why);
	}

	/** The request did not leave: the channel's problem. */
	private SendResult notSent(IOException e) {
		if (e instanceof ConnectTimeoutException) {
			return SendResult.channelProblem("SMS timeout: no connection to " + host + " within " + connectSeconds + " s");
		}
		if (e instanceof UnknownHostException || e instanceof HttpHostConnectException
				|| e instanceof NoRouteToHostException || e instanceof java.net.ConnectException) {
			return SendResult.channelProblem("SMS server unreachable (" + host + "): " + mask(e.getMessage()));
		}
		if (e instanceof SSLException) {
			return SendResult.channelProblem("SMS TLS failed (" + host + "): " + mask(e.getMessage()));
		}
		return SendResult.channelProblem("SMS connection failed (" + host + "): " + mask(e.getMessage()));
	}

	/** Any secret's value in a text replaced by ***. */
	String mask(String text) {
		String value = text == null ? "" : text;
		for (String secret : Arrays.asList(key, password)) {
			if (secret != null && secret.length() >= 3) {
				value = value.replace(secret, "***");
			}
		}
		return value;
	}

	// ─── Reading ─────────────────────────────────────────────────────

	/** The answer as text (its charset, UTF-8 by default), at most 64 KB. */
	static String read(HttpEntity entity) throws IOException {
		if (entity == null) {
			return "";
		}
		Charset charset = StandardCharsets.UTF_8;
		try {
			ContentType type = ContentType.get(entity);
			if (type != null && type.getCharset() != null) {
				charset = type.getCharset();
			}
		} catch (RuntimeException e) {
			// a content type not readable: UTF-8
		}
		try (InputStream in = entity.getContent()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int n;
			while ((n = in.read(buffer)) > 0 && out.size() < MAX_ANSWER) {
				out.write(buffer, 0, Math.min(n, MAX_ANSWER - out.size()));
			}
			return new String(out.toByteArray(), charset);
		}
	}

	/** The answer as JSON, null when it is not JSON. */
	static JsonNode json(String answer) {
		String value = answer == null ? "" : answer.trim();
		if (!(value.startsWith("{") || value.startsWith("["))) {
			return null;
		}
		try {
			return JSON.readTree(value);
		} catch (IOException e) {
			return null;
		}
	}

	/** A dotted path with indexes (status, data.id, messages[0].id), null when absent. */
	static JsonNode path(JsonNode json, String path) {
		JsonNode node = json;
		for (String part : path.split("\\.")) {
			if (node == null) {
				return null;
			}
			Matcher m = Pattern.compile("([^\\[]*)((?:\\[\\d+\\])*)").matcher(part);
			if (!m.matches()) {
				return null;
			}
			if (!m.group(1).isEmpty()) {
				node = node.get(m.group(1));
			}
			Matcher index = Pattern.compile("\\[(\\d+)\\]").matcher(m.group(2));
			while (node != null && index.find()) {
				node = node.get(Integer.parseInt(index.group(1)));
			}
		}
		return node;
	}

	private static String text(JsonNode node) {
		return node == null || node.isNull() || node.isContainerNode() ? null : node.asText();
	}

	// ─── The lines ───────────────────────────────────────────────────

	/** The host of an address with its placeholders, null when it is not http or https. */
	static String hostOf(String address) {
		if (address == null) {
			return null;
		}
		try {
			URI uri = URI.create(PLACEHOLDER.matcher(address.trim()).replaceAll("x"));
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null ? uri.getHost() : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Every line under a prefix (field.&lt;name&gt;, header.&lt;Name&gt;) by its exact name, the highest source winning. */
	static Map<String, String> withPrefix(Environment env, String prefix) {
		Map<String, String> found = new TreeMap<>();
		if (!(env instanceof ConfigurableEnvironment)) {
			return found;
		}
		for (PropertySource<?> source : ((ConfigurableEnvironment) env).getPropertySources()) {
			if (!(source instanceof EnumerablePropertySource)) {
				continue;
			}
			for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
				if (name.startsWith(prefix) && name.length() > prefix.length() && !found.containsKey(name.substring(prefix.length()))) {
					String value = env.getProperty(name);
					if (value != null && !value.trim().isEmpty()) {
						found.put(name.substring(prefix.length()), value.trim());
					}
				}
			}
		}
		return found;
	}

	/** Statuses and ranges, e.g. 200-299 or 200,202; null when not readable. */
	static List<int[]> statuses(String raw) {
		List<int[]> ranges = new ArrayList<>();
		for (String part : raw.split(",")) {
			String value = part.trim();
			Matcher m = Pattern.compile("(\\d{3})(?:\\s*-\\s*(\\d{3}))?").matcher(value);
			if (!m.matches()) {
				return null;
			}
			int from = Integer.parseInt(m.group(1));
			int to = m.group(2) == null ? from : Integer.parseInt(m.group(2));
			if (to < from) {
				return null;
			}
			ranges.add(new int[] { from, to });
		}
		return ranges.isEmpty() ? null : Collections.unmodifiableList(ranges);
	}

	private static List<String> list(String raw) {
		if (raw == null) {
			return Collections.emptyList();
		}
		return Arrays.stream(raw.split(",")).map(String::trim).filter(v -> !v.isEmpty()).collect(Collectors.toList());
	}

	private static String text(Environment env, String key) {
		String raw = env.getProperty(key);
		return raw == null || raw.trim().isEmpty() ? null : raw.trim();
	}

	private static String secret(Environment env, String key) {
		String raw = env.getProperty(key);
		return raw == null || raw.isEmpty() ? null : raw;
	}

	/** 1 to 300 seconds; the default when absent or empty; null when not readable. */
	private static Integer number(Environment env, String key, int defaultValue) {
		String raw = text(env, key);
		if (raw == null) {
			return defaultValue;
		}
		try {
			int value = Integer.parseInt(raw);
			return value >= 1 && value <= 300 ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
