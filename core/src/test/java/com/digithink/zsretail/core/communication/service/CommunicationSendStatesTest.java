package com.digithink.zsretail.core.communication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import com.digithink.zsretail.core.communication.CommunicationException;
import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.controller.CommunicationAPI;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider;
import com.digithink.zsretail.core.communication.provider.SmtpCommunicationProvider;
import com.digithink.zsretail.core.communication.provider.TestCommunicationProvider;
import com.digithink.zsretail.core.communication.service.CommunicationCampaigns.LaunchForm;
import com.digithink.zsretail.core.security.CurrentUserProvider;

/**
 * 3.3.0, communication step 8a: no double send (SENDING written before the provider is called; left so by a stop:
 * FAILED "interrupted" at the next start, sent again by hand only), the test-recipient mode (the provider given the test
 * address, the row keeping the member's), the Canaux tab (no password shown, the test send), the right to launch and
 * cancel a campaign, and the campaign's preview filled as its message is.
 */
class CommunicationSendStatesTest {

	private final CampaignTestBed bed = new CampaignTestBed();

	/** A provider that records the status of the message's row and the address it was given. */
	private final class Recording implements CommunicationProvider {
		final List<String> addresses = new ArrayList<>();
		final List<CommunicationMessageStatus> rowStatus = new ArrayList<>();
		boolean busy;

		@Override
		public String getName() {
			return "test";
		}

		@Override
		public SendResult send(CommunicationMessage message) {
			addresses.add(message.getAddress());
			rowStatus.add(bed.messageTable.get(message.getId()).getStatus());
			return busy ? SendResult.temporaryFailure("busy") : SendResult.accepted("R-" + addresses.size());
		}
	}

	@Test
	@DisplayName("SENDING is written before the provider is called; SENT after its answer")
	void sendingFirst() {
		bed.member("LYL-000001", "Sana", "20000001", true);
		CommunicationMessage message = bed.automatic("LYL-000001", "20000001");
		Recording provider = new Recording();
		assertEquals("1 sent", bed.sender(provider).run(Instant.now().plusSeconds(20)));
		assertEquals(Collections.singletonList(CommunicationMessageStatus.SENDING), provider.rowStatus);
		assertEquals(CommunicationMessageStatus.SENT, message.getStatus());
	}

	@Test
	@DisplayName("A message left SENDING by a stop: FAILED 'interrupted' at the start, never sent by itself, sent again"
			+ " by hand")
	void interrupted() {
		bed.member("LYL-000001", "Sana", "20000001", true);
		CommunicationMessage message = bed.automatic("LYL-000001", "20000001");
		message.setStatus(CommunicationMessageStatus.SENDING);
		Recording provider = new Recording();
		CommunicationSender sender = bed.sender(provider);
		assertNull(sender.run(Instant.now().plusSeconds(20)), "a SENDING message is not due");
		sender.interruptedAtStart();
		assertEquals(CommunicationMessageStatus.FAILED, message.getStatus());
		assertEquals(CommunicationSender.INTERRUPTED, message.getLastError());
		assertNull(sender.run(Instant.now().plusSeconds(20)), "never sent again by itself");
		assertTrue(provider.addresses.isEmpty());
	}

	@Test
	@DisplayName("Test-recipient mode: the provider is given the test address, the row keeps the member's and says where"
			+ " it went; a test line that is not an address: that kind's channels off")
	void testRecipient() {
		bed.properties.put(CommunicationSettings.TEST_PHONE_KEY, "99 000 111");
		bed.member("LYL-000001", "Sana", "20000001", true);
		CommunicationMessage message = bed.automatic("LYL-000001", "20000001");
		Recording provider = new Recording();
		bed.sender(provider).run(Instant.now().plusSeconds(20));
		assertEquals(Collections.singletonList("+21699000111"), provider.addresses);
		assertEquals("+21620000001", message.getAddress());
		assertEquals("+21699000111", message.getRedirectedTo());
		assertEquals(CommunicationMessageStatus.SENT, message.getStatus());

		bed.properties.put(CommunicationSettings.TEST_PHONE_KEY, "abc");
		CommunicationSettings settings = bed.settings();
		assertFalse(settings.isEnabled(CommunicationTypes.SMS));
		assertTrue(settings.offReason(CommunicationTypes.SMS).startsWith("communication.test-recipient.phone='abc'"));
	}

