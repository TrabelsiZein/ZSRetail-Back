package com.digithink.zsretail.core.communication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationEventStatus;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.model.CommunicationRule;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider;
import com.digithink.zsretail.core.communication.provider.TestCommunicationProvider;
import com.digithink.zsretail.core.communication.repository.CommunicationEventRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationRuleRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;

/**
 * 3.3.0, communication module, step 3: the processor (event to messages) and the sender (message to provider). Plain
 * JUnit: comm_event, comm_rule, comm_template, comm_message and the members are in-memory maps read with the rules of
 * the queries, a fake provider stands for a failing one, no Spring context. That the module off (or a store whose
 * loyalty is the head office's) has no processor and no sender: InstallationBeansProofTest and CommunicationSettingsTest.
 */
class CommunicationMessagesTest {

	private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 20, 17, 0);

	private LocalDateTime now = T0;
	private final Map<Long, CommunicationEvent> eventTable = new LinkedHashMap<>();
	private final List<CommunicationRule> ruleTable = new ArrayList<>();
	private final Map<String, CommunicationTemplate> templateTable = new LinkedHashMap<>();
	private final Map<Long, CommunicationMessage> messageTable = new LinkedHashMap<>();
	private final Map<String, LoyaltyMember> memberTable = new LinkedHashMap<>();
	private boolean membersFail;
	private String smsEnabled = "true";

	// --- Data ---

	private CommunicationEvent event(String type, String card, LocalDateTime occurredAt, String payload) {
		CommunicationEvent event = new CommunicationEvent();
		event.setId((long) eventTable.size() + 1);
		event.setEventType(type);
		event.setSourceType(CommunicationTypes.SALES_HEADER);
		event.setDedupKey(type + ":S:" + event.getId());
		event.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		event.setRecipientRef(card);
		event.setOccurredAt(occurredAt);
		event.setPayload(payload);
		eventTable.put(event.getId(), event);
		return event;
	}

	private CommunicationEvent ticket(String card) {
		return event(CommunicationTypes.TICKET_VALIDATED, card, T0.minusMinutes(5), "{\"ticketNumber\":\"T1\",\"total\":65.0,"
				+ "\"pointsEarned\":65,\"balanceAfter\":130,\"cardNumber\":\"" + card + "\",\"firstName\":\"Sana\","
				+ "\"lastName\":\"Ben Ali\",\"storeName\":null,\"companyName\":\"Happyness\"}");
	}

	private LoyaltyMember member(String card, String phone) {
		LoyaltyMember member = new LoyaltyMember();
		member.setCardNumber(card);
		member.setPhone(phone);
		member.setActive(true);
		memberTable.put(card, member);
		return member;
	}

	private CommunicationRule rule(String code, String eventType, String channel, String templateCode, boolean active) {
		CommunicationRule rule = new CommunicationRule();
		rule.setCode(code);
		rule.setEventType(eventType);
		rule.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		rule.setChannel(channel);
		rule.setTemplateCode(templateCode);
		rule.setActive(active);
		ruleTable.add(rule);
		return rule;
	}

	private CommunicationTemplate template(String code, String channel, String body) {
		CommunicationTemplate template = new CommunicationTemplate();
		template.setCode(code);
		template.setName(code);
		template.setChannel(channel);
		template.setLanguageCode("fr");
		template.setBody(body);
		template.setActive(true);
		templateTable.put(code, template);
		return template;
	}

	private void ticketRule() {
		rule("TICKET_THANKS_SMS", CommunicationTypes.TICKET_VALIDATED, CommunicationTypes.SMS, "TICKET_THANKS_SMS_FR", true);
		template("TICKET_THANKS_SMS_FR", CommunicationTypes.SMS, "Merci {firstName} ({total} TND) +{pointsEarned} pts,"
				+ " solde {balanceAfter} {storeName}");
	}

	// --- The repositories, as the queries read them ---

	private final CommunicationEventRepository events = stub(CommunicationEventRepository.class, (method, args) -> {
		switch (method) {
			case "findToProcess":
				long afterId = (Long) args[3];
				int maxAttempts = (Integer) args[2];
				return eventTable.values().stream()
						.filter(e -> e.getStatus() == args[0] || (e.getStatus() == args[1] && e.getAttempts() < maxAttempts))
						.filter(e -> e.getId() > afterId).map(CommunicationEvent::getId)
						.limit(((Pageable) args[4]).getPageSize()).collect(Collectors.toList());
			case "findById":
				return Optional.ofNullable(eventTable.get(args[0]));
			default:
				return UNHANDLED;
		}
	});

	private final CommunicationRuleRepository rules = stub(CommunicationRuleRepository.class, (method, args) ->
			"findByEventTypeAndActiveTrueOrderByCode".equals(method) ? ruleTable.stream()
					.filter(r -> r.getEventType().equals(args[0]) && Boolean.TRUE.equals(r.getActive()))
					.collect(Collectors.toList()) : UNHANDLED);

	private final CommunicationTemplateRepository templates = stub(CommunicationTemplateRepository.class,
			(method, args) -> "findByCode".equals(method) ? Optional.ofNullable(templateTable.get(args[0])) : UNHANDLED);

	private final CommunicationMessageRepository messages = stub(CommunicationMessageRepository.class, (method, args) -> {
		switch (method) {
			case "findByDedupKey":
				return messageTable.values().stream().filter(m -> m.getDedupKey().equals(args[0])).findFirst();
			case "save":
				CommunicationMessage message = (CommunicationMessage) args[0];
				if (message.getId() == null) {
					assertTrue(messageTable.values().stream().noneMatch(m -> m.getDedupKey().equals(message.getDedupKey())),
							"dedup key written twice: " + message.getDedupKey()); // uk_comm_message_dedup
					message.setId((long) messageTable.size() + 1);
				}
				messageTable.put(message.getId(), message);
				return message;
			case "findDue":
				LocalDateTime at = (LocalDateTime) args[1];
				long afterId = (Long) args[2];
				return messageTable.values().stream()
						.filter(m -> m.getStatus() == args[0] && (m.getNextAttemptAt() == null || !m.getNextAttemptAt().isAfter(at)))
						.filter(m -> m.getId() > afterId).limit(((Pageable) args[3]).getPageSize()).collect(Collectors.toList());
			default:
				return UNHANDLED;
		}
	});

	private final LoyaltyMemberRepository members = stub(LoyaltyMemberRepository.class, (method, args) -> {
		if (!"findByCardNumber".equals(method)) {
			return UNHANDLED;
		}
		if (membersFail) {
			throw new IllegalStateException("The query has timed out.");
		}
		return Optional.ofNullable(memberTable.get(args[0]));
	});

	private CommunicationSettings settings() {
		return new CommunicationSettings(new MockEnvironment().withProperty("communication.sms.enabled", smsEnabled),
				Collections.singletonList("test"));
	}

	private String process() {
		return new CommunicationEventProcessor(events, rules, templates, messages, members, settings(),
				TransactionOperations.withoutTransaction(), () -> now).run(Instant.now().plusSeconds(20));
	}

	private String send(CommunicationProvider... providers) {
		return new CommunicationSender(messages, settings(), Arrays.asList(providers), () -> now)
				.run(Instant.now().plusSeconds(20));
	}

	// --- Processor ---

	@Test
	@DisplayName("An event with an active rule, its channel on and a member with 8 digits: one PENDING message, PROCESSED")
	void message() {
		ticketRule();
		member("LYL-000001", "99000002");
		CommunicationEvent event = ticket("LYL-000001");
		assertEquals("1 event(s) with messages, 0 skipped, 0 in error", process());
		assertEquals(CommunicationEventStatus.PROCESSED, event.getStatus());
		assertNull(event.getLastError());
		assertEquals(now, event.getProcessedAt());
		CommunicationMessage message = messageTable.get(1L);
		assertEquals(CommunicationMessageStatus.PENDING, message.getStatus());
		assertEquals("+21699000002", message.getAddress());
		assertEquals("Merci Sana (65.000 TND) +65 pts, solde 130 ", message.getBody()); // storeName null: empty
		assertEquals("SMS", message.getChannel());
		assertEquals("test", message.getProvider());
		assertEquals("LYL-000001", message.getRecipientRef());
		assertEquals("TICKET_THANKS_SMS_FR", message.getTemplateCode());
		assertEquals("fr", message.getLanguageCode());
		assertEquals(event.getId(), message.getEventId());
		assertEquals("1:TICKET_THANKS_SMS", message.getDedupKey());
		assertEquals(T0.minusMinutes(5).plusHours(24), message.getExpiresAt()); // the lifetime counts from the event
	}

	@Test
	@DisplayName("A second pass on the same event (set back to PENDING) makes no second message")
	void secondPass() {
		ticketRule();
		member("LYL-000001", "99000002");
		CommunicationEvent event = ticket("LYL-000001");
		process();
		event.setStatus(CommunicationEventStatus.PENDING);
		process();
		assertEquals(1, messageTable.size());
		assertEquals(CommunicationEventStatus.PROCESSED, event.getStatus());
	}

	@Test
	@DisplayName("Channel off: no message, SKIPPED with the reason")
	void channelOff() {
		ticketRule();
		member("LYL-000001", "99000002");
		smsEnabled = "false";
		CommunicationEvent event = ticket("LYL-000001");
		process();
		assertTrue(messageTable.isEmpty());
		assertEquals(CommunicationEventStatus.SKIPPED, event.getStatus());
		assertEquals("TICKET_THANKS_SMS: channel sms off", event.getLastError());
	}

	@Test
	@DisplayName("Rule inactive (or none for the type): SKIPPED, no active rule")
	void ruleInactive() {
		ticketRule();
		ruleTable.get(0).setActive(false);
		member("LYL-000001", "99000002");
		CommunicationEvent event = ticket("LYL-000001");
		process();
		assertEquals(CommunicationEventStatus.SKIPPED, event.getStatus());
		assertEquals("no active rule for TICKET_VALIDATED", event.getLastError());
		assertTrue(messageTable.isEmpty());
	}

	@Test
	@DisplayName("No phone, a bad phone, a member unknown or inactive, a template inactive, an event too old: SKIPPED with the reason")
	void skipped() {
		ticketRule();
		member("LYL-1", null);
		member("LYL-2", "2995429");
		member("LYL-3", "99000003").setActive(false);
		member("LYL-4", "99000004");
		CommunicationEvent noPhone = ticket("LYL-1");
		CommunicationEvent badPhone = ticket("LYL-2");
		CommunicationEvent inactive = ticket("LYL-3");
		CommunicationEvent unknown = ticket("LYL-9");
		CommunicationEvent old = ticket("LYL-4");
		old.setOccurredAt(T0.minusHours(25));
		assertEquals("0 event(s) with messages, 5 skipped, 0 in error", process());
		assertEquals("TICKET_THANKS_SMS: phone '' is not 8 digits", noPhone.getLastError());
		assertEquals("TICKET_THANKS_SMS: phone '2995429' is not 8 digits", badPhone.getLastError());
		assertEquals("member LYL-3 inactive", inactive.getLastError());
		assertEquals("member LYL-9 not found", unknown.getLastError());
		assertEquals("TICKET_THANKS_SMS: too old: happened 2026-11-19T16:00, lifetime 24 h", old.getLastError());
		assertTrue(messageTable.isEmpty());
		templateTable.get("TICKET_THANKS_SMS_FR").setActive(false);
		CommunicationEvent noTemplate = ticket("LYL-4");
		process();
		assertEquals("TICKET_THANKS_SMS: template TICKET_THANKS_SMS_FR missing or inactive", noTemplate.getLastError());
	}

	@Test
	@DisplayName("A technical failure: ERROR with one attempt, the other events go on; tried again, at most 5 times")
	void technicalFailure() {
		ticketRule();
		member("LYL-000001", "99000002");
		CommunicationEvent event = ticket("LYL-000001");
		membersFail = true;
		assertEquals("0 event(s) with messages, 0 skipped, 1 in error", process());
		assertEquals(CommunicationEventStatus.ERROR, event.getStatus());
		assertEquals(1, event.getAttempts());
		assertEquals("IllegalStateException: The query has timed out.", event.getLastError());
		for (int i = 0; i < 6; i++) {
			process();
		}
		assertEquals(5, event.getAttempts()); // no longer picked after the 5th
		membersFail = false;
		CommunicationEvent next = ticket("LYL-000001");
		process();
		assertEquals(CommunicationEventStatus.PROCESSED, next.getStatus());
		assertEquals(CommunicationEventStatus.ERROR, event.getStatus());
	}

	// --- Sender ---

	/** A fake provider: its answers in order, then accepted. */
	private static final class FakeProvider implements CommunicationProvider {
		final List<SendResult> answers;
		int calls;

		FakeProvider(SendResult... answers) {
			this.answers = new ArrayList<>(Arrays.asList(answers));
		}

		@Override
		public String getName() {
			return "test";
		}

		@Override
		public SendResult send(CommunicationMessage message) {
			calls++;
			return answers.isEmpty() ? SendResult.accepted("FAKE-" + calls) : answers.remove(0);
		}
	}

	private CommunicationMessage waiting() {
		ticketRule();
		member("LYL-000001", "99000002");
		ticket("LYL-000001");
		process();
		return messageTable.get(1L);
	}

	@Test
	@DisplayName("Accepted by the test provider: SENT, sent_at, the provider's id TEST-...")
	void sent() {
		CommunicationMessage message = waiting();
		assertEquals("1 sent", send(new TestCommunicationProvider()));
		assertEquals(CommunicationMessageStatus.SENT, message.getStatus());
		assertEquals(now, message.getSentAt());
		assertTrue(message.getProviderMessageId().startsWith("TEST-1-"), message.getProviderMessageId());
		assertEquals(1, message.getAttempts());
		assertNull(send(new TestCommunicationProvider())); // nothing due any more
	}

	@Test
	@DisplayName("Past its expires_at: EXPIRED, never given to the provider")
	void expired() {
		CommunicationMessage message = waiting();
		FakeProvider provider = new FakeProvider();
		now = message.getExpiresAt();
		assertEquals("1 expired", send(provider));
		assertEquals(CommunicationMessageStatus.EXPIRED, message.getStatus());
		assertEquals(0, provider.calls);
	}

	@Test
	@DisplayName("Temporary failures: waits of 1, 5, 15 and 60 min, not sent before; FAILED at the 5th")
	void temporaryThenFailed() {
		CommunicationMessage message = waiting();
		FakeProvider provider = new FakeProvider(CommunicationProvider.SendResult.temporaryFailure("timeout"),
				CommunicationProvider.SendResult.temporaryFailure("timeout"),
				CommunicationProvider.SendResult.temporaryFailure("timeout"),
				CommunicationProvider.SendResult.temporaryFailure("timeout"),
				CommunicationProvider.SendResult.temporaryFailure("timeout"));
		int[] waits = { 1, 5, 15, 60 };
		for (int attempt = 1; attempt <= 4; attempt++) {
			assertEquals("1 to try again", send(provider));
			assertEquals(CommunicationMessageStatus.PENDING, message.getStatus());
			assertEquals(now.plusMinutes(waits[attempt - 1]), message.getNextAttemptAt());
			assertEquals("attempt " + attempt + " failed: timeout", message.getLastError());
			assertNull(send(provider)); // not due yet
			now = message.getNextAttemptAt();
		}
		assertEquals("1 failed", send(provider));
		assertEquals(CommunicationMessageStatus.FAILED, message.getStatus());
		assertEquals("attempt 5 of 5 failed: timeout", message.getLastError());
		assertEquals(5, provider.calls);
	}

	@Test
	@DisplayName("A permanent failure: FAILED at once; a provider that throws: a temporary failure")
	void permanentAndThrow() {
		CommunicationMessage message = waiting();
		send(new FakeProvider(CommunicationProvider.SendResult.permanentFailure("number refused")));
		assertEquals(CommunicationMessageStatus.FAILED, message.getStatus());
		assertEquals("number refused", message.getLastError());
		CommunicationEvent again = ticket("LYL-000001");
		process();
		CommunicationMessage second = messageTable.values().stream().filter(m -> m.getEventId().equals(again.getId()))
				.findFirst().get();
		send(new CommunicationProvider() {
			@Override
			public String getName() {
				return "test";
			}

			@Override
			public SendResult send(CommunicationMessage m) {
				throw new IllegalStateException("connection reset");
			}
		});
		assertEquals(CommunicationMessageStatus.PENDING, second.getStatus());
		assertEquals("attempt 1 failed: IllegalStateException: connection reset", second.getLastError());
	}

	@Test
	@DisplayName("The channel switched off while a message waits: SKIPPED, not sent")
	void channelOffAtSendTime() {
		CommunicationMessage message = waiting();
		smsEnabled = "false";
		FakeProvider provider = new FakeProvider();
		assertEquals("1 skipped", send(provider));
		assertEquals(CommunicationMessageStatus.SKIPPED, message.getStatus());
		assertEquals(0, provider.calls);
	}

	@Test
	@DisplayName("The test provider masks the address in its log line")
	void masked() {
		assertEquals("+2169****002", TestCommunicationProvider.masked("+21699000002"));
		assertEquals("s***@example.tn", TestCommunicationProvider.masked("sana@example.tn"));
	}

	// --- Stubs ---

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
