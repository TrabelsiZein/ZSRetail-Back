package com.digithink.zsretail.core.convention.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.convention.ConventionException;
import com.digithink.zsretail.core.convention.ConventionNumbers;
import com.digithink.zsretail.core.convention.enumeration.ConventionCardStatus;
import com.digithink.zsretail.core.convention.enumeration.ConventionRecognition;
import com.digithink.zsretail.core.convention.enumeration.ConventionState;
import com.digithink.zsretail.core.convention.enumeration.ConventionStatus;
import com.digithink.zsretail.core.convention.model.Convention;
import com.digithink.zsretail.core.convention.repository.ConventionCardRepository;
import com.digithink.zsretail.core.convention.repository.ConventionRepository;
import com.digithink.zsretail.core.eventlog.service.AdminEvents;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;

/**
 * 3.6.0, conventions step 1, part B3: the conventions' rules, the one way to change one (ConventionAPI; no generic CRUD).
 * docs/modules/conventions.md.
 * <ul>
 * <li>The code: trimmed, upper-cased, A-Z 0-9 "-", at most 30, unique; never changed after the creation (the copies
 * down go by code, step 6).</li>
 * <li>Name and organisation required (at most 150); a start date; the end empty or not before the start.</li>
 * <li>The three limits empty (no limit) or above 0.</li>
 * <li>Recognition, card prefix (empty: the code) and card digits (4 to 9, default 5) fixed once the convention has a
 * card, of any status.</li>
 * <li>Suspended and reactivated by their own call (never by the form); deleted only without a card (decision 9).</li>
 * <li>Decision 2: on a store whose promotions are the head office's every change answers 409; the reads stay.</li>
 * </ul>
 * The state shown (active, suspended, expired, upcoming) is worked out here from the server's date
 * ({@link Convention#stateOn}), so the list, the page and the till later read one rule.
 */
@Service
@ConditionalOnInstallation(Installation.CONVENTION)
public class ConventionService {

	public static final int MIN_CARD_DIGITS = 4;
	public static final int MAX_CARD_DIGITS = 9;
	static final int MAX_PAGE_SIZE = 100;

	private final ConventionRepository conventions;
	private final ConventionCardRepository cards;
	private final ConventionEvents events;
	private final BooleanSupplier ownedByHeadOffice;
	private final Supplier<LocalDate> today;

	@Autowired
	public ConventionService(ConventionRepository conventions, ConventionCardRepository cards, ConventionEvents events,
			ApplicationModeService mode) {
		this(conventions, cards, events, mode::isPromotionsOwnedByHeadOffice, LocalDate::now);
	}

	/** Everything given: the tests. */
	public ConventionService(ConventionRepository conventions, ConventionCardRepository cards, ConventionEvents events,
			BooleanSupplier ownedByHeadOffice, Supplier<LocalDate> today) {
		this.conventions = conventions;
		this.cards = cards;
		this.events = events;
		this.ownedByHeadOffice = ownedByHeadOffice;
		this.today = today;
	}

	/** The form of POST and PUT: every field (PUT replaces them all; an empty limit is no limit). */
	public static final class Form {
		public String code;
		public String name;
		public String organisation;
		public LocalDate startDate;
		public LocalDate endDate;
		/** CARDS (default) or NO_CARD. */
		public String recognition;
		public Integer maxUsesPerDay;
		public Integer maxUsesPerMonth;
		public Double maxAmountPerMonth;
		public String cardPrefix;
		public Integer cardDigits;
	}

	// ─── Reads ───────────────────────────────────────────────────────

