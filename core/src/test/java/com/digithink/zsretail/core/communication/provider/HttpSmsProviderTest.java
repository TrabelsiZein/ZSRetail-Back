package com.digithink.zsretail.core.communication.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider.Outcome;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider.SendResult;
import com.digithink.zsretail.core.communication.service.CommunicationSettings;
import com.digithink.zsretail.core.support.CatchHttpProvider;
import com.digithink.zsretail.core.support.CatchHttpProvider.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.3.0, communication step 8b: the provider http against a gateway inside the test (CatchHttpProvider, 127.0.0.1, no
 * network): the two worked examples of core's application.properties (a GET with the key in the address and a text
 * answer; a JSON POST with the key in a header and a JSON answer), a form; the phone's formats; Unicode for Arabic; the
 * answers (the channel's problems, the SMS's refusals, a 200 with an error inside, no answer: uncertain, never sent
 * twice); the lines checked at the start; nothing secret shown.
 */
class HttpSmsProviderTest {

	private static final String KEY = "cle-de-test-8b";
	private static final String ARABIC = "مرحبا سناء، رصيدك 120 نقطة";

	private CatchHttpProvider gateway;

	@BeforeEach
	void start() throws IOException {
		gateway = new CatchHttpProvider(0, KEY);
	}

	@AfterEach
	void stop() {
		gateway.close();
	}

	/** The GET example: the key in the address, the fields in the query, a text answer "OK &lt;id&gt;". */
	private MockEnvironment getExample() {
		return new MockEnvironment()
				.withProperty(HttpSmsProvider.URL_KEY, "http://127.0.0.1:" + gateway.getPort() + "/text/send?key={key}")
				.withProperty(HttpSmsProvider.METHOD_KEY, "GET")
				.withProperty(HttpSmsProvider.FIELD_PREFIX + "to", "{phone}")
				.withProperty(HttpSmsProvider.FIELD_PREFIX + "from", "{sender}")
				.withProperty(HttpSmsProvider.FIELD_PREFIX + "text", "{text}")
				.withProperty(HttpSmsProvider.FIELD_PREFIX + "unicode", "{unicode}")
				.withProperty(HttpSmsProvider.FIELD_PREFIX + "ref", "{messageId}")
				.withProperty(HttpSmsProvider.UNICODE_VALUES_KEY, "1,0")
				.withProperty(HttpSmsProvider.PHONE_FORMAT_KEY, "without-plus")
				.withProperty(HttpSmsProvider.SENDER_KEY, "Happyness")
				.withProperty(HttpSmsProvider.KEY_KEY, KEY)
				.withProperty(HttpSmsProvider.ACCEPTED_TEXT_KEY, "OK")
				.withProperty(HttpSmsProvider.ID_REGEX_KEY, "OK\\s+(\\S+)")
				.withProperty(HttpSmsProvider.READ_TIMEOUT_KEY, "2");
	}

	/** The JSON POST example: the key in a header, a JSON template, a JSON answer. */
	private MockEnvironment jsonExample() {
		return new MockEnvironment()
				.withProperty(HttpSmsProvider.URL_KEY, "http://127.0.0.1:" + gateway.getPort() + "/v1/sms")
				.withProperty(HttpSmsProvider.METHOD_KEY, "POST")
				.withProperty(HttpSmsProvider.BODY_KEY, "json")
				.withProperty(HttpSmsProvider.JSON_KEY, "{\"to\":\"{phone}\",\"from\":\"{sender}\",\"text\":\"{text}\","
						+ "\"unicode\":{unicode},\"clientRef\":\"{messageId}\"}")
				.withProperty(HttpSmsProvider.HEADER_PREFIX + "Authorization", "Bearer {key}")
				.withProperty(HttpSmsProvider.SENDER_KEY, "Happyness")
				.withProperty(HttpSmsProvider.KEY_KEY, KEY)
				.withProperty(HttpSmsProvider.ACCEPTED_FIELD_KEY, "status")
				.withProperty(HttpSmsProvider.ACCEPTED_VALUE_KEY, "OK")
				.withProperty(HttpSmsProvider.ID_FIELD_KEY, "messageId")
				.withProperty(HttpSmsProvider.ERROR_FIELD_KEY, "error")
				.withProperty(HttpSmsProvider.READ_TIMEOUT_KEY, "2");
	}

	private static CommunicationMessage sms(String phone, String text) {
		CommunicationMessage message = new CommunicationMessage();
		message.setId(12L);
		message.setChannel(CommunicationTypes.SMS);
		message.setAddress(phone);
		message.setBody(text);
		message.setSendRef("0123456789abcdef0123456789abcdef");
		return message;
	}