	@Test
	@DisplayName("Canaux tab: each channel, its provider's settings without the password, the test mode; a test sent now,"
			+ " kept as TEST, tried once; refused: a channel off, an address that is not one")
	void channels() {
		bed.emailEnabled = "true";
		bed.properties.put("communication.email.provider", "smtp");
		bed.properties.put(SmtpCommunicationProvider.HOST_KEY, "mail.magasin.tn");
		bed.properties.put(SmtpCommunicationProvider.FROM_ADDRESS_KEY, "fidelite@magasin.tn");
		bed.properties.put(SmtpCommunicationProvider.PASSWORD_KEY, "tres-secret");
		bed.properties.put(CommunicationSettings.TEST_EMAIL_KEY, "test@magasin.tn");
		SmtpCommunicationProvider smtp = new SmtpCommunicationProvider(env());
		bed.extraProviders.add(smtp);
		Recording sms = new Recording();
		CommunicationSender sender = bed.sender(sms, smtp);
		CommunicationChannels channels = new CommunicationChannels(bed.settings(),
				java.util.Arrays.asList(new TestCommunicationProvider(), smtp), bed.messages(), sender, () -> null,
				() -> "Licence SARL", Optional.of(() -> (createdBy, card) -> "Magasin Centre"), () -> bed.now);
		Map<String, Object> view = channels.view();
		assertFalse(view.toString().contains("tres-secret"), "never the password");
		assertEquals(true, view.get("testMode"));
		assertEquals(true, view.get("companyNameEmpty"));
		@SuppressWarnings("unchecked")
		Map<String, Object> email = ((List<Map<String, Object>>) view.get("channels")).get(2);
		assertEquals("smtp", email.get("provider"));
		assertEquals("test@magasin.tn", email.get("testRecipient"));
		assertEquals("fidelite@magasin.tn", email.get("sender"));

		Map<String, Object> sent = channels.testSend("sms", "20000009", "admin");
		assertEquals(CommunicationMessageStatus.SENT, sent.get("status"));
		CommunicationMessage test = bed.messageTable.get((Long) sent.get("id"));
		assertEquals(CommunicationChannels.TEST, test.getRecipientType());
		assertEquals("+21620000009", test.getAddress());
		assertTrue(test.getBody().startsWith("ZS Retail : message de test du canal SMS"));
		sms.busy = true;
		Map<String, Object> failed = channels.testSend("SMS", "20000009", "admin");
		assertEquals(CommunicationMessageStatus.FAILED, failed.get("status"), "a test is tried once");
		assertNull(bed.messageTable.get((Long) failed.get("id")).getNextAttemptAt());
		assertEquals("attempt 1 failed: busy", failed.get("error"));

		assertEquals("COMM_TEST_ADDRESS", assertThrows(CommunicationException.class,
				() -> channels.testSend("EMAIL", "pas-un-email", "admin")).getCode());
		assertEquals("COMM_CHANNEL_OFF", assertThrows(CommunicationException.class,
				() -> channels.testSend("WHATSAPP", "20000009", "admin")).getCode());
	}

	private org.springframework.mock.env.MockEnvironment env() {
		org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
		bed.properties.forEach(env::setProperty);
		return env;
	}

