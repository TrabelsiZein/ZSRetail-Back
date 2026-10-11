-- ============================================================
-- Version 2.3.0 — Le siège et le magasin deviennent deux applications (nettoyage du code, aucun changement fonctionnel)
-- ============================================================
-- Deployment order:
--   1) Run db/2.2.2 first if the database is older than 2.2.2 (this script stops, without changing anything, while
--      APP_VERSION is not 2.2.2 or 2.3.0).
--   2) Change the installation's outside file (<Tomcat>\conf\zsretail\zsretailws.properties) as docs/deploy-note.md
--      says ("Upgrade from 2.2.2 to 2.3.0: the outside file"); keep a copy of the 2.2.2 file for a rollback.
--   3) Run this script against the database (the same script on a store and on a head office).
--      The file is UTF-8 (accents of the release notes): with sqlcmd add -f 65001 (sqlcmd ... -f 65001 -i update.sql);
--      in SSMS open it as UTF-8. Read as ANSI, the notes are stored with garbled accents (é becomes Ã©).
--   4) Deploy the WAR of the installation's type as zsretailws.war: the store WAR on a store, the head office WAR on a
--      head office (docs/deploy-note.md).
-- The application refuses to start while APP_VERSION differs from its own version, so step 3 must be done before 4.
--
-- Schema: one change, on a head office whose catalogue comes from the ERP (Happyness) only. The table of the catalogue
-- engine's state, navpospages_state, is renamed erp_catalogue_state and its primary key pk_navpospages_state
-- pk_erp_catalogue_state, in one transaction, every row kept (the barcode cursor, the last item run, the items waiting
-- for their barcodes). A database with neither table (a store, an EMTOP database, a head office without the ERP
-- catalogue), or with erp_catalogue_state alone (the script run again, or the 2.3.0 application started first: it does
-- the same rename at its start), is not changed. When both tables exist, or the rename failed, the script ends with an
-- error naming the table and writes neither APP_VERSION nor the notes: keep the table with this head office's
-- barcodes.cursor row, drop the other, run the script again.
--
-- The session options are set here (sqlcmd starts with QUOTED_IDENTIFIER OFF); SSMS has them on already.

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET NUMERIC_ROUNDABORT OFF;
GO

-- 2.2.2 first. Otherwise the rest of the script is not executed (NOEXEC, reset at the end).
IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('2.2.2', '2.3.0'))
BEGIN
	DECLARE @current nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'2.3.0: APP_VERSION is %s; run db/2.2.2/update.sql first. Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

-- Code cleanup step 8: the catalogue engine's state table gets a neutral name (head office with the ERP catalogue only)
IF OBJECT_ID(N'navpospages_state', N'U') IS NOT NULL AND OBJECT_ID(N'erp_catalogue_state', N'U') IS NULL
BEGIN
    BEGIN TRY
        BEGIN TRANSACTION;
        EXEC sp_rename N'navpospages_state', N'erp_catalogue_state';
        IF OBJECT_ID(N'pk_navpospages_state', N'PK') IS NOT NULL
            EXEC sp_rename N'pk_navpospages_state', N'pk_erp_catalogue_state', N'OBJECT';
        COMMIT TRANSACTION;
        PRINT 'navpospages_state renamed erp_catalogue_state';
    END TRY
    BEGIN CATCH
        IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
        THROW;
    END CATCH
END
GO

-- The version only when no old table is left: both tables here, or a rename that failed above
IF OBJECT_ID(N'navpospages_state', N'U') IS NOT NULL
BEGIN
	IF OBJECT_ID(N'erp_catalogue_state', N'U') IS NOT NULL
		RAISERROR(N'2.3.0: both navpospages_state and erp_catalogue_state exist. Keep the one with the barcodes.cursor row of this head office, drop the other, run the script again. APP_VERSION not changed.', 16, 1);
	ELSE
		RAISERROR(N'2.3.0: navpospages_state was not renamed (see the error above). APP_VERSION not changed.', 16, 1);
	SET NOEXEC ON;
END
GO

UPDATE APP_VERSION SET version = '2.3.0';

-- The notes of 2.3.0 are written again on each run (the script may run more than once)
DELETE FROM APP_RELEASE_NOTES WHERE version = '2.3.0';
INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('2.3.0', 'IMPROVE', 'Aucun changement fonctionnel : les écrans, les calculs et les données restent ceux de la 2.2.2.'),
('2.3.0', 'IMPROVE', 'Le siège et le magasin sont désormais deux applications distinctes : un magasin installe l''application du magasin, un siège celle du siège, chacune sous le nom zsretailws.'),
('2.3.0', 'IMPROVE', 'Le fichier d''installation (conf\zsretail\zsretailws.properties) doit être mis à jour avant la mise à jour : retirer spring.profiles.active et node.type, choisir l''ERP par erp.connector, renommer erp.dynamicsnav.* en erp.emtop.* et erp.navpospages.* en erp.happyness.*. Une ligne ancienne restante bloque le démarrage avec un message qui la nomme.');

GO

SET NOEXEC OFF;
GO
