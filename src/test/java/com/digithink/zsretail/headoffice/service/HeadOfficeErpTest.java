package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.erp.controller.ErpSyncJobAdminController;
import com.digithink.zsretail.erp.dto.ErpJobStatisticsDTO;
import com.digithink.zsretail.erp.dto.ErpSyncJobEnabledDTO;
import com.digithink.zsretail.erp.dto.ErpSyncJobUpdateDTO;
import com.digithink.zsretail.erp.dto.ErpSyncJobViewDTO;
import com.digithink.zsretail.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.erp.model.ErpSyncJob;
import com.digithink.zsretail.erp.repository.ErpSyncJobRepository;
import com.digithink.zsretail.erp.service.ErpSyncJobRunner;
import com.digithink.zsretail.erp.service.ErpSyncWarningException;
import com.digithink.zsretail.support.Installations;
import com.digithink.zsretail.support.TestModes;

/**
 * Head office plan, task 3.4: on a head office the ERP export jobs (and the price import) never run. They are switched
 * off at each start (also the two exports the data initializer seeds enabled), refused by the runner (scheduler and
 * run now), absent from GET admin/erp/jobs and answered 404 by the job endpoints, so they can be neither enabled nor
 * run. Nothing in erp/ is changed: the guard is an aspect, exercised here through Spring AOP on test subclasses of the
 * ERP runner and controller. In-memory erp_sync_job.
 */
class HeadOfficeErpTest {

	private static final Set<ErpSyncJobType> REFUSED = EnumSet.of(ErpSyncJobType.EXPORT_CUSTOMERS,
			ErpSyncJobType.EXPORT_TICKETS, ErpSyncJobType.EXPORT_RETURNS, ErpSyncJobType.EXPORT_SESSIONS,
			ErpSyncJobType.IMPORT_SALES_PRICES_AND_DISCOUNTS);

	/** Invoices from the ERP: refused on a head office without headoffice.supply.source=ERP, besides {@link #REFUSED}. */
	private static final Set<ErpSyncJobType> REFUSED_HERE = EnumSet.of(ErpSyncJobType.EXPORT_CUSTOMERS,
			ErpSyncJobType.EXPORT_TICKETS, ErpSyncJobType.EXPORT_RETURNS, ErpSyncJobType.EXPORT_SESSIONS,
			ErpSyncJobType.IMPORT_SALES_PRICES_AND_DISCOUNTS, ErpSyncJobType.IMPORT_SUPPLY_INVOICES);

	private final Map<Long, ErpSyncJob> table = new LinkedHashMap<>();
	private int saves;
	private HeadOfficeErpJobs jobs;

	/** The jobs as ZZDataInitializer.initErpSyncJobs seeds them, after a while: exports with a next run. */
	@BeforeEach
	void setUp() {
		table.clear();
		saves = 0;
		long id = 1;
		for (ErpSyncJobType type : ErpSyncJobType.values()) {
			ErpSyncJob job = new ErpSyncJob();
			job.setId(id++);
			job.setJobType(type);
			job.setCronExpression("0 0 * * * *");
			boolean seededOn = type == ErpSyncJobType.EXPORT_RETURNS || type == ErpSyncJobType.EXPORT_SESSIONS;
			job.setEnabled(seededOn || type == ErpSyncJobType.IMPORT_ITEMS); // IMPORT_ITEMS enabled by the admin
			job.setNextRunAt(job.getEnabled() ? LocalDateTime.of(2026, 10, 3, 13, 0) : null);
			table.put(job.getId(), job);
		}
		jobs = new HeadOfficeErpJobs(repository());
	}

	private ErpSyncJob job(ErpSyncJobType type) {
		return table.values().stream().filter(j -> j.getJobType() == type).findFirst().get();
	}

