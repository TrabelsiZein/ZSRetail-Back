package com.digithink.zsretail.core.communication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.communication.CommunicationException;
import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationCampaignStatus;
import com.digithink.zsretail.core.communication.enumeration.CommunicationEventStatus;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationCampaign;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.model.CommunicationRule;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider;
import com.digithink.zsretail.core.communication.provider.TestCommunicationProvider;
import com.digithink.zsretail.core.communication.scheduler.CommunicationCampaignMaker;
import com.digithink.zsretail.core.communication.service.CommunicationCampaigns.LaunchForm;
import com.digithink.zsretail.core.model.LoyaltyMember;

/**
 * 3.3.0, communication step 7: the campaigns over the module's tables in memory (CampaignTestBed). Who receives one
 * (consent, address, one message per address), its launch, the background making (a batch made twice, a restart in the
 * middle), its cancel, the refusals, the warning of the same day, the automatic messages sent before the campaigns'
 * and the empty-field guard of every message. The member query itself runs on H2 in CampaignAudienceQueryTest.
 */
class CommunicationCampaignsTest {

	private static final String PROMO = "{firstName}, nos offres chez {companyName} ! Solde : {pointsBalance} points.";

	private final CampaignTestBed bed = new CampaignTestBed();

	@BeforeEach
	void promo() {
		bed.template("PROMO_SMS_FR", CommunicationTypes.SMS, PROMO, true);
	}

	private static LaunchForm form(String name) {
		LaunchForm form = new LaunchForm();
		form.name = name;
		form.templateCode = "PROMO_SMS_FR";
		form.channel = "SMS";
		return form;
	}

	private Map<String, Object> launch(String name) {
		return bed.campaigns().launch(form(name), "admin");
	}

	private String make() {
		return bed.maker().run(Instant.now().plusSeconds(20));
	}

	private String send(CommunicationProvider provider) {
		return bed.sender(provider).run(Instant.now().plusSeconds(20));
	}

	private CommunicationException refused(Runnable call) {
		return assertThrows(CommunicationException.class, call::run);
	}

	// ─── Who receives it, the launch, the making ─────────────────────

	@Test
	@DisplayName("The window's count and preview; launched RUNNING with that count; the messages made in the background,"
			+ " one per member with the consent; sent; DONE once none waits")
	void launchMakeSend() {
		bed.member("LYL-000001", "Sana", "20000001", true);
		bed.member("LYL-000002", "Ali", "20000002", true);
		bed.member("LYL-000003", "Rim", "20000003", false);
		Map<String, Object> audience = bed.campaigns().audience("PROMO_SMS_FR", null);
		assertEquals(2, audience.get("receive"));
		assertEquals(1L, audience.get("noConsent"));
		assertEquals(0, audience.get("noAddress"));
		@SuppressWarnings("unchecked")
		Map<String, Object> preview = (Map<String, Object>) audience.get("preview");
		assertEquals("Sana, nos offres chez Happyness ! Solde : 120 points.", preview.get("text"));
		assertEquals("LYL-000001", preview.get("card"));
		assertEquals(1, preview.get("parts"));
		assertNull(audience.get("sameDay"));

		Map<String, Object> row = launch("Promo novembre");
		assertEquals(CommunicationCampaignStatus.RUNNING, row.get("status"));
		assertEquals(2, row.get("targeted"));
		assertTrue(((String) row.get("code")).startsWith("CMP-261121-100000"), (String) row.get("code"));
		assertTrue(bed.messageTable.isEmpty(), "nothing made in the request");

		assertEquals("2 message(s) made for " + row.get("code"), make());
		List<CommunicationMessage> made = bed.messagesOf(1L);
		assertEquals(2, made.size());
		CommunicationMessage first = made.get(0);
		assertEquals("Sana, nos offres chez Happyness ! Solde : 120 points.", first.getBody());
		assertEquals("+21620000001", first.getAddress());
		assertEquals("LYL-000001", first.getRecipientRef());
		assertEquals("CAMPAIGN:1:+21620000001", first.getDedupKey());
		assertNull(first.getEventId());
		assertEquals(bed.now.plusHours(24), first.getExpiresAt());
		assertEquals(CommunicationMessageStatus.PENDING, first.getStatus());
		assertTrue(made.stream().noneMatch(m -> "LYL-000003".equals(m.getRecipientRef())), "no consent: no message");
		CommunicationCampaign campaign = bed.campaignTable.get(1L);
		assertNotNull(campaign.getMadeAt());
		assertEquals(2, campaign.getMade());

		assertEquals("2 sent", send(new TestCommunicationProvider()));
		assertEquals(CommunicationCampaignStatus.RUNNING, campaign.getStatus(), "DONE at the maker's next cycle");
		assertEquals(row.get("code") + " done", make());
		assertEquals(CommunicationCampaignStatus.DONE, campaign.getStatus());
		@SuppressWarnings("unchecked")
		Map<String, Object> listed = ((List<Map<String, Object>>) bed.campaigns().list(0, 20).get("content")).get(0);
		assertEquals(2L, listed.get("sent"));
		assertEquals(0L, listed.get("failed"));
		assertEquals(CommunicationCampaignStatus.DONE, listed.get("status"));
	}

