-- ============================================================
-- Version 2.2.2 — Retours avec quantités décimales (0,2 L retourné d'une ligne vendue 0,5 L ; 1,5 d'une ligne vendue 2)
-- ============================================================
-- Deployment order:
--   1) Run db/2.2.1 first if the database is older than 2.2.1 (this script stops, without changing anything, while
--      APP_VERSION is not 2.2.1 or 2.2.2).
--   2) Run this script against the database (the same script on a store and on a head office).
--      The file is UTF-8 (accents of the release notes): with sqlcmd add -f 65001 (sqlcmd ... -f 65001 -i update.sql);
--      in SSMS open it as UTF-8. Read as ANSI, the notes are stored with garbled accents (é becomes Ã©).
--   3) Deploy the 2.2.2 binaries (backend WAR and frontend).
-- The application refuses to start while APP_VERSION differs from its own version, so step 2 must be done before 3.
-- A head office and its stores move to 2.2.2 together: a 2.2.1 head office refuses the copy of a return of 0.2 (it
-- reads returned quantities as whole numbers only), and the store sends it again until the head office is 2.2.2.
--
-- Schema: the quantity of the return lines becomes DECIMAL(18,3), with the same nullability, as the quantity columns of
-- 2.2.1: return_line.quantity (a store) and ho_return_line.quantity (the copies of the returns at a head office). A
-- table absent from this database is skipped. Every existing value keeps its value (1 becomes 1.000). Each column is
-- done in its own transaction: an index or a default constraint on it is dropped and recreated identically; any other
-- object on the column (primary key, unique or check constraint, foreign key, statistics created by hand, computed
-- column) stops the script with its name, and that column is left untouched. A column already DECIMAL(18,3) is skipped,
-- so the script can run again.
--
-- The columns are listed once, in #zs_222_columns below. APP_VERSION and the release notes are written only when every
-- listed column of this database is DECIMAL(18,3): otherwise the script ends with an error naming the columns left, and
-- the application keeps refusing to start.
--
-- The session options are set here (sqlcmd starts with QUOTED_IDENTIFIER OFF, which the reading of filtered indexes
-- and the XML used to rebuild an index need ON); SSMS has them on already.

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET NUMERIC_ROUNDABORT OFF;
GO

-- 2.2.1 first: its quantity columns (sales, stock, copies) are the base of the decimal returns. Otherwise the rest of
-- the script is not executed (NOEXEC, reset at the end).
IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('2.2.1', '2.2.2'))
BEGIN
	DECLARE @current nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'2.2.2: APP_VERSION is %s; run db/2.2.1/update.sql first. Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

IF OBJECT_ID('tempdb..#zs_222_columns') IS NOT NULL
	DROP TABLE #zs_222_columns;
CREATE TABLE #zs_222_columns (step int NOT NULL, table_name sysname NOT NULL, column_name sysname NOT NULL);
INSERT INTO #zs_222_columns (step, table_name, column_name) VALUES
-- Step 1: the return lines of a store, and their copies at a head office (each absent from the other: skipped)
(1, 'return_line', 'quantity'),
(1, 'ho_return_line', 'quantity');
GO

IF OBJECT_ID('tempdb..#zs_decimal_quantity') IS NOT NULL
	DROP PROCEDURE #zs_decimal_quantity;
GO

