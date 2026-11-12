-- ============================================================
-- Version 3.2.0 (in progress) — payment methods the admin creates, scanned meal tickets
-- ============================================================
-- Deployment order (a database at 3.1.2; CHANGELOG.md, 3.2.0, "Before deployment"):
--   1) Bring the database to 3.1.2 first (db/3.1.2/: its update.sql says the order). This script stops, without
--      changing anything, while APP_VERSION is not 3.1.2 or 3.2.0.
--   2) Run this script against the database (the same script on a store and on a head office). The file is UTF-8: with
--      sqlcmd add -f 65001 (sqlcmd ... -f 65001 -i update.sql); in SSMS open it as UTF-8.
--   3) Deploy the WAR of the installation's type as zsretailws.war (docs/deploy-note.md).
-- The application refuses to start while APP_VERSION differs from its own version, so step 2 must be done before 3.
--
-- Re-runnable: each statement is guarded and changes nothing the second time. The application runs the same statements
-- at each start (PaymentMethodsUpgrade), before it reads a payment method.

-- The session options are set here (sqlcmd starts with QUOTED_IDENTIFIER OFF); SSMS has them on already. The filtered
-- index of payment_method needs them, at its creation and for every later INSERT or UPDATE of the table: a statement
-- typed by hand in sqlcmd on payment_method needs sqlcmd -I.

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET NUMERIC_ROUNDABORT OFF;
GO

-- 3.1.2 first. Otherwise the rest of the script is not executed (NOEXEC, reset at the end).
IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('3.1.2', '3.2.0'))
BEGIN
	DECLARE @current nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'3.2.0: APP_VERSION is %s; bring the database to 3.1.2 first (db/3.1.2/update.sql). Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

-- Payment methods (3.2.0): a method the admin creates has no type, so payment_method.type takes NULLs. 1) The unique
-- constraint on the column goes (SQL Server's allows a single NULL; its name, UK_drvond376oipl4k39o8kor2e4 from
-- Hibernate, is read here, never assumed).
DECLARE @uk sysname, @sql nvarchar(400);
WHILE 1 = 1
BEGIN
	SET @uk = (SELECT TOP 1 k.name FROM sys.key_constraints k
		JOIN sys.index_columns ic ON ic.object_id = k.parent_object_id AND ic.index_id = k.unique_index_id
		JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
		WHERE k.parent_object_id = OBJECT_ID(N'payment_method') AND k.type = 'UQ' AND c.name = 'type'
			AND NOT EXISTS (SELECT 1 FROM sys.index_columns o WHERE o.object_id = k.parent_object_id
				AND o.index_id = k.unique_index_id AND o.column_id <> c.column_id));
	IF @uk IS NULL BREAK;
	SET @sql = N'ALTER TABLE payment_method DROP CONSTRAINT ' + QUOTENAME(@uk);
	EXEC(@sql);
END
GO

-- 2) The column nullable, with its own type and length (read, never assumed).
DECLARE @type nvarchar(200) = (SELECT TYPE_NAME(user_type_id)
	+ CASE WHEN max_length = -1 THEN '(max)' WHEN TYPE_NAME(user_type_id) IN ('nvarchar', 'nchar')
		THEN '(' + CAST(max_length / 2 AS varchar(10)) + ')' ELSE '(' + CAST(max_length AS varchar(10)) + ')' END
	FROM sys.columns WHERE object_id = OBJECT_ID(N'payment_method') AND name = 'type' AND is_nullable = 0);
IF @type IS NOT NULL EXEC('ALTER TABLE payment_method ALTER COLUMN type ' + @type + ' NULL')
GO

-- 3) Still one row per fixed type (cash, cheque...: the code finds them by type), any number without: a filtered index.
IF COL_LENGTH(N'payment_method', N'type') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ux_payment_method_type' AND object_id = OBJECT_ID(N'payment_method'))
	CREATE UNIQUE INDEX ux_payment_method_type ON payment_method (type) WHERE type IS NOT NULL
GO

-- 4) What the till asks for the method (behaviour: AMOUNT, SCANNED_TICKETS; empty is AMOUNT) and the exact length of
-- its N° titre (empty: any length). The application adds them too, at its first start.
IF OBJECT_ID(N'payment_method', N'U') IS NOT NULL
	AND COL_LENGTH(N'payment_method', N'behaviour') IS NULL
	ALTER TABLE payment_method ADD behaviour varchar(255) NULL
