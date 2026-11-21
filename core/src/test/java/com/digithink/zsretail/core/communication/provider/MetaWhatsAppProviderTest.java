package com.digithink.zsretail.core.communication.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider.Outcome;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider.SendResult;
import com.digithink.zsretail.core.communication.service.CommunicationSettings;
import com.digithink.zsretail.core.communication.service.CommunicationTemplates;
import com.digithink.zsretail.core.support.CatchHttpProvider;
import com.digithink.zsretail.core.support.CatchHttpProvider.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.3.0, communication step 8c: the provider meta (WhatsApp Cloud API) against a gateway inside the test
 * (CatchHttpProvider answering as Meta, 127.0.0.1, no network): a template message with its name, its language and the
 * values of its {fields} in their order (positional or named), the phone with + and the country code, the token in its
 * header; Meta's answers sorted by error code (the channel's, this message's later or failed, the template's), a code
 * added by a line, an unknown code by its HTTP status; no answer: uncertain, sent once; the lines checked.
 */
class MetaWhatsAppProviderTest {

	private static final String TOKEN = "jeton-meta-de-test-8c";
	private static final ObjectMapper JSON = new ObjectMapper();

	private CatchHttpProvider meta;

	@BeforeEach
	void start() throws IOException {
		meta = new CatchHttpProvider(0, TOKEN);
	}

	@AfterEach
	void stop() {
		meta.close();
	}

	private MockEnvironment env() {
		return new MockEnvironment().withProperty(MetaWhatsAppProvider.URL_KEY, "http://127.0.0.1:" + meta.getPort())
				.withProperty(MetaWhatsAppProvider.PHONE_NUMBER_ID_KEY, "106540352242922")
				.withProperty(MetaWhatsAppProvider.TOKEN_KEY, TOKEN)
				.withProperty(MetaWhatsAppProvider.READ_TIMEOUT_KEY, "2");
	}

	private static CommunicationTemplate template(String body, String providerName, String format, String language) {
		CommunicationTemplate template = new CommunicationTemplate();
		template.setCode("MEMBER_CARD_WHATSAPP_FR");
		template.setChannel(CommunicationTypes.WHATSAPP);
		template.setLanguageCode("fr");
		template.setBody(body);
		template.setProviderTemplateName(providerName);
		template.setProviderParameterFormat(format);
		template.setProviderLanguage(language);
		return template;
	}

	private static CommunicationMessage whatsapp(String phone, CommunicationTemplate template, Map<String, Object> values) {
		CommunicationMessage message = new CommunicationMessage();
		message.setId(21L);
		message.setChannel(CommunicationTypes.WHATSAPP);
		message.setAddress(phone);
		message.setTemplateCode(template.getCode());
		message.setBody(CommunicationTemplates.render(template.getCode(), template.getBody(), values));
		message.setProviderParams(CommunicationTemplates.providerParams(CommunicationTemplates.providerTemplate(template),
				template.getBody(), values));
		return message;
	}

	private static Map<String, Object> values() {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("firstName", "سناء");
		values.put("companyName", "Happyness");
		values.put("cardNumber", "LYL-000004");
		values.put("total", 48.9);
		return values;
	}

