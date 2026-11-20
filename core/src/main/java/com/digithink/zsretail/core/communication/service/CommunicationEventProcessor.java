package com.digithink.zsretail.core.communication.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationEventStatus;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.model.CommunicationRule;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.repository.CommunicationEventRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationRuleRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository;
import com.digithink.zsretail.core.communication.scheduler.CommunicationTask;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.core.utils.ErrorTexts;

/**
 * 3.3.0, communication module, step 3: from each PENDING event to its messages, where this installation sends
 * (Installation.COMMUNICATION_SENDS_HERE; a store whose loyalty is the head office's keeps its events PENDING). Every
 * active rule of the event's type whose channel is on gives one PENDING message: the address read from the member now,
 * the text from the template and the payload, never sent after the event's time plus the rule's lifetime. Once per
 * event and rule (dedup key &lt;event id&gt;:&lt;rule code&gt;). The event becomes PROCESSED when it has a message,
 * SKIPPED with the reasons otherwise; a technical failure makes it ERROR, tried again at the next cycles (at most
 * {@link #MAX_ATTEMPTS} times), each event in its own transaction. See docs/modules/communication.md.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
@Order(10)
public class CommunicationEventProcessor implements CommunicationTask {

	public static final String CODE = "MESSAGES_MADE";
	static final int MAX_ATTEMPTS = 5;
	static final int PAGE_SIZE = 100;
	static final int TIMEOUT_SECONDS = 15;

	private final CommunicationEventRepository events;
	private final CommunicationRuleRepository rules;
	private final CommunicationTemplateRepository templates;
	private final CommunicationMessageRepository messages;
	private final LoyaltyMemberRepository members;
	private final CommunicationSettings settings;
	private final TransactionOperations transactions;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public CommunicationEventProcessor(CommunicationEventRepository events, CommunicationRuleRepository rules,
			CommunicationTemplateRepository templates, CommunicationMessageRepository messages,
			LoyaltyMemberRepository members, CommunicationSettings settings, PlatformTransactionManager transactionManager) {
		this(events, rules, templates, messages, members, settings, timed(transactionManager), LocalDateTime::now);
	}

	/** With given transactions and clock: used by the tests. */
	public CommunicationEventProcessor(CommunicationEventRepository events, CommunicationRuleRepository rules,
			CommunicationTemplateRepository templates, CommunicationMessageRepository messages,
			LoyaltyMemberRepository members, CommunicationSettings settings, TransactionOperations transactions,
			Supplier<LocalDateTime> clock) {
		this.events = events;
		this.rules = rules;
		this.templates = templates;
		this.messages = messages;
		this.members = members;
		this.settings = settings;
		this.transactions = transactions;
		this.clock = clock;
	}

	static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	@Override
	public String getCode() {
		return CODE;
	}

	@Override
	public String run(Instant deadline) {
		int processed = 0;
		int skipped = 0;
		int errors = 0;
		long afterId = 0;
		while (Instant.now().isBefore(deadline)) {
			long from = afterId;
			List<Long> ids = transactions.execute(status -> events.findToProcess(CommunicationEventStatus.PENDING,
					CommunicationEventStatus.ERROR, MAX_ATTEMPTS, from, PageRequest.of(0, PAGE_SIZE)));
			if (ids == null || ids.isEmpty()) {
				break;
			}
			for (Long id : ids) {
				afterId = id;
				if (!Instant.now().isBefore(deadline)) {
					break;
				}
				CommunicationEventStatus outcome;
				try {
					outcome = transactions.execute(status -> process(id));
				} catch (RuntimeException e) {
					outcome = failed(id, e);
				}
				if (outcome == CommunicationEventStatus.PROCESSED) {
					processed++;
				} else if (outcome == CommunicationEventStatus.SKIPPED) {
					skipped++;
				} else if (outcome == CommunicationEventStatus.ERROR) {
					errors++;
				}
			}
			if (ids.size() < PAGE_SIZE) {
				break;
			}
		}
		if (processed + skipped + errors == 0) {
			return null;
		}
		return processed + " event(s) with messages, " + skipped + " skipped, " + errors + " in error";
	}

	/** One event, in its transaction. Returns its new status; null when it is no longer to process. */
	CommunicationEventStatus process(long id) {
		CommunicationEvent event = events.findById(id).orElse(null);
		if (event == null || (event.getStatus() != CommunicationEventStatus.PENDING
				&& event.getStatus() != CommunicationEventStatus.ERROR)) {
			return null;
		}
		LocalDateTime now = clock.get();
		List<CommunicationRule> active = rules.findByEventTypeAndActiveTrueOrderByCode(event.getEventType());
		if (active.isEmpty()) {
			return close(event, CommunicationEventStatus.SKIPPED, "no active rule for " + event.getEventType(), now);
		}
		if (!CommunicationTypes.LOYALTY_MEMBER.equals(event.getRecipientType())) {
			return close(event, CommunicationEventStatus.SKIPPED, "recipient type " + event.getRecipientType()
					+ " not handled", now);
		}
		Optional<LoyaltyMember> found = event.getRecipientRef() == null ? Optional.empty()
				: members.findByCardNumber(event.getRecipientRef());
		if (!found.isPresent()) {
			return close(event, CommunicationEventStatus.SKIPPED, "member " + event.getRecipientRef() + " not found", now);
		}
		LoyaltyMember member = found.get();
		if (!Boolean.TRUE.equals(member.getActive())) {
			return close(event, CommunicationEventStatus.SKIPPED, "member " + member.getCardNumber() + " inactive", now);
		}
		Map<String, Object> values = CommunicationTypes.values(event.getPayload());
		List<String> reasons = new ArrayList<>();
		int made = 0;
		for (CommunicationRule rule : active) {
			String reason = message(event, rule, member, values, now);
			if (reason == null) {
				made++;
			} else {
				reasons.add(rule.getCode() + ": " + reason);
			}
		}
		String why = reasons.isEmpty() ? null : String.join("; ", reasons);
		return made > 0 ? close(event, CommunicationEventStatus.PROCESSED, why, now)
				: close(event, CommunicationEventStatus.SKIPPED, why, now);
	}

	/** The message of one rule; null when it was made (or exists already), else why not. */
	private String message(CommunicationEvent event, CommunicationRule rule, LoyaltyMember member,
			Map<String, Object> values, LocalDateTime now) {
		if (!settings.isEnabled(rule.getChannel())) {
			return "channel " + rule.getChannel().toLowerCase(java.util.Locale.ROOT) + " off";
		}
		String dedupKey = event.getId() + ":" + rule.getCode();
		if (messages.findByDedupKey(dedupKey).isPresent()) {
			return null; // made at an earlier pass: never a second one
		}
		CommunicationTemplate template = templates.findByCode(rule.getTemplateCode()).orElse(null);
		if (template == null || !Boolean.TRUE.equals(template.getActive())) {
			return "template " + rule.getTemplateCode() + " missing or inactive";
		}
		if (!rule.getChannel().equals(template.getChannel())) {
			return "template " + template.getCode() + " is for " + template.getChannel() + ", the rule for "
					+ rule.getChannel();
		}
		String address = address(rule.getChannel(), member);
		if (address == null) {
			return CommunicationTypes.EMAIL.equals(rule.getChannel()) ? "no email address"
					: "phone '" + (member.getPhone() == null ? "" : member.getPhone()) + "' is not 8 digits";
		}
		LocalDateTime happened = event.getOccurredAt() == null ? event.getCreatedAt() : event.getOccurredAt();
		LocalDateTime expires = happened.plusHours(rule.getLifetimeHours());
		if (!expires.isAfter(now)) {
			return "too old: happened " + happened + ", lifetime " + rule.getLifetimeHours() + " h";
		}
		CommunicationMessage message = new CommunicationMessage();
		message.setEventId(event.getId());
		message.setChannel(rule.getChannel());
		message.setProvider(settings.provider(rule.getChannel()));
		message.setRecipientType(rule.getRecipientType());
		message.setRecipientRef(member.getCardNumber());
		message.setAddress(address);
		message.setSubject(template.getSubject());
		message.setBody(CommunicationTemplates.render(template.getCode(), template.getBody(), values));
		message.setTemplateCode(template.getCode());
		message.setLanguageCode(template.getLanguageCode());
		message.setExpiresAt(expires);
		message.setDedupKey(dedupKey);
		messages.save(message);
		return null;
	}

	/** SMS and WhatsApp: + country code + the 8 digits; email: a plausible address. Null when there is none. */
	String address(String channel, LoyaltyMember member) {
		if (CommunicationTypes.EMAIL.equals(channel)) {
			String email = member.getEmail() == null ? "" : member.getEmail().trim();
			return email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") ? email : null;
		}
		String phone = member.getPhone() == null ? "" : member.getPhone().trim();
		return phone.matches("\\d{8}") ? "+" + settings.getCountryCode() + phone : null;
	}

	private static CommunicationEventStatus close(CommunicationEvent event, CommunicationEventStatus status, String why,
			LocalDateTime now) {
		event.setStatus(status);
		event.setLastError(cut(why));
		event.setProcessedAt(now);
		return status;
	}

	/** A technical failure: ERROR, one more attempt, the reason; in its own transaction. */
	private CommunicationEventStatus failed(long id, RuntimeException e) {
		String cause = ErrorTexts.cause(e);
		try {
			transactions.executeWithoutResult(status -> events.findById(id).ifPresent(event -> {
				event.setStatus(CommunicationEventStatus.ERROR);
				event.setAttempts(event.getAttempts() + 1);
				event.setLastError(cut(cause));
			}));
		} catch (RuntimeException ignored) {
			// the next cycle tries the event again
		}
		return CommunicationEventStatus.ERROR;
	}

	private static String cut(String text) {
		return text == null || text.length() <= CommunicationEvent.LAST_ERROR_LENGTH ? text
				: text.substring(0, CommunicationEvent.LAST_ERROR_LENGTH);
	}
}
