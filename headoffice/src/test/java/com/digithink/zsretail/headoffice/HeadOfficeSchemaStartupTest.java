package com.digithink.zsretail.headoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.support.FlywayStartups;
import com.digithink.zsretail.core.support.MigTestApplication;
import com.digithink.zsretail.core.support.QuerySweep;
import com.digithink.zsretail.core.support.Upgrade312;

/**
 * Boot 4 migration, step 1.8: a head office's schema is Flyway's (core.support.FlywayStartups), real starts on
 * zsretail_migtest. The record, schema/headoffice.txt, is the schema of a new head office database; a head office upgraded
 * from 3.1.2 ends with the same one.
 */
class HeadOfficeSchemaStartupTest {

	private final FlywayStartups headOffice = new FlywayStartups(HeadOfficeApplication.class, "headoffice");

	/** The 80 @Query of core and the 68 of a head office, run twice by QuerySweep. */
	private static final String CALLS = "calls 296";

	@Test
	@DisplayName("A new database: the baseline, catch-up 1, Hibernate's check, the schema of the record")
	void newDatabase() {
		headOffice.newDatabase();
	}

	@Test
	@DisplayName("A database at 3.1.2: taken over, catch-up 1 run, its item kept, the same schema as a new database")
	void upgradeFrom312() {
		headOffice.upgradeFrom312();
	}

	@Test
	@DisplayName("Catch-up 1 on a head office at 3.1.2: the indexes only the old scripts made, those of a head office's tables")
	void indexesOfTheOldScripts() {
		assertEquals(Arrays.asList("ix_ho_store_order_line_order", "ix_sales_header_session_status", "ix_stock_movement_created",
				"ix_stock_movement_item"), headOffice.indexesCreatedFrom312());
	}

	@Test
	@DisplayName("Refused, nothing written: an older version, no APP_VERSION, a 3.1.2 without its bulk sale or its costs")
	void refusals() {
		headOffice.refused(Upgrade312.stamped("headoffice", "3.1.1"), "this one is at 3.1.1");
		headOffice.refused(database -> database.run("CREATE TABLE not_ours (id int)"), "no APP_VERSION table");
		headOffice.refused(
				Upgrade312.at312("headoffice").andThen(database -> database.run("ALTER TABLE item DROP COLUMN sold_in_bulk")),
				"run db/3.1.2/bulk-sale.sql");
		headOffice.refused(Upgrade312.at312("headoffice").andThen(database -> database.run("DROP TABLE item_cost_history")),
				"run db/3.1.2/item-cost-history.sql");
	}

	@Test
	@DisplayName("A database a newer version ran on: refused at Flyway's check, nothing written")
	void newerDatabase() {
		String messages = headOffice.newerDatabase();
		assertTrue(messages.contains("9.9.9"), messages);
	}

	@Test
	@DisplayName("Every @Query of a head office on SQL Server, with nulls and with samples")
	void queriesOnSqlServer() {
		try (MigTestApplication started = MigTestApplication.start(HeadOfficeApplication.class, "headoffice")) {
			List<String> sweep = QuerySweep.run(started);
			assertEquals(CALLS, sweep.get(0), String.join("\n", QuerySweep.queries(started)));
			assertEquals(Collections.emptyList(), sweep.subList(1, sweep.size()), String.join("\n", sweep));
		}
	}
}
