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
-- Schema, item variants (ZEN, docs/variants-zen-analysis-2026-10-14.md): nullable columns of item (the variant, its size,
-- colour, choice, season, division, catégorie and section codes) and item_family.category_code, two new tables
-- item_category and item_section (store and head office, empty), the clearance of ho_stock_point_item (a head office
-- only) and the index ix_item_nav_item_no (step 11a, the "NAV items" page). Re-runnable; no data changed.
--
-- Data, item variants (ZEN): item.variant_description rebuilt from size, colour and choice descriptions (' / ') for the
-- items with a nav_item_no, as connectors/zen now builds it. Re-runnable.
--
-- Schema, clearance at the till (ZEN): item.clearance and item.public_price, nullable (store and head office; written
-- on a store only). Re-runnable; no data changed.
--
-- Indexes, speed (store and head office): ix_item_barcode_item (item_barcode.item_id), ix_item_barcode (item.barcode);
-- on a head office ix_ho_down_change_pull replaces ix_ho_down_change_version. Re-runnable; no data changed.
--
-- Data, payment method "Retenue à la source" (type RETENUE_SOURCE): added inactive with the neutral code RETENUE_SOURCE
-- (store and head office, as the seed), only when no row of that type exists. A database that has it already (EMTOP
-- since 1.12.1: active, with its ERP code as the code) keeps its row unchanged.
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

-- Payment method "Retenue à la source": inactive, neutral code; the admin switches it on and types the ERP code as its
-- code. Nothing is done when a row of the type exists (EMTOP 1.12.1), nor when the code is taken by another row.
IF NOT EXISTS (SELECT 1 FROM payment_method WHERE type = 'RETENUE_SOURCE')
	AND NOT EXISTS (SELECT 1 FROM payment_method WHERE code = 'RETENUE_SOURCE')
BEGIN
	INSERT INTO payment_method (code, name, type, description, active, display_order,
		require_title_number, require_due_date, require_drawer_name, require_issuing_bank,
		requires_confirmation, created_at, updated_at, created_by, updated_by)
	VALUES ('RETENUE_SOURCE', N'Retenue à la source', 'RETENUE_SOURCE', N'Paiement par retenue à la source',
		0, 10, 0, 0, 0, 0, 0, SYSDATETIME(), SYSDATETIME(), 'System', 'System');
END
GO

-- Item variants (ZEN), step 1 (docs/variants-zen-analysis-2026-10-14.md): one item per variant of the ERP. New nullable
-- columns of item, the catégorie of a family, and two new tables item_category (above the family) and item_section
-- (below the sub-family), on a store and on a head office, as the families. Empty for a customer whose ERP gives no
-- variant. No data is changed.
IF COL_LENGTH('item', 'nav_item_no') IS NULL ALTER TABLE item ADD nav_item_no varchar(255) NULL;
IF COL_LENGTH('item', 'variant_code') IS NULL ALTER TABLE item ADD variant_code varchar(255) NULL;
IF COL_LENGTH('item', 'variant_description') IS NULL ALTER TABLE item ADD variant_description varchar(255) NULL;
IF COL_LENGTH('item', 'size_code') IS NULL ALTER TABLE item ADD size_code varchar(255) NULL;
IF COL_LENGTH('item', 'size_description') IS NULL ALTER TABLE item ADD size_description varchar(255) NULL;
IF COL_LENGTH('item', 'size_sort_order') IS NULL ALTER TABLE item ADD size_sort_order int NULL;
IF COL_LENGTH('item', 'color_code') IS NULL ALTER TABLE item ADD color_code varchar(255) NULL;
IF COL_LENGTH('item', 'color_description') IS NULL ALTER TABLE item ADD color_description varchar(255) NULL;
IF COL_LENGTH('item', 'choice_code') IS NULL ALTER TABLE item ADD choice_code varchar(255) NULL;
IF COL_LENGTH('item', 'choice_description') IS NULL ALTER TABLE item ADD choice_description varchar(255) NULL;
IF COL_LENGTH('item', 'season_code') IS NULL ALTER TABLE item ADD season_code varchar(255) NULL;
IF COL_LENGTH('item', 'division_code') IS NULL ALTER TABLE item ADD division_code varchar(255) NULL;
IF COL_LENGTH('item', 'category_code') IS NULL ALTER TABLE item ADD category_code varchar(255) NULL;
IF COL_LENGTH('item', 'section_code') IS NULL ALTER TABLE item ADD section_code varchar(255) NULL;
IF COL_LENGTH('item_family', 'category_code') IS NULL ALTER TABLE item_family ADD category_code varchar(255) NULL;
GO
-- Item variants (ZEN), step 11a: the "NAV items" page groups the items by nav_item_no and GET /config asks whether one
-- item has it. A plain index, as the entity's @Index: a filtered one on item would refuse every later write to item from
-- a session with QUOTED_IDENTIFIER OFF (sqlcmd's default), for every customer.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_item_nav_item_no' AND object_id = OBJECT_ID(N'item'))
	CREATE INDEX ix_item_nav_item_no ON item (nav_item_no);
GO
IF OBJECT_ID(N'item_category', N'U') IS NULL
BEGIN
	CREATE TABLE item_category (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		erp_external_id varchar(255) NULL,
		code varchar(255) NOT NULL,
		name varchar(255) NOT NULL,
		description varchar(255) NULL,
		display_order int NULL,
		origin varchar(20) NULL,
		CONSTRAINT PK_item_category PRIMARY KEY (id),
		CONSTRAINT uk_item_category_code UNIQUE (code)
	);
