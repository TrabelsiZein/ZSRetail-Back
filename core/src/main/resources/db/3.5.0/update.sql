-- ============================================================
-- Version 3.5.0 (in progress) — ticket templates ("Modèles de tickets"): the table ticket_template and the POS
-- machine's paper width (step 2; step 1's setting is created at start-up)
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

GO

-- Ticket templates, step 2 (docs/modules/ticket-templates.md): the table of the templates, one row per document type
-- (SALE now; the others at steps 3 and 4), on a store and on a head office (step 5 sends them down). No row = the
-- built-in default (today's ticket); "Réinitialiser" deletes the row. In Hibernate's form (TicketTemplate), the unique
-- key named as in the entity; levels only, no CSS value: paper_width 58 / 80, font_size SMALL / NORMAL / LARGE,
-- compact, hide_zero_rows (the internal tickets of step 4), blocks (JSON, the sections in print order and their
-- elements' switches, its format_version), origin LOCAL (HEAD_OFFICE: a copy received, step 5). No data written.
IF OBJECT_ID(N'ticket_template', N'U') IS NULL
BEGIN
	CREATE TABLE ticket_template (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		document_type varchar(40) NOT NULL,
		paper_width int NOT NULL,
		font_size varchar(10) NOT NULL,
		compact bit NOT NULL,
		hide_zero_rows bit NOT NULL CONSTRAINT df_ticket_template_hide_zero_rows DEFAULT 0,
		blocks nvarchar(max) NULL,
		format_version int NOT NULL CONSTRAINT df_ticket_template_format_version DEFAULT 1,
		origin varchar(20) NOT NULL CONSTRAINT df_ticket_template_origin DEFAULT 'LOCAL',
		CONSTRAINT PK_ticket_template PRIMARY KEY (id),
		CONSTRAINT uk_ticket_template_document_type UNIQUE (document_type)
	);
END
GO

-- Ticket templates, step 2, a store only (it has quick_quantity and pos_machine): the paper width of a POS machine's
-- printer, 58 or 80; empty (every existing machine) = the template's width. Nullable, no default: nothing changes on
-- the machines of before.
IF OBJECT_ID(N'quick_quantity', N'U') IS NOT NULL AND OBJECT_ID(N'pos_machine', N'U') IS NOT NULL
	AND COL_LENGTH(N'pos_machine', N'paper_width') IS NULL
	ALTER TABLE pos_machine ADD paper_width int NULL;
GO

-- The release notes of 3.5.0 are written from CHANGELOG.md when the version is closed (nothing to tell the users yet).

GO

SET NOEXEC OFF;
GO
