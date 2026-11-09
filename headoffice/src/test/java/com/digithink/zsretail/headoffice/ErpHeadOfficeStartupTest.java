package com.digithink.zsretail.headoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.erp.model.ErpSyncJob;
import com.digithink.zsretail.core.erp.repository.ErpSyncJobRepository;
import com.digithink.zsretail.core.erp.scheduler.ErpSyncScheduler;
import com.digithink.zsretail.core.erp.service.ErpSyncJobRunner;
import com.digithink.zsretail.core.support.MigTestApplication;

/**
 * Boot 4 migration, steps 1.2b and 1.3b: a head office with an ERP really starts, in each of its forms, the connector's address
 * on the closed local port and the job scheduler off, so no job runs and nothing is called. Their beans exist in no other
 * start, and bean cycles are forbidden from step 1.4.
 */
class ErpHeadOfficeStartupTest {

	/** The ERP owns the catalogue, the customers and the supply (the lines of configs/dev/headoffice-erp.properties). */
	private static final String[] FULL_ERP = { "ownership.catalogue=ERP", "ownership.customers=ERP", "ownership.supply=ERP",
			"erp.connector=emtop", "erp.emtop.base-url=" + MigTestApplication.CLOSED_PORT + "/test/ODataV4",
			"erp.emtop.company=MIGTEST", "erp.emtop.domain=MIGTEST", "erp.emtop.username=MIGTEST",
			"erp.emtop.password=not-a-password" };

	/** The catalogue only from the ERP, its invoices and the stores' orders too (configs/customers/zen-headoffice.properties). */
	private static final String[] CATALOGUE_ONLY = { "headoffice.stock.enabled=false", "ownership.catalogue=ERP",
			"headoffice.supply.source=ERP", "headoffice.storeorders.post=ERP", "erp.connector=zen",
			"erp.zen.base-url=" + MigTestApplication.CLOSED_PORT + "/ZEN", "erp.zen.company=MIGTEST", "erp.zen.domain=MIGTEST",
			"erp.zen.username=MIGTEST", "erp.zen.password=not-a-password", "erp.zen.default-vat=19",
			"erp.zen.products.url=" + MigTestApplication.CLOSED_PORT + "/products", "erp.zen.products.api-key=not-a-key" };

	/** The catalogue only from the ERP, its invoices too, on POS pages (configs/customers/happyness-headoffice.properties). */
	private static final String[] HAPPYNESS = { "headoffice.stock.enabled=false", "ownership.catalogue=ERP",
			"headoffice.supply.source=ERP", "erp.connector=happyness",
			"erp.happyness.base-url=" + MigTestApplication.CLOSED_PORT + "/BC140/ODataV4", "erp.happyness.company=MIGTEST",
			"erp.happyness.domain=MIGTEST", "erp.happyness.username=MIGTEST", "erp.happyness.password=not-a-password",
			"erp.happyness.default-vat=19", "erp.happyness.price-includes-vat=true", "erp.happyness.invoices.seller-name=MIGTEST" };

	/** The catalogue only from Business Central: the connector's catalogue source exists only here, not on a store. */
	private static final String[] PARAFENDRI = { "headoffice.stock.enabled=false", "ownership.catalogue=ERP",
			"erp.connector=parafendri", "erp.parafendri.base-url=" + MigTestApplication.CLOSED_PORT + "/BC",
			"erp.parafendri.company-id=00000000-0000-0000-0000-000000000000", "erp.parafendri.domain=MIGTEST",
			"erp.parafendri.username=MIGTEST", "erp.parafendri.password=not-a-password" };

	private static void startsAndRestarts(boolean catalogueOnly, String... shape) {
		try (MigTestApplication headOffice = MigTestApplication.start(HeadOfficeApplication.class, "headoffice", shape)) {
			ApplicationModeService mode = headOffice.bean(ApplicationModeService.class);
			assertTrue(mode.isHeadOffice());
			assertTrue(mode.hasErp(), "the ERP connector");
			assertEquals(catalogueOnly, mode.isErpCatalogueOnly());
			// The table of the catalogue engine is made by the start's own T-SQL, not by Hibernate
			assertEquals(Integer.valueOf(catalogueOnly ? 1 : 0), headOffice.bean(JdbcTemplate.class)
					.queryForObject("SELECT COUNT(*) FROM sys.tables WHERE name = 'erp_catalogue_state'", Integer.class));

			assertTrue(headOffice.context().getBeansOfType(ErpSyncScheduler.class).isEmpty(), "no job runs");
			List<ErpSyncJob> jobs = headOffice.bean(ErpSyncJobRepository.class).findAll();
			assertFalse(jobs.isEmpty(), "the jobs are seeded");
			assertTrue(jobs.stream().noneMatch(job -> Boolean.TRUE.equals(job.getEnabled())), "and all disabled");
			assertTrue(AopUtils.isAopProxy(headOffice.bean(ErpSyncJobRunner.class)), "HeadOfficeErpGuard applies");

			headOffice.restart();

			assertEquals(jobs.size(), headOffice.bean(ErpSyncJobRepository.class).count(), "nothing seeded twice");
			assertEquals(200, headOffice.call("GET", "/config", null).status);
		}
	}

	@Test
	@DisplayName("Full ERP: catalogue, customers and supply from the ERP (connector emtop)")
	void fullErp() {
		startsAndRestarts(false, FULL_ERP);
	}

	@Test
	@DisplayName("Catalogue only from the ERP, with its invoices and the stores' orders (connector zen): its engine and stock points")
	void catalogueOnly() {
		startsAndRestarts(true, CATALOGUE_ONLY);
	}

	@Test
	@DisplayName("Catalogue only from the ERP, on POS pages (connector happyness)")
	void happyness() {
		startsAndRestarts(true, HAPPYNESS);
	}

	@Test
	@DisplayName("Catalogue only from the ERP, on Business Central (connector parafendri)")
	void parafendri() {
		startsAndRestarts(true, PARAFENDRI);
	}
}