CREATE PROCEDURE #zs_decimal_quantity @table sysname, @column sysname
AS
BEGIN
	SET NOCOUNT ON;
	SET XACT_ABORT ON;

	DECLARE @object int = OBJECT_ID(@table);
	DECLARE @columnId int;
	IF @object IS NULL
		RETURN; -- this table does not exist here
	SELECT @columnId = column_id FROM sys.columns WHERE object_id = @object AND name = @column;
	IF @columnId IS NULL
		RETURN;
	IF EXISTS (SELECT 1 FROM sys.columns c JOIN sys.types t ON t.user_type_id = c.user_type_id
			WHERE c.object_id = @object AND c.column_id = @columnId AND t.name IN ('decimal', 'numeric')
				AND c.precision = 18 AND c.scale = 3)
		RETURN; -- already done

	-- Objects this script does not recreate: stop, naming them
	DECLARE @blocking nvarchar(max) = N'';
	SELECT @blocking += N' index ' + i.name
		FROM sys.indexes i
		WHERE i.object_id = @object AND (i.is_primary_key = 1 OR i.is_unique_constraint = 1)
			AND EXISTS (SELECT 1 FROM sys.index_columns ic
				WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.column_id = @columnId);
	SELECT @blocking += N' check ' + k.name
		FROM sys.check_constraints k WHERE k.parent_object_id = @object AND k.parent_column_id = @columnId;
	SELECT @blocking += N' foreign key ' + f.name
		FROM sys.foreign_key_columns fc JOIN sys.foreign_keys f ON f.object_id = fc.constraint_object_id
		WHERE (fc.parent_object_id = @object AND fc.parent_column_id = @columnId)
			OR (fc.referenced_object_id = @object AND fc.referenced_column_id = @columnId);
	SELECT @blocking += N' statistics ' + s.name
		FROM sys.stats s JOIN sys.stats_columns sc ON sc.object_id = s.object_id AND sc.stats_id = s.stats_id
		WHERE s.object_id = @object AND sc.column_id = @columnId AND s.user_created = 1;
	SELECT @blocking += N' computed column ' + cc.name
		FROM sys.computed_columns cc JOIN sys.sql_expression_dependencies d
			ON d.referencing_id = cc.object_id AND d.referencing_minor_id = cc.column_id
		WHERE cc.object_id = @object AND d.referenced_id = @object AND d.referenced_minor_id = @columnId;
	IF @blocking <> N''
	BEGIN
		DECLARE @blockingText nvarchar(1000) = LEFT(@blocking, 1000);
		RAISERROR(N'2.2.2: %s.%s was not changed, these objects use it:%s. Drop them, run the script again, then recreate them.',
			16, 1, @table, @column, @blockingText);
		RETURN;
	END

	DECLARE @nullability nvarchar(10) = (SELECT CASE WHEN is_nullable = 1 THEN N'NULL' ELSE N'NOT NULL' END
		FROM sys.columns WHERE object_id = @object AND column_id = @columnId);

	-- The default constraint, kept with its name and definition
	DECLARE @defaultName sysname, @defaultDefinition nvarchar(max);
	SELECT @defaultName = name, @defaultDefinition = definition
		FROM sys.default_constraints WHERE parent_object_id = @object AND parent_column_id = @columnId;

	-- The indexes on the column, dropped then recreated as they are (keys and their order, included columns, filter)
	DECLARE @drops nvarchar(max) = N'', @creates nvarchar(max) = N'';
	SELECT
		@drops += N'DROP INDEX ' + QUOTENAME(i.name) + N' ON ' + QUOTENAME(@table) + N'; ',
		@creates += N'CREATE ' + CASE WHEN i.is_unique = 1 THEN N'UNIQUE ' ELSE N'' END
			+ CASE WHEN i.type = 1 THEN N'CLUSTERED' ELSE N'NONCLUSTERED' END
			+ N' INDEX ' + QUOTENAME(i.name) + N' ON ' + QUOTENAME(@table) + N' ('
			+ STUFF((SELECT N', ' + QUOTENAME(c.name) + CASE WHEN ic.is_descending_key = 1 THEN N' DESC' ELSE N'' END
				FROM sys.index_columns ic JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
				WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.is_included_column = 0
				ORDER BY ic.key_ordinal FOR XML PATH(''), TYPE).value('.', 'nvarchar(max)'), 1, 2, N'') + N')'
			+ ISNULL(N' INCLUDE (' + STUFF((SELECT N', ' + QUOTENAME(c.name)
				FROM sys.index_columns ic JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
				WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.is_included_column = 1
				ORDER BY ic.index_column_id FOR XML PATH(''), TYPE).value('.', 'nvarchar(max)'), 1, 2, N'') + N')', N'')
			+ CASE WHEN i.has_filter = 1 THEN N' WHERE ' + i.filter_definition ELSE N'' END + N'; '
		FROM sys.indexes i
		WHERE i.object_id = @object AND i.type IN (1, 2)
			AND EXISTS (SELECT 1 FROM sys.index_columns ic
				WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.column_id = @columnId);

	DECLARE @sql nvarchar(max);
	BEGIN TRANSACTION;
	IF @drops <> N''
		EXEC sp_executesql @drops;
	IF @defaultName IS NOT NULL
	BEGIN
		SET @sql = N'ALTER TABLE ' + QUOTENAME(@table) + N' DROP CONSTRAINT ' + QUOTENAME(@defaultName);
		EXEC sp_executesql @sql;
	END
	SET @sql = N'ALTER TABLE ' + QUOTENAME(@table) + N' ALTER COLUMN ' + QUOTENAME(@column) + N' DECIMAL(18,3) '
		+ @nullability;
	EXEC sp_executesql @sql;
	IF @defaultName IS NOT NULL
	BEGIN
		SET @sql = N'ALTER TABLE ' + QUOTENAME(@table) + N' ADD CONSTRAINT ' + QUOTENAME(@defaultName) + N' DEFAULT '
			+ @defaultDefinition + N' FOR ' + QUOTENAME(@column);
		EXEC sp_executesql @sql;
	END
	IF @creates <> N''
		EXEC sp_executesql @creates;
	COMMIT TRANSACTION;
	PRINT N'2.2.2: ' + @table + N'.' + @column + N' is DECIMAL(18,3) ' + @nullability;
