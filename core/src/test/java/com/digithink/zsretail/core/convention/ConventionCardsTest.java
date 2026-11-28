package com.digithink.zsretail.core.convention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataIntegrityViolationException;

import com.digithink.zsretail.core.convention.enumeration.ConventionCardStatus;
import com.digithink.zsretail.core.convention.model.ConventionCard;
import com.digithink.zsretail.core.convention.service.ConventionCards;
import com.digithink.zsretail.core.convention.service.ConventionCards.CardForm;
import com.digithink.zsretail.core.convention.service.ConventionCards.StatusForm;
import com.digithink.zsretail.core.convention.service.ConventionEvents;
import com.digithink.zsretail.core.convention.service.ConventionService.Form;
import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.LoyaltyMember;

/**
 * 3.6.0, conventions step 1, part B4: ConventionCards' rules over the real repositories (ConventionTestBed: H2, the
 * batch insert's SQL), the events. docs/modules/conventions.md, "The cards".
 */
class ConventionCardsTest {

	private final ConventionTestBed bed = new ConventionTestBed();

	@AfterEach
	void close() {
		bed.close();
	}

	private ConventionException refused(String code, Executable call) {
		ConventionException e = assertThrows(ConventionException.class, call);
		assertEquals(code, e.getCode(), e.getMessage());
		return e;
	}

	private long convention(String code) {
		return bed.create(ConventionTestBed.form(code));
	}

	private Map<String, Object> generate(long id, Integer count) {
		return bed.tx(() -> bed.cardService.generate(id, count, "admin"));
	}

	private Map<String, Object> add(long id, String number, String name, String phone, String employeeNo) {
		CardForm f = new CardForm();
		f.number = number;
		f.holderName = name;
		f.holderPhone = phone;
		f.holderEmployeeNo = employeeNo;
		return bed.tx(() -> bed.cardService.add(id, f, "admin"));
	}

	private Map<String, Object> status(long id, String status, Long... ids) {
		StatusForm f = new StatusForm();
		f.ids = Arrays.asList(ids);
		f.status = status;
		return bed.tx(() -> bed.cardService.setStatus(id, f, "manager"));
	}

	private long cardId(String number) {
		return bed.cards.findByCardNumber(number).get().getId();
	}

	private ConventionCard card(String number) {
		return bed.tx(() -> bed.cards.findByCardNumber(number).get());
	}

	private List<String> numbers(long conventionId) {
		return bed.tx(() -> bed.cards.findAll()).stream().filter(c -> c.getConvention().getId() == conventionId)
				.map(ConventionCard::getCardNumber).sorted().collect(Collectors.toList());
	}

	private void item(String code, String ownBarcode, String barcode) {
		bed.tx(() -> {
			Item item = new Item();
			item.setItemCode(code);
			item.setName("Article " + code);
			item.setBarcode(ownBarcode);
			bed.session.persist(item);
			if (barcode != null) {
				ItemBarcode b = new ItemBarcode();
				b.setItem(item);
				b.setBarcode(barcode);
				bed.session.persist(b);
			}
			return null;
		});
	}

	private void loyaltyCard(String number) {
		bed.tx(() -> {
			LoyaltyMember m = new LoyaltyMember();
			m.setCardNumber(number);
			m.setFirstName("Amel");
			m.setLastName("Ben Salah");
			bed.session.persist(m);
			return null;
		});
	}

	// ─── Generation ──────────────────────────────────────────────────

