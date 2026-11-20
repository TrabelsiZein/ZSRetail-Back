package com.digithink.zsretail.core.communication.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Subquery;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.communication.CommunicationException;
import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationEventStatus;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.model.CommunicationRule;
import com.digithink.zsretail.core.communication.model.CommunicationTemplate;
import com.digithink.zsretail.core.communication.repository.CommunicationEventRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationMessageRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationRuleRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationTemplateRepository;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.model.MessageConsent;
import com.digithink.zsretail.core.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.core.service.CompanyInformationService;

/**
 * 3.3.0, communication step 4: what the Communication page reads and changes, where the messages are made and sent
 * (Installation.COMMUNICATION_SENDS_HERE). The messages and the events left out (lists, one message with its event, a
 * message sent again), the counters of the day, the rules (read, change, on / off; never created nor deleted: the
 * seeded ones) and the templates (read, create, change, on / off, a preview; never deleted, switched off instead: their
 * codes stay in the messages). A change refused throws a {@link CommunicationException} (its code, the front's text).
 * See docs/modules/communication.md.
 */
@Service
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
public class CommunicationAdmin {

	static final int MAX_PAGE_SIZE = 200;
	static final int MIN_LIFETIME_HOURS = 1;
	static final int MAX_LIFETIME_HOURS = 720;
	static final int MAX_BODY = 2000;
	static final int MAX_NAME = 100;
	static final int MAX_SUBJECT = 255;
	static final int MAX_PROVIDER_TEMPLATE = 100;
	static final int MAX_TICKETS_SEARCHED = 500;
	public static final List<String> LANGUAGES = Collections.unmodifiableList(Arrays.asList("fr", "ar", "en"));
	private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	/** The sample values of the preview (the company's name is the installation's). */
	static final Map<String, Object> SAMPLE;
	static {
		Map<String, Object> sample = new LinkedHashMap<>();
		sample.put("firstName", "Amira");
		sample.put("lastName", "Ben Salah");
		sample.put("cardNumber", "LYL-000123");
		sample.put("ticketNumber", "MAG261121042");
		sample.put("total", 48.9);
		sample.put("pointsEarned", 48);
		sample.put("pointsSpent", 0);
		sample.put("balanceAfter", 161);
		sample.put("storeName", "Magasin Centre");
		sample.put("companyName", "Ma boutique");
		SAMPLE = Collections.unmodifiableMap(sample);
	}

	private final CommunicationEventRepository events;
	private final CommunicationMessageRepository messages;
	private final CommunicationRuleRepository rules;
	private final CommunicationTemplateRepository templates;
	private final LoyaltyMemberRepository members;
	private final CommunicationSettings settings;
	private final Optional<CommunicationTicketSearch> tickets;
	private final Supplier<String> companyName;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public CommunicationAdmin(CommunicationEventRepository events, CommunicationMessageRepository messages,
			CommunicationRuleRepository rules, CommunicationTemplateRepository templates, LoyaltyMemberRepository members,
			CommunicationSettings settings, Optional<CommunicationTicketSearch> tickets,
			CompanyInformationService companyInformation) {
		this(events, messages, rules, templates, members, settings, tickets, () -> {
			CompanyInformationDTO company = companyInformation.get();
			return company == null ? null : company.getCompanyName();
		}, LocalDateTime::now);
	}

	/** With a given company name and clock: used by the tests. */
	public CommunicationAdmin(CommunicationEventRepository events, CommunicationMessageRepository messages,
			CommunicationRuleRepository rules, CommunicationTemplateRepository templates, LoyaltyMemberRepository members,
			CommunicationSettings settings, Optional<CommunicationTicketSearch> tickets, Supplier<String> companyName,
			Supplier<LocalDateTime> clock) {
		this.events = events;
		this.messages = messages;
		this.rules = rules;
		this.templates = templates;
		this.members = members;
		this.settings = settings;
		this.tickets = tickets;
		this.companyName = companyName;
		this.clock = clock;
	}

	// ─── The filters of the lists ────────────────────────────────────

	/** The filters of a list, parsed once; an empty or unreadable value is no filter. */
	public static final class Filters {
		String status;
		String channel;
		LocalDateTime from;
		LocalDateTime to;
		String text;

