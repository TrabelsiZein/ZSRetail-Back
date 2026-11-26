-- ============================================================
-- Version 3.3.0 (released 2026-11-23) —communication module: comm_event, comm_message (step 1), comm_state (step 2), comm_template, comm_rule (step 3), comm_campaign (step 7), comm_channel_state (step 8a part 2), comm_message.send_ref (step 8b), WhatsApp columns (step 8c), the Vendor balance permission removed, the warranties and store orders notices (13 to 15)
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
-- communication.enabled is not true (docs/modules/communication.md). Steps 2, 3, 5, 7, 8a, 8a part 2, 8b and 8c added 1b and 3 to 12, the Vendor balance, Warranties and store orders change 13 to 15: run
-- the script again on a database that ran an earlier version of it.

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

-- 6) Step 5: the member's consent to receive messages (SMS, WhatsApp, e-mail), a member field like the phone (it exists
-- with the module on or off). loyalty_member is the one member table of a store and of a head office (the store's copy
-- table hol_loyalty_member_copy holds no member content). Existing members: no consent (0). Every database.
IF COL_LENGTH(N'loyalty_member', N'message_consent') IS NULL
	ALTER TABLE loyalty_member ADD message_consent bit NOT NULL
		CONSTRAINT df_loyalty_member_message_consent DEFAULT 0;
GO
IF COL_LENGTH(N'loyalty_member', N'message_consent_at') IS NULL
	ALTER TABLE loyalty_member ADD message_consent_at datetime2 NULL;
GO
IF COL_LENGTH(N'loyalty_member', N'message_consent_by') IS NULL
	ALTER TABLE loyalty_member ADD message_consent_by nvarchar(100) NULL;
GO

-- 7) Step 7: the campaigns (CommunicationCampaign), one template sent to every member who accepts messages: its
-- name, its template (and a copy of its text when it was launched), its channel, RUNNING / DONE / CANCELLED, who
-- launched or cancelled it and when, how many were targeted, made and left out, and the cursor of the background making
-- (last_member_id). Every database (a store whose loyalty is the head office's keeps it empty).
IF OBJECT_ID(N'comm_campaign', N'U') IS NULL
BEGIN
	CREATE TABLE comm_campaign (id bigint identity not null, active bit, created_at datetime2, created_by varchar(255),
		updated_at datetime2, updated_by varchar(255), body nvarchar(max) not null, cancelled_by nvarchar(100),
		channel nvarchar(20) not null, code nvarchar(30) not null, ended_at datetime2, language_code nvarchar(5),
		last_member_id bigint not null, launched_at datetime2 not null, launched_by nvarchar(100), left_out int not null,
		lifetime_hours int not null, made int not null, made_at datetime2, name nvarchar(100) not null,
		status nvarchar(12) not null, subject nvarchar(255), targeted int not null, template_code nvarchar(50) not null,
		primary key (id));
	ALTER TABLE comm_campaign ADD CONSTRAINT uk_comm_campaign_code UNIQUE (code);
	CREATE INDEX ix_comm_campaign_status ON comm_campaign (status);
END
GO

-- 8) Step 7: the campaign of a message (none for an automatic one, an event's), and its index (a campaign's counters,
-- its messages, its cancel). Every database.
IF COL_LENGTH(N'comm_message', N'campaign_id') IS NULL
	ALTER TABLE comm_message ADD campaign_id bigint NULL;
GO
IF OBJECT_ID(N'comm_message', N'U') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_comm_message_campaign' AND object_id = OBJECT_ID(N'comm_message'))
	CREATE INDEX ix_comm_message_campaign ON comm_message (campaign_id, status);
GO

-- 9) Step 8a, the test-recipient mode: the address a message was actually given to when it was not the member's
-- (communication.test-recipient.*), the member's kept in address. Null otherwise. Every database.
IF COL_LENGTH(N'comm_message', N'redirected_to') IS NULL
	ALTER TABLE comm_message ADD redirected_to nvarchar(255) NULL;
GO

-- 10) Step 8a part 2, what the sender knows of each channel (CommunicationChannelState), one row per channel: its pause
-- (a channel problem, e.g. a refused SMTP login, pauses the channel, not its messages: paused_since, paused_until,
-- problems in a row, pause_reason) and its measured speed (speed_messages in speed_millis on speed_provider, measured at
-- speed_at: the New campaign window's sending time). Written by the application where it sends. Every database.
IF OBJECT_ID(N'comm_channel_state', N'U') IS NULL
BEGIN
	CREATE TABLE comm_channel_state (id bigint identity not null, active bit, created_at datetime2,
		created_by varchar(255), updated_at datetime2, updated_by varchar(255), channel nvarchar(20) not null,
		pause_reason nvarchar(1000), paused_since datetime2, paused_until datetime2, problems int not null,
		speed_at datetime2, speed_messages bigint not null, speed_millis bigint not null, speed_provider nvarchar(20),
		primary key (id));
	ALTER TABLE comm_channel_state ADD CONSTRAINT uk_comm_channel_state_channel UNIQUE (channel);
END
GO

-- 11) Step 8b, our reference of a message for its provider (the placeholder {messageId}: a gateway that knows it can
-- refuse a double), 32 characters, written at its first send and kept when it is sent again by hand. Null before.
-- Every database.
IF COL_LENGTH(N'comm_message', N'send_ref') IS NULL
	ALTER TABLE comm_message ADD send_ref varchar(40) NULL;