	@Test
	@DisplayName("At each start: exports and price import disabled, no next run; imports untouched; the next start writes nothing")
	void switchedOffAtStart() {
		assertEquals(REFUSED, HeadOfficeErpJobs.NOT_ON_HEAD_OFFICE);
		assertEquals(2, jobs.switchOff(), "the two exports seeded enabled");
		for (ErpSyncJobType type : REFUSED) {
			assertFalse(job(type).getEnabled(), type.name());
			assertNull(job(type).getNextRunAt(), type.name());
		}
		assertTrue(job(ErpSyncJobType.IMPORT_ITEMS).getEnabled());
		assertNotNull(job(ErpSyncJobType.IMPORT_ITEMS).getNextRunAt());
		assertEquals(2, saves);

		assertEquals(0, jobs.switchOff());
		assertEquals(2, saves, "nothing to write");

		job(ErpSyncJobType.EXPORT_TICKETS).setEnabled(true); // enabled in the database by hand
		assertEquals(1, jobs.switchOff());
		assertFalse(job(ErpSyncJobType.EXPORT_TICKETS).getEnabled());
	}

	@Test
	@DisplayName("A store database never seeded with ERP jobs (standalone head office): nothing to do")
	void noJobs() {
		table.clear();
		assertEquals(0, jobs.switchOff());
	}

	@Test
	@DisplayName("The runner (scheduler and run now) refuses them with a warning; the imports run")
	void runnerRefuses() {
		List<ErpSyncJobType> ran = new ArrayList<>();
		ErpSyncJobRunner runner = new ErpSyncJobRunner(null, null, null, null, null, null, null) {
			@Override
			public void run(ErpSyncJob job) {
				ran.add(job.getJobType());
			}
		};
		ErpSyncJobRunner guarded = guarded(runner);
		for (ErpSyncJobType type : REFUSED_HERE) {
			ErpSyncWarningException refused = assertThrows(ErpSyncWarningException.class, () -> guarded.run(job(type)));
			assertEquals("This ERP job does not run on a head office: " + type, refused.getMessage());
		}
		for (ErpSyncJobType type : EnumSet.complementOf(EnumSet.copyOf(REFUSED_HERE))) {
			guarded.run(job(type));
		}
		assertEquals(EnumSet.complementOf(EnumSet.copyOf(REFUSED_HERE)), EnumSet.copyOf(ran));
	}

