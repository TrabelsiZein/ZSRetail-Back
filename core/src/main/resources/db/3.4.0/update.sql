-- ============================================================
-- Version 3.4.0 (in progress) — event log ("Journal des événements"): event_log, event_log_sequence (step 1);
-- event_log.install_id and doubt (step 4)
-- ============================================================
-- Deployment order (a database at 3.3.0; CHANGELOG.md, 3.4.0, "Before deployment"):
--   1) Bring the database to 3.3.0 first (db/3.3.0/update.sql). This script stops, without changing anything, while
--      APP_VERSION is not 3.3.0 or 3.4.0.
--   2) Run this script against the database (the same script on a store and on a head office):
--      sqlcmd ... -f 65001 -i update.sql (in SSMS open it as UTF-8).
--   3) Deploy the WAR of the installation's type as zsretailws.war (docs/deploy-note.md).
-- The application refuses to start while APP_VERSION differs from its own version, so step 2 must be done before 3.
--
-- Re-runnable: each statement is guarded and changes nothing the second time. The tables are created in the form
-- Hibernate gives them (the constraints and indexes named as in the entities), so the start finds them whole; on a
-- database without them Hibernate would create them at the first start too. They stay empty while eventlog.enabled is
-- not true (a store only; docs/modules/event-log.md). They exist on a head office too (core's entities), empty.

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

-- 3.3.0 first. Otherwise the rest of the script is not executed (NOEXEC, reset at the end).
IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('3.3.0', '3.4.0'))
BEGIN
	DECLARE @current nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'3.4.0: APP_VERSION is %s; bring the database to 3.3.0 first (db/3.3.0/update.sql). Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

-- 1) The event log, one row per event, inserted once and never updated (EventLogEntry). The subject is a type and an
-- id as text, the names are copied as text, no foreign key: a row stays readable after a rename or a delete. Every
-- text column is nvarchar (the driver's parameters are nvarchar: no scan). The page is always filtered by a period
-- (ix_event_log_at); a subject's history (ix_event_log_subject), a session's, a user's; the numbers of an importance
-- (ix_event_log_sequence, a missing number is a row deleted). Every database.
IF OBJECT_ID(N'event_log', N'U') IS NULL
BEGIN
	CREATE TABLE event_log (id bigint identity not null, amount float, authorised_by_id bigint,
		authorised_by_login nvarchar(100), authorised_by_name nvarchar(150), cart_id nvarchar(36),
		client_address nvarchar(45), code nvarchar(60) not null, details nvarchar(2000), event_at datetime2 not null,
		event_uuid nvarchar(36) not null, front_at datetime2, imported bit default 0 not null, importance int not null,
		machine_sequence_no bigint, new_value nvarchar(1000), old_value nvarchar(1000), pos_machine_id bigint,
		pos_machine_name nvarchar(100), sent_by_id bigint, sent_by_login nvarchar(100), sequence_no bigint,
		session_id bigint, session_number nvarchar(50), subject_id nvarchar(100), subject_label nvarchar(255),
		subject_type nvarchar(30) not null, user_id bigint, user_login nvarchar(100), user_name nvarchar(150),
		user_role nvarchar(100), primary key (id));
	ALTER TABLE event_log ADD CONSTRAINT uk_event_log_uuid UNIQUE (event_uuid);
	CREATE INDEX ix_event_log_at ON event_log (event_at);
	CREATE INDEX ix_event_log_subject ON event_log (subject_type, subject_id);
	CREATE INDEX ix_event_log_session ON event_log (session_id);
	CREATE INDEX ix_event_log_user ON event_log (user_id, event_at);
	CREATE INDEX ix_event_log_sequence ON event_log (importance, sequence_no);
END
GO

-- 2) The last number of each importance (EventLogSequence), kept apart from the events: a row deleted at the end of
-- event_log still shows (the number here is higher than the last row's). Its three rows (10 STEP, 20 NOTABLE, 30 ALERT)
-- at 0: the first event of each takes number 1. A fourth importance creates its row with its first event. Every
-- database.
IF OBJECT_ID(N'event_log_sequence', N'U') IS NULL
BEGIN
	CREATE TABLE event_log_sequence (id bigint identity not null, importance int not null, last_value bigint not null,
		primary key (id));
	ALTER TABLE event_log_sequence ADD CONSTRAINT uk_event_log_sequence_importance UNIQUE (importance);
END
GO

INSERT INTO event_log_sequence (importance, last_value)
SELECT seeded.importance, 0
FROM (VALUES (10), (20), (30)) AS seeded (importance)
WHERE NOT EXISTS (SELECT 1 FROM event_log_sequence s WHERE s.importance = seeded.importance);
GO

-- 3) Event log step 4, the till's events (POST /event-log/batch): install_id, the browser that made the event (its own
-- numbering, machine_sequence_no: a hole within one install is an event never sent; a cleared browser is a new install,
-- not a hole); doubt, what the server could not confirm of what the till said (e.g. SESSION_OF_ANOTHER_USER), null
-- when nothing. ix_event_log_install finds the holes of an install. Every database.
IF COL_LENGTH(N'event_log', N'install_id') IS NULL
	ALTER TABLE event_log ADD install_id nvarchar(36);
GO
IF COL_LENGTH(N'event_log', N'doubt') IS NULL
	ALTER TABLE event_log ADD doubt nvarchar(200);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_event_log_install' AND object_id = OBJECT_ID(N'event_log'))
	CREATE INDEX ix_event_log_install ON event_log (install_id, machine_sequence_no);
GO

UPDATE APP_VERSION SET version = '3.4.0';

-- The release notes of 3.4.0 are written from CHANGELOG.md when the version is closed (nothing to tell the users yet).

GO

SET NOEXEC OFF;
GO
