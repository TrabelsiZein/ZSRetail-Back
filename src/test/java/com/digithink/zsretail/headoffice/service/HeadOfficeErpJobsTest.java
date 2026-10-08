package com.digithink.zsretail.headoffice.service;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.erp.model.ErpSyncJob;
import com.digithink.zsretail.erp.repository.ErpSyncJobRepository;
import com.digithink.zsretail.erp.service.ErpSyncCheckpointService;
import com.digithink.zsretail.erp.service.ErpSyncJobRunner;
import com.digithink.zsretail.erp.service.ErpSyncWarningException;
import com.digithink.zsretail.erp.spi.ErpSupplyInvoiceImport;
import com.digithink.zsretail.support.Installations;
import com.digithink.zsretail.support.TestModes;

/**
 * Invoices from the ERP, step (b): the job IMPORT_SUPPLY_INVOICES is offered only on a head office whose catalogue only
 * comes from the ERP with headoffice.supply.source=ERP; every other head office refuses it (and switches it off at the
 * start); the runner runs the head office import, or warns when there is none (a store, any other head office).
 */
class HeadOfficeErpJobsTest {

	private static final Set<ErpSyncJobType> CATALOGUE_IMPORTS = EnumSet.of(ErpSyncJobType.IMPORT_ITEM_FAMILIES,
			ErpSyncJobType.IMPORT_ITEM_SUBFAMILIES, ErpSyncJobType.IMPORT_ITEMS, ErpSyncJobType.IMPORT_ITEM_BARCODES);

	private final Map<Long, ErpSyncJob> table = new LinkedHashMap<>();

	private ErpSyncJobRepository repository() {
		return proxy(ErpSyncJobRepository.class, (method, args) -> {
			switch (method) {
			case "findByJobType":
				return table.values().stream().filter(j -> j.getJobType() == args[0]).findFirst();
			case "findById":
				return Optional.ofNullable(table.get(args[0]));
			case "save":
				return args[0];
			default:
				return UNHANDLED;
			}
		});
	}

	private static MockEnvironment catalogueOnly() {
		MockEnvironment env = Installations.type("headoffice");
		env.setProperty("ownership.catalogue", "ERP");
		return env;
	}

	private static MockEnvironment erpSupply() {
		MockEnvironment env = catalogueOnly();
		env.setProperty("headoffice.supply.source", "ERP");
		env.setProperty("erp.navpospages.enabled", "true");
		return env;
	}

	private HeadOfficeErpJobs jobsOf(MockEnvironment env) {
		return new HeadOfficeErpJobs(repository(), null, TestModes.of(env));
	}

	@Test
	@DisplayName("Offered with headoffice.supply.source=ERP only: the four catalogue imports and the invoices; refused everywhere else")
	void offeredOnlyWithErpSupply() {
		HeadOfficeErpJobs withErpSupply = jobsOf(erpSupply());
		Set<ErpSyncJobType> offered = EnumSet.copyOf(CATALOGUE_IMPORTS);
		offered.add(ErpSyncJobType.IMPORT_SUPPLY_INVOICES);
		assertEquals(EnumSet.complementOf(EnumSet.copyOf(offered)), withErpSupply.refusedTypes());
		assertFalse(withErpSupply.refuses(ErpSyncJobType.IMPORT_SUPPLY_INVOICES));

		MockEnvironment[] others = { catalogueOnly(), Installations.type("headoffice"),
				Installations.machine("dev/headoffice-erp.properties"), Installations.machine("dev/headoffice.properties") };
		for (MockEnvironment other : others) {
			assertTrue(jobsOf(other).refuses(ErpSyncJobType.IMPORT_SUPPLY_INVOICES));
		}
		assertTrue(jobsOf(catalogueOnly()).refusedTypes().containsAll(EnumSet.complementOf(EnumSet.copyOf(CATALOGUE_IMPORTS))),
				"catalogue only without the source: the four imports only, as before");
		assertTrue(new HeadOfficeErpJobs(repository(), true, true).refusedTypes().equals(withErpSupply.refusedTypes()));
		assertTrue(new HeadOfficeErpJobs(repository()).refuses(ErpSyncJobType.IMPORT_SUPPLY_INVOICES));
	}

	@Test
	@DisplayName("Switched off at the start where refused (enabled by hand); left as it is with the source ERP")
	void switchOff() {
		ErpSyncJob job = new ErpSyncJob();
		job.setId(1L);
		job.setJobType(ErpSyncJobType.IMPORT_SUPPLY_INVOICES);
		job.setEnabled(true);
		job.setNextRunAt(LocalDateTime.of(2026, 10, 9, 10, 15));
		table.put(job.getId(), job);

		assertEquals(0, jobsOf(erpSupply()).switchOff());
		assertTrue(job.getEnabled());

		assertEquals(1, jobsOf(catalogueOnly()).switchOff());
		assertFalse(job.getEnabled());
		assertNull(job.getNextRunAt());
	}

	@Test
	@DisplayName("The runner: the head office import when it exists; a warning otherwise; no checkpoint read or written")
	void runner() {
		ErpSyncJob job = new ErpSyncJob();
		job.setJobType(ErpSyncJobType.IMPORT_SUPPLY_INVOICES);
		ErpSyncJobRunner runner = new ErpSyncJobRunner(null, new ErpSyncCheckpointService(null), null, null, null, null,
				null);
		ErpSyncWarningException none = assertThrows(ErpSyncWarningException.class, () -> runner.run(job));
		assertEquals("The supply invoices are read from the ERP only on a head office with headoffice.supply.source=ERP",
				none.getMessage());

		List<String> runs = new ArrayList<>();
		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		ReflectionTestUtils.setField(runner, "supplyInvoiceImport", beans.getBeanProvider(ErpSupplyInvoiceImport.class));
		assertThrows(ErpSyncWarningException.class, () -> runner.run(job), "no bean: the same warning");
		beans.registerSingleton("importer", (ErpSupplyInvoiceImport) () -> runs.add("run"));
		runner.run(job);
		assertEquals(1, runs.size());
	}

	@Test
	@DisplayName("The mode answers isSupplyFromErpSource on that head office only")
	void mode() {
		ApplicationModeService withErpSupply = TestModes.of(erpSupply());
		assertTrue(withErpSupply.isSupplyFromErpSource());
		assertFalse(TestModes.of(catalogueOnly()).isSupplyFromErpSource());
		MockEnvironment store = Installations.type("store");
		store.setProperty("headoffice.supply.source", "ERP"); // never read on a store
		assertFalse(TestModes.of(store).isSupplyFromErpSource());
	}
}