	@Test
	@DisplayName("GET admin/erp/jobs without them; run, enable, update and statistics of one of them: 404; the others as before")
	void apiHidesThem() {
		List<String> calls = new ArrayList<>();
		ErpSyncJobAdminController controller = new ErpSyncJobAdminController(null, null, null, null, null, null) {
			@Override
			public ResponseEntity<List<ErpSyncJobViewDTO>> getJobs() {
				List<ErpSyncJobViewDTO> views = new ArrayList<>();
				for (ErpSyncJob job : table.values()) {
					ErpSyncJobViewDTO view = new ErpSyncJobViewDTO();
					view.setId(job.getId());
					view.setJobType(job.getJobType());
					views.add(view);
				}
				return ResponseEntity.ok(views);
			}

			@Override
			public ResponseEntity<?> runJobNow(Long id) {
				calls.add("run " + id);
				return ResponseEntity.ok("ran");
			}

			@Override
			public ResponseEntity<ErpSyncJobViewDTO> updateEnabled(Long id, ErpSyncJobEnabledDTO enabled) {
				calls.add("enable " + id);
				return ResponseEntity.ok(new ErpSyncJobViewDTO());
			}

			@Override
			public ResponseEntity<ErpSyncJobViewDTO> updateJob(Long id, ErpSyncJobUpdateDTO update) {
				calls.add("update " + id);
				return ResponseEntity.ok(new ErpSyncJobViewDTO());
			}

			@Override
			public ResponseEntity<ErpJobStatisticsDTO> getStatistics(Long id, LocalDateTime from, LocalDateTime to) {
				calls.add("statistics " + id);
				return ResponseEntity.ok(null);
			}
		};
		ErpSyncJobAdminController guarded = guarded(controller);

		List<ErpSyncJobType> listed = guarded.getJobs().getBody().stream().map(ErpSyncJobViewDTO::getJobType)
				.collect(Collectors.toList());
		assertEquals(ErpSyncJobType.values().length - REFUSED_HERE.size(), listed.size());
		assertTrue(Collections.disjoint(listed, REFUSED_HERE), listed.toString());

		Long export = job(ErpSyncJobType.EXPORT_TICKETS).getId();
		List<ResponseEntity<?>> refused = new ArrayList<>();
		refused.add(guarded.runJobNow(export));
		refused.add(guarded.updateEnabled(export, new ErpSyncJobEnabledDTO()));
		refused.add(guarded.updateJob(export, new ErpSyncJobUpdateDTO()));
		refused.add(guarded.getStatistics(job(ErpSyncJobType.IMPORT_SALES_PRICES_AND_DISCOUNTS).getId(), null, null));
		for (ResponseEntity<?> answer : refused) {
			assertEquals(404, answer.getStatusCodeValue());
			assertEquals(Collections.singletonMap("error", "This ERP job does not run on a head office"), answer.getBody());
		}
		assertTrue(calls.isEmpty(), calls.toString());

		Long items = job(ErpSyncJobType.IMPORT_ITEMS).getId();
		assertEquals(200, guarded.runJobNow(items).getStatusCodeValue());
		guarded.updateEnabled(items, new ErpSyncJobEnabledDTO());
		guarded.updateJob(items, new ErpSyncJobUpdateDTO());
		guarded.getStatistics(items, null, null);
		assertEquals(200, guarded.runJobNow(999L).getStatusCodeValue(), "unknown id: the controller answers as before");
		assertEquals(5, calls.size(), calls.toString());
	}

	// ─── ERP catalogue, step 3: a head office whose catalogue only comes from the ERP ───

	private static final Set<ErpSyncJobType> OFFERED = EnumSet.of(ErpSyncJobType.IMPORT_ITEM_FAMILIES,
			ErpSyncJobType.IMPORT_ITEM_SUBFAMILIES, ErpSyncJobType.IMPORT_ITEMS, ErpSyncJobType.IMPORT_ITEM_BARCODES);

	private static final Set<ErpSyncJobType> REFUSED_WHEN_CATALOGUE_ONLY = EnumSet.complementOf(EnumSet.copyOf(OFFERED));

	private static MockEnvironment catalogueOnlyHeadOffice() {
		MockEnvironment env = Installations.type("headoffice");
		env.setProperty("ownership.catalogue", "ERP");
		return env;
	}

	@Test
	@DisplayName("Catalogue only from the ERP: the mode chooses the refused set; any other head office keeps today's set")
	void refusedSetByMode() {
		assertEquals(OFFERED, HeadOfficeErpJobs.OFFERED_WHEN_CATALOGUE_ONLY);
		HeadOfficeErpJobs catalogueOnly = new HeadOfficeErpJobs(repository(), null,
				TestModes.of(catalogueOnlyHeadOffice()));
		assertEquals(REFUSED_WHEN_CATALOGUE_ONLY, catalogueOnly.refusedTypes());
		assertTrue(REFUSED_WHEN_CATALOGUE_ONLY.containsAll(HeadOfficeErpJobs.NOT_ON_HEAD_OFFICE));
		assertTrue(catalogueOnly.refuses(ErpSyncJobType.IMPORT_LOCATIONS));
		assertFalse(catalogueOnly.refuses(ErpSyncJobType.IMPORT_ITEMS));
		assertFalse(catalogueOnly.refuses(null));

		MockEnvironment[] others = { Installations.type("headoffice"), Installations.preset("headoffice-erp"),
				Installations.machine("dev/headoffice-erp.properties"), Installations.machine("dev/headoffice.properties") };
		for (MockEnvironment other : others) {
			HeadOfficeErpJobs kept = new HeadOfficeErpJobs(repository(), null, TestModes.of(other));
			assertEquals(REFUSED_HERE, kept.refusedTypes());
			for (ErpSyncJobType type : ErpSyncJobType.values()) {
				assertEquals(HeadOfficeErpJobs.isRefused(type) || HeadOfficeErpJobs.OFFERED_WITH_ERP_SUPPLY.contains(type),
						kept.refuses(type), type.name());
			}
		}
		assertEquals(REFUSED_HERE, jobs.refusedTypes(), "the test constructor keeps today's set");
	}

