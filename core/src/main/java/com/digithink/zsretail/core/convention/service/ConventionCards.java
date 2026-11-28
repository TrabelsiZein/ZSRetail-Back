package com.digithink.zsretail.core.convention.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.convention.ConventionException;
import com.digithink.zsretail.core.convention.ConventionNumbers;
import com.digithink.zsretail.core.convention.enumeration.ConventionCardStatus;
import com.digithink.zsretail.core.convention.enumeration.ConventionRecognition;
import com.digithink.zsretail.core.convention.model.Convention;
import com.digithink.zsretail.core.convention.model.ConventionCard;
import com.digithink.zsretail.core.convention.repository.ConventionCardBatchInsert;
import com.digithink.zsretail.core.convention.repository.ConventionCardBatchInsert.NewCard;
import com.digithink.zsretail.core.convention.repository.ConventionCardRepository;
import com.digithink.zsretail.core.convention.repository.ConventionRepository;

/**
 * 3.6.0, conventions step 1, part B4: the cards of a convention (ConventionAPI, /conventions/{id}/cards). A card is never
 * deleted, only blocked; a blocked card can be unblocked. docs/modules/conventions.md, "The cards".
 * <ul>
 * <li>Generation (decisions 4 and 6): a convention with recognition CARDS, not expired (suspended or upcoming: yes, the
 * cards are prepared before); 1 to 5,000 numbers, all or nothing. Number = the prefix in effect + "-" + the counter
 * on its digits; the counter (last_card_seq) read under the convention's row lock, so two generations of one
 * convention follow each other; a number already used (a card of any convention, an item code, an item barcode, a
 * loyalty card number) is skipped and counted; the counter moves past it. Inserted in JDBC batches.</li>
 * <li>One card by hand: the same convention rules, the number typed (A-Z, 0-9, "-", at most 40, trimmed and
 * upper-cased); a number already a card is refused with its convention, one the till reads as an item or a loyalty
 * card is refused with what it is.</li>
 * <li>Block / unblock: one card or several of the convention in one call (at most 500); the cards already in the asked
 * status are left as they are, without an event; any card not of this convention refuses the whole call.</li>
 * <li>The holder: name, phone, employee number, all optional, trimmed, never checked.</li>
 * <li>Decision 2: every change answers 409 on a store whose promotions are the head office's; the list stays.</li>
 * </ul>
 */
@Service
@ConditionalOnInstallation(Installation.CONVENTION)
public class ConventionCards {

	public static final int MAX_GENERATION = 5000;
	public static final int MAX_STATUS_CHANGE = 500;
	/** The numbers skipped named in a generation's answer (all are counted). */
	static final int SKIPPED_SHOWN = 50;

	/** How a generation's cards reach the table: JDBC batches (ConventionCardBatchInsert); the tests' session. */
	public interface Inserts {
		void insert(long conventionId, List<NewCard> cards, String user, LocalDateTime at);
	}

	private final ConventionRepository conventions;
	private final ConventionCardRepository cards;
	private final ConventionEvents events;
	private final Inserts inserts;
	private final BooleanSupplier ownedByHeadOffice;
	private final Supplier<LocalDate> today;

	@Autowired
	public ConventionCards(ConventionRepository conventions, ConventionCardRepository cards, ConventionEvents events,
			ApplicationModeService mode, JdbcTemplate jdbc) {
		this(conventions, cards, events, new ConventionCardBatchInsert(jdbc)::insert, mode::isPromotionsOwnedByHeadOffice,
				LocalDate::now);
	}

	/** Everything given: the tests. */
	public ConventionCards(ConventionRepository conventions, ConventionCardRepository cards, ConventionEvents events,
			Inserts inserts, BooleanSupplier ownedByHeadOffice, Supplier<LocalDate> today) {
		this.conventions = conventions;
		this.cards = cards;
		this.events = events;
		this.inserts = inserts;
		this.ownedByHeadOffice = ownedByHeadOffice;
		this.today = today;
	}

	/** One card by hand: {number, holderName, holderPhone, holderEmployeeNo}; the holder's change: the three. */
	public static final class CardForm {
		public String number;
		public String holderName;
		public String holderPhone;
		public String holderEmployeeNo;
	}

	/** {ids, status: BLOCKED | ACTIVE}. */
	public static final class StatusForm {
		public List<Long> ids;
		public String status;
	}

	// ─── The list ────────────────────────────────────────────────────

