-- ============================================================
-- Version 2.1.0 — Siège et magasins (head office plan, steps 1 to 9)
-- ============================================================
-- Deployment order:
--   1) Run db/1.11.0 and db/1.12.0 first if the database is older than 1.12.0.
--   2) Write the installation's machine file (database, port, log, NAV settings, head office address and key, and
--      its preset): ${catalina.base}/conf/zsretail/<context name>.properties, or -Dzsretail.machine-file=<path>.
--      The 2.1.0 WAR has no default profile and refuses application.standalone and an old spring.profiles.active
--      (docs/deployment-modes.md, "Upgrade of an existing installation to 2.1"). The values of the old profile files
--      are in deploy/ of the backend repository.
--   3) Run this script against the database.
--   4) Deploy the 2.1.0 binaries (backend WAR and frontend).
-- The application refuses to start while APP_VERSION differs from its own version, so step 3 must be done before 4.
--
-- Schema: every new table (ho_* on a head office, hol_* on a store linked to one; both exist, empty, in every
-- database) is created by Hibernate ddl-auto=update at the first start:
--   ho_store, ho_ticket, ho_ticket_line, ho_ticket_payment, ho_return, ho_return_line, ho_session, ho_session_count,
--   ho_down_change, ho_down_sequence, ho_promotion_store, ho_loyalty_alias, ho_loyalty_movement, ho_price_list,
--   ho_price_list_line, ho_delivery, ho_delivery_line, ho_number_sequence, ho_store_stock, ho_item_supply_price,
--   ho_supply_invoice, ho_supply_invoice_line, ho_erp_invoice, ho_erp_invoice_line,
--   hol_sales_copy, hol_sales_cursor, hol_exchange_log, hol_job, hol_down_cursor, hol_down_record,
--   hol_loyalty_member_copy, hol_loyalty_movement_copy, hol_link_right, hol_delivery, hol_delivery_line,
--   hol_stock_copy.
-- The statements below add the new nullable columns of existing tables (null = the value before 2.1.0: local,
-- false, not reported). Each one is guarded: it does nothing when the column is already there (a database already
-- started by a 2.1 build) or, for the ho_* and hol_* tables, when the table does not exist yet (Hibernate then
-- creates it whole). No column or table is dropped or renamed: the columns of the removed franchise profiles
-- (item.franchise_sales_price, item.from_franchise_admin, customer.default_location,
-- invoice_header.franchise_location_code, invoice_header.franchise_received_at) and the tables franchise_sales_header
-- and franchise_sales_line stay, unused. No data is changed: a store's own items, promotions and loyalty members
-- become the head office's at its first pull, not by this script. The new permissions and head office settings are
-- added at startup. stock_movement.movement_type gets two new values (DELIVERY_OUT, DELIVERY_IN) within its varchar.

-- Existing tables of every store (steps 3, 4, 6, 7B)
IF COL_LENGTH('promotion', 'origin') IS NULL ALTER TABLE promotion ADD origin varchar(20) NULL;
IF COL_LENGTH('loyalty_member', 'origin') IS NULL ALTER TABLE loyalty_member ADD origin varchar(20) NULL;
IF COL_LENGTH('loyalty_program', 'origin') IS NULL ALTER TABLE loyalty_program ADD origin varchar(20) NULL;
IF COL_LENGTH('item', 'origin') IS NULL ALTER TABLE item ADD origin varchar(20) NULL;
IF COL_LENGTH('item_family', 'origin') IS NULL ALTER TABLE item_family ADD origin varchar(20) NULL;
IF COL_LENGTH('item_sub_family', 'origin') IS NULL ALTER TABLE item_sub_family ADD origin varchar(20) NULL;
IF COL_LENGTH('item_barcode', 'origin') IS NULL ALTER TABLE item_barcode ADD origin varchar(20) NULL;
IF COL_LENGTH('item', 'own_price') IS NULL ALTER TABLE item ADD own_price bit NULL;
IF COL_LENGTH('item', 'head_office_price') IS NULL ALTER TABLE item ADD head_office_price float NULL;
IF COL_LENGTH('purchase_invoice_header', 'origin') IS NULL ALTER TABLE purchase_invoice_header ADD origin varchar(20) NULL;
GO