	@Test
	@DisplayName("GET example: the key in the address, the fields in the query (UTF-8), the phone without +, Unicode 0 for"
			+ " French and 1 for Arabic, the reference; a text answer read for its id")
	void getGateway() {
		HttpSmsProvider provider = new HttpSmsProvider(getExample());
		assertNull(provider.unavailableReason());
		SendResult french = provider.send(sms("+21699000002", "Bienvenue chez Happyness !"));
		assertEquals(Outcome.ACCEPTED, french.getOutcome(), french.getError());
		assertEquals("CATCH-0001", french.getProviderMessageId());
		CatchHttpProvider.Request request = gateway.requests().get(0);
		assertEquals("GET", request.getMethod());
		assertEquals("/text/send", request.getPath());
		Map<String, String> params = request.params();
		assertEquals(KEY, params.get("key"));
		assertEquals("21699000002", params.get("to"));
		assertEquals("Happyness", params.get("from"));
		assertEquals("Bienvenue chez Happyness !", params.get("text"));
		assertEquals("0", params.get("unicode"));
		assertEquals("0123456789abcdef0123456789abcdef", params.get("ref"));

		SendResult arabic = provider.send(sms("+21699000002", ARABIC));
		assertEquals(Outcome.ACCEPTED, arabic.getOutcome(), arabic.getError());
		assertEquals(ARABIC, gateway.requests().get(1).params().get("text"));
		assertEquals("1", gateway.requests().get(1).params().get("unicode"));
	}

	@Test
	@DisplayName("JSON POST example: the key in a header, the template filled (values escaped, Arabic in UTF-8, the Unicode"
			+ " flag a JSON literal), the phone with +; a JSON answer read for its id")
	void jsonGateway() throws IOException {
		HttpSmsProvider provider = new HttpSmsProvider(jsonExample());
		assertNull(provider.unavailableReason());
		SendResult result = provider.send(sms("+21699000003", ARABIC + " \"cité\"\nفي"));
		assertEquals(Outcome.ACCEPTED, result.getOutcome(), result.getError());
		assertEquals("CATCH-0001", result.getProviderMessageId());
		CatchHttpProvider.Request request = gateway.requests().get(0);
		assertEquals("POST", request.getMethod());
		assertEquals("Bearer " + KEY, request.header("Authorization"));
		assertTrue(request.header("Content-Type").startsWith("application/json"), request.header("Content-Type"));
		JsonNode body = new ObjectMapper().readTree(request.getBody());
		assertEquals("+21699000003", body.get("to").asText());
		assertEquals(ARABIC + " \"cité\"\nفي", body.get("text").asText());
		assertTrue(body.get("unicode").isBoolean() && body.get("unicode").asBoolean());
		assertEquals("Happyness", body.get("from").asText());
	}

	@Test
	@DisplayName("A form POST of the fields; the phone's formats: with +, without, with 00, local")
	void formAndPhones() {
		MockEnvironment env = getExample().withProperty(HttpSmsProvider.METHOD_KEY, "POST")
				.withProperty(HttpSmsProvider.BODY_KEY, "form");
		HttpSmsProvider provider = new HttpSmsProvider(env);
		assertEquals(Outcome.ACCEPTED, provider.send(sms("+21699000004", "Merci")).getOutcome());
		CatchHttpProvider.Request request = gateway.requests().get(0);
		assertEquals("POST", request.getMethod());
		assertEquals("Merci", request.params().get("text"));
		assertTrue(request.getBody().contains("to=21699000004"), request.getBody());

		assertEquals("+21699000004", new HttpSmsProvider(env.withProperty(HttpSmsProvider.PHONE_FORMAT_KEY, "with-plus"))
				.phone("+21699000004"));
		assertEquals("0021699000004", new HttpSmsProvider(env.withProperty(HttpSmsProvider.PHONE_FORMAT_KEY, "with-00"))
				.phone("+21699000004"));
		HttpSmsProvider local = new HttpSmsProvider(env.withProperty(HttpSmsProvider.PHONE_FORMAT_KEY, "local"));
		assertEquals("99000004", local.phone("+21699000004"));
		assertEquals("33612345678", local.phone("+33612345678"), "another country keeps its code");
		assertNull(local.phone("abc"));
	}