	/**
	 * A page of conventions by code: the state of today (ACTIVE: in force today, SUSPENDED, EXPIRED, UPCOMING; the rule
	 * of {@link Convention#stateOn}) or all (empty, "all"), q in the code, the name or the organisation. {content,
	 * totalElements, totalPages, number, size}; each row as {@link #get}.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> list(String state, String q, int page, int size) {
		ConventionState wanted = state == null || state.trim().isEmpty() || "all".equalsIgnoreCase(state.trim()) ? null
				: stateOf(state);
		LocalDate day = today.get();
		Page<Convention> found = conventions.findAll(inState(wanted, day).and(containing(like(q))), PageRequest.of(
				Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE_SIZE)), Sort.by("code")));
		Map<Long, long[]> counts = counts(found.getContent().stream().map(Convention::getId).collect(Collectors.toList()));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("content", found.getContent().stream().map(c -> view(c, counts.get(c.getId()), day))
				.collect(Collectors.toList()));
		result.put("totalElements", found.getTotalElements());
		result.put("totalPages", found.getTotalPages());
		result.put("number", found.getNumber());
		result.put("size", found.getSize());
		return result;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(long id) {
		Convention c = find(id);
		return view(c, counts(java.util.Collections.singletonList(c.getId())).get(c.getId()), today.get());
	}

	// ─── Changes ─────────────────────────────────────────────────────

	@Transactional
	public Map<String, Object> create(Form form, String user) {
		managedHere();
		Form f = form == null ? new Form() : form;
		String code = ConventionNumbers.normalise(f.code);
		if (code == null) {
			throw ConventionException.refused("CONVENTION_CODE_REQUIRED", "A convention needs a code.");
		}
		if (code.length() > Convention.CODE_LENGTH) {
			throw tooLong("code", Convention.CODE_LENGTH);
		}
		if (!ConventionNumbers.wellFormed(code)) {
			throw ConventionException.refused("CONVENTION_CODE_INVALID", "The code '" + code + "' may only hold the letters"
					+ " A to Z, the digits 0 to 9 and '-'.", "code", code);
		}
		if (conventions.findByCode(code).isPresent()) {
			throw codeTaken(code);
		}
		Convention c = new Convention();
		c.setCode(code);
		apply(c, f, false);
		c.setStatus(ConventionStatus.ACTIVE);
		c.setOrigin(RecordOrigin.LOCAL);
		c.setLastCardSeq(0L);
		c.setActive(true);
		c.setCreatedBy(user);
		c.setUpdatedBy(user);
		Convention saved = saveNew(c);
		events.created(saved);
		return view(saved, null, today.get());
	}

	@Transactional
	public Map<String, Object> update(long id, Form form, String user) {
		managedHere();
		Convention c = find(id);
		Form f = form == null ? new Form() : form;
		String asked = ConventionNumbers.normalise(f.code);
		if (asked != null && !asked.equals(c.getCode())) {
			throw ConventionException.refused("CONVENTION_CODE_FIXED", "The code of the convention " + c.getCode()
					+ " cannot be changed.", "code", c.getCode(), "asked", asked);
		}
		Map<String, String> before = ConventionEvents.describe(c);
		apply(c, f, cards.existsByConventionId(c.getId()));
		if (!AdminEvents.changes(before, ConventionEvents.describe(c)).isEmpty()) {
			c.setUpdatedBy(user); // a save that changes nothing leaves the row as it was
		}
		Convention saved = conventions.save(c);
		events.updated(before, saved);
		return get(saved.getId());
	}

	/** {status: SUSPENDED | ACTIVE}; the same status again changes nothing and writes no event. */
	@Transactional
	public Map<String, Object> setStatus(long id, String status, String user) {
		managedHere();
		ConventionStatus wanted = statusOf(status);
		Convention c = find(id);
		if (c.getStatus() != wanted) {
			c.setStatus(wanted);
			c.setUpdatedBy(user);
			conventions.save(c);
			events.statusChanged(c, wanted == ConventionStatus.SUSPENDED);
		}
		return get(c.getId());
	}

	/** Only a convention without any card (decision 9): otherwise it is suspended. {id, code}. */
	@Transactional
	public Map<String, Object> delete(long id) {
		managedHere();
		Convention c = find(id);
		long held = cards.countByStatus(java.util.Collections.singletonList(c.getId())).stream()
				.mapToLong(row -> ((Number) row[2]).longValue()).sum();
		if (held > 0) {
			throw ConventionException.refused("CONVENTION_HAS_CARDS", "The convention " + c.getCode() + " has " + held
					+ (held > 1 ? " cards" : " card") + ": it cannot be deleted. Suspend it instead.", "code", c.getCode(),
					"cards", held);
		}
		conventions.delete(c);
		events.deleted(c);
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("id", c.getId());
		answer.put("code", c.getCode());
		return answer;
	}

	// ─── The rules ───────────────────────────────────────────────────