	@Test
	@DisplayName("A member's email typed but not one: no message, the reason names it; none typed: 'no email address'")
	void emailNotValid() {
		bed.emailEnabled = "true";
		bed.member("LYL-000001", "Sana", "20000001", true).setEmail("sana@");
		bed.template("THANKS_EMAIL", CommunicationTypes.EMAIL, "Merci {firstName}", true).setSubject("Merci");
		com.digithink.zsretail.core.communication.model.CommunicationRule rule =
				new com.digithink.zsretail.core.communication.model.CommunicationRule();
		rule.setCode("TICKET_THANKS_EMAIL");
		rule.setEventType(CommunicationTypes.TICKET_VALIDATED);
		rule.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		rule.setChannel(CommunicationTypes.EMAIL);
		rule.setTemplateCode("THANKS_EMAIL");
		rule.setActive(true);
		bed.ruleTable.add(rule);
		com.digithink.zsretail.core.communication.model.CommunicationEvent typed = ticketEvent();
		bed.processor().run(Instant.now().plusSeconds(20));
		assertEquals("TICKET_THANKS_EMAIL: email 'sana@' is not valid", typed.getLastError());
		bed.memberTable.get(1L).setEmail(null);
		com.digithink.zsretail.core.communication.model.CommunicationEvent none = ticketEvent();
		bed.processor().run(Instant.now().plusSeconds(20));
		assertEquals("TICKET_THANKS_EMAIL: no email address", none.getLastError());
		assertTrue(bed.messageTable.isEmpty(), "no message, nothing given to a provider");
	}

	private com.digithink.zsretail.core.communication.model.CommunicationEvent ticketEvent() {
		com.digithink.zsretail.core.communication.model.CommunicationEvent event =
				new com.digithink.zsretail.core.communication.model.CommunicationEvent();
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

	@Test
	@DisplayName("Launch and cancel need write:admin-communication-campaigns (403 without it, even with the write right)")
	void launchRight() {
		bed.member("LYL-000001", "Sana", "20000001", true);
		bed.template("PROMO_SMS_FR", CommunicationTypes.SMS, "{firstName}, nos offres !", true);
		CommunicationAPI api = new CommunicationAPI();
		ReflectionTestUtils.setField(api, "campaigns", bed.campaigns());
		List<String> rights = new ArrayList<>(java.util.Arrays.asList(CommunicationAPI.READ, CommunicationAPI.WRITE));
		ReflectionTestUtils.setField(api, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "caissier";
			}

			@Override
			public boolean currentUserHasPermission(String permissionKey) {
				return rights.contains(permissionKey);
			}
		});
		LaunchForm form = new LaunchForm();
		form.name = "Promo";
		form.templateCode = "PROMO_SMS_FR";
		ResponseEntity<?> refused = api.launch(form);
		assertEquals(403, refused.getStatusCodeValue());
		assertEquals(403, api.cancel(1L).getStatusCodeValue());
		assertTrue(bed.campaignTable.isEmpty());
		rights.add(CommunicationAPI.LAUNCH);
		assertEquals(200, api.launch(form).getStatusCodeValue());
		assertEquals(200, api.cancel(1L).getStatusCodeValue());
	}

	@Test
	@DisplayName("The campaign's preview and its message are filled by the same values (the names standing in): the"
			+ " preview's text is the message's body")
	void previewIsTheMessage() {
		bed.member("LYL-000001", "Sana", "20000001", true);
		bed.companyName = null;
		bed.storeOf = (createdBy, card) -> null; // a head office's own member: the licence holder's name
		bed.template("PROMO_SMS_FR", CommunicationTypes.SMS, "{firstName}, nos offres chez {companyName} ({storeName}) !"
				+ " Solde {pointsBalance}.", true);
		@SuppressWarnings("unchecked")
		Map<String, Object> preview = (Map<String, Object>) bed.campaigns().audience("PROMO_SMS_FR", null).get("preview");
		LaunchForm form = new LaunchForm();
		form.name = "Promo";
		form.templateCode = "PROMO_SMS_FR";
		bed.campaigns().launch(form, "admin");
		bed.maker().run(Instant.now().plusSeconds(20));
		String body = bed.messagesOf(1L).get(0).getBody();
		assertEquals("Sana, nos offres chez Licence SARL (Licence SARL) ! Solde 120.", body);
		assertEquals(body, preview.get("text"));
	}
}
