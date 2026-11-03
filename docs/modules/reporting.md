# Reporting Module

**Status**: ✅ Complete

**Overview:**
- 7 admin report pages accessible under the Reports menu (ADMIN role): Sales, Purchases, Stock, Stock Movements, Loyalty, Sessions, Promotions.
- Each report page fetches aggregated data from backend, displays summary cards + chart + paginated table.
- Configurable chart type (bar/line/area), sort order (value ↓ ↑, label A→Z Z→A), and pagination (per-page: 10/25/50/100).

### Backend — `ReportService.java` (stock reports) and `SalesReportService.java` (the store's reports)

Code cleanup step 5: the stock and stock-movement reports (a head office shows them too) stay in `ReportService` / `ReportAPI`; the sales, purchase, loyalty, promotion and session reports are the store's, `SalesReportService` / `SalesReportAPI`, under the same path `report/`. The value helpers are `ReportValues`.

**Methods:**
- `getSalesReport(dateFrom, dateTo, groupBy)` — aggregates sales headers by DAY/MONTH/YEAR; returns `SalesReportRowDTO { label, totalHt, totalTtc, nbTickets }`
- `getPurchaseReport(dateFrom, dateTo, groupBy)` — aggregates purchase headers; returns `PurchaseReportRowDTO { label, totalTtc, nbPurchases }`
- `getStockReport(groupBy, filter, date)` (3.1.2) — the stock now (no date, or today) or at the end of a past day, per item, family, sub-family, category or section, with the filters of `StockReportFilter`; `getStockReport(groupBy, belowMinStock[, date])` delegate to it. Returns `StockReportRowDTO { groupLabel, itemCode, currentQty, minStockLevel, stockValue, stockValueTtc, sellingValue, sellingValueTtc, status, costPrice, costPriceTtc, unitPrice, unitPriceTtc, itemsWithoutCost, itemsWithoutPrice, itemsAtCurrentCmp, itemCount, itemsLow, itemsOut, familyName, subFamilyName }` (the prices and the two names on an item row only; the first three counts absent when none, the other three always sent). See "Stock report" below.
- `getStockMovementsReport(dateFrom, dateTo, groupBy, filter)` (3.1.2 rebuild) — the movements of a period per item, family, sub-family, category, section, type, day or month, with the filters of `StockMovementReportFilter`; `getStockMovementsReport(dateFrom, dateTo, groupBy, movementType)` delegates to it. Returns `StockMovementReportRowDTO { groupLabel, qtyIn, qtyOut, netQty, nbMovements, itemCount, itemCode, familyName }` (the last two on an item row only). See "Stock movements report" below.
- `getLoyaltyReport(dateFrom, dateTo, groupBy)` — loyalty points earned/redeemed; returns `LoyaltyReportRowDTO { label, pointsEarned, pointsRedeemed }`
- `getSessionReport(dateFrom, dateTo, groupBy)` — session totals; returns `SessionReportRowDTO { label, totalAmount, nbSessions }`
- `getPromotionReport(dateFrom, dateTo)` — promotion performance; returns `PromotionReportRowDTO { promotionCode, promotionName, promotionType, nbTickets, totalDiscount, revenueInfluenced }`

