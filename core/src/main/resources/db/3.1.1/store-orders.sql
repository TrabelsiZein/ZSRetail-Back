-- ============================================================
-- Version 3.1.1 — Commandes centrale (orders of a store to its head office)
-- ============================================================
-- Run BEFORE db/3.1.1/update.sql (which stamps APP_VERSION 3.1.1), on a store and on a head office: the same file.
-- The file is UTF-8: with sqlcmd add -f 65001 (sqlcmd ... -f 65001 -i store-orders.sql); in SSMS open it as UTF-8.
-- See docs/modules/store-orders.md.
--
-- Schema, store only (a head office database has ho_store and never gets them): hol_order and hol_order_line, the orders
-- of the store to its head office and their lines, with the names and types Hibernate uses (a start creates them too),
-- and the filtered unique index ux_hol_order_draft (one DRAFT at a time; also made at each start, StoreOrderDraftIndex);
-- hol_order_line.available_qty, the availability a line was ordered against (added when missing);
-- hol_order.nav_message, hol_order_line.nav_accepted_qty and nav_line_message, NAV's answer as the head office sends it
-- down (added when missing); hol_order.invoice_no and delivered_at, NAV's invoice of a DELIVERED order (added when
-- missing); hol_order_colis, the colis of an ERP item in an order (one row per order and item: a colis is the whole
-- item, ZEN), hol_order_colis_detail (the composition of one colis), hol_order.total_colis and hol_order_line.vrac_allowed;
-- the rows of the earlier colis builds (by colour) merged per item, their keys dropped.
-- They exist, empty, on every store; only a store whose goods come from the head office uses them.
--
-- Schema, store and head office: item_image, the images of an item (any number, in sort order). Nothing writes it yet;
-- item.image_url stays the main image of the till, the admin pages and the copies down.
--
-- Schema, head office only (step 2; a database with ho_store): ho_store_order and ho_store_order_line, the orders the
-- stores send, one per store and number (uk_ho_store_order_number), with the names and types Hibernate uses, and
-- NAV's answer when the order was posted: ho_store_order.nav_message, ho_store_order_line.nav_accepted_qty and
-- nav_line_message (added when missing); ho_store_order.invoice_no and delivered_at, the ERP invoice that delivered it
-- (status DELIVERED, set by the invoice import from the invoice's orderNo; added when missing); ho_store_order_colis,
-- ho_store_order_colis_detail and ho_store_order.total_colis, the colis of the stores' orders (one row per order and
-- item, with the composition the store sent);
-- ho_erp_invoice.order_no
-- and its index ix_ho_erp_invoice_order, the sales order an ERP invoice names (added when missing; the invoices read
-- before keep none). They exist, empty, on every head office; only one with
-- headoffice.storeorders.post set takes orders.
--
-- Re-runnable: each object only when missing; no data changed.

-- The session options are set here (sqlcmd starts with QUOTED_IDENTIFIER OFF); SSMS has them on already.

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET NUMERIC_ROUNDABORT OFF;
GO

-- 3.1.0 (or 3.1.1 when run again) first. Otherwise the rest of the script is not executed (NOEXEC, reset at the end).
IF NOT EXISTS (SELECT 1 FROM APP_VERSION WHERE version IN ('3.1.0', '3.1.1'))
BEGIN
	DECLARE @current nvarchar(100) = ISNULL((SELECT TOP 1 version FROM APP_VERSION), N'(empty)');
	RAISERROR(N'3.1.1 store-orders: APP_VERSION is %s; run db/3.1.0/update.sql first. Nothing was changed.', 16, 1, @current);
	SET NOEXEC ON;
END
GO

-- The orders of the store to its head office (a store only; as the entity StoreOrder)
IF OBJECT_ID(N'ho_store', N'U') IS NULL AND OBJECT_ID(N'hol_order', N'U') IS NULL
BEGIN
	CREATE TABLE hol_order (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		number varchar(30) NOT NULL,
		status varchar(20) NOT NULL,
		sent_at datetime2 NULL,
		nav_order_no varchar(50) NULL,
		error_message varchar(1000) NULL,
		note varchar(500) NULL,
		line_count int NOT NULL,
		total_qty numeric(18,3) NOT NULL,
		CONSTRAINT PK_hol_order PRIMARY KEY (id),
		CONSTRAINT uk_hol_order_number UNIQUE (number)
	);
	CREATE INDEX ix_hol_order_status ON hol_order (status, created_at);
