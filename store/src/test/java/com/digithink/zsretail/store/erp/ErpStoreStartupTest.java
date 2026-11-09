package com.digithink.zsretail.store.erp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.erp.model.ErpSyncJob;
import com.digithink.zsretail.core.erp.repository.ErpSyncJobRepository;
import com.digithink.zsretail.core.erp.scheduler.ErpSyncScheduler;
import com.digithink.zsretail.core.support.MigTestApplication;
import com.digithink.zsretail.store.StoreApplication;

/**
 * Boot 4 migration, step 1.3b: a store on an ERP really starts and restarts (the lines of
 * configs/customers/parafendri-store.properties, connector parafendri), the connector's address on the closed local port and
 * the job scheduler off. Its connection check runs at the start and only logs: the store sells without its ERP.
 */
class ErpStoreStartupTest {

	private static final String[] SHAPE = { "ownership.catalogue=ERP", "ownership.customers=ERP", "ownership.supply=ERP",
			"sales.upstream=ERP", "erp.connector=parafendri", "erp.parafendri.base-url=" + MigTestApplication.CLOSED_PORT + "/BC",
			"erp.parafendri.company-id=00000000-0000-0000-0000-000000000000", "erp.parafendri.domain=MIGTEST",
			"erp.parafendri.username=MIGTEST", "erp.parafendri.password=not-a-password" };

	@Test
	@DisplayName("A store on an ERP (connector parafendri): the jobs seeded and disabled, no scheduler, a restart")
	void startsAndRestarts() {
		try (MigTestApplication store = MigTestApplication.start(StoreApplication.class, "store", SHAPE)) {
			ApplicationModeService mode = store.bean(ApplicationModeService.class);
			assertFalse(mode.isHeadOffice());
			assertTrue(mode.hasErp(), "the ERP connector");
			assertTrue(mode.isCatalogueFromErp());
			assertTrue(store.context().getBeansOfType(ErpSyncScheduler.class).isEmpty(), "no job runs");
			List<ErpSyncJob> jobs = store.bean(ErpSyncJobRepository.class).findAll();
			assertFalse(jobs.isEmpty(), "the jobs are seeded");
			assertTrue(jobs.stream().noneMatch(job -> Boolean.TRUE.equals(job.getEnabled())), "and all disabled");

			store.restart();

			assertEquals(jobs.size(), store.bean(ErpSyncJobRepository.class).count(), "nothing seeded twice");
			assertEquals(200, store.call("GET", "/config", null).status);
		}
	}
}