	/**
	 * A page of the convention's cards by number: status ACTIVE / BLOCKED or all (empty, "all"), exported true / false
	 * (null: any), q in the number, the holder's name or employee number. {content, totalElements, totalPages, number,
	 * size}.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> list(long conventionId, String status, String q, Boolean exported, int page, int size) {
		find(conventionId);
		ConventionCardStatus wanted = status == null || status.trim().isEmpty() || "all".equalsIgnoreCase(status.trim())
				? null : statusOf(status);
		String pattern = ConventionService.like(q);
		Specification<ConventionCard> spec = (root, query, cb) -> cb.equal(root.get("convention").get("id"), conventionId);
		if (wanted != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), wanted));
		}
		if (exported != null) {
			spec = spec.and((root, query, cb) -> exported ? cb.isNotNull(root.get("exportedAt"))
					: cb.isNull(root.get("exportedAt")));
		}
		if (pattern != null) {
			spec = spec.and((root, query, cb) -> cb.or(cb.like(cb.lower(root.get("cardNumber")), pattern, '\\'),
					cb.like(cb.lower(root.get("holderName")), pattern, '\\'),
					cb.like(cb.lower(root.get("holderEmployeeNo")), pattern, '\\')));
		}
		Page<ConventionCard> found = cards.findAll(spec, PageRequest.of(Math.max(0, page),
				Math.max(1, Math.min(size, ConventionService.MAX_PAGE_SIZE)), Sort.by("cardNumber")));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("content", found.getContent().stream().map(ConventionCards::view).collect(Collectors.toList()));
		result.put("totalElements", found.getTotalElements());
		result.put("totalPages", found.getTotalPages());
		result.put("number", found.getNumber());
		result.put("size", found.getSize());
		return result;
	}

	// ─── Generation ──────────────────────────────────────────────────

	/**
	 * {count}: count new cards, all or nothing. {conventionId, generated, skipped, skippedNumbers (the first 50), first,
	 * last, lastCardSeq}.
	 */
	@Transactional
	public Map<String, Object> generate(long conventionId, Integer count, String user) {
		managedHere();
		if (count == null || count < 1 || count > MAX_GENERATION) {
			throw ConventionException.refused("CONVENTION_GENERATE_COUNT", "A generation makes 1 to " + MAX_GENERATION
					+ " cards.", "min", 1, "max", MAX_GENERATION);
		}
		Convention c = conventions.lockById(conventionId).orElseThrow(() -> ConventionException.notFound(conventionId));
		cardsAllowed(c, today.get());
		String start = c.prefixInEffect() + "-";
		int digits = c.getCardDigits();
		long max = (long) Math.pow(10, digits) - 1;
		Set<String> taken = taken(start + "%");
		List<String> numbers = new ArrayList<>(count);
		List<String> skipped = new ArrayList<>();
		long seq = c.getLastCardSeq() == null ? 0 : c.getLastCardSeq();
		while (numbers.size() < count) {
			seq++;
			if (seq > max) {
				throw ConventionException.refused("CONVENTION_NUMBERS_EXHAUSTED", "The convention " + c.getCode() + " has "
						+ numbers.size() + " free numbers left on " + digits + " digits, not " + count + ".", "code",
						c.getCode(), "left", numbers.size(), "digits", digits);
			}
			String number = start + pad(seq, digits);
			(taken.contains(number) ? skipped : numbers).add(number);
		}
		LocalDateTime now = LocalDateTime.now();
		try {
			inserts.insert(c.getId(), numbers.stream().map(NewCard::numbered).collect(Collectors.toList()), user, now);
		} catch (DataIntegrityViolationException meanwhile) {
			// a number taken between the read and the insert (a card added by hand, another convention's generation)
			throw new ConventionException(409, "CONVENTION_CARDS_BUSY", "A card number was taken meanwhile: nothing was"
					+ " generated, try again.", "code", c.getCode());
		}
		c.setLastCardSeq(seq);
		c.setUpdatedBy(user);
		conventions.save(c);
		events.cardsGenerated(c, numbers.size(), numbers.get(0), numbers.get(numbers.size() - 1), skipped.size());
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("conventionId", c.getId());
		answer.put("generated", numbers.size());
		answer.put("skipped", skipped.size());
		answer.put("skippedNumbers", skipped.subList(0, Math.min(skipped.size(), SKIPPED_SHOWN)));
		answer.put("first", numbers.get(0));
		answer.put("last", numbers.get(numbers.size() - 1));
		answer.put("lastCardSeq", seq);
		return answer;
	}