	/** The form's fields but the code and the status, checked; with cards, recognition, prefix and digits unchanged. */
	private void apply(Convention c, Form f, boolean hasCards) {
		String name = required(f.name, "name", "CONVENTION_NAME_REQUIRED", "A convention needs a name.");
		String organisation = required(f.organisation, "organisation", "CONVENTION_ORGANISATION_REQUIRED",
				"A convention needs the organisation that signed it.");
		if (f.startDate == null) {
			throw ConventionException.refused("CONVENTION_START_REQUIRED", "A convention needs a start date.");
		}
		if (f.endDate != null && f.endDate.isBefore(f.startDate)) {
			throw ConventionException.refused("CONVENTION_END_BEFORE_START", "The end date " + f.endDate
					+ " is before the start date " + f.startDate + ".", "start", f.startDate.toString(), "end",
					f.endDate.toString());
		}
		positive("maxUsesPerDay", f.maxUsesPerDay == null ? null : f.maxUsesPerDay.doubleValue());
		positive("maxUsesPerMonth", f.maxUsesPerMonth == null ? null : f.maxUsesPerMonth.doubleValue());
		positive("maxAmountPerMonth", f.maxAmountPerMonth);
		ConventionRecognition recognition = recognitionOf(f.recognition);
		String prefix = ConventionNumbers.normalise(f.cardPrefix);
		if (prefix != null && prefix.length() > Convention.PREFIX_LENGTH) {
			throw tooLong("cardPrefix", Convention.PREFIX_LENGTH);
		}
		if (prefix != null && !ConventionNumbers.wellFormed(prefix)) {
			throw ConventionException.refused("CONVENTION_PREFIX_INVALID", "The card prefix '" + prefix + "' may only hold"
					+ " the letters A to Z, the digits 0 to 9 and '-'.", "prefix", prefix);
		}
		int digits = f.cardDigits == null ? Convention.DEFAULT_CARD_DIGITS : f.cardDigits;
		if (digits < MIN_CARD_DIGITS || digits > MAX_CARD_DIGITS) {
			throw ConventionException.refused("CONVENTION_CARD_DIGITS", "A generated card number has " + MIN_CARD_DIGITS
					+ " to " + MAX_CARD_DIGITS + " digits.", "min", MIN_CARD_DIGITS, "max", MAX_CARD_DIGITS);
		}
		if (hasCards) {
			if (recognition != c.getRecognition()) {
				throw settingsFixed(c, "recognition");
			}
			// the prefix in effect: empty is the code
			if (!Objects.equals(prefix == null ? c.getCode() : prefix,
					c.getCardPrefix() == null ? c.getCode() : c.getCardPrefix())) {
				throw settingsFixed(c, "cardPrefix");
			}
			if (!Objects.equals(digits, c.getCardDigits())) {
				throw settingsFixed(c, "cardDigits");
			}
		}
		c.setName(name);
		c.setOrganisation(organisation);
		c.setStartDate(f.startDate);
		c.setEndDate(f.endDate);
		c.setRecognition(recognition);
		c.setMaxUsesPerDay(f.maxUsesPerDay);
		c.setMaxUsesPerMonth(f.maxUsesPerMonth);
		c.setMaxAmountPerMonth(f.maxAmountPerMonth);
		c.setCardPrefix(prefix);
		c.setCardDigits(digits);
	}

	/** Decision 2: the conventions follow the promotions. */
	private void managedHere() {
		if (ownedByHeadOffice.getAsBoolean()) {
			throw ConventionException.ownedByHeadOffice();
		}
	}

	private Convention find(long id) {
		return conventions.findById(id).orElseThrow(() -> ConventionException.notFound(id));
	}

	/** Saved and flushed: two administrators creating the same code at once, the second meets the unique key. */
	private Convention saveNew(Convention c) {
		try {
			return conventions.saveAndFlush(c);
		} catch (DataIntegrityViolationException taken) {
			throw codeTaken(c.getCode());
		}
	}

	private static String required(String value, String field, String code, String message) {
		String v = value == null ? null : value.trim();
		if (v == null || v.isEmpty()) {
			throw ConventionException.refused(code, message);
		}
		if (v.length() > Convention.NAME_LENGTH) {
			throw tooLong(field, Convention.NAME_LENGTH);
		}
		return v;
	}

	private static void positive(String field, Double value) {
		if (value != null && (value.isNaN() || value.isInfinite() || value <= 0)) {
			throw ConventionException.refused("CONVENTION_LIMIT_INVALID", "The limit " + field + " is empty (no limit) or"
					+ " above 0.", "field", field);
		}
	}

	private static ConventionRecognition recognitionOf(String value) {
		if (value == null || value.trim().isEmpty()) {
			return ConventionRecognition.CARDS;
		}
		try {
			return ConventionRecognition.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException unknown) {
			throw ConventionException.refused("CONVENTION_RECOGNITION_UNKNOWN", "Unknown recognition '" + value.trim()
					+ "': CARDS or NO_CARD.", "value", value.trim());
		}
	}

	private static ConventionStatus statusOf(String value) {
		try {
			return ConventionStatus.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException unknown) {
			throw ConventionException.refused("CONVENTION_STATUS_UNKNOWN", "Unknown status '" + value + "': ACTIVE or"
					+ " SUSPENDED.", "value", value);
		}
	}

	private static ConventionException tooLong(String field, int max) {
		return ConventionException.refused("CONVENTION_TOO_LONG", "The field " + field + " holds at most " + max
				+ " characters.", "field", field, "max", max);
	}

