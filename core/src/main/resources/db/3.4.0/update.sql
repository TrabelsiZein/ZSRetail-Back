-- ============================================================
-- Version 3.4.0 (in progress) — event log ("Journal des événements"): event_log, event_log_sequence (step 1);
-- event_log.install_id and doubt (step 4); occurred_at, badge_owner_*, group_key (step 6); the badge scans of before copied (step 7); event_log_sequence.cleaned_to (step 8)
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

-- 4) Event log step 6, the Journal page:
--   occurred_at, the event's own time (the till's clock of the gesture, else the server's): the page's order and period,
--   so a till's event sent late takes its place. Written with every row; the rows of before get it here.
--   badge_owner_id / _login / _name, the owner of the badge scanned (its use, its refusal when the badge is known): a
--   refusal authorised nothing, so its owner is not in authorised_by. The uses of before get it from authorised_by.
--   group_key, the refused logins of one run (one line on the page); the rows of before stay one line each.
--   ix_event_log_occurred serves the period, the order and the counters; ix_event_log_user moves from event_at to
--   occurred_at. Every database.
IF COL_LENGTH(N'event_log', N'occurred_at') IS NULL
	ALTER TABLE event_log ADD occurred_at datetime2;
GO
UPDATE event_log SET occurred_at = COALESCE(front_at, event_at) WHERE occurred_at IS NULL;
GO
IF COL_LENGTH(N'event_log', N'badge_owner_id') IS NULL
	ALTER TABLE event_log ADD badge_owner_id bigint;
GO
IF COL_LENGTH(N'event_log', N'badge_owner_login') IS NULL
	ALTER TABLE event_log ADD badge_owner_login nvarchar(100);
GO
IF COL_LENGTH(N'event_log', N'badge_owner_name') IS NULL
	ALTER TABLE event_log ADD badge_owner_name nvarchar(150);
GO
UPDATE event_log SET badge_owner_id = authorised_by_id, badge_owner_login = authorised_by_login,
	badge_owner_name = authorised_by_name
WHERE code = N'TILL_RIGHT_USED' AND badge_owner_id IS NULL AND authorised_by_id IS NOT NULL;
GO
IF COL_LENGTH(N'event_log', N'group_key') IS NULL
	ALTER TABLE event_log ADD group_key nvarchar(80);
GO
IF EXISTS (SELECT 1 FROM sys.indexes i JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
		JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
		WHERE i.name = N'ix_event_log_user' AND i.object_id = OBJECT_ID(N'event_log') AND c.name = N'event_at')
	DROP INDEX ix_event_log_user ON event_log;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_event_log_user' AND object_id = OBJECT_ID(N'event_log'))
	CREATE INDEX ix_event_log_user ON event_log (user_id, occurred_at);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_event_log_occurred' AND object_id = OBJECT_ID(N'event_log'))
	CREATE INDEX ix_event_log_occurred ON event_log (occurred_at, importance);
GO