END
GO

-- One DRAFT at a time
IF OBJECT_ID(N'hol_order', N'U') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ux_hol_order_draft' AND object_id = OBJECT_ID(N'hol_order'))
	CREATE UNIQUE INDEX ux_hol_order_draft ON hol_order (status) WHERE status = 'DRAFT';
GO

-- Their lines, one per item (as the entity StoreOrderLine)
IF OBJECT_ID(N'ho_store', N'U') IS NULL AND OBJECT_ID(N'hol_order_line', N'U') IS NULL
BEGIN
	CREATE TABLE hol_order_line (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		order_id bigint NOT NULL,
		item_id bigint NOT NULL,
		item_code varchar(100) NOT NULL,
		nav_item_no varchar(255) NULL,
		variant_code varchar(50) NULL,
		description varchar(255) NULL,
		variant_description varchar(255) NULL,
		quantity numeric(18,3) NOT NULL,
		CONSTRAINT PK_hol_order_line PRIMARY KEY (id),
		CONSTRAINT uk_hol_order_line_item UNIQUE (order_id, item_id),
		CONSTRAINT fk_hol_order_line_order FOREIGN KEY (order_id) REFERENCES hol_order (id)
	);
END
GO

-- A line's availability: the quantity the head office made available when the line was added or replaced from the
-- variants window (its quantity's precision; NULL: none was given, no limit). Added to a table of an earlier run too.
IF OBJECT_ID(N'hol_order_line', N'U') IS NOT NULL AND COL_LENGTH(N'hol_order_line', N'available_qty') IS NULL
	ALTER TABLE hol_order_line ADD available_qty numeric(18,3) NULL;
GO

-- NAV's answer when the head office posted the order, as its copy down gives it: the message on the order, and per line
-- the quantity NAV accepted (NULL: none given) and its message. Added to the tables of an earlier run too.
IF OBJECT_ID(N'hol_order', N'U') IS NOT NULL AND COL_LENGTH(N'hol_order', N'nav_message') IS NULL
	ALTER TABLE hol_order ADD nav_message varchar(500) NULL;
GO
IF OBJECT_ID(N'hol_order_line', N'U') IS NOT NULL AND COL_LENGTH(N'hol_order_line', N'nav_accepted_qty') IS NULL
	ALTER TABLE hol_order_line ADD nav_accepted_qty numeric(18,3) NULL;
GO
IF OBJECT_ID(N'hol_order_line', N'U') IS NOT NULL AND COL_LENGTH(N'hol_order_line', N'nav_line_message') IS NULL
	ALTER TABLE hol_order_line ADD nav_line_message varchar(500) NULL;
GO

-- Delivered: NAV's invoice of the order (the document the store receives on "Réception des bons de livraison") and its
-- posting date, as the head office sends them down with the status DELIVERED. Added to a table of an earlier run too.
IF OBJECT_ID(N'hol_order', N'U') IS NOT NULL AND COL_LENGTH(N'hol_order', N'invoice_no') IS NULL
	ALTER TABLE hol_order ADD invoice_no varchar(30) NULL;
GO
IF OBJECT_ID(N'hol_order', N'U') IS NOT NULL AND COL_LENGTH(N'hol_order', N'delivered_at') IS NULL
	ALTER TABLE hol_order ADD delivered_at date NULL;
GO

-- The colis of an ERP item in an order (a store only; as the entity StoreOrderColis): one row per order and item, apart
-- from the lines (a colis is the whole item: one série of every colour, ZEN); the colis available and the content of one
-- colis when added, NAV's answer once posted (its colisAcceptes, quantite, numeroColis and message)
IF OBJECT_ID(N'ho_store', N'U') IS NULL AND OBJECT_ID(N'hol_order', N'U') IS NOT NULL
	AND OBJECT_ID(N'hol_order_colis', N'U') IS NULL
BEGIN
	CREATE TABLE hol_order_colis (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		order_id bigint NOT NULL,
		nav_item_no varchar(255) NOT NULL,
		description varchar(255) NULL,
		nb_colis int NOT NULL,
		colis_content int NULL,
		colis_disponibles int NULL,
		nav_accepted_colis int NULL,
		nav_accepted_qty numeric(18,3) NULL,
		nav_numero_colis varchar(50) NULL,
		nav_message varchar(500) NULL,
		CONSTRAINT PK_hol_order_colis PRIMARY KEY (id),
		CONSTRAINT uk_hol_order_colis_order_item UNIQUE (order_id, nav_item_no),
		CONSTRAINT fk_hol_order_colis_order FOREIGN KEY (order_id) REFERENCES hol_order (id)
	);
END
GO

-- A table of an earlier colis build: the columns of today (variant_code, color_description, contenance and
-- colis_possibles stay, unused)
IF OBJECT_ID(N'hol_order_colis', N'U') IS NOT NULL AND COL_LENGTH(N'hol_order_colis', N'colis_content') IS NULL
	ALTER TABLE hol_order_colis ADD colis_content int NULL;
GO
IF OBJECT_ID(N'hol_order_colis', N'U') IS NOT NULL AND COL_LENGTH(N'hol_order_colis', N'colis_disponibles') IS NULL
	ALTER TABLE hol_order_colis ADD colis_disponibles int NULL;
GO
IF OBJECT_ID(N'hol_order_colis', N'U') IS NOT NULL AND COL_LENGTH(N'hol_order_colis', N'nav_accepted_qty') IS NULL
	ALTER TABLE hol_order_colis ADD nav_accepted_qty numeric(18,3) NULL;
GO
IF OBJECT_ID(N'hol_order_colis', N'U') IS NOT NULL AND COL_LENGTH(N'hol_order_colis', N'nav_numero_colis') IS NULL
	ALTER TABLE hol_order_colis ADD nav_numero_colis varchar(50) NULL;
GO

-- Its rows by colour (the build of 2026-10-28): one row per order and item, the colis added up into the first, the
-- colour dropped; their composition stays unknown (colis_content empty)
IF OBJECT_ID(N'hol_order_colis', N'U') IS NOT NULL AND COL_LENGTH(N'hol_order_colis', N'variant_code') IS NOT NULL
	EXEC (N'
	UPDATE c SET nb_colis = t.total
	FROM hol_order_colis c
	JOIN (SELECT MIN(id) AS keep_id, SUM(nb_colis) AS total FROM hol_order_colis
		GROUP BY order_id, nav_item_no HAVING COUNT(*) > 1) t ON c.id = t.keep_id;
	DELETE c
	FROM hol_order_colis c
	JOIN (SELECT order_id, nav_item_no, MIN(id) AS keep_id FROM hol_order_colis
		GROUP BY order_id, nav_item_no HAVING COUNT(*) > 1) t
		ON c.order_id = t.order_id AND c.nav_item_no = t.nav_item_no AND c.id <> t.keep_id;
	UPDATE hol_order_colis SET variant_code = NULL, color_description = NULL
	WHERE variant_code IS NOT NULL OR color_description IS NOT NULL;');
GO

-- The keys of the earlier colis builds dropped (also at each start, StoreOrderDraftIndex), today's added
IF OBJECT_ID(N'uk_hol_order_colis_item', N'UQ') IS NOT NULL
	ALTER TABLE hol_order_colis DROP CONSTRAINT uk_hol_order_colis_item;
GO
IF OBJECT_ID(N'uk_hol_order_colis_variant', N'UQ') IS NOT NULL
	ALTER TABLE hol_order_colis DROP CONSTRAINT uk_hol_order_colis_variant;
GO
IF OBJECT_ID(N'hol_order_colis', N'U') IS NOT NULL AND OBJECT_ID(N'uk_hol_order_colis_order_item', N'UQ') IS NULL
	ALTER TABLE hol_order_colis ADD CONSTRAINT uk_hol_order_colis_order_item UNIQUE (order_id, nav_item_no);
GO

-- The composition of one colis of a row (a store only; as the entity StoreOrderColisDetail): each variant's pieces as
-- the head office gave them when the colis were added, its size and colour copied (the composition read as a matrix)
IF OBJECT_ID(N'hol_order_colis', N'U') IS NOT NULL AND OBJECT_ID(N'hol_order_colis_detail', N'U') IS NULL
BEGIN
	CREATE TABLE hol_order_colis_detail (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		colis_id bigint NOT NULL,
		item_id bigint NOT NULL,
		item_code varchar(100) NOT NULL,
		variant_code varchar(50) NULL,
		qty_per_colis int NOT NULL,
		size_code varchar(50) NULL,
		size_description varchar(255) NULL,
		color_code varchar(50) NULL,
		color_description varchar(255) NULL,
		CONSTRAINT PK_hol_order_colis_detail PRIMARY KEY (id),
		CONSTRAINT fk_hol_order_colis_detail_colis FOREIGN KEY (colis_id) REFERENCES hol_order_colis (id)
	);
	CREATE INDEX ix_hol_order_colis_detail_colis ON hol_order_colis_detail (colis_id);
END
GO

-- A line's vrac rule as the head office gave it when the line was added from the variants window (ZEN's vracAutorise;
-- NULL: none given). Added to a table of an earlier run too.
IF OBJECT_ID(N'hol_order_line', N'U') IS NOT NULL AND COL_LENGTH(N'hol_order_line', N'vrac_allowed') IS NULL
	ALTER TABLE hol_order_line ADD vrac_allowed bit NULL;
GO

-- The colis of an order in all (total_qty counts the pieces of the lines only). Added to a table of an earlier run too.
IF OBJECT_ID(N'hol_order', N'U') IS NOT NULL AND COL_LENGTH(N'hol_order', N'total_colis') IS NULL
	ALTER TABLE hol_order ADD total_colis int NULL;
GO

-- The images of an item (store and head office; as the entity ItemImage)
IF OBJECT_ID(N'item_image', N'U') IS NULL
BEGIN
	CREATE TABLE item_image (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		item_id bigint NOT NULL,
		source varchar(10) NOT NULL,
		path varchar(1000) NOT NULL,
		sort_order int NOT NULL,
		is_main bit NOT NULL,
		CONSTRAINT PK_item_image PRIMARY KEY (id)
	);
	CREATE INDEX ix_item_image_item ON item_image (item_id, sort_order);
END
GO

-- The orders of the stores at the head office (a head office only; as the entity HoStoreOrder)
IF OBJECT_ID(N'ho_store', N'U') IS NOT NULL AND OBJECT_ID(N'ho_store_order', N'U') IS NULL
BEGIN
	CREATE TABLE ho_store_order (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		store_id bigint NOT NULL,
		number varchar(30) NOT NULL,
		status varchar(20) NOT NULL,
		location_code varchar(50) NULL,
		sent_at datetime2 NULL,
		received_at datetime2 NOT NULL,
		nav_order_no varchar(50) NULL,
		posted_at datetime2 NULL,
		posted_mode varchar(10) NULL,
		error_message varchar(1000) NULL,
		post_attempts int NOT NULL,
		last_attempt_at datetime2 NULL,
		note varchar(500) NULL,
		line_count int NOT NULL,
		total_qty numeric(18,3) NOT NULL,
		CONSTRAINT PK_ho_store_order PRIMARY KEY (id),
		CONSTRAINT uk_ho_store_order_number UNIQUE (store_id, number),
		CONSTRAINT fk_ho_store_order_store FOREIGN KEY (store_id) REFERENCES ho_store (id)
	);
	CREATE INDEX ix_ho_store_order_status ON ho_store_order (status, received_at);
END
GO

-- Their lines, by codes as the store sent them (as the entity HoStoreOrderLine)
IF OBJECT_ID(N'ho_store', N'U') IS NOT NULL AND OBJECT_ID(N'ho_store_order_line', N'U') IS NULL
BEGIN
	CREATE TABLE ho_store_order_line (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		order_id bigint NOT NULL,
		item_code varchar(100) NOT NULL,
		nav_item_no varchar(255) NULL,
		variant_code varchar(50) NULL,
		description varchar(255) NULL,
		variant_description varchar(255) NULL,
		quantity numeric(18,3) NOT NULL,
		CONSTRAINT PK_ho_store_order_line PRIMARY KEY (id),
		CONSTRAINT fk_ho_store_order_line_order FOREIGN KEY (order_id) REFERENCES ho_store_order (id)
	);
	CREATE INDEX ix_ho_store_order_line_order ON ho_store_order_line (order_id);
END
GO

-- NAV's answer when the order was posted (commandesVente): the message on the order (its message), and per line the
-- quantity NAV accepted (its quantite; NULL: none given) and its message. Added to the tables of an earlier run too.
IF OBJECT_ID(N'ho_store_order', N'U') IS NOT NULL AND COL_LENGTH(N'ho_store_order', N'nav_message') IS NULL
	ALTER TABLE ho_store_order ADD nav_message varchar(500) NULL;
GO
IF OBJECT_ID(N'ho_store_order_line', N'U') IS NOT NULL
	AND COL_LENGTH(N'ho_store_order_line', N'nav_accepted_qty') IS NULL
	ALTER TABLE ho_store_order_line ADD nav_accepted_qty numeric(18,3) NULL;
GO
IF OBJECT_ID(N'ho_store_order_line', N'U') IS NOT NULL
	AND COL_LENGTH(N'ho_store_order_line', N'nav_line_message') IS NULL
	ALTER TABLE ho_store_order_line ADD nav_line_message varchar(500) NULL;
GO

-- Delivered: the ERP invoice whose orderNo is the order's ERP number (its number) and its posting date. Added to a table
-- of an earlier run too.
IF OBJECT_ID(N'ho_store_order', N'U') IS NOT NULL AND COL_LENGTH(N'ho_store_order', N'invoice_no') IS NULL
	ALTER TABLE ho_store_order ADD invoice_no varchar(30) NULL;
GO
IF OBJECT_ID(N'ho_store_order', N'U') IS NOT NULL AND COL_LENGTH(N'ho_store_order', N'delivered_at') IS NULL
	ALTER TABLE ho_store_order ADD delivered_at date NULL;
GO

-- The colis of the stores' orders (a head office only; as the entity HoStoreOrderColis), one row per order and item, as
-- the store sent them (the colis available, the content of one colis), NAV's answer once posted (its colisAcceptes,
-- quantite, numeroColis and message)
IF OBJECT_ID(N'ho_store', N'U') IS NOT NULL AND OBJECT_ID(N'ho_store_order', N'U') IS NOT NULL
	AND OBJECT_ID(N'ho_store_order_colis', N'U') IS NULL