	@Test
	@DisplayName("The channel's problems: the key refused (401), too many requests (429 with its wait), a server error"
			+ " (503), no server, a 404, a redirect; the key never in the reason")
	void channelProblems() throws IOException {
		HttpSmsProvider wrongKey = new HttpSmsProvider(jsonExample().withProperty(HttpSmsProvider.KEY_KEY, "mauvaise-cle"));
		SendResult refused = wrongKey.send(sms("+21699000002", "Bonjour"));
		assertEquals(Outcome.CHANNEL_PROBLEM, refused.getOutcome());
		assertEquals("SMS login refused (HTTP 401): invalid API key", refused.getError());

		HttpSmsProvider provider = new HttpSmsProvider(jsonExample());
		gateway.mode(Mode.TOO_MANY).retryAfter(30);
		SendResult tooMany = provider.send(sms("+21699000002", "Bonjour"));
		assertEquals(Outcome.CHANNEL_PROBLEM, tooMany.getOutcome());
		assertEquals("SMS too many requests (HTTP 429), wait 30 s: too many requests", tooMany.getError());
		assertEquals(Duration.ofSeconds(30), tooMany.getRetryAfter());

		gateway.mode(Mode.SERVER_ERROR).retryAfter(null);
		SendResult server = provider.send(sms("+21699000002", "Bonjour"));
		assertEquals(Outcome.CHANNEL_PROBLEM, server.getOutcome());
		assertEquals("SMS server error (HTTP 503): service unavailable", server.getError());
		assertNull(server.getRetryAfter());

		gateway.mode(Mode.ACCEPTED).status(404, null);
		assertEquals(Outcome.CHANNEL_PROBLEM, provider.send(sms("+21699000002", "Bonjour")).getOutcome(),
				"a wrong address is never one number's fault");
		gateway.status(302, "https://ailleurs.example.tn/login");
		SendResult redirect = provider.send(sms("+21699000002", "Bonjour"));
		assertEquals(Outcome.CHANNEL_PROBLEM, redirect.getOutcome());
		assertTrue(redirect.getError().startsWith("SMS redirected (HTTP 302) to ailleurs.example.tn"), redirect.getError());
		gateway.status(null, null);

		int closed;
		try (ServerSocket free = new ServerSocket(0)) {
			closed = free.getLocalPort();
		}
		SendResult down = new HttpSmsProvider(jsonExample().withProperty(HttpSmsProvider.URL_KEY,
				"http://127.0.0.1:" + closed + "/v1/sms")).send(sms("+21699000002", "Bonjour"));
		assertEquals(Outcome.CHANNEL_PROBLEM, down.getOutcome());
		assertTrue(down.getError().startsWith("SMS server unreachable (127.0.0.1)"), down.getError());
		for (SendResult r : Arrays.asList(refused, tooMany, server, redirect, down)) {
			assertFalse(r.getError().contains(KEY), "never the key");
		}
	}

	@Test
	@DisplayName("No answer (the read timeout once the request left): uncertain, and the request never sent twice")
	void noAnswer() throws InterruptedException {
		gateway.mode(Mode.NO_ANSWER);
		for (MockEnvironment env : Arrays.asList(getExample(), jsonExample())) {
			int before = gateway.requests().size();
			long started = System.currentTimeMillis();
			SendResult result = new HttpSmsProvider(env).send(sms("+21699000002", "Bonjour"));
			assertEquals(Outcome.UNCERTAIN, result.getOutcome());
			assertEquals("SMS: no answer within 2 s", result.getError());
			assertTrue(System.currentTimeMillis() - started < 8000, "the read timeout holds");
			Thread.sleep(500);
			assertEquals(before + 1, gateway.requests().size(), "one request, no retry behind our back");
		}
	}

