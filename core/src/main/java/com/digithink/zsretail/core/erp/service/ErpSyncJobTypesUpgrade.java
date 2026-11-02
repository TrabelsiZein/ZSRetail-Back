package com.digithink.zsretail.core.erp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 3.1.1: the rows of the four catalogue jobs of before (IMPORT_ITEM_FAMILIES, IMPORT_ITEM_SUBFAMILIES, IMPORT_ITEMS,
 * IMPORT_ITEM_BARCODES) converted into the one job IMPORT_CATALOGUE, at startup, in JDBC, before any JPA read of
 * erp_sync_job (the first thing ZZDataInitializer.init() does, as TillSchemaUpgrade; HeadOfficeErpJobs runs after it, the
 * scheduler after every bean): a row with a job type the code no longer has would fail every read of the table. IMPORT_CATALOGUE takes the cron of IMPORT_ITEMS (and its
 * next run while it is enabled), enabled when any of the four was; then the four rows are deleted, in one transaction.
 * Idempotent: once the four rows are gone it does nothing. The same batch is in db/3.1.1/update.sql (the order of the
 * script and the deploy does not matter). Store and head office alike (a head office refuses the job when its catalogue
 * comes from the ERP only, HeadOfficeErpJobs). Then the rows of the job type removed, EXPORT_CUSTOMERS, and of its
 * operation EXPORT_CUSTOMER in the communications log are deleted ({@link #DEAD_ROWS}), for the same reason: the two
 * columns are read with EnumType.STRING, a value the code no longer has throws on every read.
 */
@Component
public class ErpSyncJobTypesUpgrade {

	private static final Logger LOGGER = LoggerFactory.getLogger(ErpSyncJobTypesUpgrade.class);

	static final String OLD_TYPES = "'IMPORT_ITEM_FAMILIES', 'IMPORT_ITEM_SUBFAMILIES', 'IMPORT_ITEMS', 'IMPORT_ITEM_BARCODES'";

	/** The description of the job, as ZZDataInitializer seeds it. */
	public static final String DESCRIPTION = "Import of the catalogue: catégories, families, sub-families, sections,"
			+ " items, barcodes";

	/** True when there is something to convert (the table and one of the four rows). */
	static final String PENDING = "SELECT CASE WHEN OBJECT_ID(N'erp_sync_job', N'U') IS NOT NULL"
			+ " AND EXISTS (SELECT 1 FROM erp_sync_job WHERE job_type IN (" + OLD_TYPES + ")) THEN 1 ELSE 0 END";

	/** The conversion, one T-SQL batch, idempotent; copied as it is into db/3.1.1/update.sql. */
	public static final String CONVERSION = ""
			+ "IF OBJECT_ID(N'erp_sync_job', N'U') IS NOT NULL\n"
			+ "\tAND EXISTS (SELECT 1 FROM erp_sync_job WHERE job_type IN (" + OLD_TYPES + "))\n"
			+ "BEGIN\n"
			+ "\tBEGIN TRY\n"
			+ "\t\tBEGIN TRANSACTION;\n"
			+ "\t\tDECLARE @enabled BIT = CASE WHEN EXISTS (SELECT 1 FROM erp_sync_job WHERE job_type IN (" + OLD_TYPES
			+ ") AND enabled = 1) THEN 1 ELSE 0 END;\n"
			+ "\t\tDECLARE @cron VARCHAR(64) = COALESCE((SELECT TOP 1 cron_expression FROM erp_sync_job"
			+ " WHERE job_type = 'IMPORT_ITEMS'), '0 0 * * * *');\n"
			+ "\t\tDECLARE @next DATETIME2 = (SELECT TOP 1 next_run_at FROM erp_sync_job WHERE job_type = 'IMPORT_ITEMS'"
			+ " AND enabled = 1 AND @enabled = 1);\n"
			+ "\t\tIF EXISTS (SELECT 1 FROM erp_sync_job WHERE job_type = 'IMPORT_CATALOGUE')\n"
			+ "\t\t\tUPDATE erp_sync_job SET cron_expression = @cron, enabled = @enabled, next_run_at = @next,"
			+ " updated_by = 'System', updated_at = SYSDATETIME() WHERE job_type = 'IMPORT_CATALOGUE';\n"
			+ "\t\tELSE\n"
			+ "\t\t\tINSERT INTO erp_sync_job (active, created_at, created_by, updated_at, updated_by, cron_expression,"
			+ " description, enabled, job_type, next_run_at)\n"
			+ "\t\t\tVALUES (1, SYSDATETIME(), 'System', SYSDATETIME(), 'System', @cron, N'" + DESCRIPTION + "', @enabled,"
			+ " 'IMPORT_CATALOGUE', @next);\n"
			+ "\t\tDELETE FROM erp_sync_job WHERE job_type IN (" + OLD_TYPES + ");\n"
			+ "\t\tCOMMIT TRANSACTION;\n"
			+ "\tEND TRY\n"
			+ "\tBEGIN CATCH\n"
			+ "\t\tIF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n"
			+ "\t\tTHROW;\n"
			+ "\tEND CATCH\n"
			+ "END";

	/**
	 * 3.1.1: the rows of the job type removed, EXPORT_CUSTOMERS (never implemented: the runner only logged it, every
	 * connector answered a failure; a disabled template row on every installation with an ERP), and of its operation in
	 * the communications log, EXPORT_CUSTOMER: how many there are (0 when a table is missing).
	 */
	static final String DEAD_ROWS_PENDING = "SELECT"
			+ " (CASE WHEN OBJECT_ID(N'erp_sync_job', N'U') IS NULL THEN 0"
			+ " ELSE (SELECT COUNT(*) FROM erp_sync_job WHERE job_type = 'EXPORT_CUSTOMERS') END)"
			+ " + (CASE WHEN OBJECT_ID(N'erp_communication', N'U') IS NULL THEN 0"
			+ " ELSE (SELECT COUNT(*) FROM erp_communication WHERE operation = 'EXPORT_CUSTOMER') END)";

	/** Their deletion, one T-SQL batch, idempotent; copied as it is into db/3.1.2/update.sql. */
	public static final String DEAD_ROWS = ""
			+ "IF OBJECT_ID(N'erp_sync_job', N'U') IS NOT NULL\n"
			+ "\tDELETE FROM erp_sync_job WHERE job_type = 'EXPORT_CUSTOMERS';\n"
			+ "IF OBJECT_ID(N'erp_communication', N'U') IS NOT NULL\n"
			+ "\tDELETE FROM erp_communication WHERE operation = 'EXPORT_CUSTOMER';";

	private final JdbcTemplate jdbc;

	public ErpSyncJobTypesUpgrade(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The conversion of the four catalogue jobs, then the deletion of the rows of the removed type, each only when there is
	 * something to do; called first by ZZDataInitializer.init().
	 */
	public void run() {
		Integer pending = jdbc.queryForObject(PENDING, Integer.class);
		if (pending != null && pending > 0) {
			jdbc.execute(CONVERSION);
			Boolean enabled = jdbc.queryForObject(
					"SELECT enabled FROM erp_sync_job WHERE job_type = 'IMPORT_CATALOGUE'", Boolean.class);
			LOGGER.info("ERP jobs: the four catalogue jobs converted into IMPORT_CATALOGUE ({})",
					Boolean.TRUE.equals(enabled) ? "enabled" : "disabled");
		}
		Integer dead = jdbc.queryForObject(DEAD_ROWS_PENDING, Integer.class);
		if (dead != null && dead > 0) {
			jdbc.execute(DEAD_ROWS);
			LOGGER.info("ERP jobs: {} row(s) of the removed job EXPORT_CUSTOMERS (job and communications log) deleted",
					dead);
		}
	}
}
