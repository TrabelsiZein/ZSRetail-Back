-- ============================================================
-- Version 3.6.0 (in progress) — conventions (docs/roadmap/conventions.md): the tables convention and convention_card
-- (step 1, part B2)
-- ============================================================
-- Deployment order (a database at 3.5.0; CHANGELOG.md, 3.6.0, "Before deployment"):
--   1) Bring the database to 3.5.0 first (db/3.5.0/update.sql). This script stops, without changing anything, while
--      APP_VERSION is not 3.5.0 or 3.6.0.
--   2) Run this script against the database (the same script on a store and on a head office):
--      sqlcmd ... -f 65001 -i update.sql (in SSMS open it as UTF-8).
--   3) Deploy the WAR of the installation's type as zsretailws.war (docs/deploy-note.md).
-- The application refuses to start while APP_VERSION differs from its own version, so step 2 must be done before 3.
--
-- Re-runnable: each statement is guarded and changes nothing the second time. A database already at 3.6.0 (from the
-- version step of before) passes the check and gets what it lacks. APP_VERSION is set to 3.6.0 at the end.

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

-- 3.5.0 first. Otherwise the rest of the script is not executed (NOEXEC, reset at the end).
IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('3.5.0', '3.6.0'))
BEGIN
	DECLARE @current nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'3.6.0: APP_VERSION is %s; bring the database to 3.5.0 first (db/3.5.0/update.sql). Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

-- Conventions, step 1, part B2 (docs/roadmap/conventions.md): the conventions and their cards, on a store and on a head
-- office (core: both applications have them). In Hibernate's form (Convention, ConventionCard; ConventionTableTest checks
-- that every statement Hibernate writes for them is here, word for word): a start with ddl-auto=update adds nothing.
-- Every text column is nvarchar, but for the audit columns of _BaseEntity (created_by, updated_by: varchar(255), as in
-- every table). The defaults (named df_*) only serve a row inserted by hand: the application writes every column.
-- No data written.
IF OBJECT_ID(N'convention', N'U') IS NULL
	create table convention (id bigint identity not null, active bit, created_at datetime2, created_by varchar(255),
		updated_at datetime2, updated_by varchar(255), card_digits int not null, card_prefix nvarchar(30),
		code nvarchar(30) not null, end_date date, last_card_seq bigint not null, max_amount_per_month double precision,
		max_uses_per_day int, max_uses_per_month int, name nvarchar(150) not null, organisation nvarchar(150) not null,
		origin nvarchar(20) not null, recognition nvarchar(20) not null, start_date date not null,
		status nvarchar(20) not null, primary key (id));
GO
IF OBJECT_ID(N'uk_convention_code', N'UQ') IS NULL
	alter table convention add constraint uk_convention_code unique (code);
IF OBJECT_ID(N'df_convention_card_digits', N'D') IS NULL
	ALTER TABLE convention ADD CONSTRAINT df_convention_card_digits DEFAULT 5 FOR card_digits;
IF OBJECT_ID(N'df_convention_last_card_seq', N'D') IS NULL
	ALTER TABLE convention ADD CONSTRAINT df_convention_last_card_seq DEFAULT 0 FOR last_card_seq;
IF OBJECT_ID(N'df_convention_origin', N'D') IS NULL
	ALTER TABLE convention ADD CONSTRAINT df_convention_origin DEFAULT N'LOCAL' FOR origin;
IF OBJECT_ID(N'df_convention_recognition', N'D') IS NULL
	ALTER TABLE convention ADD CONSTRAINT df_convention_recognition DEFAULT N'CARDS' FOR recognition;
IF OBJECT_ID(N'df_convention_status', N'D') IS NULL
	ALTER TABLE convention ADD CONSTRAINT df_convention_status DEFAULT N'ACTIVE' FOR status;
GO

-- The cards: the number unique in the whole installation (not per convention); ix_convention_card_convention serves the
-- list's active / blocked counts and the Cards tab's paging.
IF OBJECT_ID(N'convention_card', N'U') IS NULL
	create table convention_card (id bigint identity not null, active bit, created_at datetime2,
		created_by varchar(255), updated_at datetime2, updated_by varchar(255), card_number nvarchar(40) not null,
		exported_at datetime2, holder_employee_no nvarchar(50), holder_name nvarchar(150), holder_phone nvarchar(30),
		status nvarchar(20) not null, convention_id bigint not null, primary key (id));
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_convention_card_convention'
	AND object_id = OBJECT_ID(N'convention_card'))
	create index ix_convention_card_convention on convention_card (convention_id, status);
IF OBJECT_ID(N'uk_convention_card_number', N'UQ') IS NULL
	alter table convention_card add constraint uk_convention_card_number unique (card_number);
IF OBJECT_ID(N'fk_convention_card_convention', N'F') IS NULL
	alter table convention_card add constraint fk_convention_card_convention foreign key (convention_id)
		references convention;
IF OBJECT_ID(N'df_convention_card_status', N'D') IS NULL
	ALTER TABLE convention_card ADD CONSTRAINT df_convention_card_status DEFAULT N'ACTIVE' FOR status;
GO

UPDATE APP_VERSION SET version = '3.6.0';

-- The release notes of 3.6.0 are written from CHANGELOG.md when the version is closed (nothing to tell the users yet).

GO

SET NOEXEC OFF;
GO