	// ─── One card by hand ────────────────────────────────────────────

	@Transactional
	public Map<String, Object> add(long conventionId, CardForm form, String user) {
		managedHere();
		Convention c = find(conventionId);
		cardsAllowed(c, today.get());
		CardForm f = form == null ? new CardForm() : form;
		String number = ConventionNumbers.normalise(f.number);
		if (number == null) {
			throw ConventionException.refused("CONVENTION_CARD_NUMBER_REQUIRED", "A card needs its number.");
		}
		if (number.length() > ConventionCard.NUMBER_LENGTH) {
			throw tooLong("cardNumber", ConventionCard.NUMBER_LENGTH);
		}
		if (!ConventionNumbers.wellFormed(number)) {
			throw ConventionException.refused("CONVENTION_CARD_NUMBER_INVALID", "The card number '" + number + "' may only"
					+ " hold the letters A to Z, the digits 0 to 9 and '-'.", "number", number);
		}
		cards.findByCardNumber(number).ifPresent(existing -> {
			throw numberTaken(number, existing.getConvention().getCode());
		});
		String kind = reservedKind(number);
		if (kind != null) {
			throw ConventionException.refused("CONVENTION_CARD_NUMBER_RESERVED", "The number " + number + " is already "
					+ ("ITEM_CODE".equals(kind) ? "an item code" : "ITEM_BARCODE".equals(kind) ? "an item barcode"
							: "a loyalty card number") + ": the till would not know which one is scanned.", "number",
					number, "kind", kind);
		}
		ConventionCard card = new ConventionCard();
		card.setConvention(c);
		card.setCardNumber(number);
		card.setStatus(ConventionCardStatus.ACTIVE);
		applyHolder(card, f);
		card.setActive(true);
		card.setCreatedBy(user);
		card.setUpdatedBy(user);
		ConventionCard saved;
		try {
			saved = cards.saveAndFlush(card);
		} catch (DataIntegrityViolationException meanwhile) {
			throw numberTaken(number, null);
		}
		events.cardAdded(c, saved);
		return view(saved);
	}

	// ─── Block, unblock, the holder ──────────────────────────────────

	/** {ids, status}: {status, changed, unchanged}; one event per card changed. */
	@Transactional
	public Map<String, Object> setStatus(long conventionId, StatusForm form, String user) {
		managedHere();
		Convention c = find(conventionId);
		ConventionCardStatus wanted = statusOf(form == null ? null : form.status);
		Set<Long> ids = form == null || form.ids == null ? new LinkedHashSet<>()
				: form.ids.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
		if (ids.isEmpty() || ids.size() > MAX_STATUS_CHANGE) {
			throw ConventionException.refused("CONVENTION_CARD_IDS", "Block or unblock 1 to " + MAX_STATUS_CHANGE
					+ " cards in one call.", "min", 1, "max", MAX_STATUS_CHANGE);
		}
		List<ConventionCard> found = cards.findByConventionIdAndIdIn(c.getId(), ids);
		if (found.size() < ids.size()) {
			Set<Long> missing = new LinkedHashSet<>(ids);
			found.forEach(card -> missing.remove(card.getId()));
			throw cardNotFound(new ArrayList<>(missing));
		}
		List<ConventionCard> changed = found.stream().filter(card -> card.getStatus() != wanted)
				.collect(Collectors.toList());
		for (ConventionCard card : changed) {
			card.setStatus(wanted);
			card.setUpdatedBy(user);
		}
		cards.saveAll(changed);
		changed.stream().sorted(java.util.Comparator.comparing(ConventionCard::getCardNumber))
				.forEach(card -> events.cardStatusChanged(c, card, wanted == ConventionCardStatus.BLOCKED));
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("status", wanted);
		answer.put("changed", changed.size());
		answer.put("unchanged", found.size() - changed.size());
		return answer;
	}