	@Test
	@DisplayName("A positional template: POST <address>/<version>/<number id>/messages, Bearer token, the template's name"
			+ " and language, the values of its {fields} in the order of their first appearance (Arabic intact, 48.900), the"
			+ " phone with +; accepted: Meta's wamid")
	void positional() throws IOException {
		CommunicationTemplate template = template("Bienvenue {firstName} ! Carte {companyName} : {cardNumber}. Merci"
				+ " {firstName}, {total} TND.", "zs_carte_fidelite", null, null);
		SendResult result = new MetaWhatsAppProvider(env()).send(whatsapp("+21699000005", template, values()));
		assertEquals(Outcome.ACCEPTED, result.getOutcome(), result.getError());
		assertEquals("wamid.CATCH-0001", result.getProviderMessageId());
		assertNull(result.getError());
		CatchHttpProvider.Request request = meta.requests().get(0);
		assertEquals("POST", request.getMethod());
		assertEquals("/v26.0/106540352242922/messages", request.getPath());
		assertEquals("Bearer " + TOKEN, request.header("Authorization"));
		JsonNode body = JSON.readTree(request.getBody());
		assertEquals("whatsapp", body.get("messaging_product").asText());
		assertEquals("individual", body.get("recipient_type").asText());
		assertEquals("+21699000005", body.get("to").asText());
		assertEquals("template", body.get("type").asText());
		assertEquals("zs_carte_fidelite", body.at("/template/name").asText());
		assertEquals("fr", body.at("/template/language/code").asText());
		JsonNode parameters = body.at("/template/components/0/parameters");
		assertEquals("body", body.at("/template/components/0/type").asText());
		assertEquals(Arrays.asList("سناء", "Happyness", "LYL-000004", "48.900"), Arrays.asList(parameters.get(0).get("text")
				.asText(), parameters.get(1).get("text").asText(), parameters.get(2).get("text").asText(),
				parameters.get(3).get("text").asText()), "each field once, in the order of its first appearance");
		assertFalse(parameters.get(0).has("parameter_name"));
	}

	@Test
	@DisplayName("A named template (parameter_name first_name for {firstName}), Meta's language code of the template"
			+ " (en_US); a template without values: no components; a reseller's address, header and prefix")
	void namedAndReseller() throws IOException {
		CommunicationTemplate named = template("Hello {firstName}, your card {cardNumber}.", "zs_card", "NAMED", "en_US");
		MetaWhatsAppProvider provider = new MetaWhatsAppProvider(env());
		assertEquals(Outcome.ACCEPTED, provider.send(whatsapp("+21699000006", named, values())).getOutcome());
		JsonNode body = JSON.readTree(meta.requests().get(0).getBody());
		assertEquals("en_US", body.at("/template/language/code").asText());
		assertEquals("first_name", body.at("/template/components/0/parameters/0/parameter_name").asText());
		assertEquals("card_number", body.at("/template/components/0/parameters/1/parameter_name").asText());
		assertEquals("LYL-000004", body.at("/template/components/0/parameters/1/text").asText());

		CommunicationTemplate plain = template("Bienvenue chez nous !", "zs_hello", null, null);
		MetaWhatsAppProvider reseller = new MetaWhatsAppProvider(env().withProperty(MetaWhatsAppProvider.API_VERSION_KEY, "v1")
				.withProperty(MetaWhatsAppProvider.TOKEN_HEADER_KEY, "X-Api-Key")
				.withProperty(MetaWhatsAppProvider.TOKEN_PREFIX_KEY, ""));
		assertEquals(Outcome.ACCEPTED, reseller.send(whatsapp("+21699000006", plain, values())).getOutcome());
		CatchHttpProvider.Request request = meta.requests().get(1);
		assertEquals("/v1/106540352242922/messages", request.getPath());
		assertEquals(TOKEN, request.header("X-Api-Key"));
		assertFalse(JSON.readTree(request.getBody()).get("template").has("components"));
	}

