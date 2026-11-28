package com.digithink.zsretail.core.convention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.digithink.zsretail.core.convention.enumeration.ConventionCardStatus;
import com.digithink.zsretail.core.convention.enumeration.ConventionRecognition;
import com.digithink.zsretail.core.convention.enumeration.ConventionState;
import com.digithink.zsretail.core.convention.enumeration.ConventionStatus;
import com.digithink.zsretail.core.convention.model.Convention;
import com.digithink.zsretail.core.convention.service.ConventionService.Form;
import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;

/**
 * 3.6.0, conventions step 1, part B3: ConventionService's rules over the real repositories (ConventionTestBed: H2), and
 * the events it writes. docs/modules/conventions.md.
 */
class ConventionServiceTest {

	private final ConventionTestBed bed = new ConventionTestBed();

	@AfterEach
	void close() {
		bed.close();
	}

	/** The refusal's code and params, the call rolled back. */
	private ConventionException refused(String code, Executable call) {
		ConventionException e = assertThrows(ConventionException.class, call);
		assertEquals(code, e.getCode(), e.getMessage());
		return e;
	}

	private ConventionException createRefused(String code, Consumer<Form> change) {
		Form f = ConventionTestBed.form("MIN");
		change.accept(f);
		return refused(code, () -> bed.create(f));
	}

	private Form formOf(long id) {
		Convention c = bed.row(id);
		Form f = new Form();
		f.code = c.getCode();
		f.name = c.getName();
		f.organisation = c.getOrganisation();
		f.startDate = c.getStartDate();
		f.endDate = c.getEndDate();
		f.recognition = c.getRecognition().name();
		f.maxUsesPerDay = c.getMaxUsesPerDay();
		f.maxUsesPerMonth = c.getMaxUsesPerMonth();
		f.maxAmountPerMonth = c.getMaxAmountPerMonth();
		f.cardPrefix = c.getCardPrefix();
		f.cardDigits = c.getCardDigits();
		return f;
	}

	private Map<String, Object> update(long id, Consumer<Form> change) {
		Form f = formOf(id);
		change.accept(f);
		return bed.tx(() -> bed.service.update(id, f, "manager"));
	}

	// ─── Create ──────────────────────────────────────────────────────

	@Test
	@DisplayName("Created: the code trimmed and upper-cased, the texts trimmed, ACTIVE, CARDS, 5 digits, LOCAL, the counter 0,"
			+ " the user; one CONVENTION_CREATED naming its values")
	void create() {
		Form f = ConventionTestBed.form(" min-sante ");
		f.name = "  Personnel de santé ";
		f.maxAmountPerMonth = 300.0;
		long id = bed.create(f);
		Convention c = bed.row(id);
		assertEquals("MIN-SANTE", c.getCode());
		assertEquals("Personnel de santé", c.getName());
		assertEquals(ConventionStatus.ACTIVE, c.getStatus());
		assertEquals(ConventionRecognition.CARDS, c.getRecognition());
		assertEquals(5, c.getCardDigits());
		assertNull(c.getCardPrefix());
		assertEquals(RecordOrigin.LOCAL, c.getOrigin());
		assertEquals(0L, c.getLastCardSeq());
		assertEquals("admin", c.getCreatedBy());
		assertEquals("admin", c.getUpdatedBy());
		EventLogEntry created = bed.log.only("CONVENTION_CREATED");
		assertEquals("CONVENTION", created.getSubjectType());
		assertEquals(String.valueOf(id), created.getSubjectId());
		assertEquals("MIN-SANTE", created.getSubjectLabel());
		assertEquals("Convention MIN-SANTE « Personnel de santé » créée : Nom Personnel de santé ; Organisation Ministère"
				+ " de la Santé ; Du 01/11/2026 ; Reconnaissance cartes ; Montant max par mois 300,000 ; Chiffres des"
				+ " cartes 5.", created.getDetails());
	}