	/** The holder's three fields, all optional (blank is empty), never checked but their lengths. */
	@Transactional
	public Map<String, Object> holder(long conventionId, long cardId, CardForm form, String user) {
		managedHere();
		Convention c = find(conventionId);
		ConventionCard card = cards.findByIdAndConventionId(cardId, c.getId()).orElseThrow(() -> cardNotFound(
				java.util.Collections.singletonList(cardId)));
		String nameBefore = card.getHolderName();
		String employeeNoBefore = card.getHolderEmployeeNo();
		String phoneBefore = card.getHolderPhone();
		applyHolder(card, form == null ? new CardForm() : form);
		boolean phoneChanged = !Objects.equals(phoneBefore, card.getHolderPhone());
		if (phoneChanged || !Objects.equals(nameBefore, card.getHolderName())
				|| !Objects.equals(employeeNoBefore, card.getHolderEmployeeNo())) {
			card.setUpdatedBy(user);
			cards.save(card);
			events.holderChanged(c, card, nameBefore, employeeNoBefore, phoneChanged);
		}
		return view(card);
	}

	// ─── The export for the card printer (part B5) ───────────────────

	/** An Excel file to send: its name, its bytes, the cards in it. */
	public static final class ExcelFile {
		public final String name;
		public final byte[] bytes;
		public final int count;

		public ExcelFile(String name, byte[] bytes, int count) {
			this.name = name;
			this.bytes = bytes;
			this.count = count;
		}
	}

