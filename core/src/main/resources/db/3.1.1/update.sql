-- ============================================================
-- Version 3.1.1 (in progress) — Commandes centrale
-- ============================================================
-- Deployment order:
--   1) Run db/3.1.0/update.sql first if the database is older than 3.1.0 (the scripts of 3.1.1 stop, without changing
--      anything, while APP_VERSION is not 3.1.0 or 3.1.1).
--   2) Run db/3.1.1/store-orders.sql, then this script, against the database (the same scripts on a store and on a head
--      office). The files are UTF-8: with sqlcmd add -f 65001 (sqlcmd ... -f 65001 -i update.sql); in SSMS open them as
--      UTF-8.
--   3) Deploy the WAR of the installation's type as zsretailws.war (docs/deploy-note.md).
-- The application refuses to start while APP_VERSION differs from its own version, so step 2 must be done before 3.
--
-- This script stamps APP_VERSION 3.1.1 and writes the notes of the version; the schema of each feature is in its own
-- file of this folder (store-orders.sql: docs/modules/store-orders.md), but for one index of the till (below). It
-- stops, without changing anything, while store-orders.sql has not run (no item_image table).

-- The session options are set here (sqlcmd starts with QUOTED_IDENTIFIER OFF); SSMS has them on already.

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET NUMERIC_ROUNDABORT OFF;
GO

-- 3.1.0 first, then the feature scripts. Otherwise the rest of the script is not executed (NOEXEC, reset at the end).
IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('3.1.0', '3.1.1'))
BEGIN
	DECLARE @current nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'3.1.1: APP_VERSION is %s; run db/3.1.0/update.sql first. Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

IF OBJECT_ID(N'item_image', N'U') IS NULL
BEGIN
	RAISERROR(N'3.1.1: run db/3.1.1/store-orders.sql first (no item_image table). APP_VERSION not changed.', 16, 1);
	SET NOEXEC ON;
END
GO

-- The till (3.1.1): its session's tickets counted (GET /cashier-session/current/ticket-count, the right rail) and its
-- pending tickets listed when its screen opens, by session and status: an index instead of a scan of every sale.
IF OBJECT_ID(N'sales_header', N'U') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_sales_header_session_status' AND object_id = OBJECT_ID(N'sales_header'))
	CREATE INDEX ix_sales_header_session_status ON sales_header (cashier_session_id, status);
GO

-- The ERP jobs (3.1.1): the four catalogue jobs IMPORT_ITEM_FAMILIES, IMPORT_ITEM_SUBFAMILIES, IMPORT_ITEMS and
-- IMPORT_ITEM_BARCODES become the one job IMPORT_CATALOGUE (docs/modules/erp-sync.md): the cron of IMPORT_ITEMS, enabled
-- when any of the four was, then the four rows deleted, in one transaction. Nothing to do once they are gone (the script
-- may run more than once). The application makes the same conversion at startup (ErpSyncJobTypesUpgrade, the same
-- statements): the order of this script and the deploy does not matter. Store and head office alike.
IF OBJECT_ID(N'erp_sync_job', N'U') IS NOT NULL
	AND EXISTS (SELECT 1 FROM erp_sync_job WHERE job_type IN ('IMPORT_ITEM_FAMILIES', 'IMPORT_ITEM_SUBFAMILIES', 'IMPORT_ITEMS', 'IMPORT_ITEM_BARCODES'))
BEGIN
	BEGIN TRY
		BEGIN TRANSACTION;
		DECLARE @enabled BIT = CASE WHEN EXISTS (SELECT 1 FROM erp_sync_job WHERE job_type IN ('IMPORT_ITEM_FAMILIES', 'IMPORT_ITEM_SUBFAMILIES', 'IMPORT_ITEMS', 'IMPORT_ITEM_BARCODES') AND enabled = 1) THEN 1 ELSE 0 END;
		DECLARE @cron VARCHAR(64) = COALESCE((SELECT TOP 1 cron_expression FROM erp_sync_job WHERE job_type = 'IMPORT_ITEMS'), '0 0 * * * *');
		DECLARE @next DATETIME2 = (SELECT TOP 1 next_run_at FROM erp_sync_job WHERE job_type = 'IMPORT_ITEMS' AND enabled = 1 AND @enabled = 1);
		IF EXISTS (SELECT 1 FROM erp_sync_job WHERE job_type = 'IMPORT_CATALOGUE')
			UPDATE erp_sync_job SET cron_expression = @cron, enabled = @enabled, next_run_at = @next, updated_by = 'System', updated_at = SYSDATETIME() WHERE job_type = 'IMPORT_CATALOGUE';
		ELSE
			INSERT INTO erp_sync_job (active, created_at, created_by, updated_at, updated_by, cron_expression, description, enabled, job_type, next_run_at)
			VALUES (1, SYSDATETIME(), 'System', SYSDATETIME(), 'System', @cron, N'Import of the catalogue: catégories, families, sub-families, sections, items, barcodes', @enabled, 'IMPORT_CATALOGUE', @next);
		DELETE FROM erp_sync_job WHERE job_type IN ('IMPORT_ITEM_FAMILIES', 'IMPORT_ITEM_SUBFAMILIES', 'IMPORT_ITEMS', 'IMPORT_ITEM_BARCODES');
		COMMIT TRANSACTION;
	END TRY
	BEGIN CATCH
		IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
		THROW;
	END CATCH
END
GO

-- The ERP jobs (3.1.1): the job type EXPORT_CUSTOMERS is removed (never implemented; a disabled template row on every
-- installation with an ERP) with its operation EXPORT_CUSTOMER of the communications log: their rows are deleted, else a
-- read of erp_sync_job or erp_communication would fail on a value the application no longer has. Nothing to do once
-- they are gone; the application does the same at startup (ErpSyncJobTypesUpgrade.DEAD_ROWS).
IF OBJECT_ID(N'erp_sync_job', N'U') IS NOT NULL
	DELETE FROM erp_sync_job WHERE job_type = 'EXPORT_CUSTOMERS';
IF OBJECT_ID(N'erp_communication', N'U') IS NOT NULL
	DELETE FROM erp_communication WHERE operation = 'EXPORT_CUSTOMER';
GO

UPDATE APP_VERSION SET version = '3.1.1';

-- The notes of 3.1.1 are written again on each run (the script may run more than once). Written from CHANGELOG.md when
-- the version is closed (the screens of the orders to the head office come in a later step).
DELETE FROM APP_RELEASE_NOTES WHERE version = '3.1.1';

GO

SET NOEXEC OFF;
GO