END
GO

-- Every listed column, in order
DECLARE @table sysname, @column sysname;
DECLARE columns_222 CURSOR LOCAL FAST_FORWARD FOR
	SELECT table_name, column_name FROM #zs_222_columns ORDER BY step, table_name, column_name;
OPEN columns_222;
FETCH NEXT FROM columns_222 INTO @table, @column;
WHILE @@FETCH_STATUS = 0
BEGIN
	EXEC #zs_decimal_quantity @table, @column;
	FETCH NEXT FROM columns_222 INTO @table, @column;
END
CLOSE columns_222;
DEALLOCATE columns_222;
GO

DROP PROCEDURE #zs_decimal_quantity;
GO

-- Step 5: the sale ticket's identity on Company Information, five nullable columns (null = the ticket of 2.2.1). Hibernate
-- (ddl-auto=update) would add them at the first start too; written here as for the columns of 2.2.0. Nothing when a column
-- is already there or when the table does not exist yet (Hibernate then creates it whole). No data is changed.
IF OBJECT_ID('company_information') IS NOT NULL
BEGIN
	IF COL_LENGTH('company_information', 'receipt_print_address') IS NULL
		ALTER TABLE company_information ADD receipt_print_address bit NULL;
	-- the two texts in nvarchar: any language, Arabic included (a varchar one, made by an earlier 2.2.2 build, is converted)
	IF COL_LENGTH('company_information', 'receipt_footer_text') IS NULL
		ALTER TABLE company_information ADD receipt_footer_text nvarchar(1000) NULL;
	ELSE IF EXISTS (SELECT 1 FROM sys.columns c JOIN sys.types t ON t.user_type_id = c.user_type_id
			WHERE c.object_id = OBJECT_ID('company_information') AND c.name = 'receipt_footer_text' AND t.name = 'varchar')
		ALTER TABLE company_information ALTER COLUMN receipt_footer_text nvarchar(1000) NULL;
	IF COL_LENGTH('company_information', 'receipt_thank_you_text') IS NULL
		ALTER TABLE company_information ADD receipt_thank_you_text nvarchar(200) NULL;
	ELSE IF EXISTS (SELECT 1 FROM sys.columns c JOIN sys.types t ON t.user_type_id = c.user_type_id
			WHERE c.object_id = OBJECT_ID('company_information') AND c.name = 'receipt_thank_you_text' AND t.name = 'varchar')
		ALTER TABLE company_information ALTER COLUMN receipt_thank_you_text nvarchar(200) NULL;
	IF COL_LENGTH('company_information', 'receipt_show_thank_you') IS NULL
		ALTER TABLE company_information ADD receipt_show_thank_you bit NULL;
	IF COL_LENGTH('company_information', 'receipt_show_software_label') IS NULL
		ALTER TABLE company_information ADD receipt_show_software_label bit NULL;