END
GO
IF OBJECT_ID(N'item_section', N'U') IS NULL
BEGIN
	CREATE TABLE item_section (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		erp_external_id varchar(255) NULL,
		code varchar(255) NOT NULL,
		name varchar(255) NOT NULL,
		description varchar(255) NULL,
		display_order int NULL,
		sub_family_code varchar(255) NULL,
		origin varchar(20) NULL,
		CONSTRAINT PK_item_section PRIMARY KEY (id),
		CONSTRAINT uk_item_section_code UNIQUE (code)
	);
END
GO
-- The clearance of a head office's point de stock row (a head office only: a store has no ho_stock_point_item). Null
-- is read as false.
IF OBJECT_ID(N'ho_stock_point_item', N'U') IS NOT NULL
BEGIN
	IF COL_LENGTH('ho_stock_point_item', 'clearance') IS NULL ALTER TABLE ho_stock_point_item ADD clearance bit NULL;
	IF COL_LENGTH('ho_stock_point_item', 'clearance_price') IS NULL ALTER TABLE ho_stock_point_item ADD clearance_price numeric(18,3) NULL;
END
GO
-- The variant description (store and head office): connectors/zen builds it from the size, colour and choice
-- descriptions, trimmed, the blank ones left out, joined with ' / ' (null when none, as the import stores a blank
-- variant field); NAV's variantDescription is always the item description on ZEN's platform and is not read. The items
-- already there get the same value, so the next items run sees no change. Only the items of an ERP with variants (nav_item_no; none for EMTOP and Happyness). Re-runnable.
UPDATE item SET variant_description = NULLIF(ISNULL(STUFF(
		ISNULL(' / ' + NULLIF(LTRIM(RTRIM(size_description)), ''), '')
		+ ISNULL(' / ' + NULLIF(LTRIM(RTRIM(color_description)), ''), '')
		+ ISNULL(' / ' + NULLIF(LTRIM(RTRIM(choice_description)), ''), ''), 1, 3, ''), ''), '')
WHERE nav_item_no IS NOT NULL;
GO
-- Clearance at the till (item variants, ZEN, 2026-10-17): a store sells the clearance price of its point de stock
-- (ho_stock_point_item clearance true and clearance_price above 0), which the head office sends as the item's price.
-- Two nullable columns of item, written by the copies down only: clearance (true while the item is sent at that price)
-- and public_price (the point's public price before VAT, shown struck through at the till). The entity is shared, so
-- they exist on a head office too, always null there. Re-runnable; no data changed.
IF COL_LENGTH('item', 'clearance') IS NULL ALTER TABLE item ADD clearance bit NULL;
IF COL_LENGTH('item', 'public_price') IS NULL ALTER TABLE item ADD public_price float NULL;
GO

-- Speed (ZEN's test head office, 21,903 items): the barcodes of an item (store and head office). Hibernate creates no
-- index for a foreign key on SQL Server: without it every read of the barcodes by item scans the table (the "Items by
-- point de stock" page with a search, its rows' barcodes, the imports). As the entity's @Index.
IF OBJECT_ID(N'item_barcode', N'U') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_item_barcode_item' AND object_id = OBJECT_ID(N'item_barcode'))
	CREATE INDEX ix_item_barcode_item ON item_barcode (item_id);
GO
-- Speed: the old item.barcode field, read for each barcode a store receives (a value there is cleared when the head
-- office sends it): without an index a scan of item per barcode (815 reads, 16-19 ms on 21,904 items, ZEN's test store).
-- Store and head office. As the entity's @Index.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_item_barcode' AND object_id = OBJECT_ID(N'item'))
	CREATE INDEX ix_item_barcode ON item (barcode);
GO
-- Speed: the pull of a store's copies down reads the domain's changes after its cursor in the index alone (a head office
-- only). It replaces ix_ho_down_change_version (domain, change_version), which did not hold the store and the code: SQL
-- Server read the whole table at every page. As the entity's @Index.
IF OBJECT_ID(N'ho_down_change', N'U') IS NOT NULL
BEGIN
	IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_ho_down_change_pull' AND object_id = OBJECT_ID(N'ho_down_change'))
		CREATE INDEX ix_ho_down_change_pull ON ho_down_change (domain, change_version, store_id, record_code);
	IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_ho_down_change_version' AND object_id = OBJECT_ID(N'ho_down_change'))
		DROP INDEX ix_ho_down_change_version ON ho_down_change;
END
GO

UPDATE APP_VERSION SET version = '3.1.0';

-- The notes of 3.1.0 are written again on each run (the script may run more than once). The RFID notes are added
-- when the release is closed (the RFID screens are not finished yet).
DELETE FROM APP_RELEASE_NOTES WHERE version = '3.1.0';
INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('3.1.0', 'NEW', N'Nouveau moyen de paiement « Retenue à la source », inactif à l''installation : l''activer dans Moyens de paiement et saisir comme code le code ERP du règlement.'),
('3.1.0', 'IMPROVE', N'Ajuster le stock : une variation décimale (1,82 L, au plus 3 décimales) est acceptée quand le magasin autorise les quantités décimales (Configuration générale) ; sinon elle est refusée en nommant l''article, jamais arrondie.');

GO

SET NOEXEC OFF;
GO
