-- ============================================================
-- Version 3.5.0 (in progress) — ticket templates ("Modèles de tickets"): the table ticket_template and the POS
-- machine's paper width (steps 2 and 2b; step 1's setting is created at start-up)
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

-- Ticket templates, steps 2 and 2b (docs/modules/ticket-templates.md): the table of the templates, one row per document
-- type (SALE now; the others at steps 3 and 4), on a store and on a head office (step 5 sends them down). No row = the
-- built-in default (today's ticket); "Réinitialiser" deletes the row. In Hibernate's form (TicketTemplate), the unique
-- key named as in the entity. Format 2 (step 2b): two JSON columns, levels only, never a CSS value: layout (paper
-- width, size, spacing, blank lines before the cut, separator style) and blocks (the sections in print order, their
-- elements' switches and options); format_version; origin LOCAL (HEAD_OFFICE: a copy received, step 5). No data written.
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
		layout nvarchar(max) NULL,
		blocks nvarchar(max) NULL,
		format_version int NOT NULL CONSTRAINT df_ticket_template_format_version DEFAULT 2,
		origin varchar(20) NOT NULL CONSTRAINT df_ticket_template_origin DEFAULT 'LOCAL',
		CONSTRAINT PK_ticket_template PRIMARY KEY (id),
		CONSTRAINT uk_ticket_template_document_type UNIQUE (document_type)
	);
END
GO

-- Step 2b, a database that ran the draft of step 2 (3.5.0 was never released: the development databases only): its
-- columns paper_width, font_size, compact, hide_zero_rows become the JSON column layout (compact = COMPACT spacing,
-- else AIRY; LINE separators; no blank line added), each row moved to format 2, then the four columns dropped (the
-- default of hide_zero_rows first). Separate batches: a batch naming a column that is not there does not compile.
IF OBJECT_ID(N'ticket_template', N'U') IS NOT NULL AND COL_LENGTH(N'ticket_template', N'layout') IS NULL
	ALTER TABLE ticket_template ADD layout nvarchar(max) NULL;
GO
IF COL_LENGTH(N'ticket_template', N'paper_width') IS NOT NULL
	EXEC(N'UPDATE ticket_template SET layout = N''{"paperWidth":'' + CAST(paper_width AS nvarchar(10))
		+ N'',"fontSize":"'' + font_size + N''","spacing":"'' + CASE WHEN compact = 1 THEN N''COMPACT'' ELSE N''AIRY'' END
		+ N''","feedLines":0,"separator":"LINE"}'', format_version = 2 WHERE layout IS NULL');
GO
IF OBJECT_ID(N'df_ticket_template_hide_zero_rows', N'D') IS NOT NULL
	ALTER TABLE ticket_template DROP CONSTRAINT df_ticket_template_hide_zero_rows;
GO
IF COL_LENGTH(N'ticket_template', N'paper_width') IS NOT NULL
	ALTER TABLE ticket_template DROP COLUMN paper_width, font_size, compact, hide_zero_rows;
GO
IF EXISTS (SELECT 1 FROM sys.default_constraints WHERE name = N'df_ticket_template_format_version'
	AND definition <> N'((2))')
BEGIN
	ALTER TABLE ticket_template DROP CONSTRAINT df_ticket_template_format_version;
	ALTER TABLE ticket_template ADD CONSTRAINT df_ticket_template_format_version DEFAULT 2 FOR format_version;
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