	@Test
	@DisplayName("Two members with the same phone: one message, to the first by id; counted 'same address'")
	void samePhone() {
		bed.member("LYL-000001", "Sana", "20000001", true);
		bed.member("LYL-000002", "Ali", "20000001", true);
		bed.member("LYL-000003", "Rim", "", true);
		Map<String, Object> audience = bed.campaigns().audience("PROMO_SMS_FR", "SMS");
		assertEquals(1, audience.get("receive"));
		assertEquals(1, audience.get("sameAddress"));
		assertEquals(1, audience.get("noAddress"));
		launch("Promo");
		make();
		assertEquals(Collections.singletonList("LYL-000001"), bed.messagesOf(1L).stream()
				.map(CommunicationMessage::getRecipientRef).collect(Collectors.toList()));
		assertEquals(2, bed.campaignTable.get(1L).getLeftOut());
	}

	@Test
	@DisplayName("A restart in the middle goes on after the last batch written; a batch made twice (a launch run twice)"
			+ " writes no double: as many messages as the count, each address once")
	void restartAndTwice() {
		for (int i = 1; i <= 450; i++) {
			bed.member(String.format("LYL-%06d", i), "M" + i, String.format("2%07d", i), true);
		}
		bed.member("LYL-000451", "Twin", "20000007", true); // the phone of LYL-000007
		Map<String, Object> row = launch("Promo");
		assertEquals(450, row.get("targeted"));

		CommunicationCampaignMaker before = bed.maker();
		CommunicationCampaignMaker.Batch batch = before.batch(1L);
		assertEquals(200, batch.getMade());
		assertFalse(batch.isLast());
		assertEquals(200L, bed.campaignTable.get(1L).getLastMemberId());

		make(); // a new maker: the application started again
		List<CommunicationMessage> made = bed.messagesOf(1L);
		assertEquals(450, made.size());
		assertEquals(450, new HashSet<>(made.stream().map(CommunicationMessage::getAddress).collect(Collectors.toList()))
				.size());
		assertEquals(450, bed.campaignTable.get(1L).getMade());

		// The same members read again (the cursor back to the start): nothing more
		CommunicationCampaign campaign = bed.campaignTable.get(1L);
		campaign.setLastMemberId(0);
		campaign.setMadeAt(null);
		make();
		assertEquals(450, bed.messagesOf(1L).size());
		assertEquals(450, campaign.getMade());
	}

