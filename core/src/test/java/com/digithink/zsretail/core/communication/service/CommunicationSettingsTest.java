package com.digithink.zsretail.core.communication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.config.NodeOwnership;

/**
 * 3.3.0, communication module, step 3: the channel lines, the provider names, the country code, where messages are made
 * and sent, and the two seeded SMS texts (one SMS part, the basic SMS alphabet).
 */
class CommunicationSettingsTest {

	private static CommunicationSettings settings(MockEnvironment env) {
		return new CommunicationSettings(env, Collections.singletonList("test"));
	}

	@Test
	@DisplayName("Channels: absent, empty or false off; true on (any case); another value off, never a refused start")
	void channels() {
		CommunicationSettings none = settings(new MockEnvironment());
		for (String channel : CommunicationTypes.CHANNELS) {
			assertFalse(none.isEnabled(channel));
			assertEquals("test", none.provider(channel));
		}
		CommunicationSettings some = settings(new MockEnvironment().withProperty("communication.sms.enabled", " TRUE ")
				.withProperty("communication.whatsapp.enabled", "").withProperty("communication.email.enabled", "yes"));
		assertTrue(some.isEnabled(CommunicationTypes.SMS));
		assertFalse(some.isEnabled(CommunicationTypes.WHATSAPP));
		assertFalse(some.isEnabled(CommunicationTypes.EMAIL));
		assertEquals("216", some.getCountryCode());
	}

	@Test
	@DisplayName("Provider: absent or empty test; an unknown name stops the start naming the key")
	void providers() {
		assertEquals("test", settings(new MockEnvironment().withProperty("communication.sms.provider", " Test "))
				.provider(CommunicationTypes.SMS));
		String message = assertThrows(IllegalStateException.class, () -> settings(new MockEnvironment()
				.withProperty("communication.whatsapp.provider", "meta"))).getMessage();
		assertEquals("Invalid value 'meta' for property communication.whatsapp.provider: the providers are test (absent"
				+ " or empty: test)", message);
	}

	@Test
	@DisplayName("Country code: absent or empty 216; 1 to 3 digits, a + accepted; anything else stops the start")
	void countryCode() {
		assertEquals("216", CommunicationSettings.countryCode(null));
		assertEquals("216", CommunicationSettings.countryCode(" "));
		assertEquals("33", CommunicationSettings.countryCode(" +33 "));
		assertEquals("Invalid value '21x' for property communication.phone-country-code: 1 to 3 digits, e.g. 216 (absent or"
				+ " empty: 216)", assertThrows(IllegalStateException.class,
						() -> CommunicationSettings.countryCode("21x")).getMessage());
	}

	@Test
	@DisplayName("Messages made and sent here: the module on where the loyalty is owned (a store with loyalty LOCAL, a head"
			+ " office, step 6); not on a store whose loyalty is the head office's, which pushes its events up")
	void sendsHere() {
		MockEnvironment storeOff = new MockEnvironment();
		MockEnvironment store = new MockEnvironment().withProperty("communication.enabled", "true");
		MockEnvironment loyaltyFromHeadOffice = new MockEnvironment().withProperty("communication.enabled", "true")
				.withProperty("ownership.loyalty", "HEAD_OFFICE").withProperty("headoffice.url", "http://ho:888/zsretail/api")
				.withProperty("headoffice.api-key", "k");
		MockEnvironment headOffice = new MockEnvironment().withProperty("communication.enabled", "true")
				.withProperty("node.type", "HEAD_OFFICE");
		assertFalse(NodeOwnership.isCommunicationSentHere(storeOff));
		assertTrue(NodeOwnership.isCommunicationSentHere(store));
		assertTrue(Installation.COMMUNICATION_SENDS_HERE.isTrue(store));
		assertFalse(NodeOwnership.isCommunicationSentHere(loyaltyFromHeadOffice)); // its events go up (step 6)
		assertTrue(Installation.COMMUNICATION_PUSHED_UP.isTrue(loyaltyFromHeadOffice));
		// Step 6: a head office owns the loyalty (its register): with the module on it sends, and pushes nothing
		assertTrue(NodeOwnership.isCommunicationSentHere(headOffice));
		assertTrue(Installation.COMMUNICATION_SENDS_HERE.isTrue(headOffice));
		assertFalse(Installation.COMMUNICATION_PUSHED_UP.isTrue(headOffice));
		assertFalse(NodeOwnership.isCommunicationSentHere(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")));
		assertFalse(Installation.COMMUNICATION_PUSHED_UP.isTrue(store));
		// A head office that does not own the loyalty cannot exist: the start refuses its owner HEAD_OFFICE (and loyalty
		// has no ERP owner)
		assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(new MockEnvironment()
				.withProperty("node.type", "HEAD_OFFICE").withProperty("ownership.loyalty", "HEAD_OFFICE")));
		assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(new MockEnvironment()
				.withProperty("node.type", "HEAD_OFFICE").withProperty("ownership.loyalty", "ERP")));
	}

	/** GSM 03.38 basic alphabet: one SMS part holds 160 of these characters. */
	private static final String GSM7 = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRS"
			+ "TUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

	@Test
	@DisplayName("The two seeded SMS texts: the basic SMS alphabet only, one SMS part (160) with long usual values")
	void seededTexts() {
		Map<String, Object> values = new HashMap<>();
		values.put("firstName", "Mohamed-Amine");
		values.put("companyName", "Parapharmacie Fendri");
		values.put("cardNumber", "LYL-FKAIROUAN-000123");
		values.put("total", 1234.5);
		values.put("pointsEarned", 1234);
		values.put("balanceAfter", 123456);
		for (String text : new String[] { CommunicationSeed.MEMBER_TEXT, CommunicationSeed.TICKET_TEXT }) {
			String sms = CommunicationTemplates.render("SEED", text, values);
			assertTrue(sms.length() <= 160, sms.length() + " characters: " + sms);
			for (char c : sms.toCharArray()) {
				assertTrue(GSM7.indexOf(c) >= 0, "not in the basic SMS alphabet: '" + c + "' in " + sms);
			}
		}
		assertEquals("Merci Mohamed-Amine pour votre achat chez Parapharmacie Fendri (1234.500 TND) : +1234 points, solde"
				+ " 123456 points (carte LYL-FKAIROUAN-000123).", CommunicationTemplates.render("SEED",
						CommunicationSeed.TICKET_TEXT, values));
	}

	@Test
	@DisplayName("A field without a value gives an empty text, never null; an unknown field is left out")
	void fields() {
		Map<String, Object> values = new HashMap<>();
		values.put("firstName", "Sana");
		values.put("lastName", null);
		assertEquals("Sana , [], 0", CommunicationTemplates.render("T", "{firstName} {lastName}, [{ticketNumber}{secret}], 0",
				values));
	}
}
