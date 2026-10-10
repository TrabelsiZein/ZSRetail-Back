package com.digithink.zsretail.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.utils.Quantities;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * 2.2.1: the quantity columns converted by db/2.2.1/update.sql must be DECIMAL(18,3), or SQL Server would cut 0.2 to 0
 * in an int column without an error. The version guard (AppVersionGuard) already stops a 2.2.1 application on a 2.2.0
 * database; this check covers what it lets through (APP_VERSION set by hand, an existing database with an empty
 * APP_VERSION). Checked once at start-up: a column still int is named in an error of the log, and decimal quantities are
 * refused (QuantityPolicy, /config) with a message asking for the script; whole quantities work as in 2.2.0. A table
 * absent from this database (a store without a head office link, ho_ tables on a store) is skipped, as by the script.
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class DecimalColumnsCheck {

	/** The columns of db/2.2.1/update.sql (#zs_221_columns), as "table.column". Keep both lists the same. */
	public static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList(
			"item.stock_quantity", "sales_line.quantity", "stock_movement.quantity",
			"ho_ticket_line.quantity", "ho_store_stock.quantity", "hol_stock_copy.quantity_sent",
			"inventory_count_line.counted_quantity", "inventory_count_line.system_quantity_at_import",
			"inventory_count_line.system_quantity_at_validation", "inventory_count_line.difference_applied",
			"ho_erp_invoice_line.quantity", "ho_erp_invoice_line.quantity_received",
			"ho_delivery_line.quantity_sent", "ho_delivery_line.quantity_received",
			"hol_delivery_line.quantity_sent", "hol_delivery_line.quantity_received",
			"purchase_invoice_line.quantity"));

	public static final String SCRIPT_MESSAGE = "decimal quantities need the 2.2.1 database script (db/2.2.1/update.sql),"
			+ " not run on this database";

	private final JdbcTemplate jdbc;

	/** The columns still not DECIMAL(18,3); null until checked. */
	private volatile List<String> notConverted;

	@EventListener(ApplicationReadyEvent.class)
	public void checkAtStartup() {
		check();
	}

	/** True when every listed column present is DECIMAL(18,3). Checked once (at start-up, or here if asked before). */
	public boolean ready() {
		List<String> left = notConverted;
		if (left == null) {
			left = check();
		}
		return left.isEmpty();
	}

	private synchronized List<String> check() {
		if (notConverted != null) {
			return notConverted;
		}
		List<String> left;
		try {
			left = notConverted(jdbc.queryForList("SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, NUMERIC_PRECISION,"
					+ " NUMERIC_SCALE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = SCHEMA_NAME()"));
		} catch (RuntimeException e) {
			log.error("2.2.1: the quantity columns could not be checked ({}); decimal quantities are refused", e.getMessage());
			left = Collections.singletonList("(not checked)");
		}
		if (left.isEmpty()) {
			log.info("2.2.1: the quantity columns are DECIMAL(18,3)");
		} else {
			log.error("═══════════════════════════════════════════════════════");
			log.error("  2.2.1: these quantity columns are not DECIMAL(18,3): {}", String.join(", ", left));
			log.error("  → Run db/2.2.1/update.sql. Until then decimal quantities are refused (Allow decimal quantities");
			log.error("    is treated as off); whole quantities work as in 2.2.0.");
			log.error("═══════════════════════════════════════════════════════");
		}
		notConverted = left;
		return left;
	}

	/** The listed columns present in these INFORMATION_SCHEMA.COLUMNS rows and not DECIMAL(18,3), in list order. */
	static List<String> notConverted(List<Map<String, Object>> rows) {
		Map<String, Map<String, Object>> byName = rows.stream().collect(Collectors.toMap(
				row -> (row.get("TABLE_NAME") + "." + row.get("COLUMN_NAME")).toLowerCase(Locale.ROOT), row -> row,
				(first, second) -> first));
		List<String> left = new ArrayList<>();
		for (String column : COLUMNS) {
			Map<String, Object> row = byName.get(column);
			if (row != null && !decimal183(row)) {
				left.add(column + " (" + row.get("DATA_TYPE") + ")");
			}
		}
		return left;
	}

	/** DECIMAL(18,3) or NUMERIC(18,3), the same type in SQL Server (Hibernate creates a new table's column as numeric), as the
	 * script tests it. */
	private static boolean decimal183(Map<String, Object> row) {
		String type = String.valueOf(row.get("DATA_TYPE"));
		return ("decimal".equalsIgnoreCase(type) || "numeric".equalsIgnoreCase(type))
				&& row.get("NUMERIC_PRECISION") instanceof Number
				&& ((Number) row.get("NUMERIC_PRECISION")).intValue() == Quantities.PRECISION
				&& row.get("NUMERIC_SCALE") instanceof Number
				&& ((Number) row.get("NUMERIC_SCALE")).intValue() == Quantities.SCALE;
	}
}