	@Test
	@DisplayName("Cancelled while running: CANCELLED, its waiting messages SKIPPED with the reason, the ones sent stay;"
			+ " nothing more made or sent; a second cancel refused")
	void cancel() {
		bed.member("LYL-000001", "Sana", "20000001", true);
		bed.member("LYL-000002", "Ali", "20000002", true);
		bed.member("LYL-000003", "Rim", "20000003", true);
		launch("Promo");
		make();
		List<CommunicationMessage> made = bed.messagesOf(1L);
		made.get(0).setStatus(CommunicationMessageStatus.SENT);
		Map<String, Object> cancelled = bed.campaigns().cancel(1L, "admin");
		assertEquals(CommunicationCampaignStatus.CANCELLED, cancelled.get("status"));
		assertEquals("admin", cancelled.get("cancelledBy"));
		assertEquals(CommunicationMessageStatus.SENT, made.get(0).getStatus());
		assertEquals(CommunicationMessageStatus.SKIPPED, made.get(1).getStatus());
		assertEquals("campaign cancelled by admin at 2026-11-21 10:00", made.get(2).getLastError());
		assertEquals(1L, cancelled.get("sent"));
		assertEquals(2L, cancelled.get("skipped"));

		// A message of the campaign still waiting (read before the cancel): never given to the provider; step 8e: SKIPPED
		// with the cancel's reason, never left waiting (it kept the 5 s cycle running)
		made.get(1).setStatus(CommunicationMessageStatus.PENDING);
		Recording provider = new Recording();
		CommunicationSender sender = bed.sender(provider);
		assertEquals("1 skipped", sender.run(java.time.Instant.now().plusSeconds(20)));
		assertTrue(provider.cards.isEmpty());
		assertEquals(CommunicationMessageStatus.SKIPPED, made.get(1).getStatus());
		assertEquals("campaign cancelled by admin at 2026-11-21 10:00", made.get(1).getLastError());
		assertFalse(sender.moreToDo(), "no message can leave: no fast cycle");
		assertNull(make());
		assertEquals("COMM_CAMPAIGN_NOT_RUNNING", refused(() -> bed.campaigns().cancel(1L, "admin")).getCode());
	}

	// ─── Refusals and the same day ───────────────────────────────────

	@Test
	@DisplayName("Refused with a clear reason: a sale's fields, a template off or of another channel, the channel off, no"
			+ " name, a bad lifetime, nobody to receive it, an unknown template")
	void refusals() {
		bed.member("LYL-000001", "Sana", "20000001", true);
		bed.template("TICKET_THANKS_SMS_FR", CommunicationTypes.SMS, "Merci {firstName} ({total} TND) +{pointsEarned}"
				+ " points", true);
		LaunchForm thanks = form("Merci");
		thanks.templateCode = "TICKET_THANKS_SMS_FR";
		CommunicationException fields = refused(() -> bed.campaigns().launch(thanks, "admin"));
		assertEquals("COMM_CAMPAIGN_TEMPLATE_FIELDS", fields.getCode());
		assertEquals("{total}, {pointsEarned}", fields.getParams().get("fields"));
		assertEquals("COMM_CAMPAIGN_TEMPLATE_FIELDS", refused(() -> bed.campaigns().audience("TICKET_THANKS_SMS_FR", null))
				.getCode());
		Map<String, Object> listed = bed.campaigns().templates().stream().filter(t -> "TICKET_THANKS_SMS_FR".equals(
				t.get("code"))).findFirst().get();
		assertEquals(false, listed.get("usable"));
		assertEquals(Arrays.asList("total", "pointsEarned"), listed.get("saleFields"));

		bed.template("OFF_SMS_FR", CommunicationTypes.SMS, "Bonjour {firstName}", false);
		LaunchForm off = form("Off");
		off.templateCode = "OFF_SMS_FR";
		assertEquals("COMM_CAMPAIGN_TEMPLATE_INACTIVE", refused(() -> bed.campaigns().launch(off, "admin")).getCode());

		LaunchForm email = form("Mail");
		email.channel = "EMAIL";
		assertEquals("COMM_CAMPAIGN_TEMPLATE_CHANNEL", refused(() -> bed.campaigns().launch(email, "admin")).getCode());

		assertEquals("COMM_CAMPAIGN_NAME_REQUIRED", refused(() -> bed.campaigns().launch(form("  "), "admin")).getCode());
		LaunchForm lifetime = form("Promo");
		lifetime.lifetimeHours = 0;
		assertEquals("COMM_CAMPAIGN_LIFETIME", refused(() -> bed.campaigns().launch(lifetime, "admin")).getCode());
		LaunchForm unknown = form("Promo");
		unknown.templateCode = "NONE";
		assertEquals("COMM_TEMPLATE_NOT_FOUND", refused(() -> bed.campaigns().launch(unknown, "admin")).getCode());

		bed.smsEnabled = "false";
		assertEquals("COMM_CHANNEL_OFF", refused(() -> launch("Promo")).getCode());
		bed.smsEnabled = "true";

		bed.memberTable.get(1L).setMessageConsent(false);
		CommunicationException nobody = refused(() -> launch("Promo"));
		assertEquals("COMM_CAMPAIGN_NO_RECIPIENT", nobody.getCode());
		assertEquals(1L, nobody.getParams().get("noConsent"));
		assertTrue(bed.campaignTable.isEmpty(), "nothing launched");
	}