	@Test
	@DisplayName("The code: required, A-Z 0-9 '-' only, at most 30, unique whatever its case")
	void codeRules() {
		createRefused("CONVENTION_CODE_REQUIRED", f -> f.code = "   ");
		assertEquals("MIN 01", createRefused("CONVENTION_CODE_INVALID", f -> f.code = "min 01").getParams().get("code"));
		createRefused("CONVENTION_CODE_INVALID", f -> f.code = "MINÉ");
		createRefused("CONVENTION_CODE_INVALID", f -> f.code = "MIN_01");
		ConventionException tooLong = createRefused("CONVENTION_TOO_LONG", f -> f.code = repeat('A', 31));
		assertEquals("code", tooLong.getParams().get("field"));
		assertEquals(30, tooLong.getParams().get("max"));
		bed.create(ConventionTestBed.form(repeat('A', 30)));
		bed.create(ConventionTestBed.form("MIN"));
		assertEquals("MIN", createRefused("CONVENTION_CODE_TAKEN", f -> f.code = " min ").getParams().get("code"));
		assertEquals(2, bed.conventions.count());
		assertEquals(Arrays.asList("CONVENTION_CREATED", "CONVENTION_CREATED"), bed.log.codes(), "no event for a refusal");
	}

	@Test
	@DisplayName("Name and organisation required (blank is empty) and at most 150; a start date; the end not before the start")
	void textsAndDates() {
		createRefused("CONVENTION_NAME_REQUIRED", f -> f.name = "  ");
		createRefused("CONVENTION_ORGANISATION_REQUIRED", f -> f.organisation = null);
		assertEquals("organisation", createRefused("CONVENTION_TOO_LONG", f -> f.organisation = repeat('x', 151))
				.getParams().get("field"));
		createRefused("CONVENTION_START_REQUIRED", f -> f.startDate = null);
		ConventionException before = createRefused("CONVENTION_END_BEFORE_START", f -> f.endDate = LocalDate.of(2026, 10, 31));
		assertEquals("2026-11-01", before.getParams().get("start"));
		assertEquals("2026-10-31", before.getParams().get("end"));
		Form oneDay = ConventionTestBed.form("ONE-DAY");
		oneDay.endDate = oneDay.startDate;
		bed.create(oneDay);
		assertEquals(1, bed.conventions.count());
	}

	@Test
	@DisplayName("The three limits: empty (no limit) or above 0; recognition CARDS / NO_CARD; the prefix's characters; 4 to 9"
			+ " digits")
	void limitsAndCardSettings() {
		assertEquals("maxUsesPerDay", createRefused("CONVENTION_LIMIT_INVALID", f -> f.maxUsesPerDay = 0).getParams()
				.get("field"));
		assertEquals("maxUsesPerMonth", createRefused("CONVENTION_LIMIT_INVALID", f -> f.maxUsesPerMonth = -2).getParams()
				.get("field"));
		createRefused("CONVENTION_LIMIT_INVALID", f -> f.maxAmountPerMonth = 0.0);
		createRefused("CONVENTION_LIMIT_INVALID", f -> f.maxAmountPerMonth = Double.NaN);
		assertEquals("maybe", createRefused("CONVENTION_RECOGNITION_UNKNOWN", f -> f.recognition = "maybe").getParams()
				.get("value"));
		createRefused("CONVENTION_PREFIX_INVALID", f -> f.cardPrefix = "MS/");
		assertEquals("cardPrefix", createRefused("CONVENTION_TOO_LONG", f -> f.cardPrefix = repeat('P', 31)).getParams()
				.get("field"));
		ConventionException digits = createRefused("CONVENTION_CARD_DIGITS", f -> f.cardDigits = 3);
		assertEquals(4, digits.getParams().get("min"));
		assertEquals(9, digits.getParams().get("max"));
		createRefused("CONVENTION_CARD_DIGITS", f -> f.cardDigits = 10);
		assertEquals(0, bed.conventions.count());

		Form f = ConventionTestBed.form("NOCARD");
		f.recognition = "no_card";
		f.maxUsesPerDay = 1;
		f.maxUsesPerMonth = 20;
		f.maxAmountPerMonth = 0.5;
		f.cardPrefix = " ms ";
		f.cardDigits = 9;
		Convention c = bed.row(bed.create(f));
		assertEquals(ConventionRecognition.NO_CARD, c.getRecognition());
		assertEquals("MS", c.getCardPrefix());
		assertEquals(9, c.getCardDigits());
		assertEquals(20, c.getMaxUsesPerMonth());
	}