	private static ConventionException codeTaken(String code) {
		return ConventionException.refused("CONVENTION_CODE_TAKEN", "A convention already has the code " + code + ".",
				"code", code);
	}

	private static ConventionException settingsFixed(Convention c, String field) {
		return ConventionException.refused("CONVENTION_CARD_SETTINGS_FIXED", "The convention " + c.getCode() + " has"
				+ " cards: its " + field + " cannot change any more.", "field", field, "code", c.getCode());
	}

	private static ConventionState stateOf(String value) {
		try {
			return ConventionState.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException unknown) {
			throw ConventionException.refused("CONVENTION_STATE_UNKNOWN", "Unknown state '" + value.trim() + "': ACTIVE,"
					+ " SUSPENDED, EXPIRED, UPCOMING or all.", "value", value.trim());
		}
	}

	/**
	 * The state of {@link Convention#stateOn} as a query (null: any): SUSPENDED as stored; else, the status ACTIVE and
	 * UPCOMING the start after the day, EXPIRED the end before it, ACTIVE within them (both days included).
	 */
	static Specification<Convention> inState(ConventionState state, LocalDate day) {
		return (root, query, cb) -> {
			if (state == null) {
				return cb.conjunction();
			}
			if (state == ConventionState.SUSPENDED) {
				return cb.equal(root.get("status"), ConventionStatus.SUSPENDED);
			}
			Predicate active = cb.equal(root.get("status"), ConventionStatus.ACTIVE);
			Path<LocalDate> start = root.get("startDate");
			Path<LocalDate> end = root.get("endDate");
			switch (state) {
			case UPCOMING:
				return cb.and(active, cb.greaterThan(start, day));
			case EXPIRED:
				return cb.and(active, cb.lessThan(end, day));
			default:
				return cb.and(active, cb.lessThanOrEqualTo(start, day), cb.or(cb.isNull(end),
						cb.greaterThanOrEqualTo(end, day)));
			}
		};
	}

	/** The text (a {@link #like} pattern; null: any) in the code, the name or the organisation, any case. */
	private static Specification<Convention> containing(String pattern) {
		return (root, query, cb) -> pattern == null ? cb.conjunction()
				: cb.or(cb.like(cb.lower(root.get("code")), pattern, '\\'),
						cb.like(cb.lower(root.get("name")), pattern, '\\'),
						cb.like(cb.lower(root.get("organisation")), pattern, '\\'));
	}

	/** "%text%" in lower case, its LIKE characters escaped by \; null when empty. Also the cards' search. */
	public static String like(String q) {
		if (q == null || q.trim().isEmpty()) {
			return null;
		}
		String text = q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
				.replace("[", "\\[");
		return "%" + text + "%";
	}

	/** Each convention's [active, blocked] cards. */
	private Map<Long, long[]> counts(List<Long> ids) {
		Map<Long, long[]> counts = new HashMap<>();
		if (ids.isEmpty()) {
			return counts;
		}
		for (Object[] row : cards.countByStatus(ids)) {
			long[] pair = counts.computeIfAbsent(((Number) row[0]).longValue(), id -> new long[2]);
			pair[row[1] == ConventionCardStatus.BLOCKED ? 1 : 0] += ((Number) row[2]).longValue();
		}
		return counts;
	}

	/** What the list and the page show: the stored values, the state of today, the cards, the card settings fixed. */
	private static Map<String, Object> view(Convention c, long[] cardCounts, LocalDate day) {
		long active = cardCounts == null ? 0 : cardCounts[0];
		long blocked = cardCounts == null ? 0 : cardCounts[1];
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", c.getId());
		m.put("code", c.getCode());
		m.put("name", c.getName());
		m.put("organisation", c.getOrganisation());
		m.put("startDate", c.getStartDate());
		m.put("endDate", c.getEndDate());
		m.put("status", c.getStatus());
		m.put("state", c.stateOn(day));
		m.put("recognition", c.getRecognition());
		m.put("maxUsesPerDay", c.getMaxUsesPerDay());
		m.put("maxUsesPerMonth", c.getMaxUsesPerMonth());
		m.put("maxAmountPerMonth", c.getMaxAmountPerMonth());
		m.put("cardPrefix", c.getCardPrefix());
		m.put("cardDigits", c.getCardDigits());
		m.put("origin", c.getOrigin());
		m.put("cardsActive", active);
		m.put("cardsBlocked", blocked);
		m.put("cardSettingsFixed", active + blocked > 0);
		m.put("createdAt", c.getCreatedAt());
		m.put("createdBy", c.getCreatedBy());
		m.put("updatedAt", c.getUpdatedAt());
		m.put("updatedBy", c.getUpdatedBy());
		return m;
	}
}
