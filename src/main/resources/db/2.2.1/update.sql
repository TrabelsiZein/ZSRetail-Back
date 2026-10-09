-- ============================================================
-- Version 2.2.1 — Quantités décimales (articles vendus au litre ou au kilo : 0,2 ou 0,058)
-- ============================================================
-- Deployment order:
--   1) Run db/2.2.0 first if the database is older than 2.2.0.
--   2) Run this script against the database (the same script on a store and on a head office).
--   3) Deploy the 2.2.1 binaries (backend WAR and frontend).
-- The application refuses to start while APP_VERSION differs from its own version, so step 2 must be done before 3.
-- A head office and its stores move to 2.2.1 together: a 2.2.0 installation reads 0.2 as 0 without an error.
--   4) To sell with decimals, a store sets Configuration générale > Autoriser les quantités décimales (created at the
--      first start of 2.2.1, off). Off, everything stays as in 2.2.0 and the till refuses a decimal quantity.
--
-- Schema: the quantity columns become DECIMAL(18,3), with the same nullability. Hibernate (ddl-auto=update) never
-- changes the type of an existing column, so they are altered here; a new database gets them from Hibernate directly.
-- Every existing value keeps its value (5 becomes 5.000). Each column is done in its own transaction: an index or a
-- default constraint on it is dropped and recreated identically; any other object on the column (primary key, unique
-- or check constraint, foreign key, statistics created by hand, computed column) stops the script with its name, and
-- that column is left untouched. A column already DECIMAL(18,3), or a table absent from this database, is skipped,
-- so the script can run again. On a large table (sales_line, stock_movement) the change rewrites every row: run it in
-- a quiet window.
--
-- The columns are listed once, in #zs_221_columns below; each step of the decimal quantities adds its own there.
-- APP_VERSION and the release notes are written only when every listed column of this database is DECIMAL(18,3):
-- otherwise the script ends with an error naming the columns left, and the application keeps refusing to start.
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

IF OBJECT_ID('tempdb..#zs_221_columns') IS NOT NULL
	DROP TABLE #zs_221_columns;
CREATE TABLE #zs_221_columns (step int NOT NULL, table_name sysname NOT NULL, column_name sysname NOT NULL);
INSERT INTO #zs_221_columns (step, table_name, column_name) VALUES
-- Step 1: the stock of an item, the sales lines, the stock movements
(1, 'item', 'stock_quantity'),
(1, 'sales_line', 'quantity'),
(1, 'stock_movement', 'quantity'),
-- Step 2: the copies of the tickets and of the stock to the head office (ho_ tables exist on a head office, the hol_
-- table on a store linked to one; elsewhere they are absent and skipped)
(2, 'ho_ticket_line', 'quantity'),
(2, 'ho_store_stock', 'quantity'),
(2, 'hol_stock_copy', 'quantity_sent');
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
		RAISERROR(N'2.2.1: %s.%s was not changed, these objects use it:%s. Drop them, run the script again, then recreate them.',
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
	PRINT N'2.2.1: ' + @table + N'.' + @column + N' is DECIMAL(18,3) ' + @nullability;
END
GO

-- Every listed column, in order
DECLARE @table sysname, @column sysname;
DECLARE columns_221 CURSOR LOCAL FAST_FORWARD FOR
	SELECT table_name, column_name FROM #zs_221_columns ORDER BY step, table_name, column_name;
OPEN columns_221;
FETCH NEXT FROM columns_221 INTO @table, @column;
WHILE @@FETCH_STATUS = 0
BEGIN
	EXEC #zs_decimal_quantity @table, @column;
	FETCH NEXT FROM columns_221 INTO @table, @column;
END
CLOSE columns_221;
DEALLOCATE columns_221;
GO

DROP PROCEDURE #zs_decimal_quantity;
GO

-- The version only when every listed column present here is DECIMAL(18,3)
DECLARE @left nvarchar(1000) = N'';
SELECT @left += N' ' + l.table_name + N'.' + l.column_name
	FROM #zs_221_columns l
	JOIN sys.columns c ON c.object_id = OBJECT_ID(l.table_name) AND c.name = l.column_name
	JOIN sys.types t ON t.user_type_id = c.user_type_id
	WHERE NOT (t.name IN ('decimal', 'numeric') AND c.precision = 18 AND c.scale = 3);
IF @left <> N''
BEGIN
	RAISERROR(N'2.2.1: APP_VERSION not changed, these columns are not DECIMAL(18,3) yet:%s', 16, 1, @left);
	RETURN;
END

UPDATE APP_VERSION SET version = '2.2.1';

-- The notes of 2.2.1 are written again on each run (the script may run more than once)
DELETE FROM APP_RELEASE_NOTES WHERE version = '2.2.1';
INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('2.2.1', 'NEW', 'Quantités décimales (jusqu''à 3 décimales) pour les articles vendus au litre ou au kilo : 0,2 ou 0,058 à la caisse. Le stock et les mouvements de stock gardent les décimales.'),
('2.2.1', 'NEW', 'Nouveau paramètre du magasin « Autoriser les quantités décimales » (Configuration générale), désactivé par défaut : désactivé, une quantité doit être un nombre entier, comme avant.'),
('2.2.1', 'IMPROVE', 'Le montant d''une ligne avec une quantité décimale est calculé par le serveur : prix unitaire TTC × quantité, arrondi au millime.'),
('2.2.1', 'NEW', 'Siège : les tickets et le stock des magasins arrivent avec leurs quantités décimales (0,2 ; 9,8), affichées sans zéros inutiles dans l''historique des tickets et le stock du réseau.');