	@Test
	@DisplayName("The SMS's own refusals: a number refused (400) and a 200 with an error inside: FAILED with the gateway's"
			+ " words; a refusal naming no credit (channel-error-texts): the channel's problem; a key echoed is masked")
	void refusals() {
		HttpSmsProvider provider = new HttpSmsProvider(jsonExample());
		gateway.refuse("21699000005");
		SendResult number = provider.send(sms("+21699000005", "Bonjour"));
		assertEquals(Outcome.PERMANENT_FAILURE, number.getOutcome());
		assertEquals("SMS refused (HTTP 400): Invalid destination number 21699000005", number.getError());
		assertEquals(Outcome.ACCEPTED, provider.send(sms("+21699000006", "Bonjour")).getOutcome(), "the others leave");

		gateway.mode(Mode.ERROR_INSIDE);
		SendResult inside = provider.send(sms("+21699000006", "Bonjour"));
		assertEquals(Outcome.PERMANENT_FAILURE, inside.getOutcome());
		assertEquals("SMS not accepted (HTTP 200): Sender name not allowed", inside.getError());
		SendResult text = new HttpSmsProvider(getExample()).send(sms("+21699000006", "Bonjour"));
		assertEquals(Outcome.PERMANENT_FAILURE, text.getOutcome(), "a text answer without OK");
		assertEquals("SMS not accepted (HTTP 200): ERR Sender name not allowed", text.getError());

		HttpSmsProvider credit = new HttpSmsProvider(jsonExample().withProperty(HttpSmsProvider.CHANNEL_ERRORS_KEY,
				"credit, sender name"));
		SendResult channel = credit.send(sms("+21699000006", "Bonjour"));
		assertEquals(Outcome.CHANNEL_PROBLEM, channel.getOutcome());
		assertEquals("SMS refused by the gateway (HTTP 200): Sender name not allowed", channel.getError());

		assertEquals("refus *** ici", provider.mask("refus " + KEY + " ici"));
	}

	@Test
	@DisplayName("The lines checked at the start: no address, not http, GET with a body, an unknown placeholder, {key}"
			+ " without a key, no {phone}; the channel off with the reason, never a refused start; SMS only; the Canaux"
			+ " tab sees the host only and \"set\" / \"not set\"")
	void lines() {
		assertEquals("communication.sms.http.url is empty", new HttpSmsProvider(new MockEnvironment()).unavailableReason());
		assertEquals("communication.sms.http.url is not an http or https address", new HttpSmsProvider(getExample()
				.withProperty(HttpSmsProvider.URL_KEY, "ftp://sms.example.tn/send")).unavailableReason());
		assertEquals("a GET sends its fields in the address: communication.sms.http.body must be query",
				new HttpSmsProvider(getExample().withProperty(HttpSmsProvider.BODY_KEY, "json")).unavailableReason());
		assertTrue(new HttpSmsProvider(getExample().withProperty(HttpSmsProvider.FIELD_PREFIX + "to", "{telephone}"))
				.unavailableReason().startsWith("communication.sms.http.field.to uses {telephone}, not a placeholder"));
		assertEquals("{key} is used, but communication.sms.http.key is empty",
				new HttpSmsProvider(getExample().withProperty(HttpSmsProvider.KEY_KEY, "")).unavailableReason());
		MockEnvironment noPhone = getExample();
		noPhone.setProperty(HttpSmsProvider.FIELD_PREFIX + "to", "");
		assertTrue(new HttpSmsProvider(noPhone).unavailableReason().startsWith("no field carries {phone}"));
		assertEquals("communication.sms.http.phone-format is not with-plus, without-plus, with-00, local",
				new HttpSmsProvider(getExample().withProperty(HttpSmsProvider.PHONE_FORMAT_KEY, "e164")).unavailableReason());

		MockEnvironment on = new MockEnvironment().withProperty("communication.sms.enabled", "true")
				.withProperty("communication.sms.provider", "http");
		HttpSmsProvider empty = new HttpSmsProvider(on);
		CommunicationSettings settings = new CommunicationSettings(on, Arrays.asList("test", "http"),
				Arrays.asList(new TestCommunicationProvider(), empty));
		assertFalse(settings.isEnabled(CommunicationTypes.SMS));
		assertEquals("communication.sms.http.url is empty", settings.offReason(CommunicationTypes.SMS));
		MockEnvironment emailOnHttp = new MockEnvironment().withProperty("communication.email.enabled", "true")
				.withProperty("communication.email.provider", "http");
		HttpSmsProvider ready = new HttpSmsProvider(getExample());
		CommunicationSettings wrongChannel = new CommunicationSettings(emailOnHttp, Arrays.asList("test", "http"),
				Arrays.asList(new TestCommunicationProvider(), ready));
		assertFalse(wrongChannel.isEnabled(CommunicationTypes.EMAIL));
		assertEquals("the provider http does not send email messages", wrongChannel.offReason(CommunicationTypes.EMAIL));

		Map<String, Object> shown = ready.describe();
		assertEquals("127.0.0.1", shown.get("host"));
		assertEquals("set", shown.get("key"));
		assertEquals("without-plus", shown.get("phoneFormat"));
		assertFalse(shown.toString().contains(KEY), "never the key");
		assertFalse(shown.toString().contains("/text/send"), "never the path");
		assertEquals("Happyness", ready.sender());
	}
}