**Rows of `stock_movement`** (without an ERP only; one row per stock change, never updated): `SALE`, `CUSTOMER_RETURN_SIMPLE`, `CUSTOMER_RETURN_VOUCHER`, `PURCHASE_RECEPTION`, and since step 7A `ADJUSTMENT_IN` / `ADJUSTMENT_OUT`: `POST /item/{id}/adjust-stock` (`ItemService.adjustStock`) writes the quantity as before and its movement in the same transaction (quantity = the absolute delta, `reference_type` `ADJUSTMENT`, the reason `COUNT`, `CORRECTION` or `DAMAGE` as note). Before step 7A an adjustment wrote no movement. Since 2.1.0 the validation of an inventory count writes `INVENTORY_IN` / `INVENTORY_OUT` (one per item whose counted quantity differs from the stock; quantity = the absolute difference, `reference_type` `INVENTORY`, `reference_id` the `inventory_count` id, its number as note; `modules/inventory-count.md`). The report groups and filters by the type's name, so both appear without a change in `ReportService`; the frontend type filter of `StockMovementsReport.vue` lists them with translated labels ("Inventory count (up)" / "(down)"). Since 3.1.2 `OPENING_STOCK` (reference type `OPENING_STOCK`, direction `IN`, `OUT` for a negative stock) is the stock an item starts with: `POST /item` with a stock (`ItemService.create`, note "Création de l'article", the user) and a new item of the Excel import (note "Import Excel", created by `Import`); the import writes an existing item's change of stock as `ADJUSTMENT_IN` / `ADJUSTMENT_OUT` (note "Import Excel"; `modules/data-import.md`). Before 3.1.2 both set the stock with no movement.

**Statistics page (`AnalyticsRepository`, `GET /admin/analytics/...`), 3.1.0:**
- Top products (test log I-49): items with or without a family (`LEFT JOIN item_family`), family "—" when none; the
  inner join gave no row at all on a catalogue without families (ZEN).
- Payment split (test log I-50): the cash method is net of the change given back, as the ticket Z
  (`SessionReportService.paymentsByMethod`): Σ cash payments − the change of each ticket with a cash payment, once per
  ticket. It counted the cash tendered (371.347 instead of 347.167 on `zen_store`, 12 tickets).
- The sales and purchase reports grouped by FAMILY (`SalesReportService`, test log I-66) count the items without a
  family too, under "—" (`LEFT JOIN i.itemFamily f`; the inner join left them out, and its "No Family" label never
  applied). Their totals now equal the ITEM grouping's.
- The sales report (every grouping) takes off the ticket discount (lines × (1 − header %)) and, since 3.1.0 (test log
  I-67), the loyalty points spent the same way: each line × total / (total + points) of its ticket, 1 without points;
  the points share is in the discount column with the ticket discount. Its total is the tickets' paid total (507.498 on
  `zen_store`, was 509.598).

- Stock report status (`ReportService.stockStatus`, 3.1.0, test log I-52): `NEGATIVE` below 0 (a data problem: a
  missing BL, a wrong count), `OUT` at 0, `LOW` below the minimum, else `OK`; the page shows a negative quantity in red,
  and its "Out of stock" card counted the items at 0 only (3.1.2 rebuild: "Rupture / négatif", `itemsOut`, both).
