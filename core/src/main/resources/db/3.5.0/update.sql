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
-- Re-runnable: each statement is guarded and changes nothing the second time. APP_VERSION is set to 3.5.0 at the end
-- (step 6: the carry of the ticket settings runs only on the pass from 3.4.0).

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

-- Ticket templates, step 5, a head office only (it has ho_store): "Le siège gère les modèles de tickets", the switch of a
-- store's row on the Stores page. Nullable, no default: every existing store keeps its own templates (null = off).
IF OBJECT_ID(N'ho_store', N'U') IS NOT NULL AND COL_LENGTH(N'ho_store', N'manages_ticket_templates') IS NULL
	ALTER TABLE ho_store ADD manages_ticket_templates bit NULL;
GO

-- Ticket templates, step 5, a store only (it has quick_quantity and pos_machine): when the head office starts managing
-- the store's templates, the store's own rows of ticket_template are copied here, dated, then replaced by the head
-- office's. Kept for a recovery by hand only: the application writes this table and never reads it.
IF OBJECT_ID(N'quick_quantity', N'U') IS NOT NULL AND OBJECT_ID(N'pos_machine', N'U') IS NOT NULL
	AND OBJECT_ID(N'ticket_template_saved', N'U') IS NULL
BEGIN
	CREATE TABLE ticket_template_saved (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		document_type varchar(40) NOT NULL,
		layout nvarchar(max) NULL,
		blocks nvarchar(max) NULL,
		format_version int NOT NULL,
		origin varchar(20) NOT NULL,
		template_updated_at datetime2 NULL,
		template_updated_by varchar(255) NULL,
		saved_at datetime2 NOT NULL,
		CONSTRAINT PK_ticket_template_saved PRIMARY KEY (id)
	);
END
GO