	@Test
	@DisplayName("Catalogue only from the ERP, at each start: every job but the four imports disabled, no next run; the four untouched")
	void catalogueOnlySwitchedOffAtStart() {
		job(ErpSyncJobType.IMPORT_LOCATIONS).setEnabled(true);
		job(ErpSyncJobType.IMPORT_LOCATIONS).setNextRunAt(LocalDateTime.of(2026, 10, 3, 13, 0));
		job(ErpSyncJobType.IMPORT_ITEM_BARCODES).setEnabled(true);
		job(ErpSyncJobType.IMPORT_ITEM_BARCODES).setNextRunAt(LocalDateTime.of(2026, 10, 3, 13, 10));
		jobs = new HeadOfficeErpJobs(repository(), true);

		assertEquals(3, jobs.switchOff(), "the two exports seeded enabled and the locations enabled by hand");
		for (ErpSyncJobType type : REFUSED_WHEN_CATALOGUE_ONLY) {
			assertFalse(job(type).getEnabled(), type.name());
			assertNull(job(type).getNextRunAt(), type.name());
		}
		for (ErpSyncJobType type : new ErpSyncJobType[] { ErpSyncJobType.IMPORT_ITEMS,
				ErpSyncJobType.IMPORT_ITEM_BARCODES }) {
			assertTrue(job(type).getEnabled(), type.name());
			assertNotNull(job(type).getNextRunAt(), type.name());
		}
		assertFalse(job(ErpSyncJobType.IMPORT_ITEM_FAMILIES).getEnabled(), "left as seeded (disabled)");
		assertEquals(0, jobs.switchOff(), "the next start writes nothing");
	}

	@Test
	@DisplayName("Catalogue only from the ERP: the runner refuses every job but the four imports with the warning; the four run")
	void catalogueOnlyRunnerRefuses() {
		jobs = new HeadOfficeErpJobs(repository(), true);
		List<ErpSyncJobType> ran = new ArrayList<>();
		ErpSyncJobRunner runner = new ErpSyncJobRunner(null, null, null, null, null, null, null) {
			@Override
			public void run(ErpSyncJob job) {
				ran.add(job.getJobType());
			}
		};
		ErpSyncJobRunner guarded = guarded(runner);
		for (ErpSyncJobType type : REFUSED_WHEN_CATALOGUE_ONLY) {
			ErpSyncWarningException refused = assertThrows(ErpSyncWarningException.class, () -> guarded.run(job(type)));
			assertEquals("This ERP job does not run on a head office: " + type, refused.getMessage());
		}
		for (ErpSyncJobType type : OFFERED) {
			guarded.run(job(type));
		}
		assertEquals(OFFERED, EnumSet.copyOf(ran));
	}

