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
- Fiches items, head office page API (step 1, the list): `GET /admin/headoffice/item-catalogue?page&size&search&status`
  on every head office, one line per NAV item number, else one per item; search on NAV number, item code, name and
  barcode; GET only, the other item pages and APIs unchanged.
- Fiches items follows the customer's data: `GET /admin/headoffice/item-catalogue/filters` (flags hasVariants,
  hasFamilies, hasDivision, hasCategory, hasSection, stockPointCount, hasBarcodes and the filter choices); the list
  filters on family, sub-family, division, catégorie, section, point de stock, has variants and price with VAT, sorts by
  code (natural for the codes of digits only), name or family, and gives each line its price range with VAT.
- Fiches items, step 2, the item card: `GET /admin/headoffice/item-catalogue/card?navItemNo|itemId[&search]`, one
  answer with the header, every variant (active or not) with its active barcodes and point de stock rows, the points,
  and the item form's read-only details; the variant whose code or barcode equals the search is highlighted.
- Item cards, step 3: the list's `status=INACTIVE` (the lines without an active variant); the card gives each variant's
  `minStockLevel` and, in its details, `minStockLevel` and `lastDirectCost` (only where the head office keeps its
  stock). Headoffice project only.
- Promotions by catégorie and section (ZEN): scopes `CATEGORY` and `SECTION`, targets `promotion.item_category_id` /
  `item_section_id`. Matched at ticket time on the item's codes (catégorie: the item's own code, else its famille's);
  order item > group > section > sous-famille > famille > catégorie > all items, each new branch only for an item
  with the code (no change where the codes are empty: EMTOP, Happyness). Cross-product buy side too. Save: each new
  scope needs its target, other scopes keep neither; both locked once used. Head office copy by code
  (`itemCategoryCode`, `itemSectionCode`), missing = waiting as a famille; a store of an older build skips such a
  promotion (error). `GET /item-category` and `GET /item-section` entries carry the row `id`.
- Promotions on every variant of a NAV item, step 1 (ZEN): an ITEM promotion targets one item row (`item`) or one NAV
  item number (`navItemNo`: every item carrying it, also variants synced later), an ITEM_GROUP holds item rows and/or
  NAV item numbers (`groupNavItemNos`); `getNavItemNo` (benefit on any variant) is stored but refused for now.
  Matched at ticket time: item on its row > item on its NAV number > its groups (rows and NAV numbers one level,
  priority decides), read only for an item carrying a NAV item number (EMTOP, Happyness: the same reads and answers);
  cross-product buy side too. Save: an ITEM never holds both, a NAV number only on ITEM, NAV numbers only on
  ITEM_GROUP, trimmed; a quantity promotion on a NAV target or with a NAV benefit is refused (400) until the till counts
  the lines of a NAV item together (step 2); the three locked once used. The head office copy and the screens follow
  (steps 1b and 3).
- Promotions on every variant of a NAV item, step 1b: `GET /item/search` items carry `navItemNo` (null without one);
  `GET /nav-item/names?navItemNo=..&navItemNo=..` (read only): `{number: name}` of the NAV item numbers asked (at most
  200, a number no item carries left out), the names of the promotions' NAV targets.
- Promotions on every variant of a NAV item, step 4: `GET /item/lookup?scope&q&page&size` (read only), the target lookup
  of the promotion form: scope ITEM, one list of ERP item lines (every active variant of an ERP item number, its
  lowest id and its number of variants) and item lines, by name and ERP item number, each ERP item line right above
  its own variants; search on code, name, ERP
  item number and barcode; packs and inactive items left out; FAMILY, SUBFAMILY, CATEGORY, SECTION: their active
  rows, with the parent's name. Without ERP item numbers: item lines only, the same statement.
- Promotions on every variant of a NAV item, step 3: the head office copy carries `navItemNo`, `getNavItemNo` and
  `groupNavItemNos` (sorted); the store saves them as they are, never waiting for an item carrying them (a group is
  missing only with none of its rows and no NAV number), and logs one INFO line for a number no item of the store
  carries yet. A copy without them (null / empty) compares equal to an existing promotion: nothing rewritten (EMTOP,
  Happyness). A store of an older build ignores the fields.