-- Head office tables already created by a 2.1 build (steps 3 to 7B): only when the table exists
IF OBJECT_ID('ho_store') IS NOT NULL
BEGIN
	IF COL_LENGTH('ho_store', 'owner_catalogue') IS NULL ALTER TABLE ho_store ADD owner_catalogue varchar(20) NULL;
	IF COL_LENGTH('ho_store', 'owner_customers') IS NULL ALTER TABLE ho_store ADD owner_customers varchar(20) NULL;
	IF COL_LENGTH('ho_store', 'owner_promotions') IS NULL ALTER TABLE ho_store ADD owner_promotions varchar(20) NULL;
	IF COL_LENGTH('ho_store', 'owner_loyalty') IS NULL ALTER TABLE ho_store ADD owner_loyalty varchar(20) NULL;
	IF COL_LENGTH('ho_store', 'owner_supply') IS NULL ALTER TABLE ho_store ADD owner_supply varchar(20) NULL;
	IF COL_LENGTH('ho_store', 'sales_upstreams') IS NULL ALTER TABLE ho_store ADD sales_upstreams varchar(50) NULL;
	IF COL_LENGTH('ho_store', 'can_edit_members') IS NULL ALTER TABLE ho_store ADD can_edit_members bit NULL;
	IF COL_LENGTH('ho_store', 'can_adjust_points') IS NULL ALTER TABLE ho_store ADD can_adjust_points bit NULL;
	IF COL_LENGTH('ho_store', 'redeem_requires_online') IS NULL ALTER TABLE ho_store ADD redeem_requires_online bit NULL;
	IF COL_LENGTH('ho_store', 'enrol_requires_online') IS NULL ALTER TABLE ho_store ADD enrol_requires_online bit NULL;
	IF COL_LENGTH('ho_store', 'selling_price_list_id') IS NULL ALTER TABLE ho_store ADD selling_price_list_id bigint NULL;
	IF COL_LENGTH('ho_store', 'may_change_prices') IS NULL ALTER TABLE ho_store ADD may_change_prices bit NULL;
	IF COL_LENGTH('ho_store', 'can_purchase') IS NULL ALTER TABLE ho_store ADD can_purchase bit NULL;
	IF COL_LENGTH('ho_store', 'deliveries_invoiced') IS NULL ALTER TABLE ho_store ADD deliveries_invoiced bit NULL;
	IF COL_LENGTH('ho_store', 'billing_legal_name') IS NULL ALTER TABLE ho_store ADD billing_legal_name varchar(200) NULL;
	IF COL_LENGTH('ho_store', 'billing_tax_number') IS NULL ALTER TABLE ho_store ADD billing_tax_number varchar(50) NULL;
	IF COL_LENGTH('ho_store', 'billing_address') IS NULL ALTER TABLE ho_store ADD billing_address varchar(500) NULL;
	IF COL_LENGTH('ho_store', 'supply_price_mode') IS NULL ALTER TABLE ho_store ADD supply_price_mode varchar(20) NULL;
	IF COL_LENGTH('ho_store', 'supply_price_list_id') IS NULL ALTER TABLE ho_store ADD supply_price_list_id bigint NULL;
	IF COL_LENGTH('ho_store', 'supply_discount_percent') IS NULL ALTER TABLE ho_store ADD supply_discount_percent float NULL;
	IF COL_LENGTH('ho_store', 'invoice_rhythm') IS NULL ALTER TABLE ho_store ADD invoice_rhythm varchar(20) NULL;
	IF COL_LENGTH('ho_store', 'erp_customer_no') IS NULL ALTER TABLE ho_store ADD erp_customer_no varchar(100) NULL;
END
GO

-- Invoices from the ERP (headoffice.supply.source=ERP): one store per ERP customer number, any number of stores without
-- one: a filtered unique index. Hibernate cannot create it, and on a new database ho_store does not exist yet here: a
-- head office also creates it at its start when it is missing (StoreErpCustomerIndex). A filtered index needs
-- QUOTED_IDENTIFIER and ANSI_NULLS ON, here and for every INSERT or UPDATE of ho_store (sqlcmd: option -I; JDBC and
-- SSMS have them on).
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO
IF COL_LENGTH('ho_store', 'erp_customer_no') IS NOT NULL
	AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ux_ho_store_erp_customer_no' AND object_id = OBJECT_ID('ho_store'))
	EXEC('CREATE UNIQUE INDEX ux_ho_store_erp_customer_no ON ho_store (erp_customer_no) WHERE erp_customer_no IS NOT NULL');
GO
IF OBJECT_ID('ho_price_list') IS NOT NULL AND COL_LENGTH('ho_price_list', 'kind') IS NULL
	ALTER TABLE ho_price_list ADD kind varchar(10) NULL;
IF OBJECT_ID('ho_delivery') IS NOT NULL AND COL_LENGTH('ho_delivery', 'invoice_id') IS NULL
	ALTER TABLE ho_delivery ADD invoice_id bigint NULL;
IF OBJECT_ID('ho_delivery') IS NOT NULL AND COL_LENGTH('ho_delivery', 'invoice_note') IS NULL
	ALTER TABLE ho_delivery ADD invoice_note varchar(500) NULL;
