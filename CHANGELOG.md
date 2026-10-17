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
- Item variants (step 12a): ZEN's connector reads ZEN's real barcodes page `barCodes` (`itemNo`, `variantCode`,
  `barCode`, `lastModifiedDateTime`, `id`) instead of Happyness's `ItemBarCodePOS`; the barcode cursor is
  `lastModifiedDateTime` in epoch milliseconds (NAV's empty date: 1), a full page completed with every row of its last
  millisecond so that none is skipped and the run ends caught up; a barcode changed in NAV is read again.
- Item variants (ZEN), clearance at the till: a point de stock row with clearance true and a clearance price above 0
  sells at its clearance price (before VAT, the item's VAT); the head office sends it as the item's price to the stores
  of the point (`HoCatalogueService.pointCopy`, one rule `StockPointPrice`), with `clearance` and `publicPrice` for the
  till's display; a clearance change reaches the stores as any row change. The head office pages give
  `clearanceApplies` and `sellingPrice`. EMTOP and Happyness unchanged (no clearance).
- ERP items page at a store: `GET /nav-item/variants` gives each variant's `clearance`, `publicPrice` and `ownPrice` as
  the till has them (null at a head office and for the other customers), so the page strikes the public price as the
  till does.
- Item variants (ZEN): the connector builds the variant description from the size, colour and choice descriptions
  ("Taille Standard / VERT PISTACHE / 1er Choix", the blank ones left out) and no longer reads NAV's
  `variantDescription` (always the item description on ZEN's platform); a change of one of them changes the item. The
  item name stays NAV's item description.

### Fixes
- Stock report: a negative stock has its own status `NEGATIVE` (was `OUT`, test log I-52). Statistics: a KPI delta is
  null, no comparison, when the previous period is 0 (was 0.00, test log I-51).
- Sales report (test log I-67): the loyalty points spent are taken off as the ticket discount, in every grouping, so the
  report adds up to the tickets' paid total; the points share is in the discount column.
- Invoice made from tickets (test log I-63): a ticket with a ticket discount, a cart promotion or points spent adds one
  "Remise ticket <number>" line (HT, VAT, TTC negative), so the invoice adds up to what was paid; the ticket's lines are
  unchanged and a ticket without a reduction gives the invoice of before.
- Head office BL sent with items without a supply price (test log I-35): the validation's answer lists them
  (`missingSupplyPrices`) and logs one WARN line; the BL is sent as before (their supply invoice, and so the store's cost,
  wait for a price).
- Reports grouped by family (test log I-66): the sales and purchase reports count the items without a family, under
  "—" (they were left out).
- Statistics (test log I-49, I-50): "Top 5 products" counts the items without a family (family "—"); the payment split's
  cash is net of the change given back, as the ticket Z (it counted the cash tendered).
- A return's total and a session's expected cash to the millime (test log I-07): `total_return_amount` and `real_cash`
  stored rounded half up; the closing cash check compares the rounded expected cash.
- Return vouchers to the millime (test log I-07): `voucher_amount` and the amount used are stored rounded half up, and a
  voucher payment debits the voucher with the payment's rounded amount (remaining and "fully used" read on the millimes),
  so the voucher and its payment carry the same value.
- Ticket amounts to the millime (test log I-07): a ticket's amounts, its lines' amounts and its payments are stored
  rounded to 3 decimals, half up, on every save (`roundAmounts` of `SalesHeader`, `SalesLine`, `Payment`; unit prices and
  percentages untouched); paid vs total is checked on the rounded values with the till's tolerance of 0.010 (a sale more
  short is refused, before nothing was checked) and the change is rounded paid − rounded total (no more 0.0002). Tickets
  stored before are not corrected; vouchers and the session's real cash are not covered.
- Ticket HT and VAT after a ticket-wide reduction (test log I-48): a ticket with a ticket discount, a cart promotion or
  loyalty points spent stores the HT and VAT of what was paid, spread over the VAT rates in proportion to the lines'
  TTC as the ticket X / Z spreads them (`SalesHeaderService.applyTicketReductionToTotals`), so HT + VAT + stamp = total
  on the receipt, the ticket and the head office copy (85.252 = 71.640 + 13.612, was 79.600 + 15.124). The lines keep
  their amounts before the reduction; a ticket without one keeps the till's values. The ERP ticket export takes its
  gross from the lines, so NAV receives the same discount % as before. Tickets stored before are not corrected.
- Users (`/user-account`, test log I-21): nobody changes the role of his own account, and only an administrator gives
  or removes the Administrateur role, on an edit or a new user; a refused change answers 403 with the reason in French
  (`RoleChangeRefusedException`). A responsible could make himself Administrateur.
- The closing cash check compares cash with cash.
- The return's ticket lookup refuses a ticket that is not completed.
- The RFID Tags page list on SQL Server (Msg 402); a pending ticket paid with its tags no longer breaks
  `uk_rfid_sale_tag_header_epc`.
- A FREE_QUANTITY discount in the session report.
- ZEN's barcodes (step 12a): the run no longer ends on "rows at or below the cursor". NAV keeps the dates in SQL
  datetime (1/300 s) and rounded the filter's cursor + 1 ms back to the cursor; the filters are now
  `lastModifiedDateTime gt <cursor>` and, to complete a full page, `eq <its last time>`.
- Head office link (store): after a "Run now" the job's next run comes from the end of that run, 5 s later when it has
  more to do (e.g. the catalogue copies down by cycles of 1,000), else its frequency; it stayed at the time the
  schedule had computed before (30 min for the copies), also when the page stopped waiting after 30 s.
- Speed with a large catalogue (ZEN's test head office, 21,903 items): the page "Items by point de stock" without a
  search no longer reads every item's barcodes (its query took minutes; the barcode search is only in the query of a
  search); the store reads the tracking rows of a page of copies down in one query, not one per record (each read
  scanned `hol_down_record`); a page of barcodes is read with its items in one query; a store's pull reads its changes
  in the new index alone (the domain cast to `varchar`). Same answers and rows as before.
- Speed of the copies down, barcode phase (store): each barcode received read the old `item.barcode` field twice, a
  scan of `item` each time (3–5 s per page of 100 on 21,904 items); now one read, on an index.

- Head office link (store), copies down: a slow head office no longer stops the catalogue catch-up for 30 min. The
  pull has its own read timeout (60 s, `headoffice.copies-down.read-timeout-seconds`; heartbeat and pushes keep
  10 s); a read timeout is `TIMEOUT` "head office answered too slowly (read timeout 60 s)", not OFFLINE; a run that
  failed on a timeout or an I/O error runs again 10 s later, up to 3 times ("retry in 10 s (1/3)"), the 4th back to
  the frequency with one WARN line; a failed pull is logged at WARN (was INFO).
- ERP catalogue, items step (speed): a packet of items reads its existing items in one query by ERP id and one by
  code, the families and sub-families once, instead of up to five SELECTs per row (each scanning `item`, which has no
  index on `erp_external_id`, and flushing the packet); the item and point de stock row updates go by JDBC batches of
  100 (that transaction only). One INFO line per items step (rows read, equal, new, changed, written, ms) and per
  packet. Unchanged rows were already never written (the engine compares every field).
- ERP catalogue run: a page read that fails on an I/O error or a timeout (e.g. a VPN drop) is read again once 5 s
  later, with one WARN line, for every connector; a second failure fails the step as before; cursors and
  checkpoints move only on a read that succeeds.
### DB script (`db/3.1.0/update.sql`, store and head office)
- RFID tables on a store: `rfid_reader` (with `min_signal`), `pos_machine`, `rfid_tag`, `rfid_sale_tag`.
- Payment method "Retenue à la source", inactive (kept as it is where EMTOP 1.12.1 has it).
- Item variants: 14 nullable columns of `item`, `item_family.category_code`, tables `item_category` and `item_section`,
  `ho_stock_point_item.clearance` and `clearance_price` (head office), index `ix_item_nav_item_no` (plain, not filtered).
- Item variants (ZEN): `item.variant_description` rebuilt from `size_description`, `color_description`,
  `choice_description` (" / ", the blank ones left out, null when none) for the items with a `nav_item_no`;
  re-runnable.
- Clearance at the till (ZEN): nullable `item.clearance` (bit) and `item.public_price` (float), store and head office
  (written on a store only, by the copies down); re-runnable, no data changed.
- Speed, the ERP import: indexes `ix_item_erp_external_id` (`item.erp_external_id`) and
  `ix_item_barcode_erp_external_id` (`item_barcode.erp_external_id`), store and head office; re-runnable.
- Speed: indexes `ix_item_barcode_item` (`item_barcode.item_id`) and `ix_item_barcode` (`item.barcode`), store and
  head office; on a head office
  `ix_ho_down_change_pull` (`domain`, `change_version`, `store_id`, `record_code`) replaces `ix_ho_down_change_version`.
  Re-runnable, no data changed.
- `APP_VERSION` 3.1.0; release notes to complete when the version is closed (RFID is missing).

### Config
- `rfid.enabled` (store, default false).
- `erp.connector=zen` and `erp.zen.*` (`base-url`, `company` or `company-id`, `domain`, `username`, `password`,
  `default-vat`, `packet-size`, `dry-run`, page names); deploy files `configs/customers/zen-headoffice.properties` and
  `zen-store.properties`.
- `erp.zen.page.barcodes` defaults to `barCodes` (step 12a; was `ItemBarCodePOS`).
- `headoffice.copies-down.read-timeout-seconds` (store, default 60): the read timeout of the pull of copies down.
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