BEGIN
	CREATE TABLE ho_store_order_colis (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		order_id bigint NOT NULL,
		nav_item_no varchar(255) NOT NULL,
		description varchar(255) NULL,
		nb_colis int NOT NULL,
		colis_content int NULL,
		colis_disponibles int NULL,
		nav_accepted_colis int NULL,
		nav_accepted_qty numeric(18,3) NULL,
		nav_numero_colis varchar(50) NULL,
		nav_message varchar(500) NULL,
		CONSTRAINT PK_ho_store_order_colis PRIMARY KEY (id),
		CONSTRAINT fk_ho_store_order_colis_order FOREIGN KEY (order_id) REFERENCES ho_store_order (id)
	);
	CREATE INDEX ix_ho_store_order_colis_order ON ho_store_order_colis (order_id);
END
GO
IF OBJECT_ID(N'ho_store_order', N'U') IS NOT NULL AND COL_LENGTH(N'ho_store_order', N'total_colis') IS NULL
	ALTER TABLE ho_store_order ADD total_colis int NULL;
GO

-- A table of an earlier colis build: the columns of today (variant_code, color_description, contenance and
-- colis_possibles stay, unused)
IF OBJECT_ID(N'ho_store_order_colis', N'U') IS NOT NULL AND COL_LENGTH(N'ho_store_order_colis', N'colis_content') IS NULL
	ALTER TABLE ho_store_order_colis ADD colis_content int NULL;