	@Test
	@DisplayName("Generated: the code as prefix, '-', 5 digits from the counter; the next run goes on; one"
			+ " CONVENTION_CARDS_GENERATED per run, the count as its amount")
	void generate() {
		long id = convention("MIN");
		Map<String, Object> run = generate(id, 20);
		assertEquals(20, run.get("generated"));
		assertEquals(0, run.get("skipped"));
		assertEquals("MIN-00001", run.get("first"));
		assertEquals("MIN-00020", run.get("last"));
		assertEquals(20L, bed.row(id).getLastCardSeq());
		assertEquals("MIN-00021", generate(id, 1).get("first"));
		List<String> all = numbers(id);
		assertEquals(21, all.size());
		ConventionCard one = card("MIN-00007");
		assertEquals(ConventionCardStatus.ACTIVE, one.getStatus());
		assertEquals("admin", one.getCreatedBy());
		assertNull(one.getHolderName());
		assertNull(one.getExportedAt());

		List<EventLogEntry> runs = bed.log.rows.stream().filter(r -> "CONVENTION_CARDS_GENERATED".equals(r.getCode()))
				.collect(Collectors.toList());
		assertEquals(2, runs.size());
		assertEquals("CONVENTION", runs.get(0).getSubjectType());
		assertEquals(String.valueOf(id), runs.get(0).getSubjectId());
		assertEquals(20.0, runs.get(0).getAmount());
		assertEquals("20 cartes générées pour la convention MIN : MIN-00001 à MIN-00020.", runs.get(0).getDetails());
		assertEquals("1 carte générée pour la convention MIN : MIN-00021.", runs.get(1).getDetails());
	}

	@Test
	@DisplayName("The card prefix and the digits of the convention; a prefix taken by another convention's code: its"
			+ " numbers are skipped, never twice")
	void prefixAndDigits() {
		Form f = ConventionTestBed.form("POSTE");
		f.cardPrefix = "PT";
		f.cardDigits = 6;
		long poste = bed.create(f);
		assertEquals("PT-000001", generate(poste, 2).get("first"));
		long pt = convention("PT"); // its own prefix is its code: PT
		Map<String, Object> run = generate(pt, 2);
		assertEquals(Arrays.asList("PT-00001", "PT-00002"), numbers(pt), "5 digits: other numbers than POSTE's");
		assertEquals(0, run.get("skipped"));
	}

	@Test
	@DisplayName("A number already used is skipped and counted, the counter past it: a card of any convention, an item"
			+ " code, an item barcode (both columns), a loyalty card number, whatever its case")
	void skipsTheNumbersUsed() {
		long id = convention("MIN");
		long other = convention("OTHER");
		bed.card(other, "MIN-00002", ConventionCardStatus.ACTIVE);
		item("MIN-00003", null, null);
		item("ART-1", null, "min-00004");
		item("ART-2", "MIN-00005", null);
		loyaltyCard("MIN-00006");
		Map<String, Object> run = generate(id, 5);
		assertEquals(5, run.get("generated"));
		assertEquals(5, run.get("skipped"));
		assertEquals(Arrays.asList("MIN-00002", "MIN-00003", "MIN-00004", "MIN-00005", "MIN-00006"),
				run.get("skippedNumbers"));
		assertEquals(Arrays.asList("MIN-00001", "MIN-00007", "MIN-00008", "MIN-00009", "MIN-00010"), numbers(id));
		assertEquals(10L, run.get("lastCardSeq"));
		assertEquals("5 cartes générées pour la convention MIN : MIN-00001 à MIN-00010 (5 numéros sautés, déjà pris).",
				bed.log.only("CONVENTION_CARDS_GENERATED").getDetails());
	}

