package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 3.1.0, payment method "Retenue à la source" in db/3.1.0/update.sql: one insert, inactive with the neutral code,
 * only when no row of the type exists (EMTOP's row from hotfix 1.12.1 stays as it is); no statement changes or
 * removes a payment method; the notes are written again on each run. Reads the script's text: no database.
 */
class PaymentMethodScriptTest {

	private static final String SCRIPT = "/db/3.1.0/update.sql";

	/** The script without its comments, whitespace compacted. */
	private static String statements() throws Exception {
		try (InputStream in = PaymentMethodScriptTest.class.getResourceAsStream(SCRIPT)) {
			assertNotNull(in, SCRIPT);
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			return text.replaceAll("(?m)--.*$", " ").replaceAll("\\s+", " ");
		}
	}

	private static int count(String text, String regex) {
		Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		return count;
	}

	@Test
	@DisplayName("Retenue à la source is inserted once, inactive, code RETENUE_SOURCE, only without a row of its type")
	void insertsRetenueSourceInactiveOnlyWhenMissing() throws Exception {
		String sql = statements();
		assertEquals(1, count(sql, "INSERT INTO payment_method"));
		assertTrue(sql.contains("IF NOT EXISTS (SELECT 1 FROM payment_method WHERE type = 'RETENUE_SOURCE')"
				+ " AND NOT EXISTS (SELECT 1 FROM payment_method WHERE code = 'RETENUE_SOURCE')"
				+ " BEGIN INSERT INTO payment_method (code, name, type, description, active, display_order,"
				+ " require_title_number,"), sql);
		// active 0, order 10, no title number, due date, drawer name, issuing bank or confirmation
		assertTrue(sql.contains("VALUES ('RETENUE_SOURCE', N'Retenue à la source', 'RETENUE_SOURCE',"
				+ " N'Paiement par retenue à la source', 0, 10, 0, 0, 0, 0, 0,"), sql);
	}

	@Test
	@DisplayName("No statement changes or removes a payment method (an existing Retenue à la source row is kept)")
	void neverChangesAPaymentMethod() throws Exception {
		String sql = statements();
		assertEquals(0, count(sql, "UPDATE\\s+payment_method"));
		assertEquals(0, count(sql, "DELETE\\s+(FROM\\s+)?payment_method"));
		assertEquals(0, count(sql, "MERGE\\s+(INTO\\s+)?payment_method"));
		assertEquals(0, count(sql, "TRUNCATE\\s+TABLE\\s+payment_method"));
	}

	@Test
	@DisplayName("The 3.1.0 notes are removed then written, so a second run adds nothing")
	void releaseNotesRunTwice() throws Exception {
		String sql = statements();
		int delete = sql.indexOf("DELETE FROM APP_RELEASE_NOTES WHERE version = '3.1.0';");
		int insert = sql.indexOf("INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES ('3.1.0', 'NEW',"
				+ " N'Nouveau moyen de paiement « Retenue à la source »");
		assertTrue(delete >= 0, "DELETE of the 3.1.0 notes");
		assertTrue(insert > delete, "the note, after the DELETE");
		assertEquals(1, count(sql, "INSERT INTO APP_RELEASE_NOTES"));
	}

	@Test
	@DisplayName("No customer ERP code in core: ENCAISS-RS-CLT is typed by the customer's admin, never shipped")
	void noCustomerCodeInCore() throws Exception {
		List<Path> found;
		try (Stream<Path> files = Files.walk(Paths.get("src/main"))) {
			found = files.filter(Files::isRegularFile).filter(PaymentMethodScriptTest::mentionsEncaiss)
					.collect(Collectors.toList());
		}
		assertTrue(found.isEmpty(), found.toString());
		assertFalse(statements().contains("ENCAISS"));
	}

	private static boolean mentionsEncaiss(Path file) {
		try {
			return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains("ENCAISS");
		} catch (Exception e) {
			throw new IllegalStateException(file.toString(), e);
		}
	}
}
