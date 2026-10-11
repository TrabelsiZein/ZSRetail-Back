package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.repository.GeneralSetupRepository;

/**
 * 2.2.1, step 6: a 2.2.1 application on a database whose quantity columns are still int (script not run, APP_VERSION
 * set by hand) names the columns, refuses decimals with a message asking for the script, and keeps whole quantities as
 * in 2.2.0.
 */
class DecimalColumnsCheckTest {

	private static Map<String, Object> column(String table, String column, String type, Integer precision, Integer scale) {
		Map<String, Object> row = new HashMap<>();
		row.put("TABLE_NAME", table);
		row.put("COLUMN_NAME", column);
		row.put("DATA_TYPE", type);
		row.put("NUMERIC_PRECISION", precision);
		row.put("NUMERIC_SCALE", scale);
		return row;
	}

	/** Every listed column as DECIMAL(18,3), then the given ones replaced. */
	private static List<Map<String, Object>> converted(Map<String, Object>... replaced) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (String name : DecimalColumnsCheck.COLUMNS) {
			String[] parts = name.split("\\.");
			boolean other = Arrays.stream(replaced).anyMatch(r -> r.get("TABLE_NAME").equals(parts[0])
					&& r.get("COLUMN_NAME").equals(parts[1]));
			if (!other) {
				rows.add(column(parts[0], parts[1], "decimal", 18, 3));
			}
		}
		rows.addAll(Arrays.asList(replaced));
		rows.add(column("item", "unit_price", "float", 53, null)); // not a quantity: ignored
		return rows;
	}

	@Test
	@DisplayName("The 17 columns of 2.2.1 and the 2 of 2.2.2; all DECIMAL(18,3): nothing left; absent tables skipped as by the script")
	@SuppressWarnings("unchecked")
	void allConverted() {
		assertEquals(19, DecimalColumnsCheck.COLUMNS.size());
		assertTrue(DecimalColumnsCheck.notConverted(converted()).isEmpty());
		List<Map<String, Object>> storeOnly = new ArrayList<>(converted());
		storeOnly.removeIf(row -> String.valueOf(row.get("TABLE_NAME")).startsWith("ho_"));
		assertTrue(DecimalColumnsCheck.notConverted(storeOnly).isEmpty());
	}

	@Test
	@DisplayName("A table created by Hibernate after the script (Bardo's inventory tables): NUMERIC(18,3) is DECIMAL(18,3), accepted as by the script")
	@SuppressWarnings("unchecked")
	void numericAccepted() {
		assertTrue(DecimalColumnsCheck.notConverted(converted(column("inventory_count_line", "counted_quantity", "numeric", 18, 3),
				column("inventory_count_line", "difference_applied", "numeric", 18, 3))).isEmpty());
		assertEquals(Arrays.asList("inventory_count_line.counted_quantity (numeric)"), DecimalColumnsCheck.notConverted(
				converted(column("inventory_count_line", "counted_quantity", "numeric", 19, 2))));
	}

	@Test
	@DisplayName("A 2.2.0 database: the int columns named in list order, a wrong scale too")
	@SuppressWarnings("unchecked")
	void intColumnsNamed() {
		assertEquals(Arrays.asList("item.stock_quantity (int)", "sales_line.quantity (int)", "purchase_invoice_line.quantity (decimal)"),
				DecimalColumnsCheck.notConverted(converted(column("sales_line", "quantity", "int", 10, 0),
						column("item", "stock_quantity", "int", 10, 0),
						column("purchase_invoice_line", "quantity", "decimal", 18, 2))));
	}

	@Test
	@DisplayName("Columns still int: decimals refused asking for the script, even with the setting on; whole quantities as in 2.2.0; /config off")
	@SuppressWarnings("unchecked")
	void policyWithIntColumns() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForList(anyString())).thenReturn(converted(column("sales_line", "quantity", "int", 10, 0)));
		DecimalColumnsCheck check = new DecimalColumnsCheck(jdbc);
		check.checkAtStartup();
		assertFalse(check.ready());
		verify(jdbc, times(1)).queryForList(anyString()); // checked once

		QuantityPolicy policy = policy(check, "true");
		assertFalse(policy.decimalAllowed());
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> policy.check(new BigDecimal("0.2"), "VH52-1L"));
		assertEquals("Quantity 0.2 of item VH52-1L: decimal quantities need the database scripts of 2.2.1 and 2.2.2"
				+ " (db/2.2.1/update.sql, db/2.2.2/update.sql), not run on this database", refused.getMessage());
		assertDoesNotThrow(() -> policy.check(new BigDecimal("2"), "B001"));
		assertDoesNotThrow(() -> policy.check(new BigDecimal("2.000"), "B001"));

		// setting off: the message of 2.2.0's setting, as before
		IllegalArgumentException off = assertThrows(IllegalArgumentException.class,
				() -> policy(check, "false").check(new BigDecimal("0.2"), "VH52-1L"));
		assertTrue(off.getMessage().endsWith("decimal quantities are not allowed in this store (General Setup, Allow decimal quantities)"));
	}

	@Test
	@DisplayName("Columns converted: the setting decides, as in steps 1 to 5")
	@SuppressWarnings("unchecked")
	void policyWithConvertedColumns() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForList(anyString())).thenReturn(converted());
		DecimalColumnsCheck check = new DecimalColumnsCheck(jdbc);
		assertTrue(check.ready());
		assertTrue(policy(check, "true").decimalAllowed());
		assertDoesNotThrow(() -> policy(check, "true").check(new BigDecimal("0.2"), "VH52-1L"));
		assertFalse(policy(check, "false").decimalAllowed());
	}

	@Test
	@DisplayName("The check cannot run (no access to INFORMATION_SCHEMA): decimals refused, never a guess")
	void checkFails() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForList(anyString())).thenThrow(new IllegalStateException("no access"));
		assertFalse(new DecimalColumnsCheck(jdbc).ready());
	}

	private static QuantityPolicy policy(DecimalColumnsCheck check, String setting) throws Exception {
		GeneralSetupRepository setup = mock(GeneralSetupRepository.class);
		GeneralSetup row = new GeneralSetup();
		row.setCode(QuantityPolicy.SETTING);
		row.setValeur(setting);
		when(setup.findByCode(QuantityPolicy.SETTING)).thenReturn(Optional.of(row));
		QuantityPolicy policy = new QuantityPolicy();
		set(policy, "generalSetupRepository", setup);
		set(policy, "decimalColumns", check);
		return policy;
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = QuantityPolicy.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