	@Test
	@DisplayName("Catalogue only from the ERP: GET admin/erp/jobs lists the four imports; run, enable, update, statistics of any other: 404")
	void catalogueOnlyApi() {
		jobs = new HeadOfficeErpJobs(repository(), true);
		List<String> calls = new ArrayList<>();
		ErpSyncJobAdminController guarded = guarded(recordingController(calls));

		List<ErpSyncJobType> listed = guarded.getJobs().getBody().stream().map(ErpSyncJobViewDTO::getJobType)
				.collect(Collectors.toList());
		assertEquals(OFFERED, EnumSet.copyOf(listed));
		assertEquals(OFFERED.size(), listed.size());

		for (ErpSyncJobType type : REFUSED_WHEN_CATALOGUE_ONLY) {
			Long id = job(type).getId();
			List<ResponseEntity<?>> answers = new ArrayList<>();
			answers.add(guarded.runJobNow(id));
			answers.add(guarded.updateEnabled(id, new ErpSyncJobEnabledDTO()));
			answers.add(guarded.updateJob(id, new ErpSyncJobUpdateDTO()));
			answers.add(guarded.getStatistics(id, null, null));
			for (ResponseEntity<?> answer : answers) {
				assertEquals(404, answer.getStatusCodeValue(), type.name());
				assertEquals(Collections.singletonMap("error", "This ERP job does not run on a head office"),
						answer.getBody());
			}
		}
		assertTrue(calls.isEmpty(), calls.toString());

		for (ErpSyncJobType type : OFFERED) {
			Long id = job(type).getId();
			assertEquals(200, guarded.runJobNow(id).getStatusCodeValue(), type.name());
			guarded.updateEnabled(id, new ErpSyncJobEnabledDTO());
			guarded.updateJob(id, new ErpSyncJobUpdateDTO());
			guarded.getStatistics(id, null, null);
		}
		assertEquals(4 * OFFERED.size(), calls.size(), calls.toString());
	}

	/** The ERP jobs controller over the in-memory table, recording the calls that reach it. */
	private ErpSyncJobAdminController recordingController(List<String> calls) {
		return new ErpSyncJobAdminController(null, null, null, null, null, null) {
			@Override
			public ResponseEntity<List<ErpSyncJobViewDTO>> getJobs() {
				List<ErpSyncJobViewDTO> views = new ArrayList<>();
				for (ErpSyncJob job : table.values()) {
					ErpSyncJobViewDTO view = new ErpSyncJobViewDTO();
					view.setId(job.getId());
					view.setJobType(job.getJobType());
					views.add(view);
				}
				return ResponseEntity.ok(views);
			}

			@Override
			public ResponseEntity<?> runJobNow(Long id) {
				calls.add("run " + id);
				return ResponseEntity.ok("ran");
			}

			@Override
			public ResponseEntity<ErpSyncJobViewDTO> updateEnabled(Long id, ErpSyncJobEnabledDTO enabled) {
				calls.add("enable " + id);
				return ResponseEntity.ok(new ErpSyncJobViewDTO());
			}

			@Override
			public ResponseEntity<ErpSyncJobViewDTO> updateJob(Long id, ErpSyncJobUpdateDTO update) {
				calls.add("update " + id);
				return ResponseEntity.ok(new ErpSyncJobViewDTO());
			}

			@Override
			public ResponseEntity<ErpJobStatisticsDTO> getStatistics(Long id, LocalDateTime from, LocalDateTime to) {
				calls.add("statistics " + id);
				return ResponseEntity.ok(null);
			}
		};
	}

	@SuppressWarnings("unchecked")
	private <T> T guarded(T target) {
		AspectJProxyFactory factory = new AspectJProxyFactory(target);
		factory.setProxyTargetClass(true);
		factory.addAspect(new HeadOfficeErpGuard(jobs));
		return (T) factory.getProxy();
	}

	private ErpSyncJobRepository repository() {
		return InMemoryDownTables.proxy(ErpSyncJobRepository.class, (method, args) -> {
			switch (method) {
				case "findByJobType":
					return table.values().stream().filter(j -> j.getJobType() == args[0]).findFirst();
				case "findById":
					return Optional.ofNullable(table.get(args[0]));
				case "save":
					saves++;
					return args[0];
				default:
					throw new UnsupportedOperationException(method);
			}
		});
	}
}
