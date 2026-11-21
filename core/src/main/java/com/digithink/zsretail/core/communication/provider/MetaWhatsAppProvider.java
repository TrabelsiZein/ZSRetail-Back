package com.digithink.zsretail.core.communication.provider;

import java.io.IOException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.PreDestroy;
import javax.net.ssl.SSLException;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HttpCoreContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.service.CommunicationTemplates;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 3.3.0, communication step 8c: the provider "meta" of the WhatsApp channel (communication.whatsapp.provider=meta), the
 * WhatsApp Cloud API, set entirely in communication.whatsapp.meta.* (a reseller offering the same API plugs in by its
 * address, token header and prefix). Always a template message, never free text: the template's name at Meta, its
 * language (the template's provider language, else its own), the values of its {fields} in the order of their first
 * appearance as the body's parameters (positional, or named with parameter_name first_name for {firstName}); no header
 * value, no media, no button. The phone with + and the country code, as Meta recommends. A 200 means accepted by Meta,
 * not delivered (delivery comes by webhook, not received in version 1): the message is SENT with Meta's id (wamid).
 * <p>
 * The same HTTP client rules as the SMS gateway: connect and read timeouts, no automatic retry, no redirect, a new
 * connection per message; the request sent and no answer: uncertain (FAILED, sent again by hand, the channel paused).
 * Meta's answers sorted by its error code (error.code), not only by the HTTP status (Meta's error codes page,
 * 2026-11-21), each family extendable by a line without a release:
 * <ul>
 * <li>the channel's problem (the pause): authorization (0, 3, 10, 190, 200-299, 100), integrity (368, 131031),
 * throughput (4, 80007, 130429, 131048, 131064), the account or number (33, 131005, 131037, 131042, 131045, 131057,
 * 133010), Meta unavailable (1, 2, 131000, 131016, 133004);</li>
 * <li>this message tried again later: 131056 (too many messages to that recipient in a short time);</li>
 * <li>this message FAILED at once: 130403, 130472, 130497, 131008, 131009, 131021, 131026 (cannot receive), 131047,
 * 131050 (stopped marketing), 131051, 135000; step 8d: 131049 (Meta limits marketing messages to this person: wait
 * at least 24 h, the waits of 1 to 60 min only used the attempts), 131030 (a test sender number's recipient not in its
 * allowed list), 131066 (from February 2027, Meta's code for a marketing message not delivered to this person for the
 * template's quality or price), 132005 (the text too long once filled with this member's values: an approved
 * template is within Meta's limit, only the values change at sending);</li>
 * <li>the template's problem: 131063, 132000, 132001, 132007, 132012, 132015, 132016, 132068, 132069.</li>
 * </ul>
 * 131049 and 131030 carry what to do in their reason. An unknown code: by the HTTP status (401, 403, 429, 5xx: the
 * channel's; another: this message's). See docs/modules/communication.md, "Step 8c" and "Step 8d".
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
public class MetaWhatsAppProvider implements CommunicationProvider {

	public static final String NAME = "meta";
	public static final String PREFIX = "communication.whatsapp.meta.";
	public static final String URL_KEY = PREFIX + "url";
	public static final String API_VERSION_KEY = PREFIX + "api-version";
	public static final String PHONE_NUMBER_ID_KEY = PREFIX + "phone-number-id";
	public static final String TOKEN_KEY = PREFIX + "token";
	public static final String TOKEN_HEADER_KEY = PREFIX + "token-header";
	public static final String TOKEN_PREFIX_KEY = PREFIX + "token-prefix";
	public static final String CONNECT_TIMEOUT_KEY = PREFIX + "connect-timeout-seconds";
	public static final String READ_TIMEOUT_KEY = PREFIX + "read-timeout-seconds";
	public static final String CHANNEL_CODES_KEY = PREFIX + "channel-codes";
	public static final String RETRY_CODES_KEY = PREFIX + "retry-codes";
	public static final String MESSAGE_CODES_KEY = PREFIX + "message-codes";
	public static final String TEMPLATE_CODES_KEY = PREFIX + "template-codes";

	static final String DEFAULT_URL = "https://graph.facebook.com";
	/** Step 8d: the newest version on Meta's versions page (v26.0, 29 July 2026; there is no v27.0 yet). */
	static final String DEFAULT_API_VERSION = "v26.0";

	/** The families of Meta's error codes. */
	enum Family {
		CHANNEL, RETRY, MESSAGE, TEMPLATE
	}

	static final String DEFAULT_CHANNEL_CODES = "0,1,2,3,4,10,33,100,190,200-299,368,80007,130429,131000,131005,131016,"
			+ "131031,131037,131042,131045,131048,131057,131064,133004,133010";
	static final String DEFAULT_RETRY_CODES = "131056";
	static final String DEFAULT_MESSAGE_CODES = "130403,130472,130497,131008,131009,131021,131026,131030,131047,131049,"
			+ "131050,131051,131066,132005,135000";
	static final String DEFAULT_TEMPLATE_CODES = "131063,132000,132001,132007,132012,132015,132016,132068,132069";

	/** Step 8d: a message refused by Meta for these codes is FAILED at once with what to do. */
	static final long MARKETING_LIMIT = 131049;
	static final long NOT_IN_ALLOWED_LIST = 131030;

	private static final ObjectMapper JSON = new ObjectMapper();

	private final String url;
	private final String apiVersion;
	private final String phoneNumberId;
	private final String token;
	private final String tokenHeader;
	private final String tokenPrefix;
	private final int connectSeconds;
	private final int readSeconds;
	/** Ranges {from, to} per family; a code in an added line wins over its default family. */
	private final Map<Family, java.util.List<long[]>> added = new LinkedHashMap<>();
	private final Map<Family, java.util.List<long[]>> defaults = new LinkedHashMap<>();
	private final String host;
	private final String unavailable;
	private final CloseableHttpClient client;

	@Autowired
	public MetaWhatsAppProvider(Environment env) {
		url = text(env, URL_KEY) == null ? DEFAULT_URL : text(env, URL_KEY).replaceAll("/+$", "");
		apiVersion = text(env, API_VERSION_KEY) == null ? DEFAULT_API_VERSION : text(env, API_VERSION_KEY);
		phoneNumberId = text(env, PHONE_NUMBER_ID_KEY);
		String rawToken = env.getProperty(TOKEN_KEY);
		token = rawToken == null || rawToken.trim().isEmpty() ? null : rawToken.trim();
		tokenHeader = text(env, TOKEN_HEADER_KEY) == null ? "Authorization" : text(env, TOKEN_HEADER_KEY);
		String prefix = env.getProperty(TOKEN_PREFIX_KEY);
		tokenPrefix = prefix == null ? "Bearer" : prefix.trim();
		Integer connect = number(env, CONNECT_TIMEOUT_KEY, 10);
		Integer read = number(env, READ_TIMEOUT_KEY, 30);
		connectSeconds = connect == null ? 10 : connect;
		readSeconds = read == null ? 30 : read;
		defaults.put(Family.CHANNEL, codes(DEFAULT_CHANNEL_CODES));
		defaults.put(Family.RETRY, codes(DEFAULT_RETRY_CODES));
		defaults.put(Family.MESSAGE, codes(DEFAULT_MESSAGE_CODES));
		defaults.put(Family.TEMPLATE, codes(DEFAULT_TEMPLATE_CODES));
		String badCodes = null;
		for (Map.Entry<Family, String> line : lines().entrySet()) {
			java.util.List<long[]> list = codes(text(env, line.getValue()) == null ? "" : text(env, line.getValue()));
			if (list == null) {
				badCodes = line.getValue() + " is not a list of codes or ranges (e.g. 131056,132000-132016)";
				list = java.util.Collections.emptyList();
			}
			added.put(line.getKey(), list);
		}
		host = HttpSmsProvider.hostOf(url);
		String problem = badCodes;
		if (problem == null && host == null) {
			problem = URL_KEY + " is not an http or https address";
		} else if (problem == null && phoneNumberId == null) {
			problem = PHONE_NUMBER_ID_KEY + " is empty";
		} else if (problem == null && !phoneNumberId.matches("[A-Za-z0-9_-]{1,64}")) {
			problem = PHONE_NUMBER_ID_KEY + " is not a number id";
		} else if (problem == null && token == null) {
			problem = TOKEN_KEY + " is empty";
		} else if (problem == null && !apiVersion.matches("[A-Za-z0-9._-]{1,20}")) {
			problem = API_VERSION_KEY + " is not a version (e.g. " + DEFAULT_API_VERSION + ")";
		} else if (problem == null && (connect == null || read == null)) {
			problem = (connect == null ? CONNECT_TIMEOUT_KEY : READ_TIMEOUT_KEY) + " is not 1 to 300 seconds";
		}
		unavailable = problem;
		RequestConfig config = RequestConfig.custom().setConnectTimeout(connectSeconds * 1000)
				.setConnectionRequestTimeout(connectSeconds * 1000).setSocketTimeout(readSeconds * 1000)
				.setRedirectsEnabled(false).build();
		client = HttpClients.custom().setDefaultRequestConfig(config).disableAutomaticRetries().disableRedirectHandling()
				.disableCookieManagement().disableAuthCaching().setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE)
				.setUserAgent("ZS Retail").build();
	}

	private static Map<Family, String> lines() {
		Map<Family, String> lines = new LinkedHashMap<>();
		lines.put(Family.CHANNEL, CHANNEL_CODES_KEY);
		lines.put(Family.RETRY, RETRY_CODES_KEY);
		lines.put(Family.MESSAGE, MESSAGE_CODES_KEY);
		lines.put(Family.TEMPLATE, TEMPLATE_CODES_KEY);
		return lines;
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
		return CommunicationTypes.WHATSAPP.equals(channel);
	}

	@Override
	public String unavailableReason() {
		return unavailable;
	}

	/** The address's host only, the API version, the sender's number id, the token "set" / "not set". */
	@Override
	public Map<String, Object> describe() {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("host", host);
		view.put("apiVersion", apiVersion);
		view.put("phoneNumberId", phoneNumberId);
		view.put("tokenHeader", tokenHeader);
		view.put("token", token == null ? "not set" : "set");
		view.put("connectTimeoutSeconds", connectSeconds);
		view.put("readTimeoutSeconds", readSeconds);
		return view;
	}

	@Override
	public SendResult send(CommunicationMessage message) {
		if (unavailable != null) {
			return SendResult.channelProblem("meta cannot send: " + unavailable);
		}
		JsonNode params = params(message.getProviderParams());
		if (params == null) {
			return SendResult.permanentFailure("no WhatsApp template on the message (its template has no provider"
					+ " template name)");
		}
		String phone = phone(message.getAddress());
		if (phone == null) {
			return SendResult.permanentFailure("invalid phone '" + message.getAddress() + "': not sent");
		}
		// Step 8e: a value empty once cleaned never goes to Meta: that message FAILED with the field's name
		String empty = emptyValue(params);
		if (empty != null) {
			return SendResult.permanentFailure("WhatsApp value of {" + empty + "} is empty: not sent");
		}
		String templateName = params.path("name").asText();
		String language = params.path("language").asText();
		HttpPost post = new HttpPost(URI.create(url + "/" + apiVersion + "/" + phoneNumberId + "/messages"));
		post.setHeader(tokenHeader, tokenPrefix.isEmpty() ? token : tokenPrefix + " " + token);
		post.setHeader("Accept", "application/json");
		post.setEntity(new StringEntity(body(phone, params), ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));
		HttpClientContext context = HttpClientContext.create();
		CloseableHttpResponse response;
		try {
			response = client.execute(post, context);
		} catch (IOException e) {
			boolean sent = Boolean.TRUE.equals(context.getAttribute(HttpCoreContext.HTTP_REQ_SENT));
			return sent ? noAnswer(e) : notSent(e);
		}
		try {
			int status = response.getStatusLine().getStatusCode();
			String answer;
			try {
				answer = HttpSmsProvider.read(response.getEntity());
			} catch (IOException e) {
				return SendResult.uncertain("WhatsApp: the answer was cut (HTTP " + status + ", " + mask(e.getMessage()) + ")");
			}
			return answer(status, answer, response, templateName, language);
		} finally {
			try {
				response.close();
			} catch (IOException ignored) {
				// the answer is read already
			}
		}
	}

	/** The message's provider params, null when absent or not readable. */
	static JsonNode params(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return null;
		}
		try {
			JsonNode node = JSON.readTree(raw);
			return node.path("name").asText("").isEmpty() ? null : node;
		} catch (IOException e) {
			return null;
		}
	}

	/** The Cloud API's template message: the template, its language, the body's values in order. */
	static String body(String phone, JsonNode params) {
		ObjectNode root = JSON.createObjectNode();
		root.put("messaging_product", "whatsapp");
		root.put("recipient_type", "individual");
		root.put("to", phone);
		root.put("type", "template");
		ObjectNode template = root.putObject("template");
		template.put("name", params.path("name").asText());
		template.putObject("language").put("code", params.path("language").asText());
		JsonNode values = params.path("values");
		if (values.isArray() && values.size() > 0) {
			boolean named = CommunicationTemplates.NAMED.equals(params.path("format").asText());
			ArrayNode parameters = template.putArray("components").addObject().put("type", "body").putArray("parameters");
			for (JsonNode value : values) {
				ObjectNode parameter = parameters.addObject();
				parameter.put("type", "text");
				if (named) {
					parameter.put("parameter_name", value.path("name").asText());
				}
				parameter.put("text", clean(value.path("value").asText()));
			}
		}
		return root.toString();
	}

	/**
	 * Step 8e: a value as Meta takes it: a tab or a line break becomes a space, more than 4 spaces in a row become one
	 * (Meta refuses them, with code 100: the channel would have paused for one member's data).
	 */
	static String clean(String value) {
		if (value == null) {
			return "";
		}
		return value.replaceAll("[\t\r\n]+", " ").replaceAll(" {5,}", " ");
	}

	/** Step 8e: the field of the first value empty once cleaned, null when none. */
	static String emptyValue(JsonNode params) {
		for (JsonNode value : params.path("values")) {
			if (clean(value.path("value").asText("")).trim().isEmpty()) {
				return value.path("field").asText("?");
			}
		}
		return null;
	}

	/** + and the country code, as Meta recommends (without +, Meta puts the business number's country code). */
	static String phone(String address) {
		String value = address == null ? "" : address.trim().replaceAll("[\\s.-]", "");
		return value.matches("\\+\\d{8,15}") ? value : null;
	}

	// ─── Meta's answer ───────────────────────────────────────────────

	SendResult answer(int status, String answer, CloseableHttpResponse response, String templateName, String language) {
		JsonNode json = HttpSmsProvider.json(answer);
		if (status >= 200 && status < 300) {
			JsonNode first = json == null ? null : json.path("messages").path(0);
			String id = first == null || first.isMissingNode() ? null : first.path("id").asText(null);
			String state = first == null || first.isMissingNode() ? null : first.path("message_status").asText(null);
			String note = state == null || "accepted".equalsIgnoreCase(state) ? null
					: "accepted by Meta as " + state + ": Meta may deliver it later or not";
			return SendResult.accepted(id == null ? "HTTP " + status : id, note);
		}
		JsonNode error = json == null ? null : json.path("error");
		Long code = error == null || !error.path("code").canConvertToLong() ? null : error.path("code").asLong();
		// Step 8e: 502, 504, 524 with no error of Meta inside: a proxy that may have passed the request on: uncertain
		if (code == null && HttpSmsProvider.PROXY_STATUSES.contains(status)) {
			return SendResult.uncertain("WhatsApp: no answer from Meta (HTTP " + status + ")");
		}
		String words = words(error, answer, status);
		Family family = code == null ? null : family(code);
		if (family == null) {
			family = status == 401 || status == 403 || status == 429 || status >= 500 ? Family.CHANNEL : Family.MESSAGE;
		}
		String number = code == null ? "HTTP " + status : "#" + code;
		switch (family) {
			case CHANNEL:
				return SendResult.channelProblem("WhatsApp channel refused by Meta (" + number + "): " + words,
						HttpSmsProvider.retryAfter(response));
			case RETRY:
				return SendResult.temporaryFailure("WhatsApp, Meta asks to try later (" + number + "): " + words);
			case TEMPLATE:
				return SendResult.templateProblem("WhatsApp template " + templateName + " (" + language + ") refused by Meta ("
						+ number + "): " + words);
			default:
				if (code != null && code == MARKETING_LIMIT) {
					return SendResult.permanentFailure("WhatsApp marketing limit at Meta (" + number + "), try again after 24 h: "
							+ words);
				}
				if (code != null && code == NOT_IN_ALLOWED_LIST) {
					return SendResult.permanentFailure("WhatsApp number not in the allowed list of Meta's test number (" + number
							+ "), add it in Meta's app dashboard (WhatsApp, API Setup): " + words);
				}
				return SendResult.permanentFailure("WhatsApp message refused by Meta (" + number + "): " + words);
		}
	}

	/** The family of a code: an added line first, then the defaults; null when unknown. */
	Family family(long code) {
		for (Map.Entry<Family, java.util.List<long[]>> line : added.entrySet()) {
			if (contains(line.getValue(), code)) {
				return line.getKey();
			}
		}
		for (Map.Entry<Family, java.util.List<long[]>> line : defaults.entrySet()) {
			if (contains(line.getValue(), code)) {
				return line.getKey();
			}
		}
		return null;
	}

	private static boolean contains(java.util.List<long[]> ranges, long code) {
		return ranges.stream().anyMatch(r -> code >= r[0] && code <= r[1]);
	}

	/** Meta's words: error.message, then error.error_data.details; the token masked. */
	private String words(JsonNode error, String answer, int status) {
		String words = null;
		if (error != null && !error.isMissingNode()) {
			String message = error.path("message").asText("");
			String details = error.path("error_data").path("details").asText("");
			words = details.isEmpty() || message.contains(details) ? message : message + " - " + details;
		}
		if (words == null || words.trim().isEmpty()) {
			words = answer == null || answer.trim().isEmpty() ? "HTTP " + status : answer;
		}
		words = mask(words.replaceAll("\\s+", " ").trim());
		return words.length() <= HttpSmsProvider.MAX_WORDS ? words : words.substring(0, HttpSmsProvider.MAX_WORDS) + "...";
	}

	private SendResult noAnswer(IOException e) {
		if (e instanceof SocketTimeoutException) {
			return SendResult.uncertain("WhatsApp: no answer within " + readSeconds + " s");
		}
		return SendResult.uncertain("WhatsApp: the connection was lost before the answer (" + mask(e.getMessage()) + ")");
	}

	private SendResult notSent(IOException e) {
		if (e instanceof ConnectTimeoutException) {
			return SendResult.channelProblem("WhatsApp timeout: no connection to " + host + " within " + connectSeconds + " s");
		}
		if (e instanceof UnknownHostException || e instanceof HttpHostConnectException
				|| e instanceof NoRouteToHostException || e instanceof java.net.ConnectException) {
			return SendResult.channelProblem("WhatsApp server unreachable (" + host + "): " + mask(e.getMessage()));
		}
		if (e instanceof SSLException) {
			return SendResult.channelProblem("WhatsApp TLS failed (" + host + "): " + mask(e.getMessage()));
		}
		return SendResult.channelProblem("WhatsApp connection failed (" + host + "): " + mask(e.getMessage()));
	}

	/** The token's value in a text replaced by ***. */
	String mask(String text) {
		String value = text == null ? "" : text;
		return token != null && token.length() >= 3 ? value.replace(token, "***") : value;
	}

	// ─── The lines ───────────────────────────────────────────────────

	/** Codes and ranges (131056, 132000-132016); empty: none; null when not readable. */
	static java.util.List<long[]> codes(String raw) {
		java.util.List<long[]> ranges = new java.util.ArrayList<>();
		for (String part : raw.split(",")) {
			String value = part.trim();
			if (value.isEmpty()) {
				continue;
			}
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,9})(?:\\s*-\\s*(\\d{1,9}))?").matcher(value);
			if (!m.matches()) {
				return null;
			}
			long from = Long.parseLong(m.group(1));
			long to = m.group(2) == null ? from : Long.parseLong(m.group(2));
			if (to < from) {
				return null;
			}
			ranges.add(new long[] { from, to });
		}
		return ranges;
	}

	private static String text(Environment env, String key) {
		String raw = env.getProperty(key);
		return raw == null || raw.trim().isEmpty() ? null : raw.trim();
	}

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
