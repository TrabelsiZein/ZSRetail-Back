package com.digithink.zsretail.core.erp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 3.1.1: the conversion of the four catalogue jobs into IMPORT_CATALOGUE, at startup (JDBC, only when one of the four
 * rows is there) and in db/3.1.1/update.sql (the same batch). Reads the script's text: no database.
 */
class ErpSyncJobTypesUpgradeTest {

	private static final String SCRIPT = "/db/3.1.1/update.sql";

	/** Whitespace compacted, comments removed. */
	private static String compact(String text) {
		return text.replaceAll("(?m)--.*$", " ").replaceAll("\\s+", " ").trim();
	}

	private static String script() throws Exception {
		try (InputStream in = ErpSyncJobTypesUpgradeTest.class.getResourceAsStream(SCRIPT)) {
			assertNotNull(in, SCRIPT);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	@DisplayName("The script holds the startup's batch, statement for statement, before APP_VERSION is stamped")
	void scriptHasTheSameBatch() throws Exception {
		String sql = compact(script());
		String batch = compact(ErpSyncJobTypesUpgrade.CONVERSION);
		int at = sql.indexOf(batch + " GO");
		assertTrue(at > 0, "the batch of ErpSyncJobTypesUpgrade.CONVERSION, then GO, in " + SCRIPT);
		assertTrue(at < sql.indexOf("UPDATE APP_VERSION SET version = '3.1.1';"));
	}

	@Test
	@DisplayName("The batch: guarded, one transaction, IMPORT_CATALOGUE updated or inserted, then the four rows deleted")
	void batch() {
		String sql = compact(ErpSyncJobTypesUpgrade.CONVERSION);
		String four = "('IMPORT_ITEM_FAMILIES', 'IMPORT_ITEM_SUBFAMILIES', 'IMPORT_ITEMS', 'IMPORT_ITEM_BARCODES')";
		assertTrue(sql.startsWith("IF OBJECT_ID(N'erp_sync_job', N'U') IS NOT NULL AND EXISTS (SELECT 1 FROM"
				+ " erp_sync_job WHERE job_type IN " + four + ") BEGIN BEGIN TRY BEGIN TRANSACTION;"), sql);
		// Enabled when any of the four was; the cron of IMPORT_ITEMS (a default without the row); its next run when enabled
		assertTrue(sql.contains("DECLARE @enabled BIT = CASE WHEN EXISTS (SELECT 1 FROM erp_sync_job WHERE job_type IN "
				+ four + " AND enabled = 1) THEN 1 ELSE 0 END;"), sql);
		assertTrue(sql.contains("DECLARE @cron VARCHAR(64) = COALESCE((SELECT TOP 1 cron_expression FROM erp_sync_job"
				+ " WHERE job_type = 'IMPORT_ITEMS'), '0 0 * * * *');"), sql);
		assertTrue(sql.contains("DECLARE @next DATETIME2 = (SELECT TOP 1 next_run_at FROM erp_sync_job WHERE job_type ="
				+ " 'IMPORT_ITEMS' AND enabled = 1 AND @enabled = 1);"), sql);
		int update = sql.indexOf("IF EXISTS (SELECT 1 FROM erp_sync_job WHERE job_type = 'IMPORT_CATALOGUE') UPDATE"
				+ " erp_sync_job SET cron_expression = @cron, enabled = @enabled, next_run_at = @next,");
		int insert = sql.indexOf("ELSE INSERT INTO erp_sync_job (active, created_at, created_by, updated_at, updated_by,"
				+ " cron_expression, description, enabled, job_type, next_run_at) VALUES (1, SYSDATETIME(), 'System',"
				+ " SYSDATETIME(), 'System', @cron, N'" + ErpSyncJobTypesUpgrade.DESCRIPTION + "', @enabled,"
				+ " 'IMPORT_CATALOGUE', @next);");
		int delete = sql.indexOf("DELETE FROM erp_sync_job WHERE job_type IN " + four + ";");
		int commit = sql.indexOf("COMMIT TRANSACTION;");
		assertTrue(update > 0 && update < insert && insert < delete && delete < commit, sql);
		assertTrue(sql.endsWith("END TRY BEGIN CATCH IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION; THROW; END CATCH END"), sql);
		// Only these rows: no other job is touched
		assertEquals(1, sql.split("DELETE FROM", -1).length - 1);
		assertEquals(1, sql.split("UPDATE erp_sync_job", -1).length - 1);
	}

	@Test
	@DisplayName("At startup: nothing done when none of the four rows is there (a converted or a new database)")
	void nothingToConvert() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForObject(ErpSyncJobTypesUpgrade.PENDING, Integer.class)).thenReturn(0);
		new ErpSyncJobTypesUpgrade(jdbc).run();
		verify(jdbc, never()).execute(anyString());
	}

	@Test
	@DisplayName("At startup: the batch run once when one of the four rows is there")
	void converts() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForObject(ErpSyncJobTypesUpgrade.PENDING, Integer.class)).thenReturn(1);
		when(jdbc.queryForObject(eq("SELECT enabled FROM erp_sync_job WHERE job_type = 'IMPORT_CATALOGUE'"),
				eq(Boolean.class))).thenReturn(true);
		new ErpSyncJobTypesUpgrade(jdbc).run();
		verify(jdbc).execute(ErpSyncJobTypesUpgrade.CONVERSION);
		assertTrue(ErpSyncJobTypesUpgrade.PENDING.contains("OBJECT_ID(N'erp_sync_job', N'U') IS NOT NULL"),
				"a new database (no table yet) has nothing to convert");
	}
}