	@Test
	@DisplayName("Meta's answers by code: token expired #190 and throughput #130429 the channel's; #132001 the template's;"
			+ " #131026 this message FAILED; #2 (503) the channel's; the token never in a reason")
	void answers() {
		CommunicationTemplate template = template("Bonjour {firstName}", "zs_bonjour", null, null);
		MetaWhatsAppProvider wrongToken = new MetaWhatsAppProvider(env().withProperty(MetaWhatsAppProvider.TOKEN_KEY, "perime"));
		SendResult expired = wrongToken.send(whatsapp("+21699000005", template, values()));
		assertEquals(Outcome.CHANNEL_PROBLEM, expired.getOutcome());
		assertTrue(expired.getError().startsWith("WhatsApp channel refused by Meta (#190): Error validating access token"),
				expired.getError());

		MetaWhatsAppProvider provider = new MetaWhatsAppProvider(env());
		meta.mode(Mode.TOO_MANY);
		SendResult rate = provider.send(whatsapp("+21699000005", template, values()));
		assertEquals(Outcome.CHANNEL_PROBLEM, rate.getOutcome());
		assertEquals("WhatsApp channel refused by Meta (#130429): (#130429) Rate limit hit - Cloud API message throughput"
				+ " has been reached.", rate.getError());

		meta.mode(Mode.SERVER_ERROR);
		assertEquals(Outcome.CHANNEL_PROBLEM, provider.send(whatsapp("+21699000005", template, values())).getOutcome());

		meta.mode(Mode.ACCEPTED).refuseTemplate("zs_bonjour");
		SendResult refusedTemplate = provider.send(whatsapp("+21699000005", template, values()));
		assertEquals(Outcome.TEMPLATE_PROBLEM, refusedTemplate.getOutcome());
		assertTrue(refusedTemplate.getError().startsWith("WhatsApp template zs_bonjour (fr) refused by Meta (#132001):"),
				refusedTemplate.getError());

		meta.refuse("21699000009");
		SendResult cannot = provider.send(whatsapp("+21699000009", template("Salut {firstName}", "zs_salut", null, null),
				values()));
		assertEquals(Outcome.PERMANENT_FAILURE, cannot.getOutcome());
		assertTrue(cannot.getError().startsWith("WhatsApp message refused by Meta (#131026): (#131026) Message"
				+ " undeliverable"), cannot.getError());
		for (SendResult r : Arrays.asList(expired, rate, refusedTemplate, cannot)) {
			assertFalse(r.getError().contains(TOKEN));
		}
	}

	@Test
	@DisplayName("Codes added by a line win over the defaults; an unknown code by the HTTP status; the families of Meta's"
			+ " page")
	void codes() {
		MetaWhatsAppProvider provider = new MetaWhatsAppProvider(env().withProperty(MetaWhatsAppProvider.RETRY_CODES_KEY,
				"131026").withProperty(MetaWhatsAppProvider.MESSAGE_CODES_KEY, "140000-140009"));
		assertEquals(MetaWhatsAppProvider.Family.RETRY, provider.family(131026), "the line wins");
		assertEquals(MetaWhatsAppProvider.Family.MESSAGE, provider.family(140005));
		assertEquals(MetaWhatsAppProvider.Family.CHANNEL, provider.family(250), "200-299");
		assertEquals(MetaWhatsAppProvider.Family.RETRY, provider.family(131056));
		assertEquals(MetaWhatsAppProvider.Family.TEMPLATE, provider.family(132015));
		assertNull(provider.family(999999));
		assertEquals(Outcome.PERMANENT_FAILURE, provider.answer(400, "{\"error\":{\"message\":\"odd\",\"code\":999999}}",
				null, "zs_x", "fr").getOutcome());
		assertEquals(Outcome.CHANNEL_PROBLEM, provider.answer(503, "<html>Service unavailable</html>", null, "zs_x", "fr")
				.getOutcome());
		SendResult held = provider.answer(200, "{\"messages\":[{\"id\":\"wamid.X\",\"message_status\":"
				+ "\"held_for_quality_assessment\"}]}", null, "zs_x", "fr");
		assertEquals(Outcome.ACCEPTED, held.getOutcome());
		assertEquals("wamid.X", held.getProviderMessageId());
		assertEquals("accepted by Meta as held_for_quality_assessment: Meta may deliver it later or not", held.getError());
		assertEquals("communication.whatsapp.meta.template-codes is not a list of codes or ranges (e.g."
				+ " 131056,132000-132016)", new MetaWhatsAppProvider(env().withProperty(MetaWhatsAppProvider.TEMPLATE_CODES_KEY,
						"abc")).unavailableReason());
	}