-- 5) Event log step 7: the badge scans of before, copied once into the Journal (badge_scan_log stays as it is and stays
--   written: CashWatch reads it). The same events as today (AccessEvents): a badge used (a badge scan or pass, success)
--   is TILL_RIGHT_USED (Step), its owner the authoriser and the badge's owner; a refused scan is TILL_RIGHT_REFUSED
--   (Alert), the reason as the new value, the owner when the badge is known. A pass on the user's own role (granted_by
--   ROLE) writes no event today: not copied. The same French texts; no badge code. Marked imported, without a number (the
--   missing numbers count only the numbered rows). The POS machine from cashier_session_pos_machine, the session's
--   number, the users' names and roles as they are now.
--   Once: each copy is event_uuid 'badge-scan-<id>' (unique); a scan that already has its event (written since step 3:
--   the same code, action and user within 5 seconds) is not copied. A second run copies nothing. Every database (a head
--   office's badge_scan_log is empty).
IF OBJECT_ID(N'badge_scan_log', N'U') IS NOT NULL AND OBJECT_ID(N'cashier_session', N'U') IS NOT NULL
	AND OBJECT_ID(N'cashier_session_pos_machine', N'U') IS NOT NULL AND OBJECT_ID(N'pos_machine', N'U') IS NOT NULL
BEGIN
-- step7:copy:begin
INSERT INTO event_log (event_uuid, importance, sequence_no, code, subject_type, subject_id, subject_label, event_at,
	front_at, occurred_at, pos_machine_id, pos_machine_name, session_id, session_number, user_id, user_login, user_name,
	user_role, authorised_by_id, authorised_by_login, authorised_by_name, badge_owner_id, badge_owner_login,
	badge_owner_name, new_value, details, imported)
SELECT x.uuid, CASE WHEN x.ok = 1 THEN 10 ELSE 30 END, NULL,
	CASE WHEN x.ok = 1 THEN N'TILL_RIGHT_USED' ELSE N'TILL_RIGHT_REFUSED' END, N'TILL_RIGHT', x.action, x.action_label,
	x.at, NULL, x.at, x.machine_id, x.machine_name, x.session_id, x.session_number, x.user_id, x.user_login, x.user_name,
	x.user_role,
	CASE WHEN x.ok = 1 THEN x.owner_id END, CASE WHEN x.ok = 1 THEN x.owner_login END,
	CASE WHEN x.ok = 1 THEN x.owner_name END, x.owner_id, x.owner_login, x.owner_name,
	CASE WHEN x.ok = 1 THEN NULL ELSE x.reason END,
	CASE WHEN x.ok = 1 THEN CONCAT(N'Droit « ', x.action_label, N' » ouvert par le badge de ', COALESCE(x.owner_who, N'—'),
			CASE WHEN x.user_who IS NOT NULL THEN CONCAT(N', pour ', x.user_who) ELSE N'' END,
			CASE WHEN RIGHT(COALESCE(x.user_who, x.owner_who, N'—'), 1) = N'.' THEN N'' ELSE N'.' END)
		ELSE CONCAT(N'Badge refusé au scan pour « ', x.action_label, N' » : ',
			CASE x.reason
				WHEN N'BADGE_NOT_EXISTS' THEN N'badge inconnu'
				WHEN N'BADGE_REVOKED' THEN CONCAT(N'badge', x.owner_of, N' révoqué')
				WHEN N'BADGE_EXPIRED' THEN CONCAT(N'badge', x.owner_of, N' expiré')
				WHEN N'BADGE_OWNER_INACTIVE' THEN CONCAT(N'badge', x.owner_of, N', compte désactivé')
				WHEN N'BADGE_NO_ACCESS' THEN CONCAT(N'le rôle', x.owner_of, N' n''a pas ce droit')
				ELSE CASE WHEN x.reason IS NULL THEN N'refusé' ELSE CONCAT(x.reason, x.owner_of) END END, N'.') END,
	1
FROM (SELECT CONCAT(N'badge-scan-', CAST(b.id AS nvarchar(20))) AS uuid,
		CASE WHEN b.success = 1 THEN 1 ELSE 0 END AS ok,
		b.functionality AS action,
		CASE b.functionality
			WHEN N'CONSULT_CUSTOMER_LIST' THEN N'Consulter la liste des clients'
			WHEN N'MAKE_RETURN' THEN N'Effectuer un retour'
			WHEN N'APPLY_LINE_DISCOUNT' THEN N'Appliquer une remise sur la ligne'
			WHEN N'APPLY_TOTAL_DISCOUNT' THEN N'Appliquer une remise sur le total'
			WHEN N'CLOSE_SESSION_WITH_DISCREPANCY' THEN N'Fermer la session avec écart'
			WHEN N'CASH_MOVEMENT' THEN N'Mouvement de caisse'
			ELSE b.functionality END AS action_label,
		b.[timestamp] AS at, b.failure_reason AS reason, b.session_id AS session_id, cs.session_number AS session_number,
		pm.id AS machine_id, pm.name AS machine_name,
		su.id AS user_id, su.username AS user_login, su.full_name AS user_name, sr.name AS user_role,
		COALESCE(NULLIF(LTRIM(RTRIM(su.full_name)), N''), su.username) AS user_who,
		ou.id AS owner_id, ou.username AS owner_login, ou.full_name AS owner_name,
		COALESCE(NULLIF(LTRIM(RTRIM(ou.full_name)), N''), ou.username) AS owner_who,
		CASE WHEN ou.id IS NULL THEN N'' ELSE CONCAT(N' de ', COALESCE(NULLIF(LTRIM(RTRIM(ou.full_name)), N''), ou.username)) END
			AS owner_of
	FROM badge_scan_log b
	LEFT JOIN user_account su ON su.id = b.scanned_by_user_id
	LEFT JOIN app_role sr ON sr.id = su.app_role_id
	LEFT JOIN user_account ou ON ou.id = b.scanned_user_id
	LEFT JOIN cashier_session cs ON cs.id = b.session_id
	LEFT JOIN (SELECT cashier_session_id, MIN(pos_machine_id) AS pos_machine_id FROM cashier_session_pos_machine
		GROUP BY cashier_session_id) sm ON sm.cashier_session_id = b.session_id
	LEFT JOIN pos_machine pm ON pm.id = sm.pos_machine_id
	WHERE (b.granted_by IS NULL OR b.granted_by <> N'ROLE')
		AND NOT EXISTS (SELECT 1 FROM event_log c WHERE c.event_uuid = CONCAT(N'badge-scan-', CAST(b.id AS nvarchar(20))))
		AND NOT EXISTS (SELECT 1 FROM event_log e WHERE e.imported = 0
			AND e.code = CASE WHEN b.success = 1 THEN N'TILL_RIGHT_USED' ELSE N'TILL_RIGHT_REFUSED' END
			AND e.subject_id = b.functionality AND e.user_id = b.scanned_by_user_id
			AND e.event_at >= DATEADD(second, -5, b.[timestamp]) AND e.event_at <= DATEADD(second, 5, b.[timestamp]))) x;
-- step7:copy:end
END
GO

-- 6) Event log step 8, the cleanup: event_log_sequence.cleaned_to, the numbers 1 to it were cleaned up (EventLogCleanup
--   deletes a run of the first numbers of an importance, older than its duration, never the last 30 days), so the page
--   does not show them as missing. 0: never cleaned. The settings (EVENTLOG_STORE_FROM, EVENTLOG_KEEP_*_MONTHS,
--   EVENTLOG_AUTO_CLEANUP) are General setup rows the start creates on a store (ZZDataInitializer), as every setting.
--   Every database.
IF COL_LENGTH(N'event_log_sequence', N'cleaned_to') IS NULL
	ALTER TABLE event_log_sequence ADD cleaned_to bigint NOT NULL CONSTRAINT df_event_log_sequence_cleaned_to DEFAULT 0;
GO

UPDATE APP_VERSION SET version = '3.4.0';

-- The release notes of 3.4.0 are written from CHANGELOG.md when the version is closed (nothing to tell the users yet).

GO

SET NOEXEC OFF;
GO
