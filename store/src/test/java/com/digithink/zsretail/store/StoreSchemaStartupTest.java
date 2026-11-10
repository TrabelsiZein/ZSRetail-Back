package com.digithink.zsretail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.support.FlywayStartups;
import com.digithink.zsretail.core.support.MigTestApplication;
import com.digithink.zsretail.core.support.QueryResultTypes;
import com.digithink.zsretail.core.support.QuerySweep;
import com.digithink.zsretail.core.support.SchemaCatalog;
import com.digithink.zsretail.core.support.Upgrade312;

/**
 * Boot 4 migration, step 1.8: a store's schema is Flyway's (core.support.FlywayStartups), real starts on zsretail_migtest.
 * The record, schema/store.txt, is the schema of a new store database; a store upgraded from 3.1.2 ends with the same one.
 */
class StoreSchemaStartupTest {

	private final FlywayStartups store = new FlywayStartups(StoreApplication.class, "store");

	/** The 80 @Query of core and the 37 of a store without RFID, run twice by QuerySweep. */
	private static final String CALLS = "calls 234";

	@Test
	@DisplayName("A new database: the baseline, catch-up 1, Hibernate's check, the schema of the record")
	void newDatabase() {
		store.newDatabase();
	}

	@Test
	@DisplayName("A database at 3.1.2: taken over, catch-up 1 run, its item kept, the same schema as a new database")
	void upgradeFrom312() {
		store.upgradeFrom312();
	}

	@Test
	@DisplayName("Catch-up 1 on a store at 3.1.2: the indexes only the old scripts made, those of a store's tables")
	void indexesOfTheOldScripts() {
		assertEquals(Arrays.asList("ix_pos_machine_reader", "ix_sales_header_session_status", "ix_stock_movement_created",
				"ix_stock_movement_item"), store.indexesCreatedFrom312());
	}

	@Test
	@DisplayName("Refused, nothing written: an older version, an empty APP_VERSION, no APP_VERSION, a 3.1.2 without its bulk sale")
	void refusals() {
		store.refused(Upgrade312.stamped("store", "3.1.1"), "this one is at 3.1.1");
		store.refused(Upgrade312.stamped("store", null), "this one is at (empty)");
		store.refused(database -> database.run("CREATE TABLE not_ours (id int)"), "no APP_VERSION table");
		store.refused(Upgrade312.at312("store").andThen(database -> database.run("ALTER TABLE item DROP COLUMN sold_in_bulk")),
				"run db/3.1.2/bulk-sale.sql");
		store.refused(Upgrade312.at312("store").andThen(database -> database.run("DROP TABLE quick_quantity")),
				"run db/3.1.2/bulk-sale.sql");
		store.refused(Upgrade312.at312("store").andThen(database -> database.run("DROP TABLE item_cost_history")),
				"run db/3.1.2/item-cost-history.sql");
		store.refused(Upgrade312.at312("store").andThen(database -> database.run("ALTER TABLE sales_line DROP COLUMN unit_cost")),
				"run db/3.1.2/sales-line-unit-cost.sql");
	}

	@Test
	@DisplayName("A database a newer version ran on: refused at Flyway's check, nothing written")
	void newerDatabase() {
		String messages = store.newerDatabase();
		assertTrue(messages.contains("9.9.9"), messages);
	}

	@Test
	@DisplayName("Hibernate 6.6 and a client's ids: an unknown id refused, nothing written (3.1.2 inserted a row); group items by id saved")
	void clientIds() {
		try (MigTestApplication started = MigTestApplication.start(StoreApplication.class, "store")) {
			started.withLicense();
			String[] admin = started.bearerOf("admin", "P@ssw0rd");
			// _BaseController.create saves what it is sent. An id the table does not have: release/3.1.2 answered 201 and
			// inserted a new row under a new id; Hibernate 6.6 refuses the merge. The front sends id: null.
			MigTestApplication.Answer unknown = started.call("POST", "/member-function", "{\"id\":999999,\"code\":\"MIG-ID\",\"name\":\"x\"}",
					admin);
			assertEquals(500, unknown.status, unknown.body);
			assertTrue(unknown.body.contains("MemberFunction#999999"), unknown.body);
			assertEquals(201, started.call("POST", "/member-function", "{\"id\":null,\"code\":\"MIG-NULL\",\"name\":\"x\"}", admin).status);
			assertEquals(201, started.call("POST", "/member-function", "{\"code\":\"MIG-NONE\",\"name\":\"x\"}", admin).status);
			assertEquals(Arrays.asList("MIG-NONE", "MIG-NULL"),
					started.database().lines("SELECT code FROM member_function ORDER BY code"));
			// GroupItemsDeserializer: the items of a group as { id } only; PromotionService.save loads them
			started.database().run(QueryResultTypes.ITEMS);
			String item = started.database().lines("SELECT id FROM item WHERE item_code = 'MIG-1'").get(0);
			MigTestApplication.Answer promotion = started.call("POST", "/promotion", "{\"code\":\"MIG-PROMO\",\"name\":\"x\","
					+ "\"promotionType\":\"SIMPLE_DISCOUNT\",\"scope\":\"ITEM_GROUP\",\"benefitType\":\"PERCENTAGE_DISCOUNT\","
					+ "\"discountValue\":10,\"startDate\":\"2026-11-01\",\"endDate\":\"2026-12-31\",\"groupItems\":[{\"id\":" + item
					+ "}]}", admin);
			assertEquals(201, promotion.status, promotion.body);
			assertEquals(Arrays.asList("MIG-PROMO " + item), started.database().lines("SELECT CONCAT(p.code, ' ', g.item_id)"
					+ " FROM promotion_group_item g JOIN promotion p ON p.id = g.promotion_id"));
		}
	}

	@Test
	@DisplayName("Every @Query of a store on SQL Server, with nulls and with samples; the classes of native and HQL results")
	void queriesOnSqlServer() {
		try (MigTestApplication started = MigTestApplication.start(StoreApplication.class, "store")) {
			List<String> sweep = QuerySweep.run(started);
			assertEquals(CALLS, sweep.get(0), String.join("\n", QuerySweep.queries(started)));
			assertEquals(Collections.emptyList(), sweep.subList(1, sweep.size()), String.join("\n", sweep));
			started.database().run(QueryResultTypes.ITEMS);
			SchemaCatalog.assertRecord(QueryResultTypes.of(started), "store", "result-types");
		}
	}
}