	@Test
	@DisplayName("Step 8d: 131049 (Meta's marketing limit, wait 24 h) and 131030 (not in the test number's allowed list)"
			+ " FAILED at once with what to do; 132005 (too long with this member's values) this message only, never the"
			+ " template's; 131066 FAILED at once; 131056 still tried again later")
	void step8dCodes() {
		MetaWhatsAppProvider provider = new MetaWhatsAppProvider(env());
		SendResult limit = provider.answer(400, metaError(131049, "(#131049) This message was not delivered to maintain"
				+ " healthy ecosystem engagement."), null, "zs_promo", "fr");
		assertEquals(Outcome.PERMANENT_FAILURE, limit.getOutcome());
		assertEquals("WhatsApp marketing limit at Meta (#131049), try again after 24 h: (#131049) This message was not"
				+ " delivered to maintain healthy ecosystem engagement.", limit.getError());
		SendResult notAllowed = provider.answer(400, metaError(131030, "(#131030) Recipient phone number not in allowed"
				+ " list"), null, "zs_promo", "fr");
		assertEquals(Outcome.PERMANENT_FAILURE, notAllowed.getOutcome());
		assertEquals("WhatsApp number not in the allowed list of Meta's test number (#131030), add it in Meta's app"
				+ " dashboard (WhatsApp, API Setup): (#131030) Recipient phone number not in allowed list", notAllowed.getError());
		SendResult tooLong = provider.answer(400, metaError(132005, "(#132005) Translated text is too long"), null,
				"zs_promo", "fr");
		assertEquals(Outcome.PERMANENT_FAILURE, tooLong.getOutcome(), "the member's values, not the template");
		assertEquals("WhatsApp message refused by Meta (#132005): (#132005) Translated text is too long",
				tooLong.getError());
		assertEquals(MetaWhatsAppProvider.Family.MESSAGE, provider.family(131066));
		assertEquals(Outcome.TEMPORARY_FAILURE, provider.answer(400, metaError(131056, "(#131056) Pair rate limit hit"),
				null, "zs_promo", "fr").getOutcome());
		assertEquals(MetaWhatsAppProvider.Family.TEMPLATE, provider.family(132001));
		assertEquals(MetaWhatsAppProvider.Family.RETRY, new MetaWhatsAppProvider(env().withProperty(
				MetaWhatsAppProvider.RETRY_CODES_KEY, "131049")).family(131049), "a line still moves it back");
	}

	@Test
	@DisplayName("Step 8e: a tab or a line break becomes a space, more than 4 spaces become one; a value empty once"
			+ " cleaned never goes to Meta (FAILED with the field's name); a proxy's 502 / 504 / 524 without Meta's error:"
			+ " no answer (uncertain), Meta's code inside still wins")
	void step8eValuesAndProxies() throws IOException {
		assertEquals("Sa na Ben Ali", MetaWhatsAppProvider.clean("Sa\tna\r\nBen      Ali"));
		assertEquals("a    b", MetaWhatsAppProvider.clean("a    b"), "4 spaces kept");
		MetaWhatsAppProvider provider = new MetaWhatsAppProvider(env());
		CommunicationTemplate template = template("Bonjour {firstName} chez {companyName}", "zs_bonjour", null, null);
		Map<String, Object> tabbed = values();
		tabbed.put("firstName", "Sana\tBen\nAli");
		assertEquals(Outcome.ACCEPTED, provider.send(whatsapp("+21699000005", template, tabbed)).getOutcome());
		assertTrue(meta.requests().get(0).getBody().contains("\"text\":\"Sana Ben Ali\""), meta.requests().get(0).getBody());

		Map<String, Object> blank = values();
		blank.put("firstName", " \t ");
		SendResult empty = provider.send(whatsapp("+21699000005", template, blank));
		assertEquals(Outcome.PERMANENT_FAILURE, empty.getOutcome());
		assertEquals("WhatsApp value of {firstName} is empty: not sent", empty.getError());
		assertEquals(1, meta.requests().size(), "no call to Meta");

		SendResult proxy = provider.answer(504, "<html>Gateway Time-out</html>", null, "zs_x", "fr");
		assertEquals(Outcome.UNCERTAIN, proxy.getOutcome());
		assertEquals("WhatsApp: no answer from Meta (HTTP 504)", proxy.getError());
		assertEquals(Outcome.UNCERTAIN, provider.answer(524, "", null, "zs_x", "fr").getOutcome());
		assertEquals(Outcome.CHANNEL_PROBLEM, provider.answer(502, metaError(131000, "(#131000) Something went wrong"),
				null, "zs_x", "fr").getOutcome(), "Meta's own code wins");
	}