- **Stock report** (3.1.2, `GET /report/stock`, `ReportService.getStockReport`; the page's "Valeur au", today by
  default). **One path in Java for now and a past date** (the rebuild of 3.1.2): the items query `STOCK_ITEMS` with the
  filters, the rows grouped in Java in its order. Now: qty = the stock now, CMP = `costPrice`, one query, no movement
  nor history read (before the rebuild: one aggregate query per grouping plus `markWithoutCost`, two; the rows, labels
  and order are the same, `StockValueAtDateTest.today`). One rule now and at a past date: **an item without cost** (no
  CMP and a stock other than 0) has no value (`stockValue` null, "—" on the page, an empty Excel cell; before 3.1.2 it
  counted 0) and `itemsWithoutCost` 1; a group row counts its items without cost, its value the sum of the others; the
  page marks the total incomplete with their count. A stock of 0 is worth 0, cost or not. Every active item the filters
  keep is listed, a stock of 0 included.
  - **Parameters**, all optional: `groupBy` `ITEM` (default) | `FAMILY` | `SUBFAMILY` | `CATEGORY` | `SECTION`;
    `date` (below); `search` (the item's code or name contains it, case ignored, `%` `_` `[` searched as themselves,
    `PosItemSearchRepository.escapeLike`); `familyId`; `subFamilyId`; `categoryCode`; `sectionCode`; `status`, a list
    (`status=LOW,OUT`) of `OK`, `LOW`, `OUT`, `NEGATIVE`, `WITHOUT_COST` (no CMP and a stock other than 0), an item kept
    when **any** applies, 400 for another value; `belowMinStock=true` adds `LOW`, `OUT`, `NEGATIVE`. Blank values are
    none. The filters on the item are in the items query's WHERE; the status, which needs qty at the date, is checked
    in Java on **the item's own status before the grouping**: a family row filtered on `LOW` sums only its low items.
  - **`belowMinStock` changed meaning (3.1.2 rebuild)**: before, a row was kept when its qty was below its minimum, for
    a family row its summed qty below its summed minimum; now it is the item's status `LOW`, `OUT` or `NEGATIVE`, so an
    item at 0 with no minimum is kept (it was not), and a family row holds only its items below the minimum.
  - **Groupings**: a family / sub-family by its name ("No Family", "No Subfamily"); a category / section by its code,
    labelled with the name of its `item_category` / `item_section` row (entity join on the unique code), the code when
    no row has it, "No Category" / "No Section" without a code (a blank code is none); ordered by that name. The group
    row's status from its summed qty and minimum, as before.
  - **Values without VAT (HT) and with VAT (Ttc)**: every unsuffixed price or value is HT, as in `Item` (`costPrice`
    the CMP and `unitPrice` the selling price are both before VAT). `stockValue` = qty x CMP, `stockValueTtc` = qty x
    CMP x (1 + VAT/100); `sellingValue` = qty x `Item.unitPrice`, `sellingValueTtc` the same with VAT. **The VAT** is
    the item's `defaultVAT`; none: 0, the till's rule (`ItemAPI` sends 0 and the till sells the item at its HT price;
    the application has no default VAT of its own, only the connectors' `erp.<name>.default-vat` for the items they
    create). An item row also carries `costPrice` (the CMP the value used: at a past date the CMP of that day),
    `costPriceTtc`, `unitPrice`, `unitPriceTtc`; a group row none of them, its four values the sums of its items.
    **The selling values use today's price, even at a past date** (no price history). Without a CMP and with a stock:
    both cost values null; without a `unitPrice` and with a stock: both selling values null, the item counted in
    `itemsWithoutPrice`; each left out of its group's sums. **Rounding**: computed per item in BigDecimal, the row's
    values (and an item row's two Ttc prices) rounded once at the end to 3 decimals, half up; the prices sent as stored.
  - **Counts and names**: every row sends `itemCount` (its items the filters kept; an item row 1), `itemsLow` (status
    `LOW`) and `itemsOut` (`OUT` or `NEGATIVE`), each item by its own status at the date, so the page's totals add up in
    every grouping (a group row's status, from its sums, can be `OK` with low items in it). An item row also sends
    `familyName` and `subFamilyName` (null when none). Same loop, no new query.
  - **Now** (no date, or today's): one query.
  - **A past day D** (before today; 400 after today, 400 where the installation keeps no stock of its own): at the end
    of D (movements and cost changes from D + 1 at 00:00 are after). **qty(D)** = the stock now minus the movements
    after D (IN minus OUT, `MOVEMENTS_AFTER`, one query grouped by item and direction). **CMP(D)** = the new cost of the
    item's last change up to D (`COSTS_BEFORE`), else the old cost of its first change after D (`COSTS_FROM`; null
    when that change gave the item its first cost), else its CMP now, counted in `itemsAtCurrentCmp` (the page's note
    "n article(s) valorisé(s) au CMP actuel"). **Value** = qty(D) x CMP(D). The rows grouped as now; the status filter
    on qty(D) and CMP(D).
  - **Queries**: now one; at a past date four, whatever the number of items and the filters (the movements and the
    history are read for every item, the filters apply to the items query): the active items, the movements after D (index
    `ix_stock_movement_created` `(created_at) INCLUDE (item_id, direction, quantity)`, `db/3.1.2/update.sql`), the two
    ends of the cost history (`MAX(id)` / `MIN(id)` per item on either side of D, index `ix_item_cost_history_item`
    `(item_id, changed_at)`; the ids follow the time of the changes). Estimate for 12,000 items and a year of
    movements (about a million rows) on a date a year back: the movements range is a covering index read of the whole
    year, the heaviest part (under a second), the items and the history a few hundred milliseconds: about 1 s; a recent
    date reads only its few days of movements.
  - **Limits**: the stock before 3.1.2 counts back from the movements that exist: the import and `POST /item` wrote
    none before 3.1.2 (`OPENING_STOCK` since), so a date before an item's import or creation may show its stock then
    as negative or off. The cost history starts empty at 3.1.2: a day before it takes the old cost of the item's first
    change, else its CMP now (the note counts them). Split bills write their movements as any sale (3.1.2).
  - Tests: `StockValueAtDateTest` (the queries on H2: the three CMP rules, qty(D) at the day's edges, the families,
    today's rows, labels and order; the rebuild: the Ttc and selling values and their rounding, CATEGORY at D and
    SECTION now, the filters in both paths, the status before the grouping), `ReportStockDateTest` (the 400s, the
    filter handed, an unknown status), `DecimalSafetyNetTest` (the decimal stock), `tools/stock-report-check.mjs` (the
    page's rules).
  - **The page** (3.1.2 rebuild, `views/admin/reports/StockReport.vue`, same route, permission and menu entry; no chart,
    no "Load report" button): `AdminListPage` (the admin banner, subtitle "Stock actuel" or "Stock au jj/mm/aaaa" of
    the rows shown; the collapsible filters; the table card). Filters as Item cards: search (debounced 350 ms),
    catégorie › famille › sous-famille › section with the choices of `GET /item-catalogue/filters` on a store,
    `GET /admin/headoffice/item-catalogue/filters` on a head office (read once; catégorie and section only where the
    installation has the level, `levelFilters`; famille only with `hasFamilies`), status (any of the five; "Sans
    coût" only with the right below), the date where the installation keeps its own stock (max today). Each change
    reloads (an older answer dropped) and is kept in the address (`q`, `family`, `subFamily`, `category`, `section`,
    `status`, `date`, `groupBy`). Grouping buttons Article / Famille / Sous-famille / Catégorie / Section (the last two
    as their filters). Totals strip: the sums of every row loaded (`itemCount`, `itemsLow`, `itemsOut`, the four
    values, the three counts). Table: every column sortable, 50 a page; "—" with its reason for a null value. **The CMP
    and the cost values** (columns, totals, the "Sans coût" status, Excel) only with `read:admin-margins`, in the page
    only: the API sends them to every role, as the project's rule (no role check in the API). Excel: every row, the
    columns of the grouping, empty cells for "—", `stock-<date>.xlsx`. Print: every row in a print-only table rendered
    while printing, without filters, pages or buttons. Rules in `libs/stock-report.js`.
- **Stock movements report** (3.1.2 rebuild, `GET /report/stock-movements`, `ReportService.getStockMovementsReport`):
  the movements of a period, grouped, quantities only (no amount: only sales and returns carry a price, the selling
  one; inventory counts and adjustments none).
  - **Parameters**, all optional: `dateFrom`, `dateTo` (none: since 2000 / until now; the page opens on the current
    month; 400 when `dateFrom` is after `dateTo`); `groupBy` `ITEM` (default) | `FAMILY` | `SUBFAMILY` | `CATEGORY` |
    `SECTION` | `TYPE` | `DAY` | `MONTH`; `search`, `familyId`, `subFamilyId`, `categoryCode`, `sectionCode` (the item
    filters of the stock report, the same WHERE: `ReportItemFilter`, `ReportService.where` / `bind`); `types`, a list of
    movement types, any of them (400 for another value); `movementType` (one type, before 3.1.2) still read, added to
    `types`.
  - **`COST_ADJUSTMENT` is never counted**: "Ajuster le CMP" writes it with a quantity of 0 as the trace of a CMP change;
    it moves no stock (before the rebuild it counted as a movement and had its TYPE row).
  - **Rows**: `qtyIn`, `qtyOut`, `netQty`, `nbMovements`, `itemCount` (the distinct items moved); an item row's label
    is the item's name, its `itemCode` and `familyName` apart (before: the label "code - name"). A day is `yyyy-MM-dd`,
    a month `yyyy-MM`, zero-padded so they sort as text (before: "2026-3-5"), in time order; the other groupings by
    quantity moved, the largest first. Family / sub-family / category / section labels as the stock report's ("No
    Family"..., a category or section by its name, else its code); a category or section without a code and one with a
    blank code are one row.
  - **Query**: one, grouped; the item joined only for an item filter or an item-level grouping (the dates, types and
    days read `stock_movement` alone, index `ix_stock_movement_created`).
  - **Keys of a row** (what the page opens): an item row `itemId`; a group row `groupKey`, the family's or sub-family's
    id, the category's or section's code, the type, the day, the month, null for the row of the items without a level.
    Families and sub-families are grouped by id (two of the same name are two rows); a sub-family row also carries its
    family's name (`familyName`; sub-family names repeat across families), none on the "No Subfamily" row.
  - **Stock card** (`GET /report/stock-movements/item/{itemId}?dateFrom&dateTo`, `ReportService.getStockCard`,
    `StockCardDTO` / `StockCardLineDTO`): one item over a period. `closingQty` = the stock now minus the movements after
    `dateTo` (IN minus OUT), `openingQty` = closing minus the period's (the rule of the stock value at a date);
    `qtyIn`, `qtyOut`, `movementCount` of the whole period; the lines in time order with `balanceAfter` (the stock after
    each, counted back from closing), at most `STOCK_CARD_MAX` = 2,000 (the latest; `truncated` then, the opening and
    the sums still whole). Each line: `type`, `direction`, `quantity`, `reference` as printed (a sale's `salesNumber`,
    a return's `returnNumber`, a purchase's `purchaseNumber`, read for the card's lines only; a BL's and an inventory
    count's number are their note), `note` otherwise (an adjustment's reason, an opening stock's origin), `user` (the
    movement's `createdBy`, or for a till movement written by "System" the cashier of its session: sale movements
    record no user, their session does). COST_ADJUSTMENT never. 404 for an unknown item, 400 for `dateFrom` after
    `dateTo`. Queries: the item, the movements after the period, the period's sums, the lines, and one per kind of
    document referenced (three at most). Tests: `StockMovementsReportTest` (`stockCard*`, `keys`),
    `ReportStockMovementsTest` (`stockCard`).
  - Tests: `StockMovementsReportTest` (the query on H2: the period, COST_ADJUSTMENT left out, every grouping, the
    filters, the call of before), `ReportStockMovementsTest` (the filter handed, the 400s), `DecimalSafetyNetTest`.
  - **The page** (`views/admin/reports/StockMovementsReport.vue`, same routes, permissions and menu entries; no chart,
    no "Load" button), the stock report's shell: `AdminListPage`, subtitle the period ("Du 01/11/2026 au 03/11/2026",
    "Le 03/11/2026"). Filters: search, the levels (`reportItemFilters.js`, a mixin: the choices of Item cards'
    `…/filters`, the narrowing, the level chips), the period (always set, the current month by default; quick periods
    Aujourd'hui / 7 derniers jours / Ce mois / Mois dernier; a start after the end refused), the types as chips, entries
    green and exits red, any of them. Each change reloads (an older answer dropped) and is kept in the address (`q`,
    `family`, `subFamily`, `category`, `section`, `types`, `from`, `to`, `groupBy`), the filters set as chips beside the
    grouping (× removes one). Totals strip: entries, exits, net (signed), movements, and the items moved only by item,
    family, sub-family, category or section (by type, day or month an item moved twice is in two rows). Table: sortable
    with arrows, quantities largest first on the first click, 50 a page, "—" for no quantity; types, days (dd/mm/yyyy)
    and months (mm/yyyy) shown in the page's language. Excel: every row, the labels as shown, the quantities as numbers,
    `stock-movements-<from>_<to>.xlsx`; Print: every row. Rules in `libs/stock-movements-report.js`
    (`tools/stock-movements-check.mjs`); the look in `report-list.scss` and the level filters in `reportItemFilters.js`,
    both shared with the stock report.
  - **A row clicked** (or Enter on it; the hint under the totals says what it does): an item row opens its **stock
    card** (`StockCardModal.vue`) over the period shown: the stock at the start and the end, the entries and exits, every
    movement in time order with the stock after it, its document ("Ticket T-000020", "Inventaire INV-000001", "#id" when
    the number is unknown), who made it, its note (an adjustment's reason translated); a totals line equal to the
    report's row; Excel of the card (`stock-card-<code>-<from>_<to>.xlsx`, the opening and closing stock around the
    lines). With a type filter the card still lists every movement (the stock after each needs them), the other types
    greyed and left out of its totals. A group row **drills down** (`drillDown`): a family, sub-family (with its family),
    category or section becomes that filter, a type the type filter, a day or a month the period (a month up to today),
    and the report shows its items; the row of the items without a level opens nothing.
- Statistics KPI deltas (`AnalyticsService.computeDeltaPercent`, 3.1.0, test log I-51): null when the previous period
  is 0 (no comparison); the page shows "—" in grey instead of a green "▲ 0.00%".
- **Statistics margin** (3.1.2, `GET /admin/analytics/summary`): `margin`, `marginRate`, `linesWithoutCost`, the
  Journal's gains over the period's days (`StoreDaysService.periodGains`, the rule of the .xlsx month total, `JournalCosts`;
  `docs/modules/cash-sessions.md`, "Gains"), so the margin is the sum of the Journal's days from..to. `marginRate` is the
  Journal's margin % (one decimal), absent with a line without cost or an HT of 0. Only where the store keeps its own
  stock and for `read:admin-margins` (`AnalyticsAPI.withMargins`, the Journal's rule), else the three are absent and the
  lines are not read; absent too when the period has no costed line. No previous-period delta. The tickets: the
  Journal's (not PENDING or CANCELLED), the same as the page's COMPLETED since REFUNDED is never written; every return
  is COMPLETED. A long period reads all its lines (one projection per kind). Page: a "Marge" card in the KPI row, only
  when `margin` is sent: the amount, the % beside it, "n ligne(s) sans coût" (`libs/journal-margins.js`). Tests:
  `JournalMarginsTest` (`statistics*`).

**CRITICAL FIX — CAST for DAY/MONTH grouping:**
- Bug: `CONCAT(YEAR(date), '-', MONTH(date))` produced arithmetic (2026 + 3 = 2029) because Hibernate treats YEAR()/MONTH()/DAY() as integers.
- Fix applied to all 5 time-based methods: `CONCAT(CAST(YEAR(date) AS string), '-', CAST(MONTH(date) AS string))` (and same for DAY).
- Without this cast every MONTH label shows the wrong year and every DAY label shows the item code sum.

**`ReportAPI.java` and `SalesReportAPI.java`** endpoints (all under `report/`):
- `GET /report/sales?dateFrom=&dateTo=&groupBy=`
- `GET /report/purchases?dateFrom=&dateTo=&groupBy=`
- `GET /report/stock?groupBy=&belowMinStock=&date=&search=&familyId=&subFamilyId=&categoryCode=&sectionCode=&status=` (3.1.2; "Stock report" above)
- `GET /report/stock-movements?dateFrom=&dateTo=&groupBy=&search=&familyId=&subFamilyId=&categoryCode=&sectionCode=&types=&movementType=` (3.1.2; "Stock movements report" above)
- `GET /report/loyalty?dateFrom=&dateTo=&groupBy=`
- `GET /report/sessions?dateFrom=&dateTo=&groupBy=`
- `GET /report/promotions?dateFrom=&dateTo=`

### Frontend — Report Pages

**Common pattern for the 4 time-based reports (SalesReport, PurchaseReport, LoyaltyReport, SessionReport; StockReport and StockMovementsReport since their 3.1.2 rebuild: "Stock report" and "Stock movements report" above):**

**Data / State:**
- `currentPage: 1`, `perPage: 50`, `chartType: 'bar'`, `sortBy: 'value_desc'`

**Computed:**
- `sortedRows`: applies `sortBy` to the raw API response rows before rendering chart and table.
  - `value_desc` / `value_asc`: numeric sort by the primary metric for that report.
  - `label_asc` / `label_desc`: chronological sort for date labels (YYYY-M-D / YYYY-M patterns detected via `first segment > 1000` heuristic → converted to `year×10000 + month×100 + day` for correct numeric comparison); falls back to `localeCompare` for non-date labels (e.g. stock report).
- `paginatedRows`: slices `sortedRows` for the current page.
- `chartSeries`: uses `sortedRows` (full sorted list — chart shows all, table paginates).
- `chartOptions.xaxis.categories`: uses `sortedRows` labels.

**Chart type switching:**
- `chartType` prop drives `<vue-apex-charts :type="chartType">`.
- Extra chart options for line/area: `stroke: { curve: 'smooth', width: 2 }`, `fill: { type: 'gradient' }`.

**Toolbar (between summary cards and chart):**
- `BButtonGroup` with Bar/Line/Area toggle buttons.
- `BFormSelect` for sort order (4 options translated via i18n).
- Per-page selector (10/25/50/100) near the `BPagination` component.

**Translation keys added (en/fr/ar):**
- `admin.reports.chartType` — "Chart Type"
- `admin.reports.sortBy` — "Sort By"
- `admin.reports.sortValueDesc` — "Value: High → Low"
- `admin.reports.sortValueAsc` — "Value: Low → High"
- `admin.reports.sortLabelAsc` — "Date / Label: A → Z"
- `admin.reports.sortLabelDesc` — "Date / Label: Z → A"

**Key Files:**
- `src/views/admin/reports/SalesReport.vue`
- `src/views/admin/reports/PurchaseReport.vue`
- `src/views/admin/reports/StockReport.vue`
- `src/views/admin/reports/StockMovementsReport.vue`
- `src/views/admin/reports/LoyaltyReport.vue`
- `src/views/admin/reports/SessionReport.vue`
- `src/views/admin/reports/PromotionReport.vue`
- `core/src/main/java/com/digithink/zsretail/core/service/ReportService.java`, `ReportValues.java` (both applications);
  `store/src/main/java/com/digithink/zsretail/store/service/SalesReportService.java` (the store)
- `core/src/main/java/com/digithink/zsretail/core/controller/ReportAPI.java`;
  `store/src/main/java/com/digithink/zsretail/store/controller/SalesReportAPI.java`

### Navigation & Router
- Reports menu group in `navigation/vertical/index.js` with 7 entries (Sales, Purchases, Stock, StockMovements, Loyalty, Sessions, Promotions).
- Routes in `router/index.js`: `admin-report-sales`, `admin-report-purchases`, `admin-report-stock`, `admin-report-stock-movements`, `admin-report-loyalty`, `admin-report-sessions`, `admin-report-promotions`.
