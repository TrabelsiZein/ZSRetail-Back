-- ============================================================
-- Version 3.3.0 (in progress) — communication module: comm_event, comm_message (step 1), comm_state (step 2), comm_template, comm_rule (step 3)
-- ============================================================
-- Deployment order (a database at 3.2.0; CHANGELOG.md, 3.3.0, "Before deployment"):
--   1) Bring the database to 3.2.0 first (db/3.2.0/update.sql). This script stops, without changing anything, while
--      APP_VERSION is not 3.2.0 or 3.3.0.
--   2) Run this script against the database (the same script on a store and on a head office):
--      sqlcmd ... -f 65001 -i update.sql (in SSMS open it as UTF-8).
--   3) Deploy the WAR of the installation's type as zsretailws.war (docs/deploy-note.md).
-- The application refuses to start while APP_VERSION differs from its own version, so step 2 must be done before 3.
--
-- Re-runnable: each statement is guarded and changes nothing the second time. The tables are created in the form
-- Hibernate gives them (the constraints and indexes named as in the entities), so the start finds them whole; on a
-- database without them Hibernate would create them at the first start too. They stay empty while
-- communication.enabled is not true (docs/modules/communication.md). Steps 2 and 3 added 1b, 3, 4 and 5: run the script
-- again on a database that ran an earlier version of it.

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

-- 3.2.0 first. Otherwise the rest of the script is not executed (NOEXEC, reset at the end).
IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('3.2.0', '3.3.0'))
BEGIN
	DECLARE @current nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'3.3.0: APP_VERSION is %s; bring the database to 3.2.0 first (db/3.2.0/update.sql). Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

-- 1) Communication, the events ("what happened": a member enrolled, a ticket validated for a member), one row per event,
-- once per dedup key (CommunicationEvent). Every text column of the module is nvarchar (Arabic); the audit columns of
-- every entity (created_by, updated_by) stay as Hibernate makes them. Every database.
IF OBJECT_ID(N'comm_event', N'U') IS NULL
BEGIN
	CREATE TABLE comm_event (id bigint identity not null, active bit, created_at datetime2, created_by varchar(255),
		updated_at datetime2, updated_by varchar(255), attempts int not null, dedup_key nvarchar(200) not null,
		event_type nvarchar(50) not null, last_error nvarchar(1000), occurred_at datetime2 not null,
		origin_store_code nvarchar(50), payload nvarchar(max), processed_at datetime2, recipient_ref nvarchar(100),
		recipient_type nvarchar(30), source_id bigint, source_type nvarchar(50) not null, status nvarchar(10) not null,
		primary key (id));
	ALTER TABLE comm_event ADD CONSTRAINT uk_comm_event_dedup UNIQUE (dedup_key);
	CREATE INDEX ix_comm_event_queue ON comm_event (status, created_at);
END
GO

-- 1b) Step 2: the events of a source (does this ticket, this member, have its event?). Its own guard: comm_event may
-- have been created by an earlier run of this script (step 1). Every database.
IF OBJECT_ID(N'comm_event', N'U') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_comm_event_source' AND object_id = OBJECT_ID(N'comm_event'))
	CREATE INDEX ix_comm_event_source ON comm_event (source_type, source_id)
GO

-- 2) Communication, the messages ("what to send"), one row per message, once per dedup key (CommunicationMessage):
-- the event that made it (none for a campaign), the address and the text copied when it is made. Every database.
IF OBJECT_ID(N'comm_message', N'U') IS NULL
BEGIN
	CREATE TABLE comm_message (id bigint identity not null, active bit, created_at datetime2, created_by varchar(255),
		updated_at datetime2, updated_by varchar(255), address nvarchar(255) not null, attempts int not null,
		body nvarchar(max), channel nvarchar(20) not null, dedup_key nvarchar(200) not null, event_id bigint,
		expires_at datetime2, language_code nvarchar(5), last_error nvarchar(1000), next_attempt_at datetime2,
		provider nvarchar(30), provider_message_id nvarchar(255), recipient_ref nvarchar(100),
		recipient_type nvarchar(30) not null, sent_at datetime2, status nvarchar(10) not null, subject nvarchar(255),
		template_code nvarchar(50), primary key (id));
	ALTER TABLE comm_message ADD CONSTRAINT uk_comm_message_dedup UNIQUE (dedup_key);
	CREATE INDEX ix_comm_message_queue ON comm_message (status, next_attempt_at);
	CREATE INDEX ix_comm_message_event ON comm_message (event_id);
END
GO

-- 3) Step 2: the module's state, one row per code (CommunicationState): ACTIVE_SINCE, the instant of the first start
-- with the module on (written by the application, never moved), and the finders' cursors (TICKET_CURSOR). Empty
-- while the module was never on. Every database.
IF OBJECT_ID(N'comm_state', N'U') IS NULL
BEGIN
	CREATE TABLE comm_state (id bigint identity not null, active bit, created_at datetime2, created_by varchar(255),
		updated_at datetime2, updated_by varchar(255), code nvarchar(50) not null, id_value bigint,
		time_value datetime2, primary key (id));
	ALTER TABLE comm_state ADD CONSTRAINT uk_comm_state_code UNIQUE (code);
END
GO

-- 4) Step 3: the message texts (CommunicationTemplate), one per code, for one channel and one language; the body holds
-- {fields}. Active or not: the column active. The application writes the two French SMS templates, inactive, at its
-- start where it sends. Every database.
IF OBJECT_ID(N'comm_template', N'U') IS NULL
BEGIN
	CREATE TABLE comm_template (id bigint identity not null, active bit, created_at datetime2, created_by varchar(255),
		updated_at datetime2, updated_by varchar(255), body nvarchar(max) not null, channel nvarchar(20) not null,
		code nvarchar(50) not null, language_code nvarchar(5) not null, name nvarchar(100) not null,
		provider_template_name nvarchar(100), subject nvarchar(255), primary key (id));
	ALTER TABLE comm_template ADD CONSTRAINT uk_comm_template_code UNIQUE (code);
END
GO

-- 5) Step 3: the rules event -> template -> channel (CommunicationRule); lifetime_hours: a message is never sent more
-- than that after its event happened (24 by default). Active or not: the column active. The application writes the two
-- rules of the templates above, inactive. Every database.
IF OBJECT_ID(N'comm_rule', N'U') IS NULL
BEGIN
	CREATE TABLE comm_rule (id bigint identity not null, active bit, created_at datetime2, created_by varchar(255),
		updated_at datetime2, updated_by varchar(255), channel nvarchar(20) not null, code nvarchar(50) not null,
		event_type nvarchar(50) not null, lifetime_hours int not null, recipient_type nvarchar(30) not null,
		template_code nvarchar(50) not null, primary key (id));
	ALTER TABLE comm_rule ADD CONSTRAINT uk_comm_rule_code UNIQUE (code);
END
GO

UPDATE APP_VERSION SET version = '3.3.0';

-- The release notes of 3.3.0 are written from CHANGELOG.md when the version is closed (nothing to tell the users yet).

GO

SET NOEXEC OFF;
GO