	@Test
	@DisplayName("The same template launched again the same day: refused 409 until confirmed; another day: no warning")
	void sameDay() {
		bed.member("LYL-000001", "Sana", "20000001", true);
		launch("Promo du matin");
		Map<String, Object> audience = bed.campaigns().audience("PROMO_SMS_FR", null);
		@SuppressWarnings("unchecked")
		Map<String, Object> earlier = (Map<String, Object>) audience.get("sameDay");
		assertEquals("Promo du matin", earlier.get("name"));
		CommunicationException again = refused(() -> launch("Promo du soir"));
		assertEquals(409, again.getStatus());
		assertEquals("COMM_CAMPAIGN_SAME_DAY", again.getCode());
		assertEquals("Promo du matin", again.getParams().get("name"));
		assertEquals("10:00", again.getParams().get("time"));
		LaunchForm confirmed = form("Promo du soir");
		confirmed.confirmSameDay = true;
		bed.campaigns().launch(confirmed, "admin");
		assertEquals(2, bed.campaignTable.size());
		bed.now = bed.now.plusDays(1);
		assertNull(bed.campaigns().audience("PROMO_SMS_FR", null).get("sameDay"));
		launch("Promo du lendemain");
	}

	// ─── The sender ──────────────────────────────────────────────────

	@Test
	@DisplayName("The automatic messages go first: a campaign's made before a thanks is sent after it")
	void automaticFirst() {
		bed.member("LYL-000001", "Sana", "20000001", true);
		bed.member("LYL-000002", "Ali", "20000002", true);
		launch("Promo");
		make();
		bed.member("LYL-000003", "Rim", "20000003", true);
		bed.automatic("LYL-000003", "20000003");
		Recording provider = new Recording();
		assertEquals("3 sent", send(provider));
		assertEquals(Arrays.asList("LYL-000003", "LYL-000001", "LYL-000002"), provider.cards);
	}

	@Test
	@DisplayName("Read again just before sending: a member made inactive, or whose phone changed, gets nothing")
	void readAgainBeforeSending() {
		LoyaltyMember sana = bed.member("LYL-000001", "Sana", "20000001", true);
		LoyaltyMember ali = bed.member("LYL-000002", "Ali", "20000002", true);
		launch("Promo");
		make();
		sana.setActive(false);
		ali.setPhone("20009999");
		Recording provider = new Recording();
		assertEquals("2 skipped", send(provider));
		List<CommunicationMessage> made = bed.messagesOf(1L);
		assertEquals("member inactive when it was to be sent", made.get(0).getLastError());
		assertEquals("address changed since the message was made", made.get(1).getLastError());
		assertTrue(provider.cards.isEmpty());
	}

	// ─── The empty-field guard ───────────────────────────────────────