	/**
	 * Decision 8: the convention's cards of this status (empty: ACTIVE, the default; "all"), only those never exported
	 * when asked, by number, in one .xlsx (number, convention code, name, organisation, holder name, employee number:
	 * never the phone). Their first export is stamped (exported_at; a card exported before keeps its date); one
	 * CONVENTION_CARDS_EXPORTED with the count, an empty export included (the file then holds its header only).
	 */
	@Transactional
	public ExcelFile export(long conventionId, String status, boolean onlyNeverExported, String user) {
		managedHere();
		Convention c = conventions.lockById(conventionId).orElseThrow(() -> ConventionException.notFound(conventionId));
		ConventionCardStatus wanted = status == null || status.trim().isEmpty() ? ConventionCardStatus.ACTIVE
				: "all".equalsIgnoreCase(status.trim()) ? null : statusOf(status);
		Specification<ConventionCard> spec = (root, query, cb) -> cb.equal(root.get("convention").get("id"), c.getId());
		if (wanted != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), wanted));
		}
		if (onlyNeverExported) {
			spec = spec.and((root, query, cb) -> cb.isNull(root.get("exportedAt")));
		}
		List<ConventionCard> found = cards.findAll(spec, Sort.by("cardNumber"));
		byte[] bytes = ConventionCardExcel.export(c.getCode(), c.getName(), c.getOrganisation(), found.stream()
				.map(card -> new ConventionCardExcel.ExportRow(card.getCardNumber(), card.getHolderName(),
						card.getHolderEmployeeNo()))
				.collect(Collectors.toList()));
		LocalDateTime now = LocalDateTime.now();
		List<Long> ids = found.stream().map(ConventionCard::getId).collect(Collectors.toList());
		for (int i = 0; i < ids.size(); i += ConventionCardImport.CHUNK) {
			cards.stampExported(ids.subList(i, Math.min(ids.size(), i + ConventionCardImport.CHUNK)), now);
		}
		events.cardsExported(c, found.size(), wanted == null ? "toutes les cartes" : wanted == ConventionCardStatus.ACTIVE
				? "cartes actives" : "cartes bloquées", onlyNeverExported);
		return new ExcelFile(ConventionCardExcel.exportName(c.getCode(), now), bytes, found.size());
	}

	// ─── The rules ───────────────────────────────────────────────────

	private void managedHere() {
		if (ownedByHeadOffice.getAsBoolean()) {
			throw ConventionException.ownedByHeadOffice();
		}
	}

	private Convention find(long id) {
		return conventions.findById(id).orElseThrow(() -> ConventionException.notFound(id));
	}

	/** Decision 4: cards only with CARDS; point 4 of B4: not on an expired convention (suspended, upcoming: yes). */
	static void cardsAllowed(Convention c, LocalDate day) {
		if (c.getRecognition() != ConventionRecognition.CARDS) {
			throw ConventionException.refused("CONVENTION_NO_CARDS", "The convention " + c.getCode() + " has no cards: its"
					+ " members give their number at the till.", "code", c.getCode());
		}
		if (c.getEndDate() != null && c.getEndDate().isBefore(day)) {
			throw ConventionException.refused("CONVENTION_EXPIRED", "The convention " + c.getCode() + " ended on "
					+ c.getEndDate() + ": no new card.", "code", c.getCode(), "end", c.getEndDate().toString());
		}
	}

	/** Every number matching the pattern already used: the cards of every convention, the items, the loyalty cards. */
	private Set<String> taken(String pattern) {
		Set<String> taken = new HashSet<>();
		addUpper(taken, cards.cardNumbersLike(pattern));
		addUpper(taken, cards.itemCodesLike(pattern));
		addUpper(taken, cards.itemOwnBarcodesLike(pattern));
		addUpper(taken, cards.itemBarcodesLike(pattern));
		addUpper(taken, cards.loyaltyCardsLike(pattern));
		return taken;
	}

	/** ITEM_CODE, ITEM_BARCODE or LOYALTY_CARD when the till reads this number already; null when free. */
	private String reservedKind(String number) {
		if (matches(cards.itemCodesLike(number), number)) {
			return "ITEM_CODE";
		}
		if (matches(cards.itemOwnBarcodesLike(number), number) || matches(cards.itemBarcodesLike(number), number)) {
			return "ITEM_BARCODE";
		}
		return matches(cards.loyaltyCardsLike(number), number) ? "LOYALTY_CARD" : null;
	}

	private static boolean matches(Collection<String> found, String number) {
		return found.stream().anyMatch(v -> v != null && number.equals(v.trim().toUpperCase(Locale.ROOT)));
	}

	private static void addUpper(Set<String> into, Collection<String> values) {
		for (String v : values) {
			if (v != null) {
				into.add(v.trim().toUpperCase(Locale.ROOT));
			}
		}
	}

	private static void applyHolder(ConventionCard card, CardForm f) {
		card.setHolderName(text(f.holderName, "holderName", ConventionCard.HOLDER_NAME_LENGTH));
		card.setHolderPhone(text(f.holderPhone, "holderPhone", ConventionCard.HOLDER_PHONE_LENGTH));
		card.setHolderEmployeeNo(text(f.holderEmployeeNo, "holderEmployeeNo", ConventionCard.HOLDER_EMPLOYEE_NO_LENGTH));
	}

	private static String text(String value, String field, int max) {
		String v = value == null ? null : value.trim();
		if (v == null || v.isEmpty()) {
			return null;
		}
		if (v.length() > max) {
			throw tooLong(field, max);
		}
		return v;
	}

	private static ConventionCardStatus statusOf(String value) {
		try {
			return ConventionCardStatus.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException unknown) {
			throw ConventionException.refused("CONVENTION_CARD_STATUS_UNKNOWN", "Unknown card status '" + value
					+ "': ACTIVE or BLOCKED.", "value", value);
		}
	}

	private static String pad(long seq, int digits) {
		String s = Long.toString(seq);
		StringBuilder b = new StringBuilder(digits);
		for (int i = s.length(); i < digits; i++) {
			b.append('0');
		}
		return b.append(s).toString();
	}

	private static ConventionException tooLong(String field, int max) {
		return ConventionException.refused("CONVENTION_TOO_LONG", "The field " + field + " holds at most " + max
				+ " characters.", "field", field, "max", max);
	}

	private static ConventionException numberTaken(String number, String conventionCode) {
		return ConventionException.refused("CONVENTION_CARD_NUMBER_TAKEN", "The card number " + number + " is already"
				+ " a card" + (conventionCode == null ? "" : " of the convention " + conventionCode) + ".", "number",
				number, "convention", conventionCode);
	}

	private static ConventionException cardNotFound(List<Long> ids) {
		List<Long> shown = ids.subList(0, Math.min(ids.size(), 20));
		return new ConventionException(404, "CONVENTION_CARD_NOT_FOUND", "No card " + shown + " in this convention.",
				"ids", shown);
	}

	/** A card as the Cards tab shows it. */
	static Map<String, Object> view(ConventionCard card) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", card.getId());
		m.put("cardNumber", card.getCardNumber());
		m.put("status", card.getStatus());
		m.put("holderName", card.getHolderName());
		m.put("holderPhone", card.getHolderPhone());
		m.put("holderEmployeeNo", card.getHolderEmployeeNo());
		m.put("exportedAt", card.getExportedAt());
		m.put("createdAt", card.getCreatedAt());
		m.put("createdBy", card.getCreatedBy());
		m.put("updatedAt", card.getUpdatedAt());
		m.put("updatedBy", card.getUpdatedBy());
		return m;
	}
}