- Promotions on every variant of a NAV item, step 2, counted across the cart lines (rule 6a): `POST /price-calculate`
  takes `cartLines` (the cart in order) and answers `acrossLines`; the cart pass (`customerId`) adds `lineResults`, every
  line priced with the cart around it (a cart of one item: its line priced alone). Quantity promotions on NAV targets
  and a NAV free item are accepted; a benefit on both an item and an ERP item number is refused; buy NAV = get NAV is
  a same-item promotion. Cross-product:
  `getNavItemNo`, `freeByItemId`, `entitledByItemId` (cheapest first). Promo code: `navItemNo`, `groupNavItemNos`,
  `getNavItemNo`. Without `cartLines`, or a cart of one line: priced exactly as before.
- Till levels catégorie and section, step 1, API only (ZEN; docs/modules/head-office.md, "Till levels"): read-only POS
  lists `GET /item-category/pos`, `GET /item-family/pos?category={code}` and `?withoutCategory=true`,
  `GET /item-section/pos?subFamily={id}`, `GET /item/pos/by-section/{id}`, the fall-throughs
  `GET /item/pos/without-section/{subFamilyId}` and `GET /item/pos/without-family?category={code}`; a section holds the
  items of its own sous-famille that carry its code. `GET /item/pos/search` adds `categories` and `sections` and a `path`
  on every hit, only where the installation has the level: without catégories and sections (EMTOP, Happyness) every
  new list is empty and the search answer is unchanged.

### Enhancements
- Till authorisation, step B: `GET /config` serves `tillActions`, the registry of the five badge actions (`{code, key}`,
  `BadgePermission.key()` = the `till:*` role permission key of step C), empty on a head office; the frontend builds its
  badge lists, labels and filters from it.
- Promotions: a free piece only when scanned (buy N get M), repeated for each group of N + M pieces; the free pieces
  carry RFID tags; a return is whole per item.
- Stock adjustments accept decimal quantities when the store allows them; the till's search runs over the whole
  catalogue.
- `GET /config` gains `cashierClosingTicket`, `navItems`, `itemCategories`, `itemSections` (each read on each call).
- Session report: an undeclared payment method reads null.
- Till authorisation, step C: the till rights belong to the role, five permissions `till:consult-customer-list`,
  `till:make-return`, `till:apply-line-discount`, `till:apply-total-discount`, `till:close-session-with-discrepancy`
  (listed by `GET /app-roles/permissions`, sent with the login's `permissions` as the page permissions); a new store
  database gives the five to every role, a head office none. A badge is a credential only (code, expiry, revoked): it
  opens an action when its owner's role holds it, else `BADGE_NO_ACCESS`. The per-user badge list is no longer read or
  written (`badgePermissions` removed from the user DTO and `PUT /user-account/{id}`).
- Till authorisation, step C: on a store of before, at its first start, once (`TILL_RIGHTS_MIGRATED`), each role gets
  the rights every active user of the role passes today without a badge (an admin: the five; with
  `ALWAYS_SHOW_BADGE_SCAN_POPUP` on: nobody else; else his card's list with a valid badge, the close with a difference
  excepted); the role ADMIN gets the five; rights only added; the role × right matrix logged once. Nothing changes at
  the till on the day of the upgrade.
- Till authorisation, step C: every pass is logged in `badge_scan_log` with `granted_by`: a scan (BADGE) or a pass on
  the role's right (ROLE, no badge code, the owner is the logged user), the latter by `POST /badge/pass {action}` (403
  `TILL_RIGHT_REQUIRED` when the role does not hold it); `GET /badge/scan-history` filters `grantedBy` and its rows give
  it; the statistics count the scans only; a scan logs the scanning user's open session (the till's `sessionId` is no
  longer trusted).
- Till authorisation, step C: closing with a cash difference: the logged user whose role holds
  `till:close-session-with-discrepancy` confirms the difference (`discrepancyConfirmed`) whatever the role's name;
  anyone else brings a badge whose owner's role holds it (2.2.2: an admin by the role enum only, a badge by its list).