	@Test
	@DisplayName("1 to 5,000 per generation; 5,000 in one run; all or nothing when the digits run out (the counter kept)")
	void countsAndAllOrNothing() {
		long id = convention("MIN");
		for (Integer count : new Integer[] { null, 0, -1, 5001 }) {
			ConventionException e = refused("CONVENTION_GENERATE_COUNT", () -> generate(id, count));
			assertEquals(5000, e.getParams().get("max"));
		}
		long started = System.nanoTime();
		assertEquals(5000, generate(id, 5000).get("generated"));
		assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30), "5,000 in seconds");
		assertEquals(5000, numbers(id).size());
		assertEquals(new HashSet<>(numbers(id)).size(), 5000);

		Form f = ConventionTestBed.form("FOUR");
		f.cardDigits = 4;
		long four = bed.create(f);
		bed.tx(() -> bed.session.createQuery("update Convention set lastCardSeq = 9997 where id = :id")
				.setParameter("id", four).executeUpdate());
		ConventionException exhausted = refused("CONVENTION_NUMBERS_EXHAUSTED", () -> generate(four, 3));
		assertEquals(2, exhausted.getParams().get("left"));
		assertEquals(Collections.emptyList(), numbers(four), "nothing inserted");
		assertEquals(9997L, bed.row(four).getLastCardSeq(), "the counter kept");
		assertEquals("FOUR-9999", generate(four, 2).get("last"));
	}

	@Test
	@DisplayName("Decisions 4 and B4's point 4: only with CARDS (NO_CARD refused, generated or by hand); expired refused;"
			+ " suspended and upcoming allowed (the cards are prepared before)")
	void whichConventions() {
		Form noCard = ConventionTestBed.form("BANQUE");
		noCard.recognition = "NO_CARD";
		long bank = bed.create(noCard);
		refused("CONVENTION_NO_CARDS", () -> generate(bank, 1));
		refused("CONVENTION_NO_CARDS", () -> add(bank, "BQ-1", null, null, null));

		Form ended = ConventionTestBed.form("OLD");
		ended.endDate = LocalDate.of(2026, 11, 27);
		long old = bed.create(ended);
		assertEquals("2026-11-27", refused("CONVENTION_EXPIRED", () -> generate(old, 1)).getParams().get("end"));
		refused("CONVENTION_EXPIRED", () -> add(old, "OLD-X", null, null, null));

		Form lastDay = ConventionTestBed.form("TODAY");
		lastDay.endDate = bed.today;
		assertEquals(1, generate(bed.create(lastDay), 1).get("generated"), "its last day");
		Form later = ConventionTestBed.form("NEXT");
		later.startDate = LocalDate.of(2027, 1, 1);
		assertEquals(1, generate(bed.create(later), 1).get("generated"), "upcoming");
		long suspended = convention("SUSP");
		bed.tx(() -> bed.service.setStatus(suspended, "SUSPENDED", "x"));
		assertEquals(1, generate(suspended, 1).get("generated"), "suspended");
		add(suspended, "SUSP-HAND", null, null, null);
		refused("CONVENTION_NOT_FOUND", () -> generate(999, 1));
	}

	@Test
	@DisplayName("Two generations of one convention at the same moment: one after the other (the row lock), no number"
			+ " twice, the counter at the sum")
	void twoGenerationsAtOnce() throws Exception {
		long id = convention("MIN");
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService two = Executors.newFixedThreadPool(2);
		List<Future<Object>> runs = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			runs.add(two.submit(() -> {
				try (ConventionTestBed other = new ConventionTestBed(false)) {
					go.await();
					return other.tx(() -> other.cardService.generate(id, 300, "admin")).get("generated");
				}
			}));
		}
		go.countDown();
		for (Future<Object> run : runs) {
			assertEquals(300, run.get(60, TimeUnit.SECONDS));
		}
		two.shutdown();
		List<String> all = numbers(id);
		assertEquals(600, all.size());
		assertEquals(600, new HashSet<>(all).size(), "no number twice");
		assertEquals("MIN-00600", all.get(all.size() - 1));
		assertEquals(600L, bed.row(id).getLastCardSeq());
	}

	@Test
	@DisplayName("A number taken between the read and the insert: 409 CONVENTION_CARDS_BUSY, nothing generated, the"
			+ " counter kept")
	void takenMeanwhile() {
		long id = convention("MIN");
		ConventionCards busy = new ConventionCards(bed.conventions, bed.cards, new ConventionEvents(bed.log.eventLog()),
				(conventionId, numbers, user, at) -> {
					throw new DataIntegrityViolationException("uk_convention_card_number");
				}, () -> false, () -> bed.today);
		ConventionException e = refused("CONVENTION_CARDS_BUSY", () -> bed.tx(() -> busy.generate(id, 10, "admin")));
		assertEquals(409, e.getStatus());
		assertEquals(0L, bed.row(id).getLastCardSeq());
		assertFalse(bed.log.codes().contains("CONVENTION_CARDS_GENERATED"));
	}

	// ─── One card by hand ────────────────────────────────────────────

	@Test
	@DisplayName("By hand: the number trimmed and upper-cased, the holder trimmed (blank is empty); one"
			+ " CONVENTION_CARD_ADDED with the holder's name and employee number, never the phone")
	void addOne() {
		long id = convention("MIN");
		Map<String, Object> view = add(id, "  min-0099 ", " Ali Ben Salah ", " 98 111 222 ", "  ");
		assertEquals("MIN-0099", view.get("cardNumber"));
		ConventionCard c = card("MIN-0099");
		assertEquals("Ali Ben Salah", c.getHolderName());
		assertEquals("98 111 222", c.getHolderPhone());
		assertNull(c.getHolderEmployeeNo());
		assertEquals(0L, bed.row(id).getLastCardSeq(), "by hand: the counter does not move");
		EventLogEntry added = bed.log.only("CONVENTION_CARD_ADDED");
		assertEquals("CONVENTION_CARD", added.getSubjectType());
		assertEquals("MIN-0099", added.getSubjectLabel());
		assertEquals("Carte MIN-0099 ajoutée à la convention MIN, titulaire Ali Ben Salah.", added.getDetails());
		add(id, "MIN-0100", null, null, "M-7");
		assertEquals("Carte MIN-0100 ajoutée à la convention MIN, matricule M-7.", bed.log.rows
				.get(bed.log.rows.size() - 1).getDetails());
	}

	@Test
	@DisplayName("By hand, refused: no number, other characters, over 40, a card of any convention (named), an item code,"
			+ " an item barcode (both columns), a loyalty card number (any case); a holder field too long")
	void addRefused() {
		long id = convention("MIN");
		long other = convention("OTHER");
		bed.card(other, "OTHER-1", ConventionCardStatus.BLOCKED);
		item("3800001", null, null);
		item("ART-1", "6191234567890", "6199999999999");
		loyaltyCard("FID-001");
		refused("CONVENTION_CARD_NUMBER_REQUIRED", () -> add(id, "  ", null, null, null));
		assertEquals("MIN 1", refused("CONVENTION_CARD_NUMBER_INVALID", () -> add(id, "min 1", null, null, null))
				.getParams().get("number"));
		refused("CONVENTION_CARD_NUMBER_INVALID", () -> add(id, "MIN_1", null, null, null));
		assertEquals("cardNumber", refused("CONVENTION_TOO_LONG", () -> add(id, repeat('A', 41), null, null, null))
				.getParams().get("field"));
		ConventionException taken = refused("CONVENTION_CARD_NUMBER_TAKEN", () -> add(id, "other-1", null, null, null));
		assertEquals("OTHER", taken.getParams().get("convention"));
		assertEquals("ITEM_CODE", refused("CONVENTION_CARD_NUMBER_RESERVED", () -> add(id, "3800001", null, null, null))
				.getParams().get("kind"));
		assertEquals("ITEM_BARCODE", refused("CONVENTION_CARD_NUMBER_RESERVED", () -> add(id, "6191234567890", null, null,
				null)).getParams().get("kind"));
		assertEquals("ITEM_BARCODE", refused("CONVENTION_CARD_NUMBER_RESERVED", () -> add(id, "6199999999999", null, null,
				null)).getParams().get("kind"));
		assertEquals("LOYALTY_CARD", refused("CONVENTION_CARD_NUMBER_RESERVED", () -> add(id, "fid-001", null, null, null))
				.getParams().get("kind"));
		assertEquals("holderPhone", refused("CONVENTION_TOO_LONG", () -> add(id, "MIN-1", null, repeat('9', 31), null))
				.getParams().get("field"));
		assertEquals(Collections.emptyList(), numbers(id));
		assertFalse(bed.log.codes().contains("CONVENTION_CARD_ADDED"));
	}

	// ─── Block, unblock, the holder ──────────────────────────────────

	@Test
	@DisplayName("Block several in one call: the cards already blocked left alone, one CONVENTION_CARD_BLOCKED per card"
			+ " changed; unblock one; never deleted")
	void blockAndUnblock() {
		long id = convention("MIN");
		generate(id, 4);
		long one = cardId("MIN-00001");
		long two = cardId("MIN-00002");
		long three = cardId("MIN-00003");
		status(id, "BLOCKED", two);
		bed.log.rows.clear();
		Map<String, Object> answer = status(id, "blocked", one, two, three, one);
		assertEquals(2, answer.get("changed"));
		assertEquals(1, answer.get("unchanged"));
		assertEquals(Arrays.asList("CONVENTION_CARD_BLOCKED", "CONVENTION_CARD_BLOCKED"), bed.log.codes());
		EventLogEntry blocked = bed.log.rows.get(0);
		assertEquals("CONVENTION_CARD", blocked.getSubjectType());
		assertEquals(String.valueOf(one), blocked.getSubjectId());
		assertEquals("ACTIVE", blocked.getOldValue());
		assertEquals("BLOCKED", blocked.getNewValue());
		assertEquals("Carte MIN-00001 de la convention MIN bloquée.", blocked.getDetails());
		assertEquals("manager", card("MIN-00001").getUpdatedBy());

		status(id, "ACTIVE", two);
		assertEquals("Carte MIN-00002 de la convention MIN débloquée.", bed.log.only("CONVENTION_CARD_UNBLOCKED")
				.getDetails());
		assertEquals(ConventionCardStatus.ACTIVE, card("MIN-00002").getStatus());
		assertEquals(ConventionCardStatus.BLOCKED, card("MIN-00003").getStatus());
		assertEquals(4, numbers(id).size(), "nothing deleted");
	}

	@Test
	@DisplayName("Block / unblock refused: no id or over 500, a card of another convention (the whole call, 404 with the"
			+ " ids), an unknown status")
	void statusRefused() {
		long id = convention("MIN");
		long other = convention("OTHER");
		generate(id, 1);
		generate(other, 1);
		long mine = cardId("MIN-00001");
		long theirs = cardId("OTHER-00001");
		refused("CONVENTION_CARD_IDS", () -> status(id, "BLOCKED"));
		Long[] many = new Long[501];
		for (int i = 0; i < many.length; i++) {
			many[i] = (long) i + 1;
		}
		assertEquals(500, refused("CONVENTION_CARD_IDS", () -> status(id, "BLOCKED", many)).getParams().get("max"));
		ConventionException notHere = refused("CONVENTION_CARD_NOT_FOUND", () -> status(id, "BLOCKED", mine, theirs));
		assertEquals(404, notHere.getStatus());
		assertEquals(Collections.singletonList(theirs), notHere.getParams().get("ids"));
		assertEquals(ConventionCardStatus.ACTIVE, card("MIN-00001").getStatus(), "all or nothing");
		assertEquals("LOST", refused("CONVENTION_CARD_STATUS_UNKNOWN", () -> status(id, "LOST", mine)).getParams()
				.get("value"));
	}

	@Test
	@DisplayName("The holder: the three fields free (never checked), blank is empty; one CONVENTION_CARD_HOLDER_CHANGED,"
			+ " the name and employee number before → after, the phone only as 'modifié' (decision 15); nothing when"
			+ " nothing changed")
	void holder() {
		long id = convention("MIN");
		add(id, "MIN-1", "Ali", "98111222", "M-1");
		long card = cardId("MIN-1");
		CardForm f = new CardForm();
		f.holderName = "Ali Ben Salah";
		f.holderPhone = "55444333";
		f.holderEmployeeNo = " ";
		Map<String, Object> view = bed.tx(() -> bed.cardService.holder(id, card, f, "manager"));
		assertEquals("55444333", view.get("holderPhone"));
		assertNull(view.get("holderEmployeeNo"));
		EventLogEntry changed = bed.log.only("CONVENTION_CARD_HOLDER_CHANGED");
		assertEquals("Titulaire de la carte MIN-1 (convention MIN) modifié : Titulaire : Ali → Ali Ben Salah ;"
				+ " Matricule : M-1 → — ; Téléphone : modifié.", changed.getDetails());
		bed.tx(() -> bed.cardService.holder(id, card, f, "manager"));
		assertEquals(1, bed.log.rows.stream().filter(r -> "CONVENTION_CARD_HOLDER_CHANGED".equals(r.getCode())).count());
		for (EventLogEntry row : bed.log.rows) {
			for (String value : new String[] { row.getDetails(), row.getOldValue(), row.getNewValue() }) {
				assertFalse(value != null && (value.contains("98111222") || value.contains("55444333")),
						"never the phone: " + value);
			}
		}
		long other = convention("OTHER");
		refused("CONVENTION_CARD_NOT_FOUND", () -> bed.tx(() -> bed.cardService.holder(other, card, f, "x")));
	}

	@Test
	@DisplayName("Decision 2: every card change 409 on a store whose promotions are the head office's; the list stays")
	void ownedByHeadOffice() {
		long id = convention("MIN");
		generate(id, 1);
		long card = cardId("MIN-00001");
		bed.ownedByHeadOffice = true;
		for (Executable change : Arrays.<Executable>asList(() -> generate(id, 1), () -> add(id, "MIN-X", null, null, null),
				() -> status(id, "BLOCKED", card), () -> bed.tx(() -> bed.cardService.holder(id, card, new CardForm(),
						"x")))) {
			assertEquals(409, refused("CONVENTION_OWNED_BY_HEAD_OFFICE", change).getStatus());
		}
		assertEquals(1L, bed.tx(() -> bed.cardService.list(id, null, null, null, 0, 20)).get("totalElements"));
	}

	// ─── The list ────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private List<Object> listed(long id, String status, String q, Boolean exported) {
		return ((List<Map<String, Object>>) bed.tx(() -> bed.cardService.list(id, status, q, exported, 0, 20))
				.get("content")).stream().map(r -> r.get("cardNumber")).collect(Collectors.toList());
	}

	@Test
	@DisplayName("The list: this convention's cards by number, the status filter, the search in the number, the holder's"
			+ " name and employee number (any case), never exported or exported, the pages")
	void list() {
		long id = convention("MIN");
		long other = convention("OTHER");
		generate(id, 3);
		generate(other, 2);
		add(id, "MIN-A", "Amel Trabelsi", "98000000", "EMP-42");
		status(id, "BLOCKED", cardId("MIN-00002"));
		bed.tx(() -> bed.session.createQuery("update ConventionCard set exportedAt = current_timestamp() where cardNumber"
				+ " = 'MIN-00003'").executeUpdate());
		assertEquals(Arrays.asList("MIN-00001", "MIN-00002", "MIN-00003", "MIN-A"), listed(id, "all", null, null));
		assertEquals(Collections.singletonList("MIN-00002"), listed(id, "blocked", null, null));
		assertEquals(Arrays.asList("MIN-00001", "MIN-00003", "MIN-A"), listed(id, "ACTIVE", " ", null));
		assertEquals(Collections.singletonList("MIN-A"), listed(id, null, "trabel", null), "the holder's name");
		assertEquals(Collections.singletonList("MIN-A"), listed(id, null, "emp-4", null), "the employee number");
		assertEquals(Collections.singletonList("MIN-00002"), listed(id, null, "0002", null), "the number");
		assertEquals(Collections.emptyList(), listed(id, null, "98000000", null), "never the phone");
		assertEquals(Collections.singletonList("MIN-00003"), listed(id, null, null, true));
		assertEquals(Arrays.asList("MIN-00001", "MIN-00002", "MIN-A"), listed(id, null, null, false));
		Map<String, Object> page = bed.tx(() -> bed.cardService.list(id, null, null, null, 1, 3));
		assertEquals(4L, page.get("totalElements"));
		assertEquals(1, ((List<?>) page.get("content")).size());
		refused("CONVENTION_CARD_STATUS_UNKNOWN", () -> listed(id, "LOST", null, null));
		refused("CONVENTION_NOT_FOUND", () -> listed(999, null, null, null));
	}

	private static String repeat(char c, int n) {
		char[] chars = new char[n];
		Arrays.fill(chars, c);
		return new String(chars);
	}
}
