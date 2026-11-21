package com.digithink.zsretail.core.communication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.communication.CommunicationException;
import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.model.CommunicationRule;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.repository.CommunicationEventRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationRuleRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository;
import com.digithink.zsretail.core.communication.service.CommunicationAdmin.TemplateForm;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;

/**
 * 3.3.0, communication step 4: the checks of the Communication page (CommunicationAdmin) on in-memory tables: a message
 * sent again, the rules' and templates' rules, the preview and its SMS count. The lists (JPA criteria) are proven by
 * the run on the PC (docs/modules/communication.md).
 */
class CommunicationAdminTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 10, 0);

	private final Map<Long, CommunicationMessage> messageTable = new LinkedHashMap<>();
	private final List<CommunicationRule> ruleTable = new ArrayList<>();
	private final List<CommunicationTemplate> templateTable = new ArrayList<>();
	private final Map<String, LoyaltyMember> memberTable = new LinkedHashMap<>();
	private String smsEnabled = "true";

	// --- Data ---

	private CommunicationTemplate template(String code, String channel, boolean active) {
		CommunicationTemplate template = new CommunicationTemplate();
		template.setId((long) templateTable.size() + 1);
		template.setCode(code);
		template.setName(code);
		template.setChannel(channel);
		template.setLanguageCode("fr");
		template.setBody("Merci {firstName}");
		template.setActive(active);
		templateTable.add(template);
		return template;
	}

	private CommunicationRule rule(String code, String templateCode, boolean active) {
		CommunicationRule rule = new CommunicationRule();
		rule.setId((long) ruleTable.size() + 1);
		rule.setCode(code);
		rule.setEventType(CommunicationTypes.TICKET_VALIDATED);
		rule.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		rule.setChannel(CommunicationTypes.SMS);
		rule.setTemplateCode(templateCode);
		rule.setLifetimeHours(48);
		rule.setActive(active);
		ruleTable.add(rule);
		return rule;
	}

	private CommunicationMessage failed(String card, String address) {
		CommunicationMessage message = new CommunicationMessage();
		message.setId((long) messageTable.size() + 1);
		message.setEventId(7L);
		message.setChannel(CommunicationTypes.SMS);
		message.setProvider("test");
		message.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		message.setRecipientRef(card);
		message.setAddress(address);
		message.setBody("Merci Sana");
		message.setTemplateCode("TICKET_THANKS_SMS_FR");
		message.setStatus(CommunicationMessageStatus.FAILED);
		message.setAttempts(5);
		message.setNextAttemptAt(NOW.minusHours(1));
		message.setExpiresAt(NOW.minusHours(2));
		message.setLastError("attempt 5 of 5 failed: timeout");
		message.setDedupKey("7:TICKET_THANKS_SMS");
		messageTable.put(message.getId(), message);
		return message;
	}

	private LoyaltyMember member(String card, String phone) {
		LoyaltyMember member = new LoyaltyMember();
		member.setCardNumber(card);
		member.setFirstName("Sana");
		member.setLastName("Ben Ali");
		member.setPhone(phone);
		member.setActive(true);
		member.setMessageConsent(true); // step 5
		memberTable.put(card, member);
		return member;
	}

	private static TemplateForm form(String code, String channel, String body) {
		TemplateForm form = new TemplateForm();
		form.code = code;
		form.name = "Merci (arabe)";
		form.channel = channel;
		form.languageCode = "ar";
		form.body = body;
		return form;
	}

	private static String refusal(Runnable call) {
		return assertThrows(CommunicationException.class, call::run).getCode();
	}

	// --- Messages sent again ---

	@Test
	@DisplayName("Send again: a FAILED message is PENDING, attempts 0, its rule's lifetime from now, the history kept")
	void resendFailed() {
		rule("TICKET_THANKS_SMS", "TICKET_THANKS_SMS_FR", true);
		member("LYL-1", "99000002");
		CommunicationMessage message = failed("LYL-1", "+21699000002");
		admin().resend(message.getId(), "admin");
		assertEquals(CommunicationMessageStatus.PENDING, message.getStatus());
		assertEquals(0, message.getAttempts());
		assertNull(message.getNextAttemptAt());
		assertEquals(NOW.plusHours(48), message.getExpiresAt());
		assertEquals("+21699000002", message.getAddress());
		assertEquals("resent 2026-11-21 10:00 by admin (FAILED after 5 attempt(s)); before: attempt 5 of 5 failed: timeout",
				message.getLastError());
		assertEquals("admin", message.getUpdatedBy());
	}

	@Test
	@DisplayName("Send again: the address read again from the member (a corrected phone); the rule gone: 24 h")
	void resendReadsTheMemberAgain() {
		member("LYL-1", "29954290");
		CommunicationMessage message = failed("LYL-1", "+21699000002");
		message.setStatus(CommunicationMessageStatus.EXPIRED);
		admin().resend(message.getId(), "admin");
		assertEquals("+21629954290", message.getAddress());
		assertEquals(NOW.plusHours(CommunicationRule.DEFAULT_LIFETIME_HOURS), message.getExpiresAt());
		assertTrue(message.getLastError().startsWith("resent 2026-11-21 10:00 by admin (EXPIRED after 5 attempt(s),"
				+ " address read again from the member); before: "), message.getLastError());
	}

	@Test
	@DisplayName("Send again refused: a SENT or PENDING message, the channel off, a member without a valid phone now")
	void resendRefused() {
		member("LYL-1", "99000002");
		CommunicationMessage sent = failed("LYL-1", "+21699000002");
		sent.setStatus(CommunicationMessageStatus.SENT);
		assertEquals("COMM_RESEND_STATUS", refusal(() -> admin().resend(sent.getId(), "admin")));
		CommunicationMessage failed = failed("LYL-1", "+21699000002");
		smsEnabled = "false";
		assertEquals("COMM_CHANNEL_OFF", refusal(() -> admin().resend(failed.getId(), "admin")));
		smsEnabled = "true";
		member("LYL-1", "123");
		assertEquals("COMM_RESEND_NO_ADDRESS", refusal(() -> admin().resend(failed.getId(), "admin")));
		assertEquals(CommunicationMessageStatus.FAILED, failed.getStatus());
		assertEquals("COMM_MESSAGE_NOT_FOUND", refusal(() -> admin().resend(99, "admin")));
	}

	@Test
	@DisplayName("Step 5: send again refused when the member withdrew the consent; the message stays FAILED")
	void resendRefusedWithoutConsent() {
		member("LYL-1", "99000002").setMessageConsent(false);
		CommunicationMessage message = failed("LYL-1", "+21699000002");
		CommunicationException refused = assertThrows(CommunicationException.class,
				() -> admin().resend(message.getId(), "admin"));
		assertEquals("COMM_RESEND_NO_CONSENT", refused.getCode());
		assertEquals("LYL-1", refused.getParams().get("card"));
		assertEquals(CommunicationMessageStatus.FAILED, message.getStatus());
	}

	// --- Step 5: rules created ---

	private static CommunicationAdmin.NewRuleForm newRule(String code, String event, String channel, String template) {
		CommunicationAdmin.NewRuleForm form = new CommunicationAdmin.NewRuleForm();
		form.code = code;
		form.eventType = event;
		form.channel = channel;
		form.templateCode = template;
		form.lifetimeHours = 12;
		return form;
	}

	@Test
	@DisplayName("Step 5: a rule created: known event, channel on, active template of the channel, code unique; one rule"
			+ " per event + channel + template")
	void ruleCreated() {
		template("MEMBER_CARD_SMS_FR", CommunicationTypes.SMS, true);
		template("MEMBER_CARD_SMS_AR", CommunicationTypes.SMS, true);
		template("OFF_SMS", CommunicationTypes.SMS, false);
		template("CARD_EMAIL", CommunicationTypes.EMAIL, true);
		CommunicationRule seeded = rule("MEMBER_CARD_SMS", "MEMBER_CARD_SMS_FR", true);
		seeded.setEventType(CommunicationTypes.MEMBER_CREATED);
		CommunicationAdmin admin = admin();
		assertEquals("COMM_RULE_CODE_INVALID",
				refusal(() -> admin.createRule(newRule("x", "MEMBER_CREATED", "SMS", "MEMBER_CARD_SMS_AR"), "a")));
		assertEquals("COMM_RULE_CODE_TAKEN",
				refusal(() -> admin.createRule(newRule("MEMBER_CARD_SMS", "MEMBER_CREATED", "SMS", "MEMBER_CARD_SMS_AR"), "a")));
		assertEquals("COMM_RULE_EVENT_UNKNOWN",
				refusal(() -> admin.createRule(newRule("BIRTHDAY_SMS", "BIRTHDAY", "SMS", "MEMBER_CARD_SMS_AR"), "a")));
		assertEquals("COMM_CHANNEL_OFF",
				refusal(() -> admin.createRule(newRule("CARD_EMAIL", "MEMBER_CREATED", "EMAIL", "CARD_EMAIL"), "a")));
		assertEquals("COMM_RULE_TEMPLATE_INACTIVE",
				refusal(() -> admin.createRule(newRule("OFF_RULE", "MEMBER_CREATED", "SMS", "OFF_SMS"), "a")));
		CommunicationException duplicate = assertThrows(CommunicationException.class,
				() -> admin.createRule(newRule("MEMBER_CARD_SMS_2", "MEMBER_CREATED", "SMS", "MEMBER_CARD_SMS_FR"), "a"));
		assertEquals("COMM_RULE_DUPLICATE", duplicate.getCode());
		assertEquals("MEMBER_CARD_SMS", duplicate.getParams().get("rule"));

		CommunicationRule made = admin.createRule(newRule(" member_card_sms_ar ", "member_created", "sms",
				"MEMBER_CARD_SMS_AR"), "zein");
		assertEquals("MEMBER_CARD_SMS_AR", made.getCode());
		assertEquals(CommunicationTypes.MEMBER_CREATED, made.getEventType());
		assertEquals(CommunicationTypes.LOYALTY_MEMBER, made.getRecipientType());
		assertEquals(12, made.getLifetimeHours());
		assertTrue(made.getActive(), "absent: on");
		assertEquals("zein", made.getCreatedBy());
		// The duplicate rule also holds when a rule is changed onto another's template
		assertEquals("COMM_RULE_DUPLICATE",
				refusal(() -> admin.updateRule(made.getId(), "SMS", "MEMBER_CARD_SMS_FR", 24, "a")));
	}

	// --- Rules ---

	@Test
	@DisplayName("A rule changed: its template must exist and be on its channel; lifetime 1 to 720 h; active: template on")
	void ruleChecks() {
		template("TICKET_THANKS_SMS_FR", CommunicationTypes.SMS, true);
		template("THANKS_EMAIL", CommunicationTypes.EMAIL, true);
		template("OFF_SMS", CommunicationTypes.SMS, false);
		CommunicationRule rule = rule("TICKET_THANKS_SMS", "TICKET_THANKS_SMS_FR", true);
		CommunicationAdmin admin = admin();
		assertEquals("COMM_TEMPLATE_NOT_FOUND", refusal(() -> admin.updateRule(rule.getId(), "SMS", "NONE", 24, "a")));
		assertEquals("COMM_RULE_TEMPLATE_CHANNEL",
				refusal(() -> admin.updateRule(rule.getId(), "SMS", "THANKS_EMAIL", 24, "a")));
		assertEquals("COMM_RULE_TEMPLATE_INACTIVE", refusal(() -> admin.updateRule(rule.getId(), "SMS", "OFF_SMS", 24, "a")));
		assertEquals("COMM_RULE_LIFETIME",
				refusal(() -> admin.updateRule(rule.getId(), "SMS", "TICKET_THANKS_SMS_FR", 0, "a")));
		assertEquals("COMM_RULE_LIFETIME",
				refusal(() -> admin.updateRule(rule.getId(), "SMS", "TICKET_THANKS_SMS_FR", 721, "a")));
		assertEquals("COMM_CHANNEL_OFF", refusal(() -> admin.updateRule(rule.getId(), "EMAIL", "THANKS_EMAIL", 24, "a")));
		assertEquals("COMM_CHANNEL_UNKNOWN", refusal(() -> admin.updateRule(rule.getId(), "FAX", "THANKS_EMAIL", 24, "a")));
		admin.updateRule(rule.getId(), "sms", "TICKET_THANKS_SMS_FR", 6, "zein");
		assertEquals(6, rule.getLifetimeHours());
		assertEquals("zein", rule.getUpdatedBy());
		// An inactive rule may point to a template still off (it is switched on before the rule)
		rule.setActive(false);
		admin.updateRule(rule.getId(), "SMS", "OFF_SMS", 24, "a");
		assertEquals("OFF_SMS", rule.getTemplateCode());
	}

	@Test
	@DisplayName("A rule on needs its channel on and its template active; off always")
	void ruleOnOff() {
		CommunicationTemplate template = template("TICKET_THANKS_SMS_FR", CommunicationTypes.SMS, false);
		CommunicationRule rule = rule("TICKET_THANKS_SMS", "TICKET_THANKS_SMS_FR", false);
		assertEquals("COMM_RULE_TEMPLATE_INACTIVE", refusal(() -> admin().ruleActive(rule.getId(), true, "a")));
		template.setActive(true);
		smsEnabled = "false";
		assertEquals("COMM_CHANNEL_OFF", refusal(() -> admin().ruleActive(rule.getId(), true, "a")));
		smsEnabled = "true";
		admin().ruleActive(rule.getId(), true, "a");
		assertTrue(rule.getActive());
		smsEnabled = "false";
		admin().ruleActive(rule.getId(), false, "a"); // off: also with the channel off
		assertFalse(rule.getActive());
	}

	// --- Templates ---

	@Test
	@DisplayName("A template created: code A-Z 0-9 _ and unique, a channel on, fr / ar / en, an unknown field named")
	void templateCreated() {
		template("TICKET_THANKS_SMS_FR", CommunicationTypes.SMS, true);
		CommunicationAdmin admin = admin();
		assertEquals("COMM_TEMPLATE_CODE_INVALID", refusal(() -> admin.createTemplate(form("merci ar", "SMS", "x"), "a")));
		assertEquals("COMM_TEMPLATE_CODE_TAKEN",
				refusal(() -> admin.createTemplate(form("ticket_thanks_sms_fr", "SMS", "x"), "a")));
		assertEquals("COMM_CHANNEL_OFF", refusal(() -> admin.createTemplate(form("THANKS_WA", "WHATSAPP", "x"), "a")));
		assertEquals("COMM_TEMPLATE_BODY_REQUIRED", refusal(() -> admin.createTemplate(form("THANKS_AR", "SMS", " "), "a")));
		TemplateForm badLanguage = form("THANKS_AR", "SMS", "x");
		badLanguage.languageCode = "de";
		assertEquals("COMM_TEMPLATE_LANGUAGE", refusal(() -> admin.createTemplate(badLanguage, "a")));
		CommunicationException unknown = assertThrows(CommunicationException.class,
				() -> admin.createTemplate(form("THANKS_AR", "SMS", "شكرا {firstName} {prenom} {prenom}"), "a"));
		assertEquals("COMM_TEMPLATE_UNKNOWN_FIELD", unknown.getCode());
		assertEquals("{prenom}", unknown.getParams().get("field"));
		CommunicationTemplate made = admin.createTemplate(form(" thanks_ar ", "sms", "شكرا {firstName}"), "zein");
		assertEquals("THANKS_AR", made.getCode());
		assertEquals("SMS", made.getChannel());
		assertEquals("ar", made.getLanguageCode());
		assertTrue(made.getActive());
		assertNull(made.getSubject()); // a subject only for an email
		assertEquals("zein", made.getCreatedBy());
	}

	@Test
	@DisplayName("A template's channel stays while a rule uses it; off refused while an active rule uses it; on: channel on")
	void templateUsed() {
		CommunicationTemplate template = template("TICKET_THANKS_SMS_FR", CommunicationTypes.SMS, true);
		CommunicationRule rule = rule("TICKET_THANKS_SMS", "TICKET_THANKS_SMS_FR", true);
		CommunicationAdmin admin = admin();
		assertEquals("COMM_TEMPLATE_USED", refusal(() -> admin.templateActive(template.getId(), false, "a")));
		rule.setActive(false);
		admin.templateActive(template.getId(), false, "a");
		assertFalse(template.getActive());
		TemplateForm edit = form("IGNORED", "EMAIL", "Merci {firstName} !");
		edit.subject = "Merci";
		assertEquals("COMM_CHANNEL_OFF", refusal(() -> admin.updateTemplate(template.getId(), edit, "a")));
		TemplateForm text = form("IGNORED", "SMS", "Merci {firstName}, à bientôt");
		admin.updateTemplate(template.getId(), text, "a");
		assertEquals("TICKET_THANKS_SMS_FR", template.getCode()); // the code stays
		assertEquals("Merci {firstName}, à bientôt", template.getBody());
		smsEnabled = "false";
		assertEquals("COMM_CHANNEL_OFF", refusal(() -> admin().templateActive(template.getId(), true, "a")));
	}

	// --- Preview and SMS parts ---

	@Test
	@DisplayName("Preview: the sample values, the company's name, an unknown field kept; GSM 7-bit or Unicode parts")
	void preview() {
		Map<String, Object> preview = admin().preview("SMS", "Merci {firstName} ({total} TND) chez {companyName} {xyz}");
		assertEquals("Merci Amira (48.900 TND) chez Happyness {xyz}", preview.get("text"));
		assertEquals(Collections.singletonList("xyz"), preview.get("unknownFields"));
		assertEquals("GSM7", preview.get("encoding"));
		assertEquals(1, preview.get("parts"));
		assertEquals("UNICODE", admin().preview("SMS", "Merci, à bientôt ça va").get("encoding"));
		Map<String, Object> email = admin().preview("EMAIL", "Bonjour {firstName}");
		assertNull(email.get("parts"));
		assertEquals(11, admin().fields().size()); // step 7: pointsBalance
		assertEquals("48.900", admin().fields().get(4).get("sample"));
	}

	@Test
	@DisplayName("SMS parts: 160 / 153 in GSM 7-bit (an extension character counts two), 70 / 67 in Unicode")
	void smsParts() {
		assertEquals(0, SmsParts.of("").getParts());
		assertEquals(1, SmsParts.of(repeat('a', 160)).getParts());
		assertEquals(2, SmsParts.of(repeat('a', 161)).getParts());
		assertEquals(2, SmsParts.of(repeat('a', 306)).getParts());
		assertEquals(3, SmsParts.of(repeat('a', 307)).getParts());
		assertEquals(160, SmsParts.of(repeat('a', 158) + "€").getLength());
		assertEquals(2, SmsParts.of(repeat('a', 159) + "€").getParts());
		assertEquals("UNICODE", SmsParts.of(repeat('a', 69) + "ç").getEncoding());
		assertEquals(1, SmsParts.of(repeat('a', 69) + "ç").getParts());
		assertEquals(2, SmsParts.of(repeat('a', 70) + "ç").getParts());
		assertEquals(3, SmsParts.of(repeat('ب', 135)).getParts());
		// Step 8b: Arabic is Unicode: 70 in one SMS, 67 a part (the provider http's {unicode} follows the same rule)
		assertEquals("UNICODE", SmsParts.of(repeat('ب', 70)).getEncoding());
		assertEquals(1, SmsParts.of(repeat('ب', 70)).getParts());
		assertEquals(2, SmsParts.of(repeat('ب', 71)).getParts());
		assertEquals(2, SmsParts.of(repeat('ب', 134)).getParts());
		assertEquals("UNICODE", SmsParts.of("Bienvenue chez Happyness مرحبا").getEncoding());
	}

	private static String repeat(char c, int n) {
		char[] chars = new char[n];
		Arrays.fill(chars, c);
		return new String(chars);
	}

	// --- The service on in-memory tables ---

	private CommunicationAdmin admin() {
		CommunicationSettings settings = new CommunicationSettings(new MockEnvironment()
				.withProperty("communication.sms.enabled", smsEnabled).withProperty("communication.sms.provider", "test"),
				Collections.singletonList("test"));
		return new CommunicationAdmin(stub(CommunicationEventRepository.class,
				(m, a) -> "findById".equals(m) ? Optional.empty() : UNHANDLED), messages, rules,
				templates, members, settings, Optional.empty(), () -> "Happyness", () -> NOW);
	}

	private final CommunicationMessageRepository messages = stub(CommunicationMessageRepository.class, (method, args) -> {
		switch (method) {
			case "findById":
				return Optional.ofNullable(messageTable.get(args[0]));
			case "save":
				return args[0];
			default:
				return UNHANDLED;
		}
	});

	private final CommunicationRuleRepository rules = stub(CommunicationRuleRepository.class, (method, args) -> {
		switch (method) {
			case "findById":
				return ruleTable.stream().filter(r -> r.getId().equals(args[0])).findFirst();
			case "findByCode":
				return ruleTable.stream().filter(r -> r.getCode().equals(args[0])).findFirst();
			case "findAll":
				return new ArrayList<>(ruleTable);
			case "save":
				CommunicationRule saved = (CommunicationRule) args[0];
				if (saved.getId() == null) {
					saved.setId((long) ruleTable.size() + 1);
					ruleTable.add(saved);
				}
				return saved;
			default:
				return UNHANDLED;
		}
	});

	private final CommunicationTemplateRepository templates = stub(CommunicationTemplateRepository.class,
			(method, args) -> {
				switch (method) {
					case "findById":
						return templateTable.stream().filter(t -> t.getId().equals(args[0])).findFirst();
					case "findByCode": // the database's collation: any case
						return templateTable.stream().filter(t -> t.getCode().equalsIgnoreCase((String) args[0])).findFirst();
					case "findAll":
						return new ArrayList<>(templateTable);
					case "save":
						CommunicationTemplate template = (CommunicationTemplate) args[0];
						if (template.getId() == null) {
							template.setId((long) templateTable.size() + 1);
							templateTable.add(template);
						}
						return template;
					default:
						return UNHANDLED;
				}
			});

	@SuppressWarnings("unchecked")
	private final LoyaltyMemberRepository members = stub(LoyaltyMemberRepository.class, (method, args) -> {
		switch (method) {
			case "findByCardNumber":
				return Optional.ofNullable(memberTable.get(args[0]));
			case "findByCardNumberIn":
				return ((Collection<String>) args[0]).stream().map(memberTable::get).filter(m -> m != null)
						.collect(Collectors.toList());
			default:
				return UNHANDLED;
		}
	});

	private interface Handler {
		Object handle(String method, Object[] args);
	}

	private static final Object UNHANDLED = new Object();

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				case "toString": return type.getSimpleName() + "Stub";
				default: break;
			}
			Object result = handler.handle(method.getName(), args == null ? new Object[0] : args);
			if (result == UNHANDLED) {
				throw new UnsupportedOperationException("Unexpected call: " + type.getSimpleName() + "." + method.getName());
			}
			return result;
		});
	}
}