	@Test
	@DisplayName("Empty-field guard, an automatic message: the company's name empty takes the store's, both empty the"
			+ " licence holder's; nothing to stand in: no message, the reason names the field")
	void guardAutomatic() {
		bed.member("LYL-000001", "Sana", "20000001", true);
		bed.template("THANKS", CommunicationTypes.SMS, "Merci {firstName} chez {companyName}", true);
		CommunicationRule rule = new CommunicationRule();
		rule.setCode("TICKET_RULE");
		rule.setEventType(CommunicationTypes.TICKET_VALIDATED);
		rule.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		rule.setChannel(CommunicationTypes.SMS);
		rule.setTemplateCode("THANKS");
		rule.setActive(true);
		bed.ruleTable.add(rule);

		CommunicationEvent store = event("{\"firstName\":\"Sana\",\"companyName\":\"\",\"storeName\":\"Magasin X\"}");
		CommunicationEvent licence = event("{\"firstName\":\"Sana\",\"companyName\":null,\"storeName\":null}");
		bed.processor().run(Instant.now().plusSeconds(20));
		assertEquals("Merci Sana chez Magasin X", messageOf(store).getBody());
		assertEquals("Merci Sana chez Licence SARL", messageOf(licence).getBody());

		bed.licenceName = null;
		CommunicationEvent hole = event("{\"firstName\":\"Sana\"}");
		bed.processor().run(Instant.now().plusSeconds(20));
		assertEquals(CommunicationEventStatus.SKIPPED, hole.getStatus());
		assertEquals("TICKET_RULE: no value for {companyName}", hole.getLastError());
		assertNull(messageOf(hole));
	}

	@Test
	@DisplayName("Empty-field guard, a campaign: a member without a value for a field used is left out (counted), the"
			+ " preview keeps the hole visible; the company's name empty takes the store's")
	void guardCampaign() {
		bed.member("LYL-000001", "Sana", "20000001", true);
		bed.member("LYL-000002", " ", "20000002", true);
		bed.companyName = null;
		Map<String, Object> audience = bed.campaigns().audience("PROMO_SMS_FR", null);
		assertEquals(1, audience.get("receive"));
		assertEquals(1, audience.get("noValue"));
		assertEquals(Collections.singletonList("firstName"), audience.get("emptyFields"));
		@SuppressWarnings("unchecked")
		Map<String, Object> preview = (Map<String, Object>) audience.get("preview");
		assertEquals("Sana, nos offres chez Magasin Centre ! Solde : 120 points.", preview.get("text"));

		bed.storeOf = (createdBy, card) -> null;
		bed.licenceName = null;
		Map<String, Object> none = bed.campaigns().audience("PROMO_SMS_FR", null);
		assertEquals(0, none.get("receive"));
		assertEquals(2, none.get("noValue"));
		assertNull(none.get("preview"));
		assertEquals("COMM_CAMPAIGN_NO_RECIPIENT", refused(() -> launch("Promo")).getCode());
		assertEquals("Bonjour {companyName}, Sana", CommunicationTemplates.preview("Bonjour {companyName}, {firstName}",
				Collections.singletonMap("firstName", "Sana")));
	}

	// ─── Helpers ─────────────────────────────────────────────────────

	private CommunicationEvent event(String payload) {
		CommunicationEvent event = new CommunicationEvent();
		event.setId((long) bed.eventTable.size() + 1);
		event.setEventType(CommunicationTypes.TICKET_VALIDATED);
		event.setSourceType(CommunicationTypes.SALES_HEADER);
		event.setDedupKey("TICKET_VALIDATED:S:" + event.getId());
		event.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		event.setRecipientRef("LYL-000001");
		event.setOccurredAt(bed.now.minusMinutes(1));
		event.setPayload(payload);
		bed.eventTable.put(event.getId(), event);
		return event;
	}

	private CommunicationMessage messageOf(CommunicationEvent event) {
		return bed.messageTable.values().stream().filter(m -> event.getId().equals(m.getEventId())).findFirst()
				.orElse(null);
	}

	/** A provider that keeps the order of the cards it was given. */
	private static final class Recording implements CommunicationProvider {
		final List<String> cards = new ArrayList<>();

		@Override
		public String getName() {
			return "test";
		}

		@Override
		public SendResult send(CommunicationMessage message) {
			cards.add(message.getRecipientRef());
			return SendResult.accepted("R-" + cards.size());
		}
	}
}
