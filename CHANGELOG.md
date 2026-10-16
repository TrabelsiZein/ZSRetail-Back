# Changelog — ZSRetail-Back

One section per product version, newest first. Every commit that changes behaviour, a DB script or a config key adds
its line here, under the version of its branch. The screens are in `ZSRetail-Front/CHANGELOG.md`. The French notes shown
in the application (`APP_RELEASE_NOTES`) are written in `db/<version>/update.sql` from this file when a version is closed.

## 3.1.0 — in progress (branch `release/3.1.0`)

### Features
- RFID at the store (`rfid.enabled`, docs/modules/rfid.md): readers (Hopeland SDK, a fake reader for tests) and POS
  machines, one read at a time per reader, `GET /rfid/my-machine`; tags table, Tags and Tag assignment pages API, the tag
  button of a reception line; the till reads pieces into the cart (`POST /rfid/pos-read`), the tags go with the payment,
  the pending ticket and the return (`rfid_sale_tag`, `GET /rfid/ticket-tags`); a weak tag is below the reader's minimum
  signal; a pack holds no tag.
- Ticket X / ticket Z of a cashier session (`GET /cashier-session/{id}/report-ticket`) and of the store's day, every
  session (`GET /cashier-session/store-report-ticket/x` and `/z`); the cashier closing prints the ticket Z.
- Journal: the days of a month, a day's journal, Excel exports.
- Payment method "Retenue à la source" (type `RETENUE_SOURCE`), inactive by default.
- Item variants for ZEN (docs/variants-zen-analysis-2026-10-14.md): one item per ERP variant (code
  `itemNo~variantCode`) with its 14 variant fields; catégories and sections (the ERP's tree around families and
  sub-families); clearance and clearance price per point de stock row; an items run of changes only, with a checkpoint
  per point de stock; the head office copies carry catégories, sections and variant fields to the stores; ZEN's
  connector `connectors/zen` (`erp.connector=zen`, catalogue only, head office).
- NAV items page API (step 11a): `GET /nav-item`, `GET /nav-item/variants`, one line per NAV item number, the variant ×
  point de stock price grid at the head office, the stock only where the installation keeps its own.
- Catégories and Sections pages API (step 11b): `GET /item-category` (with the number of families),
  `GET /item-category/families?code`, `GET /item-section` (with the path catégorie › famille › sous-famille); GET only.

### Enhancements
- Promotions: a free piece only when scanned (buy N get M), repeated for each group of N + M pieces; the free pieces
  carry RFID tags; a return is whole per item.
- Stock adjustments accept decimal quantities when the store allows them; the till's search runs over the whole
  catalogue.
- `GET /config` gains `cashierClosingTicket`, `navItems`, `itemCategories`, `itemSections` (each read on each call).
- Session report: an undeclared payment method reads null.
- Item variants (step 11c): the return screen's ticket lookup and the return voucher lines give the item's
  `variantDescription` (`ReturnHeaderAPI`), so the till and the tickets show "name — variant" (null for the other
  customers: the name alone, as before). The sale lines already carried it through the item.

### Fixes
- The closing cash check compares cash with cash.
- The return's ticket lookup refuses a ticket that is not completed.
- The RFID Tags page list on SQL Server (Msg 402); a pending ticket paid with its tags no longer breaks
  `uk_rfid_sale_tag_header_epc`.
- A FREE_QUANTITY discount in the session report.

### DB script (`db/3.1.0/update.sql`, store and head office)
- RFID tables on a store: `rfid_reader` (with `min_signal`), `pos_machine`, `rfid_tag`, `rfid_sale_tag`.
- Payment method "Retenue à la source", inactive (kept as it is where EMTOP 1.12.1 has it).
- Item variants: 14 nullable columns of `item`, `item_family.category_code`, tables `item_category` and `item_section`,
  `ho_stock_point_item.clearance` and `clearance_price` (head office), index `ix_item_nav_item_no` (plain, not filtered).
- `APP_VERSION` 3.1.0; release notes to complete when the version is closed (RFID is missing).

### Config
- `rfid.enabled` (store, default false).
- `erp.connector=zen` and `erp.zen.*` (`base-url`, `company` or `company-id`, `domain`, `username`, `password`,
  `default-vat`, `packet-size`, `dry-run`, page names); deploy files `configs/customers/zen-headoffice.properties` and
  `zen-store.properties`.
- General setup `CASHIER_CLOSING_TICKET` (`Z`, the default, or `DECLARED`).

## 2.3.0 — 2026-10-11 (tag `v2.3.0`)

### Enhancements
- No functional change from 2.2.2: the store and the head office are two applications, each its WAR deployed as
  `zsretailws` (code cleanup, projects erp-api, connectors, core, store, headoffice, network-tests).