GO
IF OBJECT_ID(N'ho_store_order_colis', N'U') IS NOT NULL
	AND COL_LENGTH(N'ho_store_order_colis', N'colis_disponibles') IS NULL
	ALTER TABLE ho_store_order_colis ADD colis_disponibles int NULL;
GO
IF OBJECT_ID(N'ho_store_order_colis', N'U') IS NOT NULL
	AND COL_LENGTH(N'ho_store_order_colis', N'nav_accepted_qty') IS NULL
	ALTER TABLE ho_store_order_colis ADD nav_accepted_qty numeric(18,3) NULL;
GO
IF OBJECT_ID(N'ho_store_order_colis', N'U') IS NOT NULL
	AND COL_LENGTH(N'ho_store_order_colis', N'nav_numero_colis') IS NULL
	ALTER TABLE ho_store_order_colis ADD nav_numero_colis varchar(50) NULL;
GO

-- Its rows by colour (the build of 2026-10-28): one row per order and item, the colis added up into the first, the
-- colour dropped; their composition stays unknown (colis_content empty)
IF OBJECT_ID(N'ho_store_order_colis', N'U') IS NOT NULL
	AND COL_LENGTH(N'ho_store_order_colis', N'variant_code') IS NOT NULL
	EXEC (N'
	UPDATE c SET nb_colis = t.total
	FROM ho_store_order_colis c
	JOIN (SELECT MIN(id) AS keep_id, SUM(nb_colis) AS total FROM ho_store_order_colis
		GROUP BY order_id, nav_item_no HAVING COUNT(*) > 1) t ON c.id = t.keep_id;
	DELETE c
	FROM ho_store_order_colis c
	JOIN (SELECT order_id, nav_item_no, MIN(id) AS keep_id FROM ho_store_order_colis
		GROUP BY order_id, nav_item_no HAVING COUNT(*) > 1) t
		ON c.order_id = t.order_id AND c.nav_item_no = t.nav_item_no AND c.id <> t.keep_id;
	UPDATE ho_store_order_colis SET variant_code = NULL, color_description = NULL
	WHERE variant_code IS NOT NULL OR color_description IS NOT NULL;');
GO

-- The composition of one colis of a row (a head office only; as the entity HoStoreOrderColisDetail), as the store sent
-- it, by codes
IF OBJECT_ID(N'ho_store_order_colis', N'U') IS NOT NULL AND OBJECT_ID(N'ho_store_order_colis_detail', N'U') IS NULL
BEGIN
	CREATE TABLE ho_store_order_colis_detail (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		colis_id bigint NOT NULL,
		item_code varchar(100) NOT NULL,
		variant_code varchar(50) NULL,
		qty_per_colis int NOT NULL,
		size_code varchar(50) NULL,
		size_description varchar(255) NULL,
		color_code varchar(50) NULL,
		color_description varchar(255) NULL,
		CONSTRAINT PK_ho_store_order_colis_detail PRIMARY KEY (id),
		CONSTRAINT fk_ho_store_order_colis_detail_colis FOREIGN KEY (colis_id) REFERENCES ho_store_order_colis (id)
	);
	CREATE INDEX ix_ho_store_order_colis_detail_colis ON ho_store_order_colis_detail (colis_id);
END
GO

-- The sales order an ERP invoice names (its orderNo), kept so that an order posted after its invoice was read is
-- delivered by it; the index serves that lookup. Invoices read before this script keep none.
IF OBJECT_ID(N'ho_erp_invoice', N'U') IS NOT NULL AND COL_LENGTH(N'ho_erp_invoice', N'order_no') IS NULL
	ALTER TABLE ho_erp_invoice ADD order_no varchar(50) NULL;
GO
IF OBJECT_ID(N'ho_erp_invoice', N'U') IS NOT NULL AND COL_LENGTH(N'ho_erp_invoice', N'order_no') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_ho_erp_invoice_order'
		AND object_id = OBJECT_ID(N'ho_erp_invoice'))
	CREATE INDEX ix_ho_erp_invoice_order ON ho_erp_invoice (order_no);
GO

SET NOEXEC OFF;
GO