GO
IF OBJECT_ID(N'payment_method', N'U') IS NOT NULL
	AND COL_LENGTH(N'payment_method', N'title_number_length') IS NULL
	ALTER TABLE payment_method ADD title_number_length int NULL
GO

-- 5) In one transaction: the lengths of the four settings PAYMENT_METHOD_<type>_TITLE_NUMBER_LENGTH copied to the row of
-- their type (a length already set is kept; blank, 0 or not digits only: none); Ticket Restaurant and Chèque Cadeau
-- become methods without type, behaviour AMOUNT (code, name, active, order, switches and payments untouched); every row
-- without behaviour gets AMOUNT; the four settings deleted.
IF COL_LENGTH(N'payment_method', N'behaviour') IS NOT NULL
	AND COL_LENGTH(N'payment_method', N'title_number_length') IS NOT NULL
	AND OBJECT_ID(N'general_setup', N'U') IS NOT NULL
	EXEC('BEGIN TRY BEGIN TRANSACTION;
		UPDATE m SET m.title_number_length = CASE WHEN t.v <> '''' AND t.v NOT LIKE ''%[^0-9]%'' AND LEN(t.v) <= 9
			THEN NULLIF(CAST(t.v AS int), 0) END
		FROM payment_method m JOIN general_setup g ON g.code = ''PAYMENT_METHOD_'' + m.type + ''_TITLE_NUMBER_LENGTH''
		CROSS APPLY (SELECT LTRIM(RTRIM(g.valeur)) AS v) t
		WHERE m.type IS NOT NULL AND m.title_number_length IS NULL;
		UPDATE payment_method SET type = NULL, behaviour = ''AMOUNT'', updated_at = SYSDATETIME(), updated_by = ''System''
		WHERE type IN (''TICKET_RESTAURANT'', ''CHEQUE_CADEAU'');
		UPDATE payment_method SET behaviour = ''AMOUNT'' WHERE behaviour IS NULL;
		DELETE FROM general_setup WHERE code IN (''PAYMENT_METHOD_CLIENT_CHEQUE_TITLE_NUMBER_LENGTH'',
			''PAYMENT_METHOD_TICKET_RESTAURANT_TITLE_NUMBER_LENGTH'', ''PAYMENT_METHOD_CHEQUE_CADEAU_TITLE_NUMBER_LENGTH'',
			''PAYMENT_METHOD_CLIENT_TRAITE_TITLE_NUMBER_LENGTH'');
		COMMIT TRANSACTION;
	END TRY BEGIN CATCH IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION; THROW; END CATCH')
GO

-- Payment methods, step 3b: "send to the ERP" per method (the payment export), replacing ERP_SKIP_CHEQUE_PAYMENTS.
-- 6) The column (the application adds it too, at its first start).
IF OBJECT_ID(N'payment_method', N'U') IS NOT NULL
	AND COL_LENGTH(N'payment_method', N'send_to_erp') IS NULL
	ALTER TABLE payment_method ADD send_to_erp bit NULL
GO

-- 7) In one transaction, only on the rows not set yet: the return voucher off (never sent), the cheque off when
-- ERP_SKIP_CHEQUE_PAYMENTS was true, every other method on; then the setting deleted (it existed only with an ERP).
IF COL_LENGTH(N'payment_method', N'send_to_erp') IS NOT NULL
	AND OBJECT_ID(N'general_setup', N'U') IS NOT NULL
	EXEC('BEGIN TRY BEGIN TRANSACTION;
		UPDATE payment_method SET send_to_erp = CASE WHEN type = ''RETURN_VOUCHER'' THEN 0
			WHEN type = ''CLIENT_CHEQUE'' AND EXISTS (SELECT 1 FROM general_setup
				WHERE code = ''ERP_SKIP_CHEQUE_PAYMENTS'' AND LOWER(LTRIM(RTRIM(valeur))) = ''true'') THEN 0 ELSE 1 END
		WHERE send_to_erp IS NULL;
		DELETE FROM general_setup WHERE code = ''ERP_SKIP_CHEQUE_PAYMENTS'';
		COMMIT TRANSACTION;
	END TRY BEGIN CATCH IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION; THROW; END CATCH')
GO

-- Payment methods, step 2: the N° titre asked but optional (a card on a terminal: the slip number); with
-- require_title_number, the three states off / optional / required. Empty is "not asked". The application adds it too, at
-- its first start (Hibernate), so it is not among the statements run at each start.
IF OBJECT_ID(N'payment_method', N'U') IS NOT NULL
	AND COL_LENGTH(N'payment_method', N'ask_title_number') IS NULL
	ALTER TABLE payment_method ADD ask_title_number bit NULL
GO

-- Payment methods, step 3: the tickets a scanned-tickets method accepts (AcceptedTicket): issuer, code (the barcode's
-- prefix), deduction %, validity (months after the printed year), barcode layout. Created here in the form Hibernate
-- gives it (the foreign key named as in the entity), so the start finds it whole; on a database without it Hibernate
-- would create it at the first start too. Store and head office alike (a head office leaves it empty).
IF OBJECT_ID(N'payment_method', N'U') IS NOT NULL AND OBJECT_ID(N'accepted_ticket', N'U') IS NULL
BEGIN
	CREATE TABLE accepted_ticket (id bigint identity not null, active bit, created_at datetime2, created_by varchar(255),
		updated_at datetime2, updated_by varchar(255), deduction_percent numeric(5,2) not null,
		issuer_name varchar(255) not null, barcode_length int, serial_length int, serial_start int, value_length int,
		value_start int, year_length int, year_start int, ticket_code varchar(20) not null, validity_months int,
		payment_method_id bigint not null, primary key (id));
	ALTER TABLE accepted_ticket ADD CONSTRAINT fk_accepted_ticket_payment_method FOREIGN KEY (payment_method_id)
		REFERENCES payment_method;
END
GO

-- Payment methods, step 4, a store only (it has quick_quantity, a head office never): the scanned tickets that paid sales,
-- one row per ticket beside its payment (one to one), with the copies taken at the sale; in Hibernate's form, the
-- constraints named as in the entity. On a store without it Hibernate creates it at the first start.
IF OBJECT_ID(N'quick_quantity', N'U') IS NOT NULL AND OBJECT_ID(N'scanned_ticket_payment', N'U') IS NULL
BEGIN
	CREATE TABLE scanned_ticket_payment (id bigint identity not null, active bit, created_at datetime2,
		created_by varchar(255), updated_at datetime2, updated_by varchar(255), applied_amount numeric(12,3) not null,
		barcode varchar(64) not null, counted_value numeric(12,3) not null, deduction_percent numeric(5,2) not null,
		excess_amount numeric(12,3) not null, face_value numeric(12,3) not null, issuer_name varchar(255) not null,
		live_barcode varchar(64), printed_year int, ticket_code varchar(20) not null, accepted_ticket_id bigint not null,
		payment_id bigint not null, primary key (id));
	ALTER TABLE scanned_ticket_payment ADD CONSTRAINT uk_scanned_ticket_payment_payment UNIQUE (payment_id);
	ALTER TABLE scanned_ticket_payment ADD CONSTRAINT fk_scanned_ticket_payment_payment FOREIGN KEY (payment_id)
		REFERENCES payment;
	ALTER TABLE scanned_ticket_payment ADD CONSTRAINT fk_scanned_ticket_payment_ticket FOREIGN KEY (accepted_ticket_id)
		REFERENCES accepted_ticket;
END
GO

-- 8) A scanned ticket is held by one sale at most, across tills: the barcode while it counts (live_barcode), unique among
-- the rows that have it (the application creates it at each start too).
IF OBJECT_ID(N'scanned_ticket_payment', N'U') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ux_scanned_ticket_live_barcode' AND object_id = OBJECT_ID(N'scanned_ticket_payment'))
	CREATE UNIQUE INDEX ux_scanned_ticket_live_barcode ON scanned_ticket_payment (live_barcode)
	WHERE live_barcode IS NOT NULL
GO

-- 9) Step 5: a card on a terminal (CLIENT_TPE) asks its slip number, optional (N° titre); only while not set (a fixed
-- method's switches are not the admin's). Through EXEC: the column may be added by this script's step above.
IF COL_LENGTH(N'payment_method', N'ask_title_number') IS NOT NULL
	EXEC('UPDATE payment_method SET ask_title_number = 1 WHERE type = ''CLIENT_TPE'' AND ask_title_number IS NULL')
GO

UPDATE APP_VERSION SET version = '3.2.0';

-- The notes of 3.2.0 are written again on each run (the script may run more than once). Written from CHANGELOG.md when
-- the version is closed.
DELETE FROM APP_RELEASE_NOTES WHERE version = '3.2.0';

GO

SET NOEXEC OFF;
GO
