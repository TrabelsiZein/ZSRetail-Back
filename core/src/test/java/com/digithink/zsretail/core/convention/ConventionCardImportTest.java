package com.digithink.zsretail.core.convention;

import static com.digithink.zsretail.core.convention.ConventionCardExcelTest.xlsx;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.digithink.zsretail.core.convention.enumeration.ConventionCardStatus;
import com.digithink.zsretail.core.convention.model.ConventionCard;
import com.digithink.zsretail.core.convention.service.ConventionCards;
import com.digithink.zsretail.core.convention.service.ConventionService.Form;
import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.LoyaltyMember;

/**
 * 3.6.0, conventions step 1, part B5: ConventionCardImport (the lines kept and refused, each reason, the conventions
 * that take an import, the event) and ConventionCards.export (the columns, the options, exported_at, the event) over
 * the real repositories (ConventionTestBed: H2).
 */
class ConventionCardImportTest {

	private static final Object[] HEADER = { "Numéro de carte", "Titulaire", "Téléphone", "Matricule" };

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

	private Map<String, Object> run(long id, byte[] file) {
		return bed.tx(() -> bed.cardImport.run(id, file, "C:\\Users\\admin\\Desktop\\cartes santé.xlsx", "admin"));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> refusedLines(Map<String, Object> answer) {
		return (List<Map<String, Object>>) answer.get("refused");
	}

	private static List<String> reasons(Map<String, Object> answer) {
		return refusedLines(answer).stream().map(r -> r.get("row") + " " + r.get("reason")).collect(Collectors.toList());
	}

	private List<String> numbers(long conventionId) {
		return bed.tx(() -> bed.cards.findAll()).stream().filter(c -> c.getConvention().getId() == conventionId)
				.map(ConventionCard::getCardNumber).sorted().collect(Collectors.toList());
	}

	private ConventionCard card(String number) {
		return bed.tx(() -> bed.cards.findByCardNumber(number).get());
	}

	private void taken() {
		long other = bed.create(ConventionTestBed.form("OTHER"));
		bed.card(other, "OTHER-1", ConventionCardStatus.BLOCKED);
		bed.tx(() -> {
			Item item = new Item();
			item.setItemCode("3800001");
			item.setName("Article");
			item.setBarcode("6191234567890");
			bed.session.persist(item);
			ItemBarcode b = new ItemBarcode();
			b.setItem(item);
			b.setBarcode("6199999999999");
			bed.session.persist(b);
			LoyaltyMember m = new LoyaltyMember();
			m.setCardNumber("FID-001");
			m.setFirstName("Amel");
			m.setLastName("Ben Salah");
			bed.session.persist(m);
			return null;
		});
	}

	private static Object[] row(Object... cells) {
		return cells;
	}

	// ─── Import ──────────────────────────────────────────────────────

	@Test
	@DisplayName("The good lines kept, normalised, their holder stored (Arabic, accents; a missing holder is fine); each"
			+ " refused line with its Excel row, the number as read and its reason; one CONVENTION_CARDS_IMPORTED")
	void importsAndRefuses() {
		taken();
		long id = bed.create(ConventionTestBed.form("MIN"));
		String tooLongName = String.join("", Collections.nCopies(151, "x"));
		Map<String, Object> answer = run(id, xlsx(HEADER,
				row(" min-001 ", "محمد بن علي", "98111222", "M-1"), // 2 kept
				row("MIN-002", null, null, null), // 3 kept: no holder
				row(12345, "Hélène Gaëlle", 98765432, 4521), // 4 kept: number cells
				row(null, "Sans numéro", null, null), // 5 NUMBER_EMPTY
				row("MIN 004", null, null, null), // 6 NUMBER_INVALID
				row("MIN_005", null, null, null), // 7 NUMBER_INVALID
				row(String.join("", Collections.nCopies(41, "A")), null, null, null), // 8 NUMBER_TOO_LONG
				row(1234567890123456L, null, null, null), // 9 NUMBER_CHANGED_BY_EXCEL
				row("min-001", "Doublon", null, null), // 10 DUPLICATE_IN_FILE (row 2)
				row("MIN-010", tooLongName, null, null), // 11 HOLDER_NAME_TOO_LONG
				row("MIN-011", null, String.join("", Collections.nCopies(31, "9")), null), // 12 HOLDER_PHONE_TOO_LONG
				row("other-1", null, null, null), // 13 CARD_TAKEN (OTHER)
				row("3800001", null, null, null), // 14 ITEM_CODE
				row("6191234567890", null, null, null), // 15 ITEM_BARCODE (item's own column)
				row(6199999999999L, null, null, null), // 16 ITEM_BARCODE (item_barcode), a number cell
				row("fid-001", null, null, null), // 17 LOYALTY_CARD
				row("MIN-012", "Ali", null, null))); // 18 kept
		assertEquals(17, answer.get("lines"));
		assertEquals(4, answer.get("accepted"));
		assertEquals(Arrays.asList("5 NUMBER_EMPTY", "6 NUMBER_INVALID", "7 NUMBER_INVALID", "8 NUMBER_TOO_LONG",
				"9 NUMBER_CHANGED_BY_EXCEL", "10 DUPLICATE_IN_FILE", "11 HOLDER_NAME_TOO_LONG", "12 HOLDER_PHONE_TOO_LONG",
				"13 CARD_TAKEN", "14 ITEM_CODE", "15 ITEM_BARCODE", "16 ITEM_BARCODE", "17 LOYALTY_CARD"), reasons(answer));
		List<Map<String, Object>> refused = refusedLines(answer);
		assertEquals("MIN 004", refused.get(1).get("number"), "the number as read");
		assertEquals(2, refused.get(5).get("firstRow"));
		assertEquals(150, refused.get(6).get("max"));
		assertEquals("OTHER", refused.get(8).get("convention"));
		assertEquals("1234567890123456", refused.get(4).get("number"));

		assertEquals(Arrays.asList("12345", "MIN-001", "MIN-002", "MIN-012"), numbers(id));
		ConventionCard arabic = card("MIN-001");
		assertEquals("محمد بن علي", arabic.getHolderName());
		assertEquals("98111222", arabic.getHolderPhone());
		assertEquals("M-1", arabic.getHolderEmployeeNo());
		assertEquals(ConventionCardStatus.ACTIVE, arabic.getStatus());
		assertEquals("admin", arabic.getCreatedBy());
		assertNull(arabic.getExportedAt());
		assertNull(card("MIN-002").getHolderName(), "no holder: empty, never refused");
		assertEquals("Hélène Gaëlle", card("12345").getHolderName());
		assertEquals("98765432", card("12345").getHolderPhone());
		assertEquals(0L, bed.row(id).getLastCardSeq(), "the counter does not move");

		EventLogEntry imported = bed.log.only("CONVENTION_CARDS_IMPORTED");
		assertEquals("CONVENTION", imported.getSubjectType());
		assertEquals(4.0, imported.getAmount());
		assertEquals("4 cartes importées pour la convention MIN depuis « cartes santé.xlsx » (13 lignes refusées).",
				imported.getDetails());
		assertFalse(bed.log.rows.stream().anyMatch(r -> r.getDetails() != null && r.getDetails().contains("98111222")),
				"never a phone");
	}

	@Test
	@DisplayName("A second run of the same file: every line refused as a card already there; a generation then skips the"
			+ " numbers imported")
	void againAndGeneration() {
		long id = bed.create(ConventionTestBed.form("MIN"));
		byte[] file = xlsx(HEADER, row("MIN-00002"), row("MIN-00003"));
		assertEquals(2, run(id, file).get("accepted"));
		Map<String, Object> again = run(id, file);
		assertEquals(0, again.get("accepted"));
		assertEquals(Arrays.asList("2 CARD_TAKEN", "3 CARD_TAKEN"), reasons(again));
		assertEquals(2, bed.log.rows.stream().filter(r -> "CONVENTION_CARDS_IMPORTED".equals(r.getCode())).count(),
				"one event per run, an empty one too");
		Map<String, Object> generated = bed.tx(() -> bed.cardService.generate(id, 2, "admin"));
		assertEquals(2, generated.get("skipped"));
		assertEquals(Arrays.asList("MIN-00001", "MIN-00002", "MIN-00003", "MIN-00004"), numbers(id));
	}

	@Test
	@DisplayName("Only with CARDS and not expired (suspended and upcoming: yes); 404; 409 on a store whose promotions are"
			+ " the head office's; a wrong file refused whole, without an event and without a card")
	void whichConventionsAndWrongFiles() {
		Form noCard = ConventionTestBed.form("BANQUE");
		noCard.recognition = "NO_CARD";
		long bank = bed.create(noCard);
		byte[] good = xlsx(HEADER, row("X-1"));
		refused("CONVENTION_NO_CARDS", () -> run(bank, good));
		Form ended = ConventionTestBed.form("OLD");
		ended.endDate = LocalDate.of(2026, 11, 27);
		long old = bed.create(ended);
		refused("CONVENTION_EXPIRED", () -> run(old, good));
		long suspended = bed.create(ConventionTestBed.form("SUSP"));
		bed.tx(() -> bed.service.setStatus(suspended, "SUSPENDED", "x"));
		assertEquals(1, run(suspended, good).get("accepted"));
		refused("CONVENTION_NOT_FOUND", () -> run(999, good));

		long id = bed.create(ConventionTestBed.form("MIN"));
		refused("CONVENTION_IMPORT_NOT_XLSX", () -> run(id, "MIN-1;Ali".getBytes()));
		refused("CONVENTION_IMPORT_NO_HEADER", () -> run(id, xlsx(row("MIN-1"), row("MIN-2"))));
		refused("CONVENTION_IMPORT_EMPTY", () -> run(id, xlsx(HEADER)));
		bed.ownedByHeadOffice = true;
		assertEquals(409, refused("CONVENTION_OWNED_BY_HEAD_OFFICE", () -> run(id, good)).getStatus());
		assertEquals(1, bed.log.rows.stream().filter(r -> "CONVENTION_CARDS_IMPORTED".equals(r.getCode())).count());
		assertEquals(Collections.emptyList(), numbers(id));
	}

	@Test
	@DisplayName("5,000 lines in one file: all kept, in seconds")
	void fiveThousand() {
		long id = bed.create(ConventionTestBed.form("BIG"));
		Object[][] rows = new Object[5001][];
		rows[0] = HEADER;
		for (int i = 1; i <= 5000; i++) {
			rows[i] = row(String.format("BIG-%05d", i), "Titulaire " + i, null, "E" + i);
		}
		long started = System.nanoTime();
		assertEquals(5000, run(id, xlsx(rows)).get("accepted"));
		assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
		assertEquals(5000, numbers(id).size());
	}

	// ─── Export ──────────────────────────────────────────────────────

	private static List<String[]> sheet(byte[] file) {
		try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
			Sheet sheet = book.getSheetAt(0);
			List<String[]> rows = new ArrayList<>();
			for (int r = 0; r <= sheet.getLastRowNum(); r++) {
				String[] cells = new String[6];
				for (int c = 0; c < 6; c++) {
					cells[c] = sheet.getRow(r).getCell(c) == null ? "" : sheet.getRow(r).getCell(c).getStringCellValue();
				}
				rows.add(cells);
			}
			return rows;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private ConventionCards.ExcelFile export(long id, String status, boolean onlyNeverExported) {
		return bed.tx(() -> bed.cardService.export(id, status, onlyNeverExported, "admin"));
	}

	@Test
	@DisplayName("The export: the active cards by default, by number, the decision 8 columns (never the phone);"
			+ " exported_at stamped on the cards written (the first export kept); 'never exported' twice: the second"
			+ " empty; one CONVENTION_CARDS_EXPORTED each, with the count")
	void export() {
		long id = bed.create(ConventionTestBed.form("MIN"));
		run(id, xlsx(HEADER, row("MIN-3", "Sana", "98111222", "M-3"), row("MIN-1", "Ali", "55444333", null),
				row("MIN-2", null, null, null)));
		long blocked = bed.cards.findByCardNumber("MIN-2").get().getId();
		ConventionCards.StatusForm f = new ConventionCards.StatusForm();
		f.ids = Collections.singletonList(blocked);
		f.status = "BLOCKED";
		bed.tx(() -> bed.cardService.setStatus(id, f, "admin"));
		bed.log.rows.clear();

		ConventionCards.ExcelFile first = export(id, null, true);
		assertEquals(2, first.count);
		assertTrue(first.name.matches("cartes-MIN-\\d{8}-\\d{4}\\.xlsx"), first.name);
		List<String[]> rows = sheet(first.bytes);
		assertArrayEquals(new String[] { "Numéro de carte", "Code convention", "Convention", "Organisation", "Titulaire",
				"Matricule" }, rows.get(0));
		assertArrayEquals(new String[] { "MIN-1", "MIN", "Convention MIN", "Ministère de la Santé", "Ali", "" },
				rows.get(1));
		assertArrayEquals(new String[] { "MIN-3", "MIN", "Convention MIN", "Ministère de la Santé", "Sana", "M-3" },
				rows.get(2));
		assertEquals(3, rows.size(), "the blocked card left out");
		assertFalse(rows.stream().flatMap(Arrays::stream).anyMatch(v -> v.contains("98111222") || v.contains("55444333")),
				"never the phone");
		java.time.LocalDateTime stamped = card("MIN-1").getExportedAt();
		assertNotNull(stamped);
		assertNotNull(card("MIN-3").getExportedAt());
		assertNull(card("MIN-2").getExportedAt(), "not written, not stamped");

		ConventionCards.ExcelFile second = export(id, "ACTIVE", true);
		assertEquals(0, second.count);
		assertEquals(1, sheet(second.bytes).size(), "the header only");

		ConventionCards.ExcelFile all = export(id, "all", false);
		assertEquals(3, all.count);
		assertEquals(stamped, card("MIN-1").getExportedAt(), "the first export kept");
		assertNotNull(card("MIN-2").getExportedAt());
		assertEquals(1, export(id, "blocked", false).count);
		refused("CONVENTION_CARD_STATUS_UNKNOWN", () -> export(id, "LOST", false));

		List<EventLogEntry> events = bed.log.rows.stream().filter(r -> "CONVENTION_CARDS_EXPORTED".equals(r.getCode()))
				.collect(Collectors.toList());
		assertEquals(4, events.size());
		assertEquals(2.0, events.get(0).getAmount());
		assertEquals("2 cartes exportées pour la convention MIN (cartes actives, jamais exportées).",
				events.get(0).getDetails());
		assertEquals("0 carte exportée pour la convention MIN (cartes actives, jamais exportées).",
				events.get(1).getDetails());
		assertEquals("3 cartes exportées pour la convention MIN (toutes les cartes).", events.get(2).getDetails());

		bed.ownedByHeadOffice = true;
		refused("CONVENTION_OWNED_BY_HEAD_OFFICE", () -> export(id, null, false));
		refused("CONVENTION_NOT_FOUND", () -> {
			bed.ownedByHeadOffice = false;
			export(999, null, false);
		});
	}
}
