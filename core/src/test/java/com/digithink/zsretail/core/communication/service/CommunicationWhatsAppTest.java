package com.digithink.zsretail.core.communication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.communication.CommunicationException;
import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationCampaignStatus;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationCampaign;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.model.CommunicationRule;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider;
import com.digithink.zsretail.core.communication.provider.TestCommunicationProvider;
import com.digithink.zsretail.core.communication.service.CommunicationAdmin.NewRuleForm;
import com.digithink.zsretail.core.communication.service.CommunicationCampaigns.LaunchForm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.3.0, communication step 8c: WhatsApp through Meta, above the provider: each message carries its template at Meta and
 * the values of its {fields} in order (an event's, a campaign's, a test's); a WhatsApp template without its name at
 * Meta is refused by a rule and by the campaign window; a template refused by Meta stops its campaign (its waiting
 * messages SKIPPED), marks the template (the Rules tab's warning), never pauses the channel, and the next message of it
 * accepted clears the mark; a provider's wait never pauses a channel longer than an hour.
 */
class CommunicationWhatsAppTest {

	private static final String WELCOME = "Bienvenue {firstName} ! Carte {companyName} : {cardNumber}. {firstName}, a bientot.";

	private final CampaignTestBed bed = new CampaignTestBed();
	private final ObjectMapper json = new ObjectMapper();

	/** The WhatsApp provider of the test: refuses the templates named in refused, accepts the others. */
	private static final class Meta implements CommunicationProvider {
		final List<CommunicationMessage> given = new ArrayList<>();
		final List<String> refused = new ArrayList<>();

		@Override
		public String getName() {
			return "meta";
		}

		@Override
		public SendResult send(CommunicationMessage message) {
			given.add(message);
			String name = message.getProviderParams() == null ? "" : message.getProviderParams();
			for (String template : refused) {
				if (name.contains("\"name\":\"" + template + "\"")) {
					return SendResult.templateProblem("WhatsApp template " + template + " (fr) refused by Meta (#132001):"
							+ " (#132001) Template name does not exist in the translation");
				}
			}
			return SendResult.accepted("wamid.T-" + given.size());
		}
	}

	private final Meta meta = new Meta();

	@BeforeEach
	void whatsappOn() {
		bed.smsEnabled = "false";
		bed.properties.put("communication.whatsapp.enabled", "true");
		bed.properties.put("communication.whatsapp.provider", "meta");
		bed.extraProviders.add(meta);
	}

	private CommunicationTemplate whatsappTemplate(String code, String body, String providerName) {
		CommunicationTemplate template = bed.template(code, CommunicationTypes.WHATSAPP, body, true);
		template.setProviderTemplateName(providerName);
		return template;
	}

	@Test
	@DisplayName("The values of a WhatsApp message: each {field} once, in the order of its first appearance, filled as the"
			+ " text is; named: first_name for {firstName}")
	void params() throws Exception {
		CommunicationTemplate template = whatsappTemplate("W", "Merci {firstName} : {total} TND, solde {balanceAfter}."
				+ " {firstName} !", "zs_merci");
		template.setProviderLanguage("en_US");
		template.setProviderParameterFormat("named");
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("firstName", "Sana");
		values.put("total", 48.9);
		values.put("balanceAfter", 161.0);
		JsonNode params = json.readTree(CommunicationTemplates.providerParams(CommunicationTemplates.providerTemplate(template),
				template.getBody(), values));
		assertEquals("zs_merci", params.get("name").asText());
		assertEquals("en_US", params.get("language").asText());
		assertEquals("NAMED", params.get("format").asText());
		assertEquals(3, params.get("values").size());
		assertEquals("first_name", params.at("/values/0/name").asText());
		assertEquals("Sana", params.at("/values/0/value").asText());
		assertEquals("48.900", params.at("/values/1/value").asText());
		assertEquals("balance_after", params.at("/values/2/name").asText());
		assertEquals("161", params.at("/values/2/value").asText());
		assertEquals("points_balance", CommunicationTemplates.namedParameter("pointsBalance"));
		template.setProviderTemplateName(null);
		assertNull(CommunicationTemplates.providerTemplate(template));
	}

	@Test
	@DisplayName("An event's WhatsApp message carries its template at Meta and its values; a template without its name at"
			+ " Meta: no message, the event says why")
	void eventMessage() throws Exception {
		bed.member("LYL-000001", "Sana", "20000001", true);
		whatsappTemplate("TICKET_THANKS_WHATSAPP_FR", "Merci {firstName} pour votre achat chez {companyName}.", "zs_merci");
		rule("TICKET_THANKS_WHATSAPP", "TICKET_THANKS_WHATSAPP_FR");
		ticketEvent();
		bed.processor().run(Instant.now().plusSeconds(20));
		CommunicationMessage message = bed.messageTable.values().iterator().next();
		assertEquals(CommunicationTypes.WHATSAPP, message.getChannel());
		JsonNode params = json.readTree(message.getProviderParams());
		assertEquals("zs_merci", params.get("name").asText());
		assertEquals("Sana", params.at("/values/0/value").asText());
		assertEquals("Happyness", params.at("/values/1/value").asText());

		bed.templateTable.get("TICKET_THANKS_WHATSAPP_FR").setProviderTemplateName(null);
		CommunicationEvent second = ticketEvent();
		bed.processor().run(Instant.now().plusSeconds(20));
		assertEquals("TICKET_THANKS_WHATSAPP: template TICKET_THANKS_WHATSAPP_FR has no provider template name",
				second.getLastError());
	}

	@Test
	@DisplayName("A WhatsApp template without its name at Meta: refused by a rule (created or switched on) and by the"
			+ " campaign window (not offered, its launch refused); with one: the campaign keeps it, its messages carry it")
	void providerNameRequired() throws Exception {
		whatsappTemplate("PROMO_WHATSAPP_FR", "{firstName}, nos offres chez {companyName} !", null);
		NewRuleForm form = new NewRuleForm();
		form.code = "WELCOME_WHATSAPP";
		form.eventType = CommunicationTypes.MEMBER_CREATED;
		form.channel = CommunicationTypes.WHATSAPP;
		form.templateCode = "PROMO_WHATSAPP_FR";
		CommunicationException rule = assertThrows(CommunicationException.class, () -> bed.admin().createRule(form, "admin"));
		assertEquals("COMM_TEMPLATE_PROVIDER_NAME", rule.getCode());

		Map<String, Object> row = bed.campaigns().templates().stream().filter(t -> "PROMO_WHATSAPP_FR".equals(t.get("code")))
				.findFirst().get();
		assertEquals(false, row.get("usable"));
		assertEquals(true, row.get("noProviderName"));
		CommunicationException launch = assertThrows(CommunicationException.class, () -> bed.campaigns().launch(
				launchForm("PROMO_WHATSAPP_FR"), "admin"));
		assertEquals("COMM_TEMPLATE_PROVIDER_NAME", launch.getCode());

		bed.templateTable.get("PROMO_WHATSAPP_FR").setProviderTemplateName("zs_promo");
		assertEquals("WELCOME_WHATSAPP", bed.admin().createRule(form, "admin").getCode());
		bed.member("LYL-000001", "Sana", "20000001", true);
		bed.campaigns().launch(launchForm("PROMO_WHATSAPP_FR"), "admin");
		CommunicationCampaign campaign = bed.campaignTable.values().iterator().next();
		assertEquals("zs_promo", json.readTree(campaign.getProviderTemplate()).get("name").asText());
		bed.maker().run(Instant.now().plusSeconds(20));
		CommunicationMessage made = bed.messagesOf(campaign.getId()).get(0);
		assertEquals("zs_promo", json.readTree(made.getProviderParams()).get("name").asText());
	}

	@Test
	@DisplayName("A template refused by Meta during a campaign: that message FAILED, the campaign STOPPED with the reason,"
			+ " its waiting messages SKIPPED, the template marked (the rule's warning), the channel not paused; a message of"
			+ " another template leaves; the next message of the template accepted clears the mark")
	void templateRefused() {
		for (int i = 1; i <= 3; i++) {
			bed.member("LYL-00000" + i, "Membre" + i, "2000000" + i, true);
		}
		whatsappTemplate("PROMO_WHATSAPP_FR", "{firstName}, nos offres chez {companyName} !", "zs_promo");
		whatsappTemplate("MEMBER_CARD_WHATSAPP_FR", WELCOME, "zs_carte");
		rule("PROMO_RULE", "PROMO_WHATSAPP_FR");
		bed.campaigns().launch(launchForm("PROMO_WHATSAPP_FR"), "admin");
		bed.maker().run(Instant.now().plusSeconds(20));
		CommunicationCampaign campaign = bed.campaignTable.values().iterator().next();
		CommunicationMessage welcome = automatic("LYL-000001", "20000001", "MEMBER_CARD_WHATSAPP_FR", "zs_carte");
		meta.refused.add("zs_promo");

		String done = bed.sender(meta).run(Instant.now().plusSeconds(20));
		assertEquals(CommunicationMessageStatus.SENT, welcome.getStatus(), "another template leaves");
		List<CommunicationMessage> ofCampaign = bed.messagesOf(campaign.getId());
		assertEquals(1, ofCampaign.stream().filter(m -> m.getStatus() == CommunicationMessageStatus.FAILED).count(), done);
		assertEquals(2, ofCampaign.stream().filter(m -> m.getStatus() == CommunicationMessageStatus.SKIPPED).count(), done);
		assertTrue(ofCampaign.stream().filter(m -> m.getStatus() == CommunicationMessageStatus.SKIPPED)
				.allMatch(m -> m.getLastError().startsWith("campaign stopped: WhatsApp template zs_promo (fr) refused by"
						+ " Meta (#132001)")));
		assertEquals(CommunicationCampaignStatus.STOPPED, campaign.getStatus());
		assertTrue(campaign.getStopReason().startsWith("WhatsApp template zs_promo (fr) refused by Meta (#132001)"));
		assertEquals(2, meta.given.size(), "the campaign's two others never given to Meta");
		assertNull(bed.channelStateTable.get(CommunicationTypes.WHATSAPP) == null ? null
				: bed.channelStateTable.get(CommunicationTypes.WHATSAPP).getPausedSince(), "the channel not paused");
		CommunicationTemplate marked = bed.templateTable.get("PROMO_WHATSAPP_FR");
		assertTrue(marked.getProviderProblem().contains("#132001"));
		CommunicationRule warned = bed.admin().rules().stream().filter(r -> "PROMO_RULE".equals(r.getCode())).findFirst().get();
		assertEquals(marked.getProviderProblem(), warned.getTemplateProblem());

		meta.refused.clear();
		CommunicationMessage later = automatic("LYL-000002", "20000002", "PROMO_WHATSAPP_FR", "zs_promo");
		bed.sender(meta).run(Instant.now().plusSeconds(20));
		assertEquals(CommunicationMessageStatus.SENT, later.getStatus());
		assertNull(bed.templateTable.get("PROMO_WHATSAPP_FR").getProviderProblem(), "accepted: the mark cleared");
	}

	@Test
	@DisplayName("A WhatsApp test from the Canaux tab sends the chosen template with the sample values; none chosen:"
			+ " refused; a provider's wait never pauses a channel longer than 60 min")
	void testAndWait() throws Exception {
		whatsappTemplate("MEMBER_CARD_WHATSAPP_FR", WELCOME, "zs_carte");
		CommunicationChannels channels = new CommunicationChannels(bed.settings(), Arrays.asList(
				new TestCommunicationProvider(), meta), bed.messages(), bed.sender(meta), () -> "Happyness",
				() -> "Licence SARL", Optional.empty(), () -> bed.now).withTemplates(bed.templates());
		Map<String, Object> sent = channels.testSend("WHATSAPP", "20000009", "admin", "MEMBER_CARD_WHATSAPP_FR");
		assertEquals(CommunicationMessageStatus.SENT, sent.get("status"));
		JsonNode params = json.readTree(meta.given.get(0).getProviderParams());
		assertEquals("zs_carte", params.get("name").asText());
		assertEquals("Amira", params.at("/values/0/value").asText());
		assertEquals("Happyness", params.at("/values/1/value").asText());
		assertEquals("LYL-000123", params.at("/values/2/value").asText());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> offered = (List<Map<String, Object>>) channels.view().get("whatsappTemplates");
		assertEquals("zs_carte", offered.get(0).get("providerTemplateName"));
		assertEquals("COMM_TEST_TEMPLATE", assertThrows(CommunicationException.class,
				() -> channels.testSend("WHATSAPP", "20000009", "admin", null)).getCode());

		bed.channelStates().problem(CommunicationTypes.SMS, "SMS too many requests (HTTP 429), wait 10800 s", false,
				Duration.ofHours(3));
		assertEquals(bed.now.plusMinutes(60), bed.channelStateTable.get(CommunicationTypes.SMS).getPausedUntil());
	}

	// ─── Helpers ─────────────────────────────────────────────────────

	private void rule(String code, String templateCode) {
		CommunicationRule rule = new CommunicationRule();
		rule.setId((long) bed.ruleTable.size() + 1);
		rule.setCode(code);
		rule.setEventType(CommunicationTypes.TICKET_VALIDATED);
		rule.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		rule.setChannel(CommunicationTypes.WHATSAPP);
		rule.setTemplateCode(templateCode);
		rule.setActive(true);
		bed.ruleTable.add(rule);
	}

	private static LaunchForm launchForm(String templateCode) {
		LaunchForm form = new LaunchForm();
		form.name = "Promo WhatsApp";
		form.templateCode = templateCode;
		form.channel = CommunicationTypes.WHATSAPP;
		return form;
	}

	private CommunicationMessage automatic(String card, String phone, String templateCode, String providerName) {
		CommunicationTemplate template = bed.templateTable.get(templateCode);
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("firstName", "Sana");
		values.put("companyName", "Happyness");
		values.put("cardNumber", card);
		CommunicationMessage message = new CommunicationMessage();
		message.setEventId(99L);
		message.setChannel(CommunicationTypes.WHATSAPP);
		message.setProvider("meta");
		message.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		message.setRecipientRef(card);
		message.setAddress("+216" + phone);
		message.setTemplateCode(templateCode);
		message.setBody(CommunicationTemplates.render(templateCode, template.getBody(), values));
		message.setProviderParams(CommunicationTemplates.providerParams(CommunicationTemplates.providerTemplate(template),
				template.getBody(), values));
		message.setDedupKey("99:" + templateCode + ":" + card + ":" + bed.messageTable.size());
		return bed.messages().save(message);
	}

	private CommunicationEvent ticketEvent() {
		CommunicationEvent event = new CommunicationEvent();
		event.setId((long) bed.eventTable.size() + 1);
		event.setEventType(CommunicationTypes.TICKET_VALIDATED);
		event.setSourceType(CommunicationTypes.SALES_HEADER);
		event.setDedupKey("TICKET_VALIDATED:S:" + event.getId());
		event.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		event.setRecipientRef("LYL-000001");
		event.setOccurredAt(bed.now.minusMinutes(1));
		event.setPayload("{\"ticketNumber\":\"T1\",\"firstName\":\"Sana\",\"companyName\":\"Happyness\"}");
		bed.eventTable.put(event.getId(), event);
		return event;
	}
}