- Till authorisation, step D: the server checks the till rights on the requests that do the actions: each manual line
  discount and the manual header discount of `process-sale`, `save-pending`, `update-pending/{id}`,
  `complete-pending/{id}`, the return (`process-return`) and the close with a cash difference. The role's right passes
  (logged ROLE once per request); else the request carries the grant of a badge scan (`tillGrant`, per line, header,
  return, close), used once (logged BADGE with the badge's owner); else 403 `{error: TILL_RIGHT_REQUIRED, action,
  reason, lineIndex}` and nothing of the request is kept. A pending ticket keeps the discounts it held (not larger).
  Promotions, price lists and lines without a source need nothing; the split payment and the head office unchanged.
- Till authorisation, step D: `POST /badge/scan` gives a grant for the actions checked on their request (`grant: {token,
  action, badgeOwnerId, issuedAt, expiresAt}`): one action, the scanning user's open session, single use, good for
  `TILL_GRANT_MAX_MINUTES` (240); only its SHA-256 is stored. Such a scan's success is logged when the grant is used,
  no longer at the scan. `GET /config tillActions` gives `checkedOnRequest`. The close takes `tillGrant` instead of
  `badgeCode` and `badgePermission`.
- Step E: the enum `Role` is retired; a user's role is his AppRole and an administrator the user of the system role
  named ADMIN (`SystemRoles`; the ERP admin pages and the rule giving or removing the ADMIN role use it). The login,
  `UserAccountDTO`, `CreateUserRequestDTO` and `GET /user-account/cashiers` no longer carry `role`; `PUT
  /user-account/{id}` no longer reads it; creating a user needs `appRoleId`. Users of before without an AppRole get the
  role named as their enum value at the start (as before, now by JDBC).
- Till rights, discount sources: every discount of a sale or pending request needs the right (or a grant) unless the
  server verifies its source: PROMOTION with an existing active promotion, SALES_PRICE / SALES_DISCOUNT with the item's
  price list discount for the customer (not a larger percentage). A source missing, unknown or not verified counts as
  MANUAL (403 `NO_GRANT` without the right). `save-pending` and `update-pending/{id}` now store each line's and the
  header's discount source and promotion, so a recalled ticket keeps them; a ticket parked before counts as MANUAL and
  passes on the discounts it held.
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
- Fiches items card (`GET /admin/headoffice/item-catalogue/card`): `storeStocks`, the stock each active store last sent
  for the card's item codes (`ho_store_stock`, one query, by store code then item code), and `header.withStoreStocks`;
  empty where no store sends its stock. Parameters and the other fields unchanged.
- Fiches items list (`GET /admin/headoffice/item-catalogue`): `sort=variants` (the line's variant count) and
  `sort=price` (its smallest price with VAT, lines without a price last both ways), each then by code.
- Network stock (`GET /admin/headoffice/stock`): `stock=ALL|IN_STOCK|ZERO|BELOW_ZERO` on the columns shown (the head
  office's stock where it keeps one, and the stores shown; `belowZero=true` still means `BELOW_ZERO`), and the item
  filters `familyId`, `subFamilyId`, `divisionCode`, `categoryCode`, `sectionCode`; native SQL built with only the
  filters set (`HoNetworkStockQuery`).
- Network stock, step 2: `GET /admin/headoffice/stock` sorts on `code` (natural, the default), `name`, `headOffice`,
  `store-<id>` (items the store never sent last) and `total` (the active stores; with 2 stores shown or more), `dir`;
  rows give `itemId`, `navItemNo` and `totalStores`, the answer `withTotal`. `GET .../stock/own`: `stock`
  (IN_STOCK, ZERO, BELOW_ZERO on the row's quantity), `sort` (store, code, name, quantity, storeTime, receivedAt), `dir`;
  default order store code then item code (was store id).

### Fixes
- ERP jobs no longer stop for good on a NAV call that never answers (Emtop 1.12.1, 2026-10-22: Import Customers waited
  on NAV from 14:18 and no job ran by itself again, only "Run now"): the emtop connector's client has a connect timeout
  (30 s) and a read timeout (600 s of silence, not the length of a read), like the happyness and zen connectors.
- Promotions, group of items (Emtop 1.12.1: a group saved with one item): the back end no longer depends on what the
  screen sends. `groupItems` is read by distinct id (`{ id }`, `{ id, itemCode }` or a full item give the same group)
  and `PromotionService.save` loads those ids from the database; an unknown id or an item without an id is refused.
  `Item` equality is unchanged.
- Quantity promotions count the whole pieces of every cart line of their target (rule 6a, every customer, decided
  2026-10-23): a minimum quantity or a "buy N get M free" on a famille, sous-famille, section, catégorie, group, NAV
  item or all items read each line alone (2 shirts of one famille in two lines never reached "buy 2"); the cross-product
  buy side summed each line's floor (1 + 1 pieces never reached N = 2). Level by level (row, NAV item, group, section,
  sous-famille, famille, catégorie, all items), each level counting only the lines no earlier level took; free pieces
  cheapest first, then cart order; a free promotion giving a free piece takes every line it counts. A cart of one line
  is priced as before. Check EMTOP and Happyness quantity promotions on other scopes than ITEM before deploying.
- `Item` equality, root cause of the group saved with one item (Emtop 1.12.1): two items are equal when they have the
  same id (read through `getId()`, a Hibernate proxy equals its item), an item without an id only to itself, one hash
  for every item; Lombok's equality left the id out, so items with only an id were all equal. The other maps keyed by
  an item hold saved rows (invoice and purchase invoice lines grouped by item, head office supply prices): unchanged.
- Promotions page of a store (`POST /promotion`, `PUT /promotion/{id}`): a promotion refused by the save rules (a group
  without items, a catégorie or section promotion without its target, the NAV targets' rules) answers 400 with the
  reason, as the head office does; it answered 500.
- Fiches items list (`GET /admin/headoffice/item-catalogue`): failed on SQL Server ("duplicated sql alias []", the page
  showed no item): every column of its native queries now has its own alias.
- Badge scan history: the statistics count every row of the period, the passes on the role's right (ROLE) with the
  badge scans (BADGE), as the list shows them (the tiles said 19 for a list of 20).
- Batch 3 (test log 2026-10-17): a scan of a known barcode whose item is inactive answers 404 with `inactive: true`
  (the till says "Inactive item"), and an inactive item is never found by the scan (I-04); points removed on a return are
  rounded down as the points earned, the money returned at the sale's rate (24.657 TND returned removes 24, was 25), a
  tiered program still removing what the money kept loses to a lower tier (I-57); the head office ticket and return
  details, network stock and delivery note lines give the item's `variantDescription` when the head office item has one
  (I-24); the sales history list gives `loyaltyCardNumber` (I-54); `GET /promotion/paginated` takes `active`
  (true / false, none: all).
- Invoices (test log I-71): the invoice header's and lines' amounts (HT, VAT, discount, TTC) are stored to the millime,
  half up, on every save, as the tickets (I-07); the header is the sum of the rounded lines. Unit prices unchanged;
  invoices stored before are not corrected.
- Sales report (test log I-67 re-test): the report answered 500 in every grouping since the points fix (HQL
  `unexpected token: /`: Hibernate 5 takes a single term after `THEN`); the points share is in parentheses.
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
- Security (step S): the `/user` controller is removed (`UseAccountAPI`: generic CRUD on the raw `UserAccount`, password
  hash included, and `PUT /user/{id}/role`, which set the role enum without the I-21 check), and so are the unlogged
  badge lookups `GET /badge/by-badge/{code}`, `POST /badge/check-permission`, `GET /user-account/by-badge/{code}`,
  `GET` and `PUT /user-account/{id}/badge`; no caller in the front.
- Security (step S): the badge scan history reads (`/badge/scan-history`, `/badge/{code}/scan-history`,
  `/badge/scan-statistics`) answer 403 unless the role holds `read:admin-badge-scan-history` (any logged user read them),
  and give flat rows (`BadgeScanLogDTO`, the fields the page reads) instead of the entity, which carried both users'
  `UserAccount` (password hash, badge code): the "Scanned by" and "Badge owner" columns are filled again; no badge code
  in the rows nor in the statistics (`topBadges` removed), which give `failedScans` (the "Failed" card showed 0).
- Security (step S): the badge of a deactivated user is refused: at a scan `BADGE_OWNER_INACTIVE` (logged like the
  other refusals), at the close with a cash difference as an unknown badge. It opened every action before.
- Security (step S): badge codes are unique in the database (filtered index `ux_user_account_badge_code`, created by
  the script, or at the start of a new database by `UserBadgeCodeIndex`); a generated code takes its 4 digits from
  `SecureRandom` (was `java.util.Random`); a new database's responsible gets a generated badge code, logged once at the
  first start, instead of the fixed `RESP-BADGE-001` written in the source.
### DB script (`db/3.1.0/update.sql`, store and head office)
- RFID tables on a store: `rfid_reader` (with `min_signal`), `pos_machine`, `rfid_tag`, `rfid_sale_tag`.
- Payment method "Retenue à la source", inactive (kept as it is where EMTOP 1.12.1 has it).
- Promotions by catégorie and section: nullable `promotion.item_category_id` and `item_section_id`, their foreign keys
  (only when the column has none) and indexes, store and head office; re-runnable, no data changed.
- Promotions on every variant of a NAV item: nullable `promotion.nav_item_no` and `get_nav_item_no` (varchar(255), as
  `item.nav_item_no`), index `idx_promotion_nav_item_no`, table `promotion_group_nav_item` (`promotion_id`,
  `nav_item_no`, primary key both, foreign key to `promotion`) with index `idx_promo_group_nav_item_nav`; store and head
  office, re-runnable, no data changed.
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
- Security (step S): filtered unique index `ux_user_account_badge_code` (`user_account.badge_code`, not null), store
  and head office; skipped with a warning when two users already share a code; re-runnable, no data changed.
- Till authorisation, step C: `badge_scan_log.granted_by` (varchar(10), nullable; the rows of before set to `BADGE`)
  and `scanned_badge_code` nullable, store and head office, re-runnable (also at each start, `TillSchemaUpgrade`). The
  till rights are given to the roles by the first start, not by the script (it must read `ALWAYS_SHOW_BADGE_SCAN_POPUP`
  first). `user_account.badge_permissions` is kept, unused (deprecated, dropped in the next version).
- Till authorisation, step D: table `till_grant` on a store (the grants of the badge scans: token SHA-256, action,
  session, user who scanned, owner, badge code, issued, expires, consumed, by what); re-runnable, no data changed.
- Step E: `user_account.role` nullable (its type kept; also at each start, `TillSchemaUpgrade`), kept unused
  (deprecated, dropped in the next version); the users without an AppRole given the role of their enum's name. Store
  and head office, re-runnable.
- Discount sources of pending tickets: `sales_line` / `sales_header` `.discount_source` and `.promotion_id` made sure
  of (they exist already); nothing filled in, the pending tickets of before keep nulls. Re-runnable.
- `APP_VERSION` 3.1.0; release notes to complete when the version is closed (RFID is missing).

### Config
- `rfid.enabled` (store, default false).
- `erp.connector=zen` and `erp.zen.*` (`base-url`, `company` or `company-id`, `domain`, `username`, `password`,
  `default-vat`, `packet-size`, `dry-run`, page names); deploy files `configs/customers/zen-headoffice.properties` and
  `zen-store.properties`.
- `erp.zen.page.barcodes` defaults to `barCodes` (step 12a; was `ItemBarCodePOS`).
- `headoffice.copies-down.read-timeout-seconds` (store, default 60): the read timeout of the pull of copies down.
- `erp.emtop.connect-timeout-seconds` (default 30) and `erp.emtop.read-timeout-seconds` (default 600): the emtop
  connector's NAV timeouts.
- General setup `CASHIER_CLOSING_TICKET` (`Z`, the default, or `DECLARED`).
- General setup `ALWAYS_SHOW_BADGE_SCAN_POPUP` removed (till authorisation, step C): no longer created; on a store its
  row is read once by the till rights migration, then deleted. `TILL_RIGHTS_MIGRATED` (read only): written once by that
  migration, with its date and the role × right matrix.
- General setup `TILL_GRANT_MAX_MINUTES` (store, default 240): the minutes a badge scan's grant stays good when no
  request used it (till authorisation, step D).

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