		/** Dates as "2026-11-21" (the whole day) or "2026-11-21T10:00". */
		public static Filters of(String status, String channel, String dateFrom, String dateTo, String text) {
			Filters filters = new Filters();
			filters.status = upper(status);
			filters.channel = upper(channel);
			filters.from = dateTime(dateFrom, false);
			filters.to = dateTime(dateTo, true);
			filters.text = blankToNull(text);
			return filters;
		}
	}

	// ─── Messages ────────────────────────────────────────────────────

	/** A page of messages, newest first: {content, totalElements, totalPages, number, size}. */
	@Transactional(readOnly = true)
	public Map<String, Object> messages(Filters filters, int page, int size) {
		// One status, or several joined by commas (the counter "failed": FAILED,EXPIRED); an unknown name is left out
		List<CommunicationMessageStatus> statuses = filters.status == null ? Collections.emptyList()
				: Arrays.stream(filters.status.split(",")).map(s -> enumOf(CommunicationMessageStatus.class, s.trim()))
						.filter(s -> s != null).collect(Collectors.toList());
		List<Long> ticketIds = ticketIds(filters.text);
		Specification<CommunicationMessage> spec = (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			if (!statuses.isEmpty()) {
				where.add(root.get("status").in(statuses));
			}
			if (filters.channel != null) {
				where.add(cb.equal(root.get("channel"), filters.channel));
			}
			if (filters.from != null) {
				where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filters.from));
			}
			if (filters.to != null) {
				where.add(cb.lessThanOrEqualTo(root.get("createdAt"), filters.to));
			}
			if (filters.text != null) {
				String like = "%" + escapeLike(filters.text) + "%";
				List<Predicate> any = new ArrayList<>();
				any.add(cb.like(root.get("recipientRef"), like, '\\'));
				any.add(cb.like(root.get("address"), like, '\\'));
				if (!ticketIds.isEmpty()) {
					Subquery<Long> ofTickets = query.subquery(Long.class);
					javax.persistence.criteria.Root<CommunicationEvent> e = ofTickets.from(CommunicationEvent.class);
					ofTickets.select(e.get("id")).where(cb.equal(e.get("sourceType"), CommunicationTypes.SALES_HEADER),
							e.get("sourceId").in(ticketIds));
					any.add(root.get("eventId").in(ofTickets));
				}
				where.add(cb.or(any.toArray(new Predicate[0])));
			}
			return cb.and(where.toArray(new Predicate[0]));
		};
		Page<CommunicationMessage> found = messages.findAll(spec, pageRequest(page, size));
		Map<String, String> names = memberNames(found.getContent().stream().map(CommunicationMessage::getRecipientRef)
				.collect(Collectors.toList()));
		return page(found, m -> messageRow(m, names.get(m.getRecipientRef())));
	}

	/** One message, its event (with the event's values read from its payload) and the member's name now. */
	@Transactional(readOnly = true)
	public Map<String, Object> message(long id) {
		CommunicationMessage message = messages.findById(id).orElseThrow(() -> CommunicationException.notFound(
				"COMM_MESSAGE_NOT_FOUND", "Message " + id + " not found"));
		Map<String, Object> detail = messageRow(message, memberNames(Collections.singletonList(message.getRecipientRef()))
				.get(message.getRecipientRef()));
		detail.put("subject", message.getSubject());
		detail.put("body", message.getBody());
		detail.put("provider", message.getProvider());
		detail.put("languageCode", message.getLanguageCode());
		detail.put("nextAttemptAt", message.getNextAttemptAt());
		detail.put("expiresAt", message.getExpiresAt());
		detail.put("providerMessageId", message.getProviderMessageId());
		detail.put("lastError", message.getLastError());
		detail.put("updatedAt", message.getUpdatedAt());
		detail.put("updatedBy", message.getUpdatedBy());
		CommunicationEvent event = message.getEventId() == null ? null : events.findById(message.getEventId()).orElse(null);
		detail.put("event", event == null ? null : eventRow(event, null));
		return detail;
	}

	/**
	 * A FAILED or EXPIRED message sent again: PENDING, attempts 0, sent at the next cycle; a new lifetime from now (its
	 * rule's, the code after the ':' of its dedup key; 24 h when the rule is gone); its channel's provider now; the
	 * address read again from the member (a phone corrected on the card is used; a member gone keeps the address of the
	 * message). last_error keeps the history: "resent ... by ...; before: ...". Refused while its channel is off.
	 */
	@Transactional
	public Map<String, Object> resend(long id, String user) {
		CommunicationMessage message = messages.findById(id).orElseThrow(() -> CommunicationException.notFound(
				"COMM_MESSAGE_NOT_FOUND", "Message " + id + " not found"));
		CommunicationMessageStatus was = message.getStatus();
		if (was != CommunicationMessageStatus.FAILED && was != CommunicationMessageStatus.EXPIRED) {
			throw CommunicationException.refused("COMM_RESEND_STATUS", "Only a FAILED or EXPIRED message is sent again, not "
					+ was, "status", was == null ? "" : was.name());
		}
		requireChannelOn(message.getChannel());
		boolean addressChanged = false;
		if (CommunicationTypes.LOYALTY_MEMBER.equals(message.getRecipientType()) && message.getRecipientRef() != null) {
			Optional<LoyaltyMember> member = members.findByCardNumber(message.getRecipientRef());
			if (member.isPresent()) {
				if (!MessageConsent.given(member.get())) { // step 5: never to a member who withdrew the consent
					throw CommunicationException.refused("COMM_RESEND_NO_CONSENT", "Member " + message.getRecipientRef()
							+ " does not accept messages any more", "card", message.getRecipientRef());
				}
				String address = CommunicationEventProcessor.address(message.getChannel(), member.get(),
						settings.getCountryCode());
				if (address == null) {
					throw CommunicationException.refused("COMM_RESEND_NO_ADDRESS", "Member " + message.getRecipientRef()
							+ " has no valid address for " + message.getChannel() + " now", "card",
							message.getRecipientRef());
				}
				addressChanged = !address.equals(message.getAddress());
				message.setAddress(address);
			}
		}
		LocalDateTime now = clock.get();
		String history = "resent " + now.format(HISTORY_TIME) + " by " + (user == null ? "?" : user) + " (" + was + " after "
				+ message.getAttempts() + " attempt(s)" + (addressChanged ? ", address read again from the member" : "")
				+ ")" + (message.getLastError() == null ? "" : "; before: " + message.getLastError());
		message.setLastError(cut(history, CommunicationMessage.LAST_ERROR_LENGTH));
		message.setStatus(CommunicationMessageStatus.PENDING);
		message.setAttempts(0);
		message.setNextAttemptAt(null);
		message.setExpiresAt(now.plusHours(lifetimeOf(message)));
		message.setProvider(settings.provider(message.getChannel()));
		message.setUpdatedBy(user);
		messages.save(message);
		return message(id);
	}

	/** The events left out (SKIPPED) or in error (ERROR), newest first; status filters one of the two. */
	@Transactional(readOnly = true)
	public Map<String, Object> events(Filters filters, int page, int size) {
		CommunicationEventStatus status = enumOf(CommunicationEventStatus.class, filters.status);
		List<CommunicationEventStatus> statuses = status == CommunicationEventStatus.SKIPPED
				|| status == CommunicationEventStatus.ERROR ? Collections.singletonList(status)
						: Arrays.asList(CommunicationEventStatus.SKIPPED, CommunicationEventStatus.ERROR);
		List<Long> ticketIds = ticketIds(filters.text);
		Specification<CommunicationEvent> spec = (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			where.add(root.get("status").in(statuses));
			if (filters.from != null) {
				where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filters.from));
			}
			if (filters.to != null) {
				where.add(cb.lessThanOrEqualTo(root.get("createdAt"), filters.to));
			}
			if (filters.text != null) {
				String like = "%" + escapeLike(filters.text) + "%";
				Predicate card = cb.like(root.get("recipientRef"), like, '\\');
				where.add(ticketIds.isEmpty() ? card : cb.or(card, cb.and(
						cb.equal(root.get("sourceType"), CommunicationTypes.SALES_HEADER), root.get("sourceId").in(ticketIds))));
			}
			return cb.and(where.toArray(new Predicate[0]));
		};
		Page<CommunicationEvent> found = events.findAll(spec, pageRequest(page, size));
		Map<String, String> names = memberNames(found.getContent().stream().map(CommunicationEvent::getRecipientRef)
				.collect(Collectors.toList()));
		return page(found, e -> eventRow(e, names.get(e.getRecipientRef())));
	}

	/**
	 * The four counters of the page, each the count of a list's filter (a click on it shows its rows): the messages made
	 * today and SENT, the messages waiting (PENDING), the messages to look at (FAILED or EXPIRED, any day: sending one
	 * again takes it out), the events of today left out (SKIPPED). Today: the installation's clock, from 00:00.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> counters() {
		LocalDateTime today = clock.get().toLocalDate().atStartOfDay();
		Map<String, Object> counters = new LinkedHashMap<>();
		counters.put("sentToday", messages.count((root, query, cb) -> cb.and(
				cb.equal(root.get("status"), CommunicationMessageStatus.SENT),
				cb.greaterThanOrEqualTo(root.get("createdAt"), today))));
		counters.put("waiting", messages.count((root, query, cb) -> cb.equal(root.get("status"),
				CommunicationMessageStatus.PENDING)));
		counters.put("failed", messages.count((root, query, cb) -> root.get("status")
				.in(CommunicationMessageStatus.FAILED, CommunicationMessageStatus.EXPIRED)));
		counters.put("skippedToday", events.count((root, query, cb) -> cb.and(
				cb.equal(root.get("status"), CommunicationEventStatus.SKIPPED),
				cb.greaterThanOrEqualTo(root.get("createdAt"), today))));
		counters.put("today", today.toLocalDate());
		return counters;
	}

	// ─── Rules ───────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public List<CommunicationRule> rules() {
		return rules.findAll(Sort.by("code"));
	}

	@Transactional(readOnly = true)
	public CommunicationRule rule(long id) {
		return rules.findById(id).orElseThrow(() -> CommunicationException.notFound("COMM_RULE_NOT_FOUND",
				"Rule " + id + " not found"));
	}

	/**
	 * A rule changed: its channel, its template and its lifetime (its code, event and recipient stay). The template must
	 * exist and be on the rule's channel; an active rule also needs its channel on and its template active.
	 */
	@Transactional
	public CommunicationRule updateRule(long id, String channel, String templateCode, Integer lifetimeHours, String user) {
		CommunicationRule rule = rule(id);
		String newChannel = channel(channel);
		if (Boolean.TRUE.equals(rule.getActive())) {
			requireChannelOn(newChannel);
		}
		if (lifetimeHours == null || lifetimeHours < MIN_LIFETIME_HOURS || lifetimeHours > MAX_LIFETIME_HOURS) {
			throw CommunicationException.refused("COMM_RULE_LIFETIME", "The lifetime is " + MIN_LIFETIME_HOURS + " to "
					+ MAX_LIFETIME_HOURS + " hours", "min", MIN_LIFETIME_HOURS, "max", MAX_LIFETIME_HOURS);
		}
		CommunicationTemplate template = templateOfRule(templateCode, newChannel, Boolean.TRUE.equals(rule.getActive()));
		refuseDuplicate(rule.getEventType(), newChannel, template.getCode(), rule.getId());
		rule.setChannel(newChannel);
		rule.setTemplateCode(template.getCode());
		rule.setLifetimeHours(lifetimeHours);
		rule.setUpdatedBy(user);
		return rules.save(rule);
	}

	/** Step 5: the events a rule may answer, in the order the page lists them. */
	public static final List<String> EVENT_TYPES = Collections.unmodifiableList(Arrays.asList(
			CommunicationTypes.MEMBER_CREATED, CommunicationTypes.TICKET_VALIDATED));

	/** Step 5: the values of a new rule. */
	public static final class NewRuleForm {
		public String code;
		public String eventType;
		public String channel;
		public String templateCode;
		public Integer lifetimeHours;
		public Boolean active;
	}

	/**
	 * Step 5: a new rule for a loyalty member: code A-Z 0-9 _ (2 to 50, unique), a known event, a channel switched on, an
	 * active template of that channel, lifetime 1 to 720 hours, on or off (absent: on). One rule per event + channel +
	 * template (COMM_RULE_DUPLICATE). Never deleted: switched off.
	 */
	@Transactional
	public CommunicationRule createRule(NewRuleForm form, String user) {
		String code = form.code == null ? "" : form.code.trim().toUpperCase(Locale.ROOT);
		if (!code.matches("[A-Z0-9_]{2,50}")) {
			throw CommunicationException.refused("COMM_RULE_CODE_INVALID",
					"The code is 2 to 50 letters, digits or _ (e.g. MEMBER_CARD_SMS_AR)");
		}
		if (rules.findByCode(code).isPresent()) {
			throw CommunicationException.refused("COMM_RULE_CODE_TAKEN", "A rule " + code + " exists already", "code", code);
		}
		String eventType = upper(form.eventType);
		if (eventType == null || !EVENT_TYPES.contains(eventType)) {
			throw CommunicationException.refused("COMM_RULE_EVENT_UNKNOWN", "Unknown event " + form.eventType + ": "
					+ String.join(", ", EVENT_TYPES), "event", form.eventType == null ? "" : form.eventType);
		}
		String channel = channel(form.channel);
		requireChannelOn(channel);
		Integer lifetimeHours = form.lifetimeHours == null ? CommunicationRule.DEFAULT_LIFETIME_HOURS : form.lifetimeHours;
		if (lifetimeHours < MIN_LIFETIME_HOURS || lifetimeHours > MAX_LIFETIME_HOURS) {
			throw CommunicationException.refused("COMM_RULE_LIFETIME", "The lifetime is " + MIN_LIFETIME_HOURS + " to "
					+ MAX_LIFETIME_HOURS + " hours", "min", MIN_LIFETIME_HOURS, "max", MAX_LIFETIME_HOURS);
		}
		CommunicationTemplate template = templateOfRule(form.templateCode, channel, true);
		refuseDuplicate(eventType, channel, template.getCode(), null);
		CommunicationRule rule = new CommunicationRule();
		rule.setCode(code);
		rule.setEventType(eventType);
		rule.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		rule.setChannel(channel);
		rule.setTemplateCode(template.getCode());
		rule.setLifetimeHours(lifetimeHours);
		rule.setActive(form.active == null || form.active);
		rule.setCreatedBy(user);
		rule.setUpdatedBy(user);
		return rules.save(rule);
	}

	/** Step 5: one rule per event + channel + template; {@code exceptId}: the rule being changed. */
	private void refuseDuplicate(String eventType, String channel, String templateCode, Long exceptId) {
		rules.findAll(Sort.by("code")).stream()
				.filter(r -> exceptId == null || !exceptId.equals(r.getId()))
				.filter(r -> eventType.equals(r.getEventType()) && channel.equals(r.getChannel())
						&& templateCode.equals(r.getTemplateCode()))
				.findFirst().ifPresent(r -> {
					throw CommunicationException.refused("COMM_RULE_DUPLICATE", "The rule " + r.getCode()
							+ " already sends this template on this channel for this event", "rule", r.getCode());
				});
	}

	/** A rule on or off; on needs its channel on and its template there, active and on its channel. */
	@Transactional
	public CommunicationRule ruleActive(long id, boolean active, String user) {
		CommunicationRule rule = rule(id);
		if (active) {
			requireChannelOn(rule.getChannel());
			templateOfRule(rule.getTemplateCode(), rule.getChannel(), true);
		}
		rule.setActive(active);
		rule.setUpdatedBy(user);
		return rules.save(rule);
	}

	private CommunicationTemplate templateOfRule(String templateCode, String channel, boolean mustBeActive) {
		String code = blankToNull(templateCode);
		CommunicationTemplate template = code == null ? null : templates.findByCode(code).orElse(null);
		if (template == null) {
			throw CommunicationException.refused("COMM_TEMPLATE_NOT_FOUND", "Template " + code + " not found", "code",
					code == null ? "" : code);
		}
		if (!channel.equals(template.getChannel())) {
			throw CommunicationException.refused("COMM_RULE_TEMPLATE_CHANNEL", "Template " + template.getCode() + " is for "
					+ template.getChannel() + ", the rule for " + channel, "template", template.getCode(), "channel",
					template.getChannel());
		}
		if (mustBeActive && !Boolean.TRUE.equals(template.getActive())) {
			throw CommunicationException.refused("COMM_RULE_TEMPLATE_INACTIVE", "Template " + template.getCode()
					+ " is off: switch it on first", "template", template.getCode());
		}
		return template;
	}

	// ─── Templates ───────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public List<CommunicationTemplate> templates() {
		return templates.findAll(Sort.by("code"));
	}

	@Transactional(readOnly = true)
	public CommunicationTemplate template(long id) {
		return templates.findById(id).orElseThrow(() -> CommunicationException.notFound("COMM_TEMPLATE_NOT_FOUND",
				"Template " + id + " not found", "code", String.valueOf(id)));
	}

	/** The values a template's form sends. */
	public static final class TemplateForm {
		public String code;
		public String name;
		public String channel;
		public String languageCode;
		public String subject;
		public String body;
		public String providerTemplateName;
	}

	/** A new template, on: its code unique (A-Z, 0-9, _), on a channel switched on. */
	@Transactional
	public CommunicationTemplate createTemplate(TemplateForm form, String user) {
		String code = form.code == null ? "" : form.code.trim().toUpperCase(Locale.ROOT);
		if (!code.matches("[A-Z0-9_]{2,50}")) {
			throw CommunicationException.refused("COMM_TEMPLATE_CODE_INVALID",
					"The code is 2 to 50 letters, digits or _ (e.g. TICKET_THANKS_SMS_AR)");
		}
		if (templates.findByCode(code).isPresent()) {
			throw CommunicationException.refused("COMM_TEMPLATE_CODE_TAKEN", "A template " + code + " exists already",
					"code", code);
		}
		String channel = channel(form.channel);
		requireChannelOn(channel);
		CommunicationTemplate template = new CommunicationTemplate();
		template.setCode(code);
		fill(template, form, channel);
		template.setActive(true);
		template.setCreatedBy(user);
		template.setUpdatedBy(user);
		return templates.save(template);
	}

	/**
	 * A template changed (its code stays: the rules and the messages name it). Its channel changes only when no rule uses
	 * it, and only to a channel switched on.
	 */
	@Transactional
	public CommunicationTemplate updateTemplate(long id, TemplateForm form, String user) {
		CommunicationTemplate template = template(id);
		String channel = channel(form.channel);
		if (!channel.equals(template.getChannel())) {
			requireChannelOn(channel);
			Optional<CommunicationRule> using = rulesUsing(template.getCode(), false).stream().findFirst();
			if (using.isPresent()) {
				throw CommunicationException.refused("COMM_TEMPLATE_USED_CHANNEL", "Rule " + using.get().getCode()
						+ " uses this template: its channel cannot change", "rule", using.get().getCode());
			}
		}
		fill(template, form, channel);
		template.setUpdatedBy(user);
		return templates.save(template);
	}

	/** A template on (its channel on) or off (no active rule uses it). */
	@Transactional
	public CommunicationTemplate templateActive(long id, boolean active, String user) {
		CommunicationTemplate template = template(id);
		if (active) {
			requireChannelOn(template.getChannel());
		} else {
			Optional<CommunicationRule> using = rulesUsing(template.getCode(), true).stream().findFirst();
			if (using.isPresent()) {
				throw CommunicationException.refused("COMM_TEMPLATE_USED", "The active rule " + using.get().getCode()
						+ " uses this template: switch the rule off first", "rule", using.get().getCode());
			}
		}
		template.setActive(active);
		template.setUpdatedBy(user);
		return templates.save(template);
	}

	/**
	 * A body filled with the sample values: {text, length, encoding (SMS: GSM7 or UNICODE), parts (SMS only),
	 * unknownFields}. An unknown field stays as typed in the text.
	 */
	public Map<String, Object> preview(String channel, String body) {
		String text = CommunicationTemplates.preview(body, sample());
		Map<String, Object> preview = new LinkedHashMap<>();
		preview.put("text", text);
		if (CommunicationTypes.SMS.equals(upper(channel))) {
			SmsParts sms = SmsParts.of(text);
			preview.put("length", sms.getLength());
			preview.put("encoding", sms.getEncoding());
			preview.put("parts", sms.getParts());
		} else {
			preview.put("length", text.length());
			preview.put("encoding", null);
			preview.put("parts", null);
		}
		preview.put("unknownFields", CommunicationTemplates.unknownFields(body));
		return preview;
	}

	/** The fields a body may use, in order, each with its sample value (the buttons of the template window). */
	public List<Map<String, Object>> fields() {
		Map<String, Object> sample = sample();
		return CommunicationTemplates.FIELDS.stream().map(name -> {
			Map<String, Object> field = new LinkedHashMap<>();
			field.put("name", name);
			field.put("sample", CommunicationTemplates.preview("{" + name + "}", sample));
			return field;
		}).collect(Collectors.toList());
	}

	private Map<String, Object> sample() {
		Map<String, Object> sample = new LinkedHashMap<>(SAMPLE);
		String company = companyName.get();
		if (company != null && !company.trim().isEmpty()) {
			sample.put("companyName", company.trim());
		}
		return sample;
	}

	private void fill(CommunicationTemplate template, TemplateForm form, String channel) {
		String name = blankToNull(form.name);
		if (name == null) {
			throw CommunicationException.refused("COMM_TEMPLATE_NAME_REQUIRED", "The name is required");
		}
		tooLong("name", name, MAX_NAME);
		String language = form.languageCode == null ? "" : form.languageCode.trim().toLowerCase(Locale.ROOT);
		if (!LANGUAGES.contains(language)) {
			throw CommunicationException.refused("COMM_TEMPLATE_LANGUAGE", "The language is fr, ar or en");
		}
		String subject = null;
		if (CommunicationTypes.EMAIL.equals(channel)) {
			subject = blankToNull(form.subject);
			if (subject == null) {
				throw CommunicationException.refused("COMM_TEMPLATE_SUBJECT_REQUIRED", "An email template needs a subject");
			}
			tooLong("subject", subject, MAX_SUBJECT);
			unknownField(subject);
		}
		String body = form.body == null || form.body.trim().isEmpty() ? null : form.body;
		if (body == null) {
			throw CommunicationException.refused("COMM_TEMPLATE_BODY_REQUIRED", "The text is required");
		}
		tooLong("body", body, MAX_BODY);
		unknownField(body);
		String providerTemplate = CommunicationTypes.WHATSAPP.equals(channel) ? blankToNull(form.providerTemplateName) : null;
		tooLong("providerTemplateName", providerTemplate, MAX_PROVIDER_TEMPLATE);
		template.setName(name);
		template.setChannel(channel);
		template.setLanguageCode(language);
		template.setSubject(subject);
		template.setBody(body);
		template.setProviderTemplateName(providerTemplate);
	}

	private static void unknownField(String text) {
		List<String> unknown = CommunicationTemplates.unknownFields(text);
		if (!unknown.isEmpty()) {
			String fields = unknown.stream().map(f -> "{" + f + "}").collect(Collectors.joining(", "));
			throw CommunicationException.refused("COMM_TEMPLATE_UNKNOWN_FIELD", "Unknown field " + fields + ": the fields are "
					+ String.join(", ", CommunicationTemplates.FIELDS), "field", fields);
		}
	}

	private static void tooLong(String field, String value, int max) {
		if (value != null && value.length() > max) {
			throw CommunicationException.refused("COMM_TEMPLATE_TOO_LONG", "The " + field + " is at most " + max
					+ " characters", "field", field, "max", max);
		}
	}

	private List<CommunicationRule> rulesUsing(String templateCode, boolean activeOnly) {
		return rules.findAll(Sort.by("code")).stream().filter(r -> templateCode.equals(r.getTemplateCode()))
				.filter(r -> !activeOnly || Boolean.TRUE.equals(r.getActive())).collect(Collectors.toList());
	}

	// ─── Helpers ─────────────────────────────────────────────────────

	private String channel(String value) {
		String channel = upper(value);
		if (channel == null || !CommunicationTypes.CHANNELS.contains(channel)) {
			throw CommunicationException.refused("COMM_CHANNEL_UNKNOWN", "Unknown channel " + value + ": "
					+ String.join(", ", CommunicationTypes.CHANNELS), "channel", value == null ? "" : value);
		}
		return channel;
	}

	private void requireChannelOn(String channel) {
		if (!settings.isEnabled(channel)) {
			throw CommunicationException.refused("COMM_CHANNEL_OFF", "The channel " + channel + " is off (communication."
					+ channel.toLowerCase(Locale.ROOT) + ".enabled)", "channel", channel);
		}
	}

	private int lifetimeOf(CommunicationMessage message) {
		String key = message.getDedupKey();
		int colon = key == null ? -1 : key.indexOf(':');
		Optional<CommunicationRule> rule = colon < 0 ? Optional.empty() : rules.findByCode(key.substring(colon + 1));
		return rule.map(CommunicationRule::getLifetimeHours).filter(h -> h > 0)
				.orElse(CommunicationRule.DEFAULT_LIFETIME_HOURS);
	}

	private List<Long> ticketIds(String text) {
		return text == null || !tickets.isPresent() ? Collections.emptyList()
				: tickets.get().ticketIds(text, MAX_TICKETS_SEARCHED);
	}

	/** The members' names ("first last") by card, for the cards given (one query). */
	private Map<String, String> memberNames(List<String> cards) {
		Set<String> wanted = cards.stream().filter(c -> c != null && !c.isEmpty()).collect(Collectors.toSet());
		Map<String, String> names = new LinkedHashMap<>();
		if (!wanted.isEmpty()) {
			for (LoyaltyMember member : members.findByCardNumberIn(wanted)) {
				String name = ((member.getFirstName() == null ? "" : member.getFirstName()) + " "
						+ (member.getLastName() == null ? "" : member.getLastName())).trim();
				names.put(member.getCardNumber(), name);
			}
		}
		return names;
	}

	private static Map<String, Object> messageRow(CommunicationMessage m, String memberName) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", m.getId());
		row.put("createdAt", m.getCreatedAt());
		row.put("channel", m.getChannel());
		row.put("recipientRef", m.getRecipientRef());
		row.put("memberName", memberName);
		row.put("address", m.getAddress());
		row.put("templateCode", m.getTemplateCode());
		row.put("status", m.getStatus());
		row.put("attempts", m.getAttempts());
		row.put("sentAt", m.getSentAt());
		row.put("eventId", m.getEventId());
		return row;
	}

	private static Map<String, Object> eventRow(CommunicationEvent e, String memberName) {
		Map<String, Object> values;
		try {
			values = CommunicationTypes.values(e.getPayload());
		} catch (IllegalStateException unreadable) {
			values = Collections.singletonMap("payload", e.getPayload());
		}
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", e.getId());
		row.put("eventType", e.getEventType());
		row.put("occurredAt", e.getOccurredAt());
		row.put("createdAt", e.getCreatedAt());
		row.put("recipientRef", e.getRecipientRef());
		row.put("memberName", memberName);
		row.put("ticketNumber", values.get("ticketNumber"));
		row.put("originStoreCode", e.getOriginStoreCode());
		row.put("status", e.getStatus());
		row.put("attempts", e.getAttempts());
		row.put("reason", e.getLastError());
		row.put("processedAt", e.getProcessedAt());
		row.put("values", values);
		return row;
	}

	private static <T> Map<String, Object> page(Page<T> found, Function<T, Map<String, Object>> row) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("content", found.getContent().stream().map(row).collect(Collectors.toList()));
		result.put("totalElements", found.getTotalElements());
		result.put("totalPages", found.getTotalPages());
		result.put("number", found.getNumber());
		result.put("size", found.getSize());
		return result;
	}

	private static PageRequest pageRequest(int page, int size) {
		return PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE_SIZE)), Sort.by(Sort.Direction.DESC,
				"id"));
	}

	private static <E extends Enum<E>> E enumOf(Class<E> type, String name) {
		if (name == null) {
			return null;
		}
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String cut(String text, int max) {
		return text == null || text.length() <= max ? text : text.substring(0, max);
	}

	private static String escapeLike(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static String blankToNull(String text) {
		return text == null || text.trim().isEmpty() ? null : text.trim();
	}

	private static String upper(String text) {
		String value = blankToNull(text);
		return value == null || "ALL".equalsIgnoreCase(value) ? null : value.toUpperCase(Locale.ROOT);
	}

	/** A "yyyy-MM-ddTHH:mm[:ss]" or a day (its start, or its end for {@code end}); null when absent or unreadable. */
	static LocalDateTime dateTime(String text, boolean end) {
		String value = blankToNull(text);
		if (value == null) {
			return null;
		}
		try {
			return LocalDateTime.parse(value);
		} catch (DateTimeParseException e) {
			try {
				LocalDate day = LocalDate.parse(value);
				return end ? day.atTime(23, 59, 59, 999_000_000) : day.atStartOfDay();
			} catch (DateTimeParseException e2) {
				return null;
			}
		}
	}
}