GO

-- 12) Step 8c, WhatsApp through Meta: a template's language code as Meta knows it and the way its values are named
-- (POSITIONAL / NAMED), and the provider's last refusal of it (the Templates and Rules tabs' mark); a campaign's template
-- at Meta kept at its launch and why it was stopped (status STOPPED); a message's template at Meta and its values. Every
-- database.
IF COL_LENGTH(N'comm_template', N'provider_language') IS NULL
	ALTER TABLE comm_template ADD provider_language nvarchar(10) NULL;
IF COL_LENGTH(N'comm_template', N'provider_parameter_format') IS NULL
	ALTER TABLE comm_template ADD provider_parameter_format nvarchar(10) NULL;
IF COL_LENGTH(N'comm_template', N'provider_problem') IS NULL
	ALTER TABLE comm_template ADD provider_problem nvarchar(1000) NULL;
IF COL_LENGTH(N'comm_template', N'provider_problem_at') IS NULL
	ALTER TABLE comm_template ADD provider_problem_at datetime2 NULL;
IF COL_LENGTH(N'comm_campaign', N'provider_template') IS NULL
	ALTER TABLE comm_campaign ADD provider_template nvarchar(400) NULL;
IF COL_LENGTH(N'comm_campaign', N'stop_reason') IS NULL
	ALTER TABLE comm_campaign ADD stop_reason nvarchar(1000) NULL;
IF COL_LENGTH(N'comm_message', N'provider_params') IS NULL
	ALTER TABLE comm_message ADD provider_params nvarchar(max) NULL;
GO

-- 13) The page Vendor balance is gone (a store and a head office): its permission leaves the roles that have it. A row
-- left behind is harmless (no page reads it, the Roles page neither shows nor counts it); this only tidies. Every
-- database.
IF OBJECT_ID(N'app_role_permission', N'U') IS NOT NULL
	DELETE FROM app_role_permission
	WHERE permission_key IN ('read:vendor-balance', 'read:admin-headoffice-vendor-balance');
GO

-- 14) The Warranties page is off by default since 3.3.0 (warranty.enabled, a store only): a store that has warranties
-- keeps its page only with the line in its file. Nothing is changed (the table and its rows stay). A store only (it has
-- the warranty table).
IF OBJECT_ID(N'warranty', N'U') IS NOT NULL
	IF EXISTS (SELECT 1 FROM warranty)
		PRINT 'Warranties exist: add warranty.enabled=true to this store''s file.';
GO

-- 15) Commandes centrale is off by default since 3.3.0 (storeorders.enabled, a store whose goods come from its head office):
-- a store that has orders keeps its pages only with the line in its file. Nothing is changed (the tables and the orders
-- stay; the statuses that come down from the head office are still written). A store only (it has the hol_order table).
IF OBJECT_ID(N'hol_order', N'U') IS NOT NULL
	IF EXISTS (SELECT 1 FROM hol_order)
		PRINT 'Store orders exist: add storeorders.enabled=true to this store''s file.';
GO

UPDATE APP_VERSION SET version = '3.3.0';

-- The notes of 3.3.0 are written again on each run (the script may run more than once). Written from CHANGELOG.md when
-- the version was closed (2026-11-23).
DELETE FROM APP_RELEASE_NOTES WHERE version = '3.3.0';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('3.3.0', 'NEW', N'Communication (option, activée sur demande) : messages aux membres fidélité par e-mail, SMS ou WhatsApp, messages automatiques (bienvenue, remerciement) et campagnes, depuis la page Communication.'),
('3.3.0', 'NEW', N'Communication : sur la fiche du membre fidélité, son accord pour recevoir des messages (non par défaut) ; seuls les membres qui ont accepté en reçoivent.'),
('3.3.0', 'IMPROVE', N'Les pages Garanties et Commandes centrale deviennent des options, masquées par défaut et activées sur demande pour les magasins qui les utilisent.'),
('3.3.0', 'IMPROVE', N'La page Balance fournisseurs est retirée, au magasin et au siège ; les fournisseurs, les achats et les factures d''achat ne changent pas.'),
('3.3.0', 'FIX', N'Tableau de bord du magasin : les montants sont écrits comme dans le reste de l''application ; le bouton de langue nomme chaque langue dans sa propre langue.');

GO

SET NOEXEC OFF;
GO
