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
-- Schema, RFID (docs/modules/rfid.md): two new tables on a store, created here (re-runnable: only when missing) with
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
		CONSTRAINT PK_rfid_reader PRIMARY KEY (id),
		CONSTRAINT uk_rfid_reader_name UNIQUE (name),
		CONSTRAINT uk_rfid_reader_address UNIQUE (ip_address, port)
	);
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

UPDATE APP_VERSION SET version = '3.1.0';

-- The release notes of 3.1.0 are written when the release is closed (the RFID screens are not built yet).

GO

SET NOEXEC OFF;
GO
