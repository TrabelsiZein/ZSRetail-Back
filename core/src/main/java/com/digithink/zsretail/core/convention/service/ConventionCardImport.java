package com.digithink.zsretail.core.convention.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.convention.ConventionException;
import com.digithink.zsretail.core.convention.ConventionNumbers;
import com.digithink.zsretail.core.convention.model.Convention;
import com.digithink.zsretail.core.convention.model.ConventionCard;
import com.digithink.zsretail.core.convention.repository.ConventionCardBatchInsert;
import com.digithink.zsretail.core.convention.repository.ConventionCardBatchInsert.NewCard;
import com.digithink.zsretail.core.convention.repository.ConventionCardRepository;
import com.digithink.zsretail.core.convention.repository.ConventionRepository;
import com.digithink.zsretail.core.convention.service.ConventionCardExcel.Line;

/**
 * 3.6.0, conventions step 1, part B5 (decision 7): a convention's cards imported from an .xlsx file
 * (ConventionCardExcel.read: the header, the columns, the whole-file refusals). The good lines are kept, the refused
 * ones answered with their Excel row, the number as read and a reason; a line is never refused for a missing holder.
 * The number rules are the card's by hand (ConventionCards.add); a number twice in the file: the first kept, the next
 * refused. Only for a convention with recognition CARDS, not expired; the convention's row locked (one import or
 * generation at a time); the cards inserted in JDBC batches; one CONVENTION_CARDS_IMPORTED per run. The counter
 * last_card_seq does not move (a later generation skips the numbers imported). docs/modules/conventions.md, "Import".
 */
@Service
@ConditionalOnInstallation(Installation.CONVENTION)
public class ConventionCardImport {

	/** The numbers sent per query (SQL Server takes 2,100 parameters at most). */
	static final int CHUNK = 1000;

	private final ConventionRepository conventions;
	private final ConventionCardRepository cards;
	private final ConventionEvents events;
	private final ConventionCards.Inserts inserts;
	private final BooleanSupplier ownedByHeadOffice;
	private final Supplier<LocalDate> today;

	@Autowired
	public ConventionCardImport(ConventionRepository conventions, ConventionCardRepository cards,
			ConventionEvents events, ApplicationModeService mode, JdbcTemplate jdbc) {
		this(conventions, cards, events, new ConventionCardBatchInsert(jdbc)::insert, mode::isPromotionsOwnedByHeadOffice,
				LocalDate::now);
	}

	/** Everything given: the tests. */
	public ConventionCardImport(ConventionRepository conventions, ConventionCardRepository cards,
			ConventionEvents events, ConventionCards.Inserts inserts, BooleanSupplier ownedByHeadOffice,
			Supplier<LocalDate> today) {
		this.conventions = conventions;
		this.cards = cards;
		this.events = events;
		this.inserts = inserts;
		this.ownedByHeadOffice = ownedByHeadOffice;
		this.today = today;
	}

	/** A line kept so far: its row, its number, its holder's fields checked. */
	private static final class Candidate {
		final Line line;
		final NewCard card;

		Candidate(Line line, NewCard card) {
			this.line = line;
			this.card = card;
		}
	}