	// ─── Update ──────────────────────────────────────────────────────

	@Test
	@DisplayName("Edited: every field but the code; the code fixed (the same in any case, or none, is fine); one"
			+ " CONVENTION_UPDATED with each change before → after; a save that changes nothing writes nothing")
	void update() {
		long id = bed.create(ConventionTestBed.form("MIN"));
		ConventionException fixed = refused("CONVENTION_CODE_FIXED", () -> update(id, f -> f.code = "MIN2"));
		assertEquals("MIN", fixed.getParams().get("code"));
		assertEquals("MIN2", fixed.getParams().get("asked"));

		Map<String, Object> view = update(id, f -> {
			f.code = " min ";
			f.name = "Santé";
			f.endDate = LocalDate.of(2027, 6, 30);
			f.maxUsesPerDay = 2;
		});
		assertEquals("Santé", view.get("name"));
		assertEquals("manager", bed.row(id).getUpdatedBy());
		EventLogEntry updated = bed.log.only("CONVENTION_UPDATED");
		assertEquals("Convention MIN « Santé » modifiée : Nom : Convention MIN → Santé ; Au : — → 30/06/2027 ;"
				+ " Utilisations max par jour : — → 2.", updated.getDetails());

		update(id, f -> f.code = null); // the same values again
		assertEquals(1, bed.log.rows.stream().filter(r -> "CONVENTION_UPDATED".equals(r.getCode())).count());
		refused("CONVENTION_NOT_FOUND", () -> bed.tx(() -> bed.service.update(999, ConventionTestBed.form("MIN"), "x")));
	}

	@Test
	@DisplayName("With a card (blocked too): recognition, prefix and digits fixed, the rest editable; the prefix empty and"
			+ " the code are the same prefix")
	void cardSettingsFixed() {
		long id = bed.create(ConventionTestBed.form("MIN"));
		update(id, f -> f.recognition = "NO_CARD"); // no card yet: free
		update(id, f -> f.recognition = "CARDS");
		bed.card(id, "MIN-00001", ConventionCardStatus.BLOCKED);
		assertEquals("recognition", refused("CONVENTION_CARD_SETTINGS_FIXED", () -> update(id, f -> f.recognition = "NO_CARD"))
				.getParams().get("field"));
		assertEquals("cardPrefix", refused("CONVENTION_CARD_SETTINGS_FIXED", () -> update(id, f -> f.cardPrefix = "SANTE"))
				.getParams().get("field"));
		assertEquals("cardDigits", refused("CONVENTION_CARD_SETTINGS_FIXED", () -> update(id, f -> f.cardDigits = 6))
				.getParams().get("field"));
		update(id, f -> {
			f.cardPrefix = "min"; // the code: the prefix in effect is unchanged
			f.organisation = "Ministère";
			f.maxAmountPerMonth = 150.0;
		});
		Convention c = bed.row(id);
		assertEquals("MIN", c.getCardPrefix());
		assertEquals("Ministère", c.getOrganisation());
		update(id, f -> f.cardPrefix = null); // back to empty: still the code
		assertTrue((Boolean) bed.tx(() -> bed.service.get(id)).get("cardSettingsFixed"));
	}

	// ─── Status and delete ───────────────────────────────────────────

