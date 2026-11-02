-- ============================================================
-- Version 3.1.2 (in progress) — weighted average cost, unit cost on the ticket lines, ERP connector parafendri
-- ============================================================
-- Deployment order (a database at 3.1.1; CHANGELOG.md, 3.1.2, "Before deployment" for the checks first):
--   1) Run db/3.1.1/store-orders.sql and db/3.1.1/update.sql first if the database is older than 3.1.1 (the scripts of
--      3.1.2 stop, without changing anything, while APP_VERSION is not 3.1.1 or 3.1.2).
--   2) Run db/3.1.2/sales-line-unit-cost.sql, then this script, against the database (the same scripts on a store and
--      on a head office). The files are UTF-8: with sqlcmd add -f 65001 (sqlcmd ... -f 65001 -i update.sql); in SSMS
--      open them as UTF-8.
--   3) Deploy the WAR of the installation's type as zsretailws.war (docs/deploy-note.md).
-- The application refuses to start while APP_VERSION differs from its own version, so step 2 must be done before 3.
--
-- This script stamps APP_VERSION 3.1.2 and writes the notes of the version; the schema of each feature is in its own
-- file of this folder (sales-line-unit-cost.sql: the unit cost frozen on the ticket lines). It stops, without changing
-- anything, while sales-line-unit-cost.sql has not run (no sales_line.unit_cost column).

-- The session options are set here (sqlcmd starts with QUOTED_IDENTIFIER OFF); SSMS has them on already.

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET NUMERIC_ROUNDABORT OFF;
GO

-- 3.1.1 first, then the feature scripts. Otherwise the rest of the script is not executed (NOEXEC, reset at the end).
IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('3.1.1', '3.1.2'))
BEGIN
	DECLARE @current nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'3.1.2: APP_VERSION is %s; run db/3.1.1/update.sql first. Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

IF (OBJECT_ID(N'sales_line', N'U') IS NOT NULL AND COL_LENGTH(N'sales_line', N'unit_cost') IS NULL)
	OR (OBJECT_ID(N'return_line', N'U') IS NOT NULL AND COL_LENGTH(N'return_line', N'unit_cost') IS NULL)
BEGIN
	RAISERROR(N'3.1.2: run db/3.1.2/sales-line-unit-cost.sql first (no unit_cost column). APP_VERSION not changed.', 16, 1);
	SET NOEXEC ON;
END
GO

-- The ERP jobs (3.1.2): the job type EXPORT_CUSTOMERS is removed (never implemented; a disabled template row on every
-- installation with an ERP) with its operation EXPORT_CUSTOMER of the communications log: their rows are deleted, else a
-- read of erp_sync_job or erp_communication would fail on a value the application no longer has. Nothing to do once
-- they are gone; the application does the same at startup (ErpSyncJobTypesUpgrade.DEAD_ROWS).
IF OBJECT_ID(N'erp_sync_job', N'U') IS NOT NULL
	DELETE FROM erp_sync_job WHERE job_type = 'EXPORT_CUSTOMERS';
IF OBJECT_ID(N'erp_communication', N'U') IS NOT NULL
	DELETE FROM erp_communication WHERE operation = 'EXPORT_CUSTOMER';
GO

UPDATE APP_VERSION SET version = '3.1.2';

-- The notes of 3.1.2 are written again on each run (the script may run more than once). Written from CHANGELOG.md when
-- the version is closed.
DELETE FROM APP_RELEASE_NOTES WHERE version = '3.1.2';

GO

SET NOEXEC OFF;
GO