IF OBJECT_ID('hol_delivery') IS NOT NULL AND COL_LENGTH('hol_delivery', 'invoice_number') IS NULL
	ALTER TABLE hol_delivery ADD invoice_number varchar(30) NULL;
GO

-- Inventory count (docs/modules/inventory-count.md): two new tables, created here (re-runnable: only when missing) with
-- the names Hibernate uses, so ddl-auto finds them complete. Used on a store that keeps its own stock; they exist,
-- empty, everywhere. stock_movement.movement_type gets two new values (INVENTORY_IN, INVENTORY_OUT) within its varchar.
-- The permissions read:admin-inventory-counts and write:admin-inventory-counts are added to ADMIN at startup.
IF OBJECT_ID('inventory_count') IS NULL
BEGIN
	CREATE TABLE inventory_count (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		number varchar(30) NOT NULL,
		count_date date NOT NULL,
		note varchar(500) NULL,
		file_name varchar(255) NULL,
		rows_read int NULL,
		status varchar(20) NOT NULL,
		validated_at datetime2 NULL,
		validated_by varchar(100) NULL,
		CONSTRAINT PK_inventory_count PRIMARY KEY (id),
		CONSTRAINT uk_inventory_count_number UNIQUE (number)
	);
END
GO
IF OBJECT_ID('inventory_count_line') IS NULL
BEGIN
	CREATE TABLE inventory_count_line (
		id bigint IDENTITY(1,1) NOT NULL,
		active bit NULL,
		created_at datetime2 NULL,
		created_by varchar(255) NULL,
		updated_at datetime2 NULL,
		updated_by varchar(255) NULL,
		count_id bigint NOT NULL,
		item_id bigint NULL,
		code varchar(100) NULL,
		counted_quantity int NULL,
		merged_rows int NULL,
		system_quantity_at_import int NULL,
		system_quantity_at_validation int NULL,
		difference_applied int NULL,
		status varchar(20) NOT NULL,
		message varchar(255) NULL,
		CONSTRAINT PK_inventory_count_line PRIMARY KEY (id),
		CONSTRAINT fk_inventory_count_line_count FOREIGN KEY (count_id) REFERENCES inventory_count (id)
	);
	CREATE INDEX ix_inventory_count_line_count ON inventory_count_line (count_id);
END
GO

UPDATE APP_VERSION SET version = '2.1.0';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('2.1.0', 'NEW', 'Siège : un nouveau type d''installation qui gère les magasins sans jamais vendre. Liste des magasins avec leur état en ligne, une clé par magasin, et les ventes, retours et sessions de chaque magasin consultables au siège.'),
('2.1.0', 'NEW', 'Promotions et fidélité décidées par le siège pour les magasins qui le choisissent : un seul registre de membres pour le réseau, gain et utilisation des points dans n''importe quel magasin.'),
('2.1.0', 'NEW', 'Articles et prix de vente décidés par le siège : prix de base et listes de prix par magasin, droit d''un magasin à changer ses prix ou à acheter ses propres articles.'),
('2.1.0', 'NEW', 'Approvisionnement par le siège : achats et stock au siège, bons de livraison vers les magasins, réception et écarts au magasin, stock de tous les magasins au siège.'),
('2.1.0', 'NEW', 'Facturation des livraisons aux magasins qui paient (franchise) : prix de cession par article ou en pourcentage, facture par BL ou groupée, reçue au magasin comme facture d''achat, payé ou non payé et ce que doit chaque magasin.'),
('2.1.0', 'IMPROVE', 'Installation : chaque installation a son fichier machine hors du WAR (base, port, journal, ERP, adresse et clé du siège) et un préréglage (magasin, magasin ERP, siège, siège ERP, magasin du réseau, magasin ERP du réseau). Les anciens profils franchise-admin et franchise-customer sont retirés ; un réseau de franchise s''installe avec les préréglages siège et magasin du réseau.'),
('2.1.0', 'IMPROVE', 'Le stock d''un ajustement manuel enregistre maintenant son mouvement, et la modification d''un article ne remplace plus le stock en cours.'),
('2.1.0', 'NEW', 'Inventaire : import d''un comptage physique depuis un fichier Excel (code ou code-barres, quantité), écarts avec le stock avant validation, puis le stock prend la quantité comptée avec un mouvement d''inventaire par écart. Magasin qui gère son propre stock uniquement.'),
('2.1.0', 'NEW', 'Siège avec le catalogue de l''ERP : les factures des magasins franchisés peuvent être lues dans l''ERP (Business Central) au lieu des BL et factures du siège ; chaque facture va au magasin dont le numéro client ERP est celui de la facture.');