	@Test
	@DisplayName("Suspended and reactivated by their call (one event each, none when the status is already so); an unknown"
			+ " status refused; the form never changes the status")
	void status() {
		long id = bed.create(ConventionTestBed.form("MIN"));
		Map<String, Object> suspended = bed.tx(() -> bed.service.setStatus(id, "suspended", "manager"));
		assertEquals(ConventionStatus.SUSPENDED, suspended.get("status"));
		assertEquals(ConventionState.SUSPENDED, suspended.get("state"));
		bed.tx(() -> bed.service.setStatus(id, "SUSPENDED", "manager"));
		update(id, f -> f.name = "Toujours suspendue");
		assertEquals(ConventionStatus.SUSPENDED, bed.row(id).getStatus());
		bed.tx(() -> bed.service.setStatus(id, "ACTIVE", "manager"));
		assertEquals(Arrays.asList("CONVENTION_CREATED", "CONVENTION_SUSPENDED", "CONVENTION_UPDATED",
				"CONVENTION_REACTIVATED"), bed.log.codes());
		EventLogEntry back = bed.log.only("CONVENTION_REACTIVATED");
		assertEquals("SUSPENDED", back.getOldValue());
		assertEquals("ACTIVE", back.getNewValue());
		assertEquals("CLOSED", refused("CONVENTION_STATUS_UNKNOWN", () -> bed.tx(() -> bed.service.setStatus(id, "CLOSED",
				"x"))).getParams().get("value"));
		refused("CONVENTION_STATUS_UNKNOWN", () -> bed.tx(() -> bed.service.setStatus(id, null, "x")));
	}

	@Test
	@DisplayName("Deleted only without a card (one CONVENTION_DELETED); with one, even blocked, refused with the count and"
			+ " kept; 404 after")
	void delete() {
		long free = bed.create(ConventionTestBed.form("FREE"));
		long used = bed.create(ConventionTestBed.form("USED"));
		bed.card(used, "USED-00001", ConventionCardStatus.ACTIVE);
		bed.card(used, "USED-00002", ConventionCardStatus.BLOCKED);
		ConventionException hasCards = refused("CONVENTION_HAS_CARDS", () -> bed.tx(() -> bed.service.delete(used)));
		assertEquals(2L, hasCards.getParams().get("cards"));
		assertEquals("USED", hasCards.getParams().get("code"));
		assertEquals("FREE", bed.tx(() -> bed.service.delete(free)).get("code"));
		assertFalse(bed.conventions.existsById(free));
		assertTrue(bed.conventions.existsById(used));
		assertTrue(bed.log.only("CONVENTION_DELETED").getDetails().startsWith("Convention FREE « Convention FREE »"
				+ " supprimée : Nom Convention FREE ; Organisation"));
		assertEquals(free, refused("CONVENTION_NOT_FOUND", () -> bed.tx(() -> bed.service.delete(free))).getParams().get("id"));
	}

	@Test
	@DisplayName("Decision 2, a store whose promotions are the head office's: every change 409, nothing written; the reads"
			+ " stay")
	void ownedByHeadOffice() {
		long id = bed.create(ConventionTestBed.form("MIN"));
		bed.ownedByHeadOffice = true;
		for (Executable change : Arrays.<Executable>asList(() -> bed.create(ConventionTestBed.form("OTHER")),
				() -> update(id, f -> f.name = "X"), () -> bed.tx(() -> bed.service.setStatus(id, "SUSPENDED", "x")),
				() -> bed.tx(() -> bed.service.delete(id)))) {
			assertEquals(409, refused("CONVENTION_OWNED_BY_HEAD_OFFICE", change).getStatus());
		}
		assertEquals(1, bed.conventions.count());
		assertEquals("Convention MIN", bed.row(id).getName());
		assertEquals(Collections.singletonList("CONVENTION_CREATED"), bed.log.codes());
		assertEquals("MIN", bed.tx(() -> bed.service.get(id)).get("code"));
		assertEquals(1L, bed.tx(() -> bed.service.list(null, null, 0, 20)).get("totalElements"));
	}