	/**
	 * The file's lines imported into the convention: {conventionId, lines, accepted, refused: [{row, number, reason,
	 * ...}]} (refused in the file's order). The whole-file refusals throw (no event).
	 */
	@Transactional
	public Map<String, Object> run(long conventionId, byte[] file, String fileName, String user) {
		if (ownedByHeadOffice.getAsBoolean()) {
			throw ConventionException.ownedByHeadOffice();
		}
		Convention c = conventions.lockById(conventionId).orElseThrow(() -> ConventionException.notFound(conventionId));
		ConventionCards.cardsAllowed(c, today.get());
		List<Line> lines = ConventionCardExcel.read(file);

		List<Map<String, Object>> refused = new ArrayList<>();
		Map<String, Integer> firstRow = new HashMap<>();
		List<Candidate> candidates = new ArrayList<>();
		for (Line line : lines) {
			String number = ConventionNumbers.normalise(line.number);
			if (number == null) {
				refused.add(refusal(line, "NUMBER_EMPTY"));
			} else if (line.numberTooLongForExcel) {
				refused.add(refusal(line, "NUMBER_CHANGED_BY_EXCEL"));
			} else if (number.length() > ConventionCard.NUMBER_LENGTH) {
				refused.add(refusal(line, "NUMBER_TOO_LONG", "max", ConventionCard.NUMBER_LENGTH));
			} else if (!ConventionNumbers.wellFormed(number)) {
				refused.add(refusal(line, "NUMBER_INVALID"));
			} else if (firstRow.containsKey(number)) {
				refused.add(refusal(line, "DUPLICATE_IN_FILE", "firstRow", firstRow.get(number)));
			} else if (line.holderName.length() > ConventionCard.HOLDER_NAME_LENGTH) {
				refused.add(refusal(line, "HOLDER_NAME_TOO_LONG", "max", ConventionCard.HOLDER_NAME_LENGTH));
			} else if (line.holderPhone.length() > ConventionCard.HOLDER_PHONE_LENGTH) {
				refused.add(refusal(line, "HOLDER_PHONE_TOO_LONG", "max", ConventionCard.HOLDER_PHONE_LENGTH));
			} else if (line.holderEmployeeNo.length() > ConventionCard.HOLDER_EMPLOYEE_NO_LENGTH) {
				refused.add(refusal(line, "HOLDER_EMPLOYEE_NO_TOO_LONG", "max", ConventionCard.HOLDER_EMPLOYEE_NO_LENGTH));
			} else {
				firstRow.put(number, line.row);
				candidates.add(new Candidate(line, new NewCard(number, orNull(line.holderName), orNull(line.holderPhone),
						orNull(line.holderEmployeeNo))));
			}
		}

		// The numbers already used: a card of any convention, an item code, an item barcode, a loyalty card number
		List<String> numbers = new ArrayList<>();
		candidates.forEach(candidate -> numbers.add(candidate.card.number));
		Map<String, String> takenBy = new HashMap<>();
		for (Object[] row : chunked(numbers, cards::cardsIn)) {
			takenBy.put(String.valueOf(row[0]).toUpperCase(java.util.Locale.ROOT), String.valueOf(row[1]));
		}
		Set<String> itemCodes = numbers.isEmpty() ? new HashSet<>() : upper(cards.allItemCodes());
		Set<String> barcodes = numbers.isEmpty() ? new HashSet<>() : upper(cards.allItemOwnBarcodes());
		if (!numbers.isEmpty()) {
			barcodes.addAll(upper(cards.allItemBarcodes()));
		}
		Set<String> loyaltyCards = numbers.isEmpty() ? new HashSet<>() : upper(cards.allLoyaltyCards());

		List<NewCard> accepted = new ArrayList<>();
		for (Candidate candidate : candidates) {
			String number = candidate.card.number;
			if (takenBy.containsKey(number)) {
				refused.add(refusal(candidate.line, "CARD_TAKEN", "convention", takenBy.get(number)));
			} else if (itemCodes.contains(number)) {
				refused.add(refusal(candidate.line, "ITEM_CODE"));
			} else if (barcodes.contains(number)) {
				refused.add(refusal(candidate.line, "ITEM_BARCODE"));
			} else if (loyaltyCards.contains(number)) {
				refused.add(refusal(candidate.line, "LOYALTY_CARD"));
			} else {
				accepted.add(candidate.card);
			}
		}
		if (!accepted.isEmpty()) {
			try {
				inserts.insert(c.getId(), accepted, user, LocalDateTime.now());
			} catch (DataIntegrityViolationException meanwhile) {
				throw new ConventionException(409, "CONVENTION_CARDS_BUSY", "A card number was taken meanwhile: nothing"
						+ " was imported, try again.", "code", c.getCode());
			}
		}
		refused.sort(java.util.Comparator.comparingInt(r -> (Integer) r.get("row")));
		events.cardsImported(c, accepted.size(), refused.size(), shortName(fileName));

		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("conventionId", c.getId());
		answer.put("lines", lines.size());
		answer.put("accepted", accepted.size());
		answer.put("refused", refused);
		return answer;
	}

	/** {row, number (as read), reason, its values}. */
	private static Map<String, Object> refusal(Line line, String reason, Object... keysAndValues) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("row", line.row);
		m.put("number", line.number);
		m.put("reason", reason);
		for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
			m.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
		}
		return m;
	}

	private static <T> List<T> chunked(List<String> numbers, Function<Collection<String>, List<T>> query) {
		List<T> found = new ArrayList<>();
		for (int i = 0; i < numbers.size(); i += CHUNK) {
			found.addAll(query.apply(numbers.subList(i, Math.min(numbers.size(), i + CHUNK))));
		}
		return found;
	}

	/** Trimmed and upper-cased, as the numbers compared (any case in those tables). */
	private static Set<String> upper(List<String> values) {
		Set<String> out = new HashSet<>(values.size() * 2);
		for (String v : values) {
			out.add(v.trim().toUpperCase(java.util.Locale.ROOT));
		}
		return out;
	}

	private static String orNull(String text) {
		return text == null || text.isEmpty() ? null : text;
	}

	/** The file's name in the event: its last part, at most 100 characters. */
	private static String shortName(String fileName) {
		if (fileName == null || fileName.trim().isEmpty()) {
			return null;
		}
		String name = fileName.replace('\\', '/');
		name = name.substring(name.lastIndexOf('/') + 1).trim();
		return name.length() > 100 ? name.substring(0, 100) : name;
	}
}