-- Ticket templates, step 6 part 1 (docs/roadmap/ticket-templates.md): the new printing is the only one. A store's ticket
-- settings of before (Company Information: the address, the thank-you line and its text, the "ZS Retail" label, the
-- ticket footer text, else the invoice footer note the old ticket printed in its place; General setup:
-- TICKET_SHOW_LOYALTY_EARNED / _BALANCE) are carried into its templates, so it prints the same ticket the day after
-- with nobody configuring anything. A row only for a document whose carried values differ from the built-in default
-- (a store that never changed them gets none): SALE (every one of them), RETURN_VOUCHER, CASH_REFUND and WARRANTY (the
-- address, the footer text, the label). The row is partial: the elements carried, every one that was linked to an old
-- setting named, the tombola section on (TOMBOLA_ENABLED alone decides); the application completes the rest from its
-- catalogue (TicketTemplateService.normalise) and keeps a text longer than the page's limits (it prints as before; the
-- page asks to shorten it before the next save). Origin LOCAL, updated_by 'upgrade 3.5.0', no layout (the default).
-- The old columns and rows stay (not read any more; dropped with the next release).
-- Guards: a store database only: it has quick_quantity (every store started at 3.1.2 or later) or no ho_store (a store
-- brought up by the scripts alone); a head office has ho_store and never quick_quantity. "No ho_store" alone is not
-- enough: a store database created before the projects were split has every table, ho_store too. Only on the pass that
-- brings the database
-- from 3.4.0 (APP_VERSION is set to 3.5.0 at the end of this script, so a second run carries nothing again, even after
-- an admin's "Réinitialiser"); never over a row of that document. A run stopped before the end leaves 3.4.0: the next
-- run carries what is missing only.
IF EXISTS (SELECT 1 FROM APP_VERSION WHERE version = '3.4.0')
	AND (OBJECT_ID(N'quick_quantity', N'U') IS NOT NULL OR OBJECT_ID(N'ho_store', N'U') IS NULL)
	AND OBJECT_ID(N'ticket_template', N'U') IS NOT NULL
	AND COL_LENGTH(N'company_information', N'receipt_footer_text') IS NOT NULL
BEGIN
	DECLARE @printAddress bit, @showThanks bit, @showLabel bit,
		@thanksText nvarchar(max), @footerText nvarchar(max), @invoiceNote nvarchar(max);
	SELECT TOP 1 @printAddress = receipt_print_address, @showThanks = receipt_show_thank_you,
		@showLabel = receipt_show_software_label, @thanksText = receipt_thank_you_text,
		@footerText = receipt_footer_text, @invoiceNote = invoice_footer_note
	FROM company_information ORDER BY id;

	-- The switches as the old ticket read them: the address only when on (null = no), the thank-you line and the label
	-- unless off (null = yes), a loyalty row printed unless its General setup row says anything but true (absent = yes)
	DECLARE @addressOn bit = CASE WHEN @printAddress = 1 THEN 1 ELSE 0 END;
	DECLARE @thanksOn bit = CASE WHEN @showThanks = 0 THEN 0 ELSE 1 END;
	DECLARE @labelOn bit = CASE WHEN @showLabel = 0 THEN 0 ELSE 1 END;
	DECLARE @earnedRow bit = 0, @earnedValue nvarchar(255), @balanceRow bit = 0, @balanceValue nvarchar(255);
	SELECT TOP 1 @earnedRow = 1, @earnedValue = valeur FROM general_setup WHERE code = 'TICKET_SHOW_LOYALTY_EARNED';
	SELECT TOP 1 @balanceRow = 1, @balanceValue = valeur FROM general_setup WHERE code = 'TICKET_SHOW_LOYALTY_BALANCE';
	DECLARE @earnedOn bit = CASE WHEN @earnedRow = 0 OR LOWER(LTRIM(RTRIM(@earnedValue))) = N'true' THEN 1 ELSE 0 END;
	DECLARE @balanceOn bit = CASE WHEN @balanceRow = 0 OR LOWER(LTRIM(RTRIM(@balanceValue))) = N'true' THEN 1 ELSE 0 END;

	-- The texts as the old ticket printed them. The thank-you: one line, trimmed; empty or the built-in one = null (the
	-- built-in prints). The footer: the ticket footer text, its line breaks kept, the whitespace at its end cut; empty, the
	-- invoice footer note (printed on one line: its line breaks and runs of spaces as one space); neither = null (no
	-- footer text). Other control characters dropped (they printed nothing; JSON would refuse them).
	SET @thanksText = LTRIM(RTRIM(REPLACE(REPLACE(REPLACE(ISNULL(@thanksText, N''), NCHAR(13), N' '), NCHAR(10), N' '), NCHAR(9), N' ')));
	SET @footerText = REPLACE(ISNULL(@footerText, N''), NCHAR(13), N'');
	WHILE LEN(@footerText + N'x') > 1 AND RIGHT(@footerText, 1) IN (N' ', NCHAR(10), NCHAR(9))
		SET @footerText = LEFT(@footerText, LEN(@footerText + N'x') - 2);
	IF LEN(@footerText + N'x') = 1
	BEGIN
		SET @footerText = LTRIM(RTRIM(REPLACE(REPLACE(REPLACE(ISNULL(@invoiceNote, N''), NCHAR(13), N' '), NCHAR(10), N' '), NCHAR(9), N' ')));
		-- its spaces as they printed (one): the template's text keeps its line breaks (pre-line), a single line is the same
		WHILE CHARINDEX(N'  ', @footerText) > 0
			SET @footerText = REPLACE(@footerText, N'  ', N' ');
	END
	DECLARE @control int = 1;
	WHILE @control < 32
	BEGIN
		IF @control NOT IN (9, 10, 13)
		BEGIN
			SET @thanksText = REPLACE(@thanksText COLLATE Latin1_General_BIN, NCHAR(@control), N'');
			SET @footerText = REPLACE(@footerText COLLATE Latin1_General_BIN, NCHAR(@control), N'');
		END
		SET @control = @control + 1;
	END
	IF LEN(@thanksText + N'x') = 1 OR @thanksText COLLATE Latin1_General_BIN = N'Merci pour votre achat !'
		SET @thanksText = NULL;
	IF LEN(@footerText + N'x') = 1
		SET @footerText = NULL;

	-- JSON: a text as a string (backslash, quote, line break, tab escaped) or null; a switch as true / false
	DECLARE @thanksJson nvarchar(max) = CASE WHEN @thanksText IS NULL THEN N'null' ELSE N'"' + REPLACE(REPLACE(REPLACE(REPLACE(
		@thanksText, N'\', N'\\'), N'"', N'\"'), NCHAR(10), N'\n'), NCHAR(9), N'\t') + N'"' END;
	DECLARE @footerJson nvarchar(max) = CASE WHEN @footerText IS NULL THEN N'null' ELSE N'"' + REPLACE(REPLACE(REPLACE(REPLACE(
		@footerText, N'\', N'\\'), N'"', N'\"'), NCHAR(10), N'\n'), NCHAR(9), N'\t') + N'"' END;
	DECLARE @address nvarchar(5) = CASE WHEN @addressOn = 1 THEN N'true' ELSE N'false' END;
	DECLARE @thanks nvarchar(5) = CASE WHEN @thanksOn = 1 THEN N'true' ELSE N'false' END;
	DECLARE @label nvarchar(5) = CASE WHEN @labelOn = 1 THEN N'true' ELSE N'false' END;
	DECLARE @earned nvarchar(5) = CASE WHEN @earnedOn = 1 THEN N'true' ELSE N'false' END;
	DECLARE @balance nvarchar(5) = CASE WHEN @balanceOn = 1 THEN N'true' ELSE N'false' END;

	IF (@addressOn = 1 OR @thanksOn = 0 OR @labelOn = 0 OR @thanksText IS NOT NULL OR @footerText IS NOT NULL
		OR @earnedOn = 0 OR @balanceOn = 0)
		AND NOT EXISTS (SELECT 1 FROM ticket_template WHERE document_type = 'SALE')
		INSERT INTO ticket_template (active, created_at, created_by, updated_at, updated_by, document_type, layout, blocks,
			format_version, origin)
		VALUES (1, SYSDATETIME(), 'upgrade 3.5.0', SYSDATETIME(), 'upgrade 3.5.0', 'SALE', NULL,
			N'[{"code":"HEADER","on":true,"elements":[{"code":"ADDRESS","on":' + @address + N'}]},'
			+ N'{"code":"LOYALTY","on":true,"elements":[{"code":"EARNED","on":' + @earned + N'},{"code":"BALANCE","on":' + @balance + N'}]},'
			+ N'{"code":"FOOTER","on":true,"elements":[{"code":"THANK_YOU","on":' + @thanks + N',"options":{"text":' + @thanksJson + N'}},'
			+ N'{"code":"FOOTER_NOTE","on":true,"options":{"text":' + @footerJson + N'}},{"code":"SOFTWARE_LABEL","on":' + @label + N'}]},'
			+ N'{"code":"TOMBOLA","on":true,"elements":[]}]',
			2, 'LOCAL');

	IF @addressOn = 1 OR @labelOn = 0 OR @footerText IS NOT NULL
		INSERT INTO ticket_template (active, created_at, created_by, updated_at, updated_by, document_type, layout, blocks,
			format_version, origin)
		SELECT 1, SYSDATETIME(), 'upgrade 3.5.0', SYSDATETIME(), 'upgrade 3.5.0', d.document_type, NULL,
			N'[{"code":"HEADER","on":true,"elements":[{"code":"ADDRESS","on":' + @address + N'}]},'
			+ N'{"code":"FOOTER","on":true,"elements":[{"code":"FOOTER_NOTE","on":true,"options":{"text":' + @footerJson + N'}},'
			+ N'{"code":"SOFTWARE_LABEL","on":' + @label + N'}]}]',
			2, 'LOCAL'
		FROM (VALUES ('RETURN_VOUCHER'), ('CASH_REFUND'), ('WARRANTY')) AS d (document_type)
		WHERE NOT EXISTS (SELECT 1 FROM ticket_template t WHERE t.document_type = d.document_type);
END
GO

UPDATE APP_VERSION SET version = '3.5.0';

-- The notes of 3.5.0 are written again on each run (the script may run more than once), from CHANGELOG.md; the version
-- is still in progress (step 6 of the ticket templates written 2026-11-27).
DELETE FROM APP_RELEASE_NOTES WHERE version = '3.5.0';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('3.5.0', 'NEW', N'Modèles de tickets (Paramètres) : chaque ticket imprimé se règle au même endroit, avec un aperçu : ticket de vente et son duplicata, bon de retour, remboursement, garantie, ticket de tombola, tickets X et Z, bons de caisse. Largeur du papier, taille du texte, espacement, rubriques affichées et leur ordre, texte du haut et du pied.'),
('3.5.0', 'NEW', N'Siège : il peut gérer les modèles de tickets de ses magasins (interrupteur « Le siège gère les modèles de tickets » de la page Magasins) ; le magasin les reçoit à la synchronisation suivante.'),
('3.5.0', 'NEW', N'Caisses : la largeur du papier de l''imprimante (58 ou 80 mm) se règle pour chaque caisse.'),
('3.5.0', 'IMPROVE', N'Tous les tickets s''impriment avec les modèles de tickets : l''ancienne impression et le réglage « Impression des tickets » de la Configuration générale disparaissent.'),
('3.5.0', 'IMPROVE', N'Les réglages du ticket sont dans « Modèles de tickets » : l''adresse sur le ticket, la ligne de remerciement et son texte, la mention « ZS Retail » et le texte de bas de ticket quittent Informations société ; le solde de points et les points gagnés sur le ticket quittent la Configuration générale. À la mise à jour, les valeurs déjà réglées sont reprises dans les modèles : le ticket reste le même.'),
('3.5.0', 'IMPROVE', N'Le pied des tickets appartient au modèle : après la mise à jour, modifier la note de bas de facture dans Informations société ne change plus que les factures A4, pas les tickets.'),
('3.5.0', 'FIX', N'Ticket de garantie : il imprime la TVA réelle (au lieu de 0.000) et, quand la garantie couvre une partie de la ligne, le montant de cette part, remise du ticket comprise.'),
('3.5.0', 'FIX', N'Bon de retour réimprimé : « DUPLICATA » sous le titre, le caissier du retour et la même TVA que le premier ticket ; sa ligne de paiement s''appelle « Bon de retour ».'),
('3.5.0', 'FIX', N'Ticket de tombola : il ne s''imprime que si la tombola est activée, et retrouve sa présentation sur un ticket à part.'),
('3.5.0', 'FIX', N'Tickets X et Z et bons de caisse : les dates s''impriment en jj/mm/aaaa hh:mm ; sur le ticket de clôture, un nom avec « < » ou « & » s''imprime tel qu''il est saisi.'),
('3.5.0', 'FIX', N'Tous les tickets s''impriment en noir sur blanc (les bandeaux de numéro en couleur étaient illisibles sur une imprimante thermique).');

GO

SET NOEXEC OFF;
GO
