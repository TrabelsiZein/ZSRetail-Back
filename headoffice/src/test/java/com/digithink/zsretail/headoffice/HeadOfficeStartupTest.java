package com.digithink.zsretail.headoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.core.erp.model.ErpSyncJob;
import com.digithink.zsretail.core.erp.repository.ErpSyncJobRepository;
import com.digithink.zsretail.core.erp.scheduler.ErpSyncScheduler;
import com.digithink.zsretail.core.erp.service.ErpSyncJobRunner;
import com.digithink.zsretail.core.link.dto.TicketCopyDTO;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.repository.AppRoleRepository;
import com.digithink.zsretail.core.repository.AppVersionRepository;
import com.digithink.zsretail.core.repository.CompanyInformationRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.repository.UserAccountRepository;
import com.digithink.zsretail.core.security.StoreKeyFilter;
import com.digithink.zsretail.core.service.UserBadgeCodeIndex;
import com.digithink.zsretail.core.support.GoldenJson;
import com.digithink.zsretail.core.support.MigTestApplication;
import com.digithink.zsretail.core.support.MigTestDatabase;
import com.digithink.zsretail.core.support.SecurityChains;
import com.digithink.zsretail.erp.api.ErpSyncWarningException;
import com.digithink.zsretail.headoffice.service.HeadOfficeErpGuard;
import com.digithink.zsretail.headoffice.service.StoreErpCustomerIndex;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Boot 4 migration, step 1.2: the head office application really starts, on the throwaway database, with no ERP connector
 * and no jobs. No other test starts a context: bean cycles, the @Query checked at startup, the startup T-SQL and an aspect
 * that stops applying only show here. Then it starts again on its own database, as an installation does at every restart.
 */
class HeadOfficeStartupTest {

	private static MigTestApplication headOffice;

	@BeforeAll
	static void start() {
		headOffice = MigTestApplication.start(HeadOfficeApplication.class, "headoffice");
	}

	@AfterAll
	static void stop() {
		if (headOffice != null) {
			headOffice.close();
		}
	}

	private static List<String> roles() {
		return headOffice.bean(AppRoleRepository.class).findAll().stream().map(AppRole::getName).sorted()
				.collect(Collectors.toList());
	}

	private static List<String> users() {
		return headOffice.bean(UserAccountRepository.class).findAll().stream().map(UserAccount::getUsername).sorted()
				.collect(Collectors.toList());
	}

	private static Integer indexes(String name, String table) {
		return headOffice.bean(JdbcTemplate.class).queryForObject(
				"SELECT COUNT(*) FROM sys.indexes WHERE name = ? AND object_id = OBJECT_ID(?)", Integer.class, name, table);
	}

	@Test
	@DisplayName("It runs on the throwaway database and nowhere else")
	void onTheThrowawayDatabase() {
		assertEquals(MigTestDatabase.NAME,
				headOffice.bean(JdbcTemplate.class).queryForObject("SELECT DB_NAME()", String.class));
	}

	@Test
	@DisplayName("A head office, without an ERP, without jobs; its store key filter exists")
	void noConnectorNoJobs() {
		ApplicationModeService mode = headOffice.bean(ApplicationModeService.class);
		assertTrue(mode.isHeadOffice(), "a head office");
		assertFalse(mode.hasErp(), "no ERP");
		assertTrue(headOffice.context().getBeansOfType(ErpSyncScheduler.class).isEmpty(), "no job scheduler");
		assertEquals(1, headOffice.context().getBeansOfType(StoreKeyFilter.class).size(), "the /ho/** filter");
	}

	@Test
	@DisplayName("The first start seeds the database: the admin only (no till), payment methods, version")
	void seeds() {
		assertEquals(Collections.singletonList("ADMIN"), roles());
		assertEquals(Collections.singletonList("admin"), users());
		assertEquals(10, headOffice.bean(PaymentMethodRepository.class).count());
		assertEquals(headOffice.context().getEnvironment().getProperty("app.version"),
				headOffice.bean(AppVersionRepository.class).findAll().get(0).getVersion());
		assertEquals(1, headOffice.bean(CompanyInformationRepository.class).count());
	}

	@Test
	@DisplayName("The two security chains, /ho/** first, on AuthorizationFilter")
	void securityChains() {
		SecurityChains.assertTheTwoChains(headOffice.bean(FilterChainProxy.class));
	}

	@Test
	@DisplayName("The startup T-SQL really ran: it only logs a failure, so the start alone does not prove it")
	void startupSql() {
		assertEquals(Integer.valueOf(1), indexes(UserBadgeCodeIndex.INDEX, "user_account"));
		assertEquals(Integer.valueOf(1), indexes(StoreErpCustomerIndex.INDEX, "ho_store"));
	}

	@Test
	@DisplayName("HeadOfficeErpGuard applies: the runner is proxied and refuses an export job, which stays unrun")
	void erpGuardApplies() {
		assertEquals(1, headOffice.context().getBeansOfType(HeadOfficeErpGuard.class).size());
		ErpSyncJobRunner runner = headOffice.bean(ErpSyncJobRunner.class);
		assertTrue(AopUtils.isAopProxy(runner), "an aspect that stops applying would leave a plain runner");

		ErpSyncJob export = new ErpSyncJob();
		export.setJobType(ErpSyncJobType.EXPORT_TICKETS);
		ErpSyncWarningException refused = assertThrows(ErpSyncWarningException.class, () -> runner.run(export));
		assertTrue(refused.getMessage().contains("does not run on a head office"), refused.getMessage());
	}

	@Test
	@DisplayName("HeadOfficeErpGuard applies on the jobs API too: such a job is hidden from the list and answers 404")
	void erpGuardAppliesOnTheController() {
		ErpSyncJob export = new ErpSyncJob();
		export.setJobType(ErpSyncJobType.EXPORT_TICKETS);
		export.setCronExpression("0 0 * * * *");
		export.setDescription("migtest");
		export.setEnabled(false);
		export = headOffice.bean(ErpSyncJobRepository.class).save(export);

		headOffice.withLicense();
		String[] admin = headOffice.bearerOf("admin", "P@ssw0rd");
		assertFalse(headOffice.call("GET", "/admin/erp/jobs", null, admin).body.contains("EXPORT_TICKETS"));
		MigTestApplication.Answer statistics = headOffice.call("GET", "/admin/erp/jobs/" + export.getId() + "/statistics", null,
				admin);
		assertEquals(404, statistics.status, statistics.body);
		assertEquals("{\"error\":\"This ERP job does not run on a head office\"}", statistics.body);
	}

	@Test
	@DisplayName("The mapper bean of the real context writes what the golden files record (they are made without a context)")
	void realMapperIsTheGoldenMapper() throws Exception {
		ObjectMapper mapper = headOffice.bean(ObjectMapper.class);
		GoldenJson.assertSameAs("golden/link/TicketCopyDTO.json", mapper.writeValueAsString(GoldenJson.sample(TicketCopyDTO.class)));
		GoldenJson.assertSameAs("golden/core/Item.json", mapper.writeValueAsString(GoldenJson.sample(Item.class)));
	}

	@Test
	@DisplayName("A restart on its own database: the version check passes, nothing is seeded twice")
	void restart() {
		List<String> roles = roles();
		List<String> users = users();
		long methods = headOffice.bean(PaymentMethodRepository.class).count();

		headOffice.restart();

		assertEquals(roles, roles());
		assertEquals(users, users());
		assertEquals(methods, headOffice.bean(PaymentMethodRepository.class).count());
		assertEquals(1, headOffice.bean(CompanyInformationRepository.class).count());
	}
}