	// ─── The list ────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> rows(String status, String q) {
		return (List<Map<String, Object>>) bed.tx(() -> bed.service.list(status, q, 0, 20)).get("content");
	}

	private List<Object> codes(String status, String q) {
		return rows(status, q).stream().map(r -> r.get("code")).collect(Collectors.toList());
	}

	@Test
	@DisplayName("The list by code: the cards active / blocked per convention, the state of today from the dates, the status"
			+ " filter, the search in code, name and organisation (any case, % and _ as typed), the pages")
	void list() {
		Form expired = ConventionTestBed.form("B-OLD");
		expired.endDate = LocalDate.of(2026, 11, 27);
		bed.create(expired);
		Form upcoming = ConventionTestBed.form("C-NEXT");
		upcoming.startDate = LocalDate.of(2026, 11, 29);
		upcoming.organisation = "Banque 100% Tunisienne";
		bed.create(upcoming);
		long active = bed.create(ConventionTestBed.form("A-NOW"));
		Form lastDay = ConventionTestBed.form("D_LAST");
		lastDay.code = "D-LAST";
		lastDay.name = "Dernier_jour";
		lastDay.endDate = bed.today;
		long last = bed.create(lastDay);
		bed.tx(() -> bed.service.setStatus(last, "SUSPENDED", "x"));
		bed.card(active, "A-NOW-00001", ConventionCardStatus.ACTIVE);
		bed.card(active, "A-NOW-00002", ConventionCardStatus.ACTIVE);
		bed.card(active, "A-NOW-00003", ConventionCardStatus.BLOCKED);

		List<Map<String, Object>> all = rows("all", null);
		assertEquals(Arrays.asList("A-NOW", "B-OLD", "C-NEXT", "D-LAST"), all.stream().map(r -> r.get("code"))
				.collect(Collectors.toList()));
		assertEquals(Arrays.asList(ConventionState.ACTIVE, ConventionState.EXPIRED, ConventionState.UPCOMING,
				ConventionState.SUSPENDED), all.stream().map(r -> r.get("state")).collect(Collectors.toList()));
		assertEquals(2L, all.get(0).get("cardsActive"));
		assertEquals(1L, all.get(0).get("cardsBlocked"));
		assertEquals(true, all.get(0).get("cardSettingsFixed"));
		assertEquals(0L, all.get(1).get("cardsActive"));
		assertEquals(false, all.get(1).get("cardSettingsFixed"));

		assertEquals(Arrays.asList("A-NOW", "B-OLD", "C-NEXT"), codes("ACTIVE", null), "the stored status");
		assertEquals(Collections.singletonList("D-LAST"), codes("suspended", " "));
		assertEquals(codes(null, null), codes("", null));
		refused("CONVENTION_STATUS_UNKNOWN", () -> rows("EXPIRED", null));

		assertEquals(Collections.singletonList("B-OLD"), codes(null, "b-o"), "the code");
		assertEquals(Collections.singletonList("C-NEXT"), codes(null, "100%"), "% as typed");
		assertEquals(Collections.singletonList("D-LAST"), codes(null, "r_j"), "_ as typed");
		assertEquals(Arrays.asList("A-NOW", "B-OLD", "D-LAST"), codes(null, "MINISTÈRE"), "the organisation, any case");
		assertEquals(Collections.emptyList(), codes(null, "zz"));

		Map<String, Object> second = bed.tx(() -> bed.service.list(null, null, 1, 3));
		assertEquals(4L, second.get("totalElements"));
		assertEquals(2, second.get("totalPages"));
		assertEquals(1, ((List<?>) second.get("content")).size());
		refused("CONVENTION_NOT_FOUND", () -> bed.tx(() -> bed.service.get(12345)));
	}

	@Test
	@DisplayName("The state: within its dates (both days included) ACTIVE, before UPCOMING, after EXPIRED, suspended"
			+ " SUSPENDED whatever the dates")
	void stateOn() {
		Convention c = new Convention();
		c.setStartDate(LocalDate.of(2026, 12, 1));
		c.setEndDate(LocalDate.of(2026, 12, 31));
		assertEquals(ConventionState.UPCOMING, c.stateOn(LocalDate.of(2026, 11, 30)));
		assertEquals(ConventionState.ACTIVE, c.stateOn(LocalDate.of(2026, 12, 1)));
		assertEquals(ConventionState.ACTIVE, c.stateOn(LocalDate.of(2026, 12, 31)));
		assertEquals(ConventionState.EXPIRED, c.stateOn(LocalDate.of(2027, 1, 1)));
		c.setEndDate(null);
		assertEquals(ConventionState.ACTIVE, c.stateOn(LocalDate.of(2040, 1, 1)));
		c.setStatus(ConventionStatus.SUSPENDED);
		assertEquals(ConventionState.SUSPENDED, c.stateOn(LocalDate.of(2026, 11, 30)));
	}

	private static String repeat(char c, int n) {
		char[] chars = new char[n];
		Arrays.fill(chars, c);
		return new String(chars);
	}
}
