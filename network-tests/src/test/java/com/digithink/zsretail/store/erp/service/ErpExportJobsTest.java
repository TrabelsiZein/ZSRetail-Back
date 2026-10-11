package com.digithink.zsretail.store.erp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.core.erp.model.ErpSyncJob;
import com.digithink.zsretail.core.erp.service.ErpExportJob;
import com.digithink.zsretail.core.erp.service.ErpSyncCheckpointService;
import com.digithink.zsretail.core.erp.service.ErpSyncJobRunner;
import com.digithink.zsretail.erp.api.dto.ErpSyncFilter;
import com.digithink.zsretail.support.Installations;

/**
 * Code cleanup step 5.6: the ERP job runner calls the store's exports through ErpExportJob. For each export job type the
 * same method runs as before (exportTickets, exportReturns, exportSessions), in its transaction, and no other; each
 * type has exactly one export in the application; the runner and the three exports exist in every configuration of the
 * installation beans record (deploy/, configs/, the six shapes), as before (they carry no condition).
 */
class ErpExportJobsTest {

	private TicketExportService tickets;
	private ReturnExportService returns;
	private SessionExportService sessions;

	/** The three exports, their export methods mocked, jobType() and export() real. */
	private List<ErpExportJob> exports() {
		tickets = mock(TicketExportService.class);
		returns = mock(ReturnExportService.class);
		sessions = mock(SessionExportService.class);
		for (ErpExportJob export : Arrays.<ErpExportJob>asList(tickets, returns, sessions)) {
			when(export.jobType()).thenCallRealMethod();
			doCallRealMethod().when(export).export();
		}
		return Arrays.asList(tickets, returns, sessions);
	}

	private ErpSyncJobRunner runner(List<ErpExportJob> exports) {
		ErpSyncCheckpointService checkpoints = mock(ErpSyncCheckpointService.class);
		when(checkpoints.createFilterForJob(any())).thenReturn(new ErpSyncFilter());
		ErpSyncJobRunner runner = new ErpSyncJobRunner(null, checkpoints, null, null);
		ReflectionTestUtils.setField(runner, "exportJobs", exports);
		return runner;
	}

	private static ErpSyncJob job(ErpSyncJobType type) {
		ErpSyncJob job = new ErpSyncJob();
		job.setJobType(type);
		return job;
	}

	@Test
	@DisplayName("Each export job type runs the same export method as before, once, and no other")
	void sameHandlerPerJobType() {
		runner(exports()).run(job(ErpSyncJobType.EXPORT_TICKETS));
		verify(tickets, times(1)).exportTickets();
		verify(returns, never()).exportReturns();
		verify(sessions, never()).exportSessions();

		runner(exports()).run(job(ErpSyncJobType.EXPORT_RETURNS));
		verify(tickets, never()).exportTickets();
		verify(returns, times(1)).exportReturns();
		verify(sessions, never()).exportSessions();

		runner(exports()).run(job(ErpSyncJobType.EXPORT_SESSIONS));
		verify(tickets, never()).exportTickets();
		verify(returns, never()).exportReturns();
		verify(sessions, times(1)).exportSessions();

		// The order of the beans does not matter
		List<ErpExportJob> reversed = new ArrayList<>(exports());
		java.util.Collections.reverse(reversed);
		runner(reversed).run(job(ErpSyncJobType.EXPORT_TICKETS));
		verify(tickets, times(1)).exportTickets();
		verify(sessions, never()).exportSessions();
	}

	@Test
	@DisplayName("export() runs in a transaction, as the export method did when the runner called it through the proxy")
	void exportIsTransactional() throws Exception {
		for (Class<?> type : Arrays.asList(TicketExportService.class, ReturnExportService.class,
				SessionExportService.class)) {
			assertTrue(type.getMethod("export").isAnnotationPresent(Transactional.class), type.getSimpleName());
		}
		assertTrue(TicketExportService.class.getMethod("exportTickets").isAnnotationPresent(Transactional.class));
	}

	@Test
	@DisplayName("Each export job type has exactly one export in the application")
	void oneExportPerType() throws Exception {
		AssignableTypeFilter exportJobs = new AssignableTypeFilter(ErpExportJob.class);
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
				return exportJobs.match(reader, getMetadataReaderFactory()) && !reader.getClassMetadata().isInterface();
			}
		};
		Map<ErpSyncJobType, String> byType = new LinkedHashMap<>();
		for (BeanDefinition candidate : scanner.findCandidateComponents("com.digithink.zsretail")) {
			Class<?> type = Class.forName(candidate.getBeanClassName());
			ErpExportJob export = (ErpExportJob) mock(type);
			when(export.jobType()).thenCallRealMethod();
			String previous = byType.put(export.jobType(), type.getSimpleName());
			assertEquals(null, previous, "two exports of " + export.jobType());
		}
		assertEquals(EnumSet.of(ErpSyncJobType.EXPORT_TICKETS, ErpSyncJobType.EXPORT_RETURNS,
				ErpSyncJobType.EXPORT_SESSIONS), EnumSet.copyOf(byType.keySet()));
		assertEquals("TicketExportService", byType.get(ErpSyncJobType.EXPORT_TICKETS));
		assertEquals("ReturnExportService", byType.get(ErpSyncJobType.EXPORT_RETURNS));
		assertEquals("SessionExportService", byType.get(ErpSyncJobType.EXPORT_SESSIONS));
	}

	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	@Test
	@DisplayName("The runner and the three exports exist in every configuration of the installation beans record")
	void everyConfiguration() {
		Map<String, MockEnvironment> configurations = new LinkedHashMap<>();
		Installations.machineFiles().forEach(file -> configurations.put(file, Installations.machine(file)));
		Installations.configFiles().forEach(file -> configurations.put(file, Installations.config(file)));
		Installations.variants().forEach(variant -> configurations.put(variant, Installations.preset(variant)));
		assertTrue(configurations.size() > 20, configurations.keySet().toString());
		Set<String> missing = new TreeSet<>();
		for (Map.Entry<String, MockEnvironment> configuration : configurations.entrySet()) {
			for (Class<?> bean : Arrays.asList(ErpSyncJobRunner.class, TicketExportService.class,
					ReturnExportService.class, SessionExportService.class)) {
				if (!registered(configuration.getValue(), bean)) {
					missing.add(configuration.getKey() + " " + bean.getSimpleName());
				}
			}
		}
		assertEquals(new TreeSet<String>(), missing);
	}
}
