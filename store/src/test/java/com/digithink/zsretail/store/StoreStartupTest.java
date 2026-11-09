package com.digithink.zsretail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.config.SqlServerVersionCheck;
import com.digithink.zsretail.core.erp.scheduler.ErpSyncScheduler;
import com.digithink.zsretail.core.link.dto.TicketCopyDTO;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.SalesHeader;
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
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Boot 4 migration, step 1.2: the store application really starts, on the throwaway database, with no ERP connector, no
 * head office link and no jobs. No other test starts a context: bean cycles, the @Query checked at startup and the startup
 * T-SQL only show here. Then it starts again on its own database, as an installation does at every restart.
 */
class StoreStartupTest {

	private static MigTestApplication store;

	@BeforeAll
	static void start() {
		store = MigTestApplication.start(StoreApplication.class, "store");
	}

	@AfterAll
	static void stop() {
		if (store != null) {
			store.close();
		}
	}

	private static List<String> names(List<String> values) {
		return values.stream().sorted().collect(Collectors.toList());
	}

	private static List<String> roles() {
		return names(store.bean(AppRoleRepository.class).findAll().stream().map(AppRole::getName).collect(Collectors.toList()));
	}

	private static List<String> users() {
		return names(store.bean(UserAccountRepository.class).findAll().stream().map(UserAccount::getUsername)
				.collect(Collectors.toList()));
	}

	@Test
	@DisplayName("It runs on the throwaway database and nowhere else")
	void onTheThrowawayDatabase() {
		assertEquals(MigTestDatabase.NAME, store.bean(JdbcTemplate.class).queryForObject("SELECT DB_NAME()", String.class));
	}

	@Test
	@DisplayName("A store, without an ERP, without a head office link, without jobs")
	void noConnectorNoLinkNoJobs() {
		ApplicationModeService mode = store.bean(ApplicationModeService.class);
		assertFalse(mode.isHeadOffice(), "a store");
		assertFalse(mode.hasErp(), "no ERP");
		assertFalse(mode.isHeadOfficeLinked(), "no head office link");
		assertTrue(store.context().getBeansOfType(ErpSyncScheduler.class).isEmpty(), "no job scheduler");
		assertTrue(store.context().getBeansOfType(StoreKeyFilter.class).isEmpty(), "the store key filter is head office only");
	}

	@Test
	@DisplayName("The first start seeds the database: roles, users, payment methods, version")
	void seeds() {
		assertEquals(Arrays.asList("ADMIN", "POS_USER", "RESPONSIBLE"), roles());
		assertEquals(Arrays.asList("admin", "cashier", "responsible"), users());
		assertEquals(10, store.bean(PaymentMethodRepository.class).count());
		assertEquals(store.context().getEnvironment().getProperty("app.version"),
				store.bean(AppVersionRepository.class).findAll().get(0).getVersion());
		assertEquals(1, store.bean(CompanyInformationRepository.class).count());
	}

	@Test
	@DisplayName("The SQL Server version check is in the start")
	void versionCheck() {
		assertEquals(1, store.context().getBeansOfType(SqlServerVersionCheck.class).size());
	}

	@Test
	@DisplayName("The startup T-SQL really ran: it only logs a failure, so the start alone does not prove it")
	void startupSql() {
		assertEquals(Integer.valueOf(1), store.bean(JdbcTemplate.class).queryForObject(
				"SELECT COUNT(*) FROM sys.indexes WHERE name = ? AND object_id = OBJECT_ID('user_account')", Integer.class,
				UserBadgeCodeIndex.INDEX));
	}

	@Test
	@DisplayName("The mapper bean of the real context writes what the golden files record (they are made without a context)")
	void realMapperIsTheGoldenMapper() throws Exception {
		ObjectMapper mapper = store.bean(ObjectMapper.class);
		GoldenJson.assertSameAs("golden/link/TicketCopyDTO.json", mapper.writeValueAsString(GoldenJson.sample(TicketCopyDTO.class)));
		GoldenJson.assertSameAs("golden/core/SalesHeader.json", mapper.writeValueAsString(GoldenJson.sample(SalesHeader.class)));
	}

	@Test
	@DisplayName("A restart on its own database: the version check passes, nothing is seeded twice")
	void restart() {
		List<String> roles = roles();
		List<String> users = users();
		long methods = store.bean(PaymentMethodRepository.class).count();

		store.restart();

		assertEquals(roles, roles());
		assertEquals(users, users());
		assertEquals(methods, store.bean(PaymentMethodRepository.class).count());
		assertEquals(1, store.bean(CompanyInformationRepository.class).count());
	}
}
