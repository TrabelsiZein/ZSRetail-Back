-- ============================================================
-- Version 3.5.0 (in progress) — ticket templates ("Modèles de tickets"): the version only so far (step 1's setting is
-- created at start-up)
-- ============================================================
-- Deployment order (a database at 3.4.0; CHANGELOG.md, 3.5.0, "Before deployment"):
--   1) Bring the database to 3.4.0 first (db/3.4.0/update.sql). This script stops, without changing anything, while
--      APP_VERSION is not 3.4.0 or 3.5.0.
--   2) Run this script against the database (the same script on a store and on a head office):
--      sqlcmd ... -f 65001 -i update.sql (in SSMS open it as UTF-8).
--   3) Deploy the WAR of the installation's type as zsretailws.war (docs/deploy-note.md).
-- The application refuses to start while APP_VERSION differs from its own version, so step 2 must be done before 3.
--
-- Re-runnable: each statement is guarded and changes nothing the second time.

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

-- 3.4.0 first. Otherwise the rest of the script is not executed (NOEXEC, reset at the end).
IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('3.4.0', '3.5.0'))
BEGIN
	DECLARE @current nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'3.5.0: APP_VERSION is %s; bring the database to 3.4.0 first (db/3.4.0/update.sql). Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

UPDATE APP_VERSION SET version = '3.5.0';

-- Ticket templates, step 1: the store setting TICKET_RENDERER (General Setup, tab "Caisse et clôture", OLD / NEW, OLD by
-- default: the print code of before) is created at start-up on a store, as every General Setup setting
-- (ZZDataInitializer); no statement here. A head office has none.

-- The release notes of 3.5.0 are written from CHANGELOG.md when the version is closed (nothing to tell the users yet).

GO

SET NOEXEC OFF;
GO