END
GO

-- The version only when every listed column present here is DECIMAL(18,3)
DECLARE @left nvarchar(1000) = N'';
SELECT @left += N' ' + l.table_name + N'.' + l.column_name
	FROM #zs_222_columns l
	JOIN sys.columns c ON c.object_id = OBJECT_ID(l.table_name) AND c.name = l.column_name
	JOIN sys.types t ON t.user_type_id = c.user_type_id
	WHERE NOT (t.name IN ('decimal', 'numeric') AND c.precision = 18 AND c.scale = 3);
IF @left <> N''
BEGIN
	RAISERROR(N'2.2.2: APP_VERSION not changed, these columns are not DECIMAL(18,3) yet:%s', 16, 1, @left);
	RETURN;
END

UPDATE APP_VERSION SET version = '2.2.2';

-- The notes of 2.2.2 are written again on each run (the script may run more than once)
DELETE FROM APP_RELEASE_NOTES WHERE version = '2.2.2';
INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('2.2.2', 'NEW', 'Retours avec quantités décimales quand le magasin les autorise (Configuration générale, Autoriser les quantités décimales) : une ligne vendue 0,5 L se retourne en 0,2 puis 0,3 ; une ligne vendue 2 peut se retourner en 1,5. Au plus 3 décimales, saisies avec une virgule ou un point, jamais plus que ce qui reste à retourner.'),
('2.2.2', 'IMPROVE', 'Montant d''une ligne retournée avec des décimales : la part de la ligne vendue (total de la ligne / quantité vendue × quantité retournée), arrondie au millime ; le retour qui termine la ligne rembourse ce qui en reste, si bien que les retours additionnés font exactement la ligne payée. Les retours en nombres entiers sont calculés comme avant.'),
('2.2.2', 'IMPROVE', 'Sans le paramètre, une quantité décimale saisie au retour est refusée avec un message (elle était arrondie sans le dire : 1,5 devenait 2), et une ligne vendue avec des décimales n''est pas retournable, avec la raison.'),
('2.2.2', 'NEW', 'Siège : les retours arrivent avec leurs quantités décimales, affichées sans zéros inutiles ; le stock du réseau suit le stock du magasin.'),
('2.2.2', 'IMPROVE', 'Un administrateur n''a jamais à scanner de badge à la caisse (liste des clients, retour, remises, fermeture de session) ; à la fermeture avec un écart, il voit l''écart et le confirme.'),
('2.2.2', 'NEW', 'Caisse : deux paramètres masquent les familles et les sous-familles sans article à vendre ; les articles d''une famille sans sous-famille apparaissent sous une tuile « Sans sous-famille », ou directement à l''ouverture de la famille.'),
('2.2.2', 'FIX', 'Une promotion à montant fixe plus grande que la ligne n''enregistre plus de montants négatifs ; une quantité décimale tapée alors que les décimales ne sont pas autorisées est refusée avec un message ; la fonction du membre fidélité n''est demandée que s''il en existe une active ; fréquences par défaut des échanges avec le siège allongées ; « Code du magasin » dans la configuration générale d''un magasin relié au siège ; le navigateur ne propose plus de traduire la page.'),
('2.2.2', 'NEW', 'Informations de la société, bloc « Ticket de vente » : adresse sur le ticket, texte de bas de ticket, ligne de remerciement et mention « ZS Retail » au choix ; laissés vides, le ticket reste comme avant.');

GO

SET NOEXEC OFF;
GO
