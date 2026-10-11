-- ============================================================
-- Version 3.1.0 (in progress) — RFID au magasin
-- ============================================================
-- Deployment order:
--   1) Run db/2.3.0 first if the database is older than 2.3.0 (this script stops, without changing anything, while
--      APP_VERSION is not 2.3.0 or 3.1.0).
--   2) Run this script against the database (the same script on a store and on a head office).
--      The file is UTF-8: with sqlcmd add -f 65001 (sqlcmd ... -f 65001 -i update.sql); in SSMS open it as UTF-8.
--   3) Deploy the WAR of the installation's type as zsretailws.war (docs/deploy-note.md).
-- The application refuses to start while APP_VERSION differs from its own version, so step 2 must be done before 3.
--
-- Schema, RFID (docs/modules/rfid.md): four new tables on a store (rfid_reader, pos_machine, rfid_tag, rfid_sale_tag), created here
-- (re-runnable: only when missing) with
-- the names and types Hibernate uses, so ddl-auto finds them complete. They exist, empty, on every store, whatever
-- rfid.enabled; a head office database (it has ho_store) never gets them.
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

-- 2.3.0 first. Otherwise the rest of the script is not executed (NOEXEC, reset at the end).
IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('2.3.0', '3.1.0'))
BEGIN
	DECLARE @current nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'3.1.0: APP_VERSION is %s; run db/2.3.0/update.sql first. Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

-- RFID step 3: the store's readers and POS machines (a store only)
IF OBJECT_ID(N'ho_store', N'U') IS NULL AND OBJECT_ID(N'rfid_reader', N'U') IS NULL
BEGIN
	CREATE TABLE rfid_reader (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		name varchar(100) NOT NULL,
		ip_address varchar(45) NOT NULL,
		port int NOT NULL,
		power int NOT NULL,
		read_time_ms int NOT NULL,
		min_signal int NOT NULL CONSTRAINT df_rfid_reader_min_signal DEFAULT -50,
		CONSTRAINT PK_rfid_reader PRIMARY KEY (id),
		CONSTRAINT uk_rfid_reader_name UNIQUE (name),
		CONSTRAINT uk_rfid_reader_address UNIQUE (ip_address, port)
	);
END
GO
-- RFID weak tags: the reader's minimum signal (dB), -50 for the readers already there (a table made before this column,
-- by this script or by ddl-auto)
IF OBJECT_ID(N'rfid_reader', N'U') IS NOT NULL AND COL_LENGTH('rfid_reader', 'min_signal') IS NULL
BEGIN
	ALTER TABLE rfid_reader ADD min_signal int NOT NULL CONSTRAINT df_rfid_reader_min_signal DEFAULT -50 WITH VALUES;
END
GO
IF OBJECT_ID(N'ho_store', N'U') IS NULL AND OBJECT_ID(N'pos_machine', N'U') IS NULL
BEGIN
	CREATE TABLE pos_machine (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		name varchar(100) NOT NULL,
		ip_address varchar(45) NOT NULL,
		reader_id bigint NULL,
		CONSTRAINT PK_pos_machine PRIMARY KEY (id),
		CONSTRAINT uk_pos_machine_name UNIQUE (name),
		CONSTRAINT uk_pos_machine_ip UNIQUE (ip_address),
		CONSTRAINT fk_pos_machine_reader FOREIGN KEY (reader_id) REFERENCES rfid_reader (id)
	);
	CREATE INDEX ix_pos_machine_reader ON pos_machine (reader_id);
END
GO

-- RFID tags step: one row per tag, one piece of an item of the store (a store only; the item a foreign key: an item
-- with tags is not deleted, the application says so before the database does)
IF OBJECT_ID(N'ho_store', N'U') IS NULL AND OBJECT_ID(N'rfid_tag', N'U') IS NULL
BEGIN
	CREATE TABLE rfid_tag (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		epc varchar(128) NOT NULL,
		item_id bigint NOT NULL,
		status varchar(10) NOT NULL,
		source_type varchar(10) NOT NULL,
		source_document varchar(30) NULL,
		source_line int NULL,
		sold_at datetime2 NULL,
		ticket_number varchar(100) NULL,
		CONSTRAINT PK_rfid_tag PRIMARY KEY (id),
		CONSTRAINT uk_rfid_tag_epc UNIQUE (epc),
		CONSTRAINT fk_rfid_tag_item FOREIGN KEY (item_id) REFERENCES item (id)
	);
	CREATE INDEX ix_rfid_tag_item ON rfid_tag (item_id);
	CREATE INDEX ix_rfid_tag_status ON rfid_tag (status);
END
GO

-- RFID POS step: the tags of the tickets' lines (a store only). A pending ticket holds its tags here, a completed ticket
-- keeps them with its paid line, a return marks the ones it put back in stock
IF OBJECT_ID(N'ho_store', N'U') IS NULL AND OBJECT_ID(N'rfid_sale_tag', N'U') IS NULL
BEGIN
	CREATE TABLE rfid_sale_tag (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		sales_header_id bigint NOT NULL,
		item_id bigint NOT NULL,
		epc varchar(128) NOT NULL,
		sales_line_id bigint NULL,
		return_line_id bigint NULL,
		CONSTRAINT PK_rfid_sale_tag PRIMARY KEY (id),
		CONSTRAINT uk_rfid_sale_tag_header_epc UNIQUE (sales_header_id, epc),
		CONSTRAINT fk_rfid_sale_tag_header FOREIGN KEY (sales_header_id) REFERENCES sales_header (id),
		CONSTRAINT fk_rfid_sale_tag_item FOREIGN KEY (item_id) REFERENCES item (id),
		CONSTRAINT fk_rfid_sale_tag_line FOREIGN KEY (sales_line_id) REFERENCES sales_line (id),
		CONSTRAINT fk_rfid_sale_tag_return_line FOREIGN KEY (return_line_id) REFERENCES return_line (id)
	);
	CREATE INDEX ix_rfid_sale_tag_epc ON rfid_sale_tag (epc);
END
GO

UPDATE APP_VERSION SET version = '3.1.0';

-- The release notes of 3.1.0 are written when the release is closed (the RFID screens are not built yet).

GO

SET NOEXEC OFF;
GO