	private static String metaError(long code, String message) {
		return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"OAuthException\",\"code\":" + code
				+ ",\"fbtrace_id\":\"ATEST\"}}";
	}

	@Test
	@DisplayName("No answer once the request left: uncertain, sent once")
	void noAnswer() throws InterruptedException {
		meta.mode(Mode.NO_ANSWER);
		SendResult result = new MetaWhatsAppProvider(env()).send(whatsapp("+21699000005",
				template("Bonjour {firstName}", "zs_bonjour", null, null), values()));
		assertEquals(Outcome.UNCERTAIN, result.getOutcome());
		assertEquals("WhatsApp: no answer within 2 s", result.getError());
		Thread.sleep(500);
		assertEquals(1, meta.requests().size());
	}

	@Test
	@DisplayName("The lines: no number id or no token, the channel off with one reason; WhatsApp only; the Canaux view"
			+ " (host, version, number id, token set / not set, never its value); no template on the message: FAILED")
	void lines() {
		assertEquals("communication.whatsapp.meta.phone-number-id is empty",
				new MetaWhatsAppProvider(new MockEnvironment().withProperty(MetaWhatsAppProvider.TOKEN_KEY, TOKEN)).unavailableReason());
		assertEquals("communication.whatsapp.meta.token is empty", new MetaWhatsAppProvider(new MockEnvironment()
				.withProperty(MetaWhatsAppProvider.PHONE_NUMBER_ID_KEY, "1065")).unavailableReason());
		MockEnvironment on = new MockEnvironment().withProperty("communication.whatsapp.enabled", "true")
				.withProperty("communication.whatsapp.provider", "meta");
		CommunicationSettings settings = new CommunicationSettings(on, Arrays.asList("test", "meta"),
				Arrays.asList(new TestCommunicationProvider(), new MetaWhatsAppProvider(on)));
		assertFalse(settings.isEnabled(CommunicationTypes.WHATSAPP));
		assertEquals("communication.whatsapp.meta.phone-number-id is empty", settings.offReason(CommunicationTypes.WHATSAPP));
		MetaWhatsAppProvider provider = new MetaWhatsAppProvider(env());
		assertTrue(provider.serves(CommunicationTypes.WHATSAPP));
		assertFalse(provider.serves(CommunicationTypes.SMS));
		Map<String, Object> shown = provider.describe();
		assertEquals("127.0.0.1", shown.get("host"));
		assertEquals("v26.0", shown.get("apiVersion"));
		assertEquals("106540352242922", shown.get("phoneNumberId"));
		assertEquals("set", shown.get("token"));
		assertFalse(shown.toString().contains(TOKEN));
		CommunicationMessage bare = new CommunicationMessage();
		bare.setChannel(CommunicationTypes.WHATSAPP);
		bare.setAddress("+21699000005");
		bare.setBody("texte");
		assertEquals(Outcome.PERMANENT_FAILURE, provider.send(bare).getOutcome());
		assertTrue(meta.requests().isEmpty());
	}
}
