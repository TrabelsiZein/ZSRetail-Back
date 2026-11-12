package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;

/**
 * 3.2.0, payment methods: the statements run at each start (PaymentMethodsUpgrade) and by db/3.2.0/update.sql, the same
 * text in the same order. Reads the script's text and mocks the JDBC: no database (the statements were run on a
 * throwaway database when the step was built: a 3.1.2 database, rows with payments, a second run, a new database).
 */
class PaymentMethodsUpgradeTest {

	private static final String SCRIPT = "/db/3.2.0/update.sql";

	/** Whitespace compacted, comments removed. */
	private static String compact(String text) {
		return text.replaceAll("(?m)--.*$", " ").replaceAll("\\s+", " ").trim();
	}

	private static String script() throws Exception {
		try (InputStream in = PaymentMethodsUpgradeTest.class.getResourceAsStream(SCRIPT)) {
			assertNotNull(in, SCRIPT);
			return compact(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private static int count(String text, String part) {
		return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
	}

	@Test
	@DisplayName("The script holds the startup's statements, each a batch of its own, in order, before APP_VERSION 3.2.0")
	void scriptHasTheSameStatements() throws Exception {
		String sql = script();
		int versionCheck = sql.indexOf("IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('3.1.2', '3.2.0'))");
		int stamp = sql.indexOf("UPDATE APP_VERSION SET version = '3.2.0';");
		assertTrue(versionCheck > 0 && stamp > versionCheck, "the version check, then the stamp");
		int previous = versionCheck;
		for (String statement : PaymentMethodsUpgrade.STATEMENTS) {
			int at = sql.indexOf("GO " + compact(statement) + " GO", previous);
			assertTrue(at > previous, "in order and alone in its batch: " + statement);
			previous = at;
		}
		assertTrue(previous < stamp, "every statement before APP_VERSION is stamped");
		assertEquals(1, count(sql, "UPDATE APP_VERSION"));
	}

	@Test
	@DisplayName("The order: the constraint dropped, the column nullable, the index, the two columns, the conversion")
	void statementsInTheirOrder() {
		List<String> statements = PaymentMethodsUpgrade.STATEMENTS;
		assertEquals(6, statements.size());
		assertTrue(statements.get(0).contains("ALTER TABLE payment_method DROP CONSTRAINT ' + QUOTENAME(@uk)"));
		assertTrue(statements.get(0).contains("k.type = 'UQ' AND c.name = 'type'"), "a unique constraint on type only");
		assertTrue(statements.get(1).contains("ALTER TABLE payment_method ALTER COLUMN type ' + @type + ' NULL"));
		assertTrue(statements.get(1).contains("AND is_nullable = 0"), "only while the column refuses NULLs");
		assertTrue(statements.get(2).endsWith(
				"CREATE UNIQUE INDEX ux_payment_method_type ON payment_method (type) WHERE type IS NOT NULL"));
		assertTrue(statements.get(2).contains("NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ux_payment_method_type'"));
		assertTrue(statements.get(3).endsWith("COL_LENGTH(N'payment_method', N'behaviour') IS NULL"
				+ " ALTER TABLE payment_method ADD behaviour varchar(255) NULL"));
		assertTrue(statements.get(4).endsWith("COL_LENGTH(N'payment_method', N'title_number_length') IS NULL"
				+ " ALTER TABLE payment_method ADD title_number_length int NULL"));
		assertEquals(PaymentMethodsUpgrade.CONVERSION, statements.get(5));
	}

	@Test
	@DisplayName("The conversion: one transaction, the lengths copied before the two rows lose their type, then the settings deleted")
	void conversion() {
		String sql = compact(PaymentMethodsUpgrade.CONVERSION);
		assertTrue(sql.startsWith("IF COL_LENGTH(N'payment_method', N'behaviour') IS NOT NULL"
				+ " AND COL_LENGTH(N'payment_method', N'title_number_length') IS NOT NULL"
				+ " AND OBJECT_ID(N'general_setup', N'U') IS NOT NULL EXEC('BEGIN TRY BEGIN TRANSACTION;"), sql);
		int copy = sql.indexOf("UPDATE m SET m.title_number_length = CASE WHEN t.v <> '''' AND t.v NOT LIKE"
				+ " ''%[^0-9]%'' AND LEN(t.v) <= 9 THEN NULLIF(CAST(t.v AS int), 0) END FROM payment_method m JOIN"
				+ " general_setup g ON g.code = ''PAYMENT_METHOD_'' + m.type + ''_TITLE_NUMBER_LENGTH''");
		int keepSet = sql.indexOf("WHERE m.type IS NOT NULL AND m.title_number_length IS NULL;");
		int convert = sql.indexOf("UPDATE payment_method SET type = NULL, behaviour = ''AMOUNT'', updated_at ="
				+ " SYSDATETIME(), updated_by = ''System'' WHERE type IN (''TICKET_RESTAURANT'', ''CHEQUE_CADEAU'');");
		int amount = sql.indexOf("UPDATE payment_method SET behaviour = ''AMOUNT'' WHERE behaviour IS NULL;");
		int delete = sql.indexOf("DELETE FROM general_setup WHERE code IN (''PAYMENT_METHOD_CLIENT_CHEQUE_TITLE_NUMBER_LENGTH'',"
				+ " ''PAYMENT_METHOD_TICKET_RESTAURANT_TITLE_NUMBER_LENGTH'', ''PAYMENT_METHOD_CHEQUE_CADEAU_TITLE_NUMBER_LENGTH'',"
				+ " ''PAYMENT_METHOD_CLIENT_TRAITE_TITLE_NUMBER_LENGTH'');");
		int commit = sql.indexOf("COMMIT TRANSACTION;");
		assertTrue(copy > 0 && copy < keepSet && keepSet < convert && convert < amount && amount < delete
				&& delete < commit, sql);
		assertTrue(sql.endsWith("END TRY BEGIN CATCH IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION; THROW; END CATCH')"), sql);
		// No payment method is deleted or inserted; one general_setup DELETE; no TRY_CAST (old compatibility levels)
		assertEquals(0, count(sql, "DELETE FROM payment_method"));
		assertEquals(0, count(sql, "INSERT"));
		assertEquals(1, count(sql, "DELETE FROM"));
		assertEquals(0, count(sql.toUpperCase(), "TRY_CAST"));
	}

	@Test
	@DisplayName("At startup: every statement run once, in order, the next one run when one fails")
	void runsEachInOrder() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		doThrow(new DataAccessResourceFailureException("refused")).when(jdbc)
				.execute(PaymentMethodsUpgrade.DROP_TYPE_UNIQUE_CONSTRAINT);
		new PaymentMethodsUpgrade(jdbc).run();
		InOrder order = inOrder(jdbc);
		for (String statement : PaymentMethodsUpgrade.STATEMENTS) {
			order.verify(jdbc).execute(statement);
		}
		verify(jdbc, times(PaymentMethodsUpgrade.STATEMENTS.size())).execute(anyString());
	}

	@Test
	@DisplayName("The two converted types are no longer values of the enum; the eight fixed ones stay")
	void enumValues() {
		List<String> names = Stream.of(PaymentMethodType.values()).map(Enum::name).collect(Collectors.toList());
		assertEquals(java.util.Arrays.asList("CLIENT_ESPECES", "CLIENT_CHEQUE", "CLIENT_TPE", "CLIENT_TRAITE",
				"DEPOT_BANQUE", "RETURN_VOUCHER", "VIREMENT_BANCAIRE", "RETENUE_SOURCE"), names);
	}

	@Test
	@DisplayName("No main source reads a setting PAYMENT_METHOD_<type>_TITLE_NUMBER_LENGTH any more (only its deletion here)")
	void noTitleNumberLengthSetting() throws Exception {
		List<Path> found = new ArrayList<>();
		for (String root : new String[] { "src/main", "../store/src/main", "../headoffice/src/main" }) {
			try (Stream<Path> files = Files.walk(Paths.get(root))) {
				files.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".java"))
						.filter(f -> !f.getFileName().toString().equals("PaymentMethodsUpgrade.java"))
						.filter(PaymentMethodsUpgradeTest::readsALengthSetting).forEach(found::add);
			}
		}
		assertTrue(found.isEmpty(), found.toString());
	}

	private static boolean readsALengthSetting(Path file) {
		try {
			String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
			return text.contains("_TITLE_NUMBER_LENGTH\"") || text.contains("\"PAYMENT_METHOD_\"");
		} catch (Exception e) {
			throw new IllegalStateException(file.toString(), e);
		}
	}
}