### DB script
- `APP_VERSION` 2.3.0 and its notes.

### Config
- The outside file names no type: remove `spring.profiles.active` and `node.type`; choose the ERP with
  `erp.connector`; rename `erp.dynamicsnav.*` to `erp.emtop.*` and `erp.navpospages.*` to `erp.happyness.*` (an old line
  stops the start, docs/deploy-note.md).

## 2.2.2 — 2026-10-10

### Features
- Returns with decimal quantities (when allowed), the amount the share of the line sold.
- Till: hide families and sub-families without an item to sell; a "Without sub-family" tile.
- Company information, "Sale ticket" block (address, footer text, thank-you line, "ZS Retail" mention).
- Store setting "Show amounts with 3 decimals on the POS screens".

### Enhancements
- An administrator is never asked for a badge at the till; at closing with a difference, the administrator sees the
  difference and confirms it.

### Fixes
- A fixed-amount promotion larger than the line no longer writes negative amounts; a decimal quantity typed when not
  allowed is refused with a message.

### DB script
- `return_line.quantity` and `ho_return_line.quantity` become DECIMAL(18,3); five nullable columns of the company
  information (the sale ticket's identity); `APP_VERSION` 2.2.2 (needs 2.2.1).

### Config
- General setup `POS_AMOUNTS_3_DECIMALS`, `POS_HIDE_EMPTY_FAMILIES` and `POS_HIDE_EMPTY_SUB_FAMILIES`, created at the
  first start, off.

## 2.2.1 — 2026-10-10

### Features
- Decimal quantities (up to 3 decimals) at the till, in the stock and its movements, the inventory count, the receptions
  and the head office's tickets, stock and ERP invoices.

### Enhancements
- The line amount of a decimal quantity is computed by the server (unit price with VAT × quantity, to the millime);
  pages show decimals untruncated; where decimals are not possible yet, a refusal names the item.

### Fixes
- Tax stamp: one rule for the till and the server.

### DB script
- The quantity columns (stock, sales lines, stock movements, head office copies, inventory counts, ERP invoices, BLs and
  receptions, purchase invoices) become DECIMAL(18,3), one transaction per column; `APP_VERSION` only when all are done.

### Config
- General setup `ALLOW_DECIMAL_QUANTITY`, created at the first start, off (off: as in 2.2.0).

## 2.2.0 — 2026-10-09

### Features
- Head office with the ERP catalogue: several points de stock (one ERP location each), read by the items job; each
  store receives only the items of its point, with their name, family and price there.

### Enhancements
- ERP invoices: a warning names the items not in the store's point de stock.

### DB script
- `ho_store.stock_point_id` and the last read columns of `ho_stock_point`; the tables `ho_stock_point` and
  `ho_stock_point_item` are created by Hibernate at the first start.

### Config
- `erp.navpospages.location-code`, `invoices.years` and `invoices.start-number` are no longer read (points created
  at the head office; the start number in General setup).

## 2.1.0 — 2026-10-08

### Features
- Head office: a new installation type that manages stores and never sells (stores, keys, their sales, returns and
  sessions); promotions and loyalty decided by the head office (one member register for the network); items and selling
  prices decided by the head office (base prices, price lists per store); supply by the head office (purchases, stock,
  BLs, reception at the store, the stock of every store); invoicing of deliveries to franchise stores; ERP invoices read
  from Business Central for a head office with the ERP catalogue.
- Inventory count imported from an Excel file.

### Enhancements
- A manual stock adjustment records its movement; editing an item no longer replaces its stock.

### DB script
- New nullable columns of existing tables; the `ho_*` (head office) and `hol_*` (store linked to one) tables are
  created by Hibernate at the first start; nothing dropped (the franchise columns stay, unused).

### Config
- One outside file per installation (database, port, log, ERP, head office address and key) and a preset (store, store
  ERP, head office, head office ERP, network store, network store ERP); the franchise profiles removed.

## 1.12.1 — 2026-10-12 (tag `v1.12.1`, hotfix of 1.12.0)

### Features
- Payment method "Retenue à la source" (`ENCAISS-RS-CLT`) for EMTOP.

## 1.12.0 — 2026-10-02

### Features
- Promotions: a new scope "All items", for an item no narrower promotion covers.
- Loyalty: earning tiers by ticket amount.

### Fixes
- Pending tickets earn and use loyalty points like a direct sale, with the tax stamp line and the loyalty block.
- Points conversion: the same ceiling at the till and on the server.
- Returns: only the points earned by the items returned are taken back, the points used are given back in proportion.

## Before 1.12.0

1.2.5 to 1.11.0: the changes are described in the release notes of each `db/<version>/update.sql`.
