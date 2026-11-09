package com.digithink.zsretail.store.holink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.erp.scheduler.ErpSyncScheduler;
import com.digithink.zsretail.core.support.GoldenJson;
import com.digithink.zsretail.core.support.MigTestApplication;
import com.digithink.zsretail.store.StoreApplication;
import com.digithink.zsretail.store.holink.client.HeadOfficeClient;
import com.digithink.zsretail.store.holink.scheduler.LinkJobScheduler;
import com.digithink.zsretail.store.holink.service.StoreOrderDraftIndex;

/**
 * Boot 4 migration, step 1.2b: a store of a network really starts, everything from its head office (the lines of
 * configs/customers/zen-store.properties), the link's beans and jobs built and running, on the closed local port. Those 40
 * beans exist in no other start, and bean cycles are forbidden from step 1.4.
 */
class LinkedStoreStartupTest {

	/** The lines of that file, its head office address on the closed port. */
	private static final String[] LINKED = { "ownership.catalogue=HEAD_OFFICE", "ownership.supply=HEAD_OFFICE",
			"ownership.promotions=HEAD_OFFICE", "ownership.loyalty=HEAD_OFFICE", "sales.upstream=HEAD_OFFICE",
			"headoffice.url=" + MigTestApplication.CLOSED_PORT + "/zsretail/api", "headoffice.api-key=not-a-key" };

	private static MigTestApplication store;

	@BeforeAll
	static void start() {
		store = MigTestApplication.start(StoreApplication.class, "store", LINKED);
	}

	@AfterAll
	static void stop() {
		if (store != null) {
			store.close();
		}
	}

	@Test
	@DisplayName("A store linked to its head office, everything from it; no ERP, no ERP scheduler")
	void linked() {
		ApplicationModeService mode = store.bean(ApplicationModeService.class);
		assertTrue(mode.isHeadOfficeLinked());
		assertTrue(mode.isCatalogueFromHeadOffice());
		assertTrue(mode.isSupplyFromHeadOffice());
		assertTrue(mode.isPromotionsOwnedByHeadOffice());
		assertTrue(mode.isLoyaltyOwnedByHeadOffice());
		assertFalse(mode.hasErp());
		assertTrue(store.context().getBeansOfType(ErpSyncScheduler.class).isEmpty());
	}

	@Test
	@DisplayName("The link's client and job scheduler exist, and the jobs were scheduled")
	void linkBeans() {
		assertEquals(1, store.context().getBeansOfType(HeadOfficeClient.class).size());
		assertFalse(store.bean(LinkJobScheduler.class).getJobs().isEmpty());
	}

	@Test
	@DisplayName("The startup T-SQL of the link ran: the filtered index of the one draft order")
	void startupSql() {
		assertEquals(Integer.valueOf(1), store.bean(JdbcTemplate.class).queryForObject(
				"SELECT COUNT(*) FROM sys.indexes WHERE name = ? AND object_id = OBJECT_ID('hol_order')", Integer.class,
				StoreOrderDraftIndex.INDEX));
	}

	@Test
	@DisplayName("/config as the front reads it on a linked store")
	void config() {
		GoldenJson.assertMatches("golden/served/store-linked-config.json", store.call("GET", "/config", null).body);
	}

	@Test
	@DisplayName("A restart on its own database")
	void restart() {
		store.restart();
		assertTrue(store.bean(ApplicationModeService.class).isHeadOfficeLinked());
		assertEquals(200, store.call("GET", "/config", null).status);
	}
}
