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
-- file of this folder (store-orders.sql: docs/modules/store-orders.md). It stops, without changing anything, while
-- store-orders.sql has not run (no item_image table).

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

UPDATE APP_VERSION SET version = '3.1.1';

-- The notes of 3.1.1 are written again on each run (the script may run more than once). Written from CHANGELOG.md when
-- the version is closed (the screens of the orders to the head office come in a later step).
DELETE FROM APP_RELEASE_NOTES WHERE version = '3.1.1';

GO

SET NOEXEC OFF;
GO
