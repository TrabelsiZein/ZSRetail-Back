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
- `getStockReport(groupBy, belowMinStock)` — the stock now per item, family or sub-family; `getStockReport(groupBy, belowMinStock, date)` (3.1.2) the same at the end of a past day; returns `StockReportRowDTO { groupLabel, itemCode, currentQty, minStockLevel, stockValue, status, itemsWithoutCost, itemsAtCurrentCmp }` (the last two absent when none)
- `getStockMovementsReport(dateFrom, dateTo, groupBy)` — stock in/out by label; returns `StockMovementsReportRowDTO { label, qtyIn, qtyOut }`
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
  and its "Out of stock" card counts the items at 0 only.
- **Stock value at a date** (3.1.2, `GET /report/stock?groupBy&belowMinStock&date`, `ReportService.getStockReport`;
  the page's "Valeur au", today by default). One rule today and at a past date: **an item without cost** (no CMP and a
  stock other than 0) has no value (`stockValue` null, "—" on the page, an empty Excel cell; before 3.1.2 it counted
  0) and `itemsWithoutCost` 1; a family or sub-family row counts its items without cost, its value the sum of the
  others; the page marks the total incomplete with their count. A stock of 0 is worth 0, cost or not. Every active item
  is listed, a stock of 0 included.
  - **Today** (no date, or today's): the queries of before, unchanged; one more (`markWithoutCost`) finds the items
    without cost.
  - **A past day D** (before today; 400 after today, 400 where the installation keeps no stock of its own): at the end
    of D (movements and cost changes from D + 1 at 00:00 are after). **qty(D)** = the stock now minus the movements
    after D (IN minus OUT, `MOVEMENTS_AFTER`, one query grouped by item and direction). **CMP(D)** = the new cost of the
    item's last change up to D (`COSTS_BEFORE`), else the old cost of its first change after D (`COSTS_FROM`; null
    when that change gave the item its first cost), else its CMP now, counted in `itemsAtCurrentCmp` (the page's note
    "n article(s) valorisé(s) au CMP actuel"). **Value** = qty(D) x CMP(D). The rows grouped in Java in the order of the
    items query (`STOCK_ITEMS` ordered by the grouping's name), the labels of today's report ("No Family", "No
    Subfamily"); the minimum filter on qty(D).
  - **Queries**: four, whatever the number of items: the active items, the movements after D (index
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
    today), `ReportStockDateTest` (the 400s), `tools/stock-report-check.mjs` (the page's rules).
- Statistics KPI deltas (`AnalyticsService.computeDeltaPercent`, 3.1.0, test log I-51): null when the previous period
  is 0 (no comparison); the page shows "—" in grey instead of a green "▲ 0.00%".

**CRITICAL FIX — CAST for DAY/MONTH grouping:**
- Bug: `CONCAT(YEAR(date), '-', MONTH(date))` produced arithmetic (2026 + 3 = 2029) because Hibernate treats YEAR()/MONTH()/DAY() as integers.
- Fix applied to all 5 time-based methods: `CONCAT(CAST(YEAR(date) AS string), '-', CAST(MONTH(date) AS string))` (and same for DAY).
- Without this cast every MONTH label shows the wrong year and every DAY label shows the item code sum.

**`ReportAPI.java` and `SalesReportAPI.java`** endpoints (all under `report/`):
- `GET /report/sales?dateFrom=&dateTo=&groupBy=`
- `GET /report/purchases?dateFrom=&dateTo=&groupBy=`
- `GET /report/stock?dateFrom=&dateTo=&familyId=`
- `GET /report/stock-movements?dateFrom=&dateTo=&groupBy=`
- `GET /report/loyalty?dateFrom=&dateTo=&groupBy=`
- `GET /report/sessions?dateFrom=&dateTo=&groupBy=`
- `GET /report/promotions?dateFrom=&dateTo=`

### Frontend — Report Pages

**Common pattern for all 6 time-based reports (SalesReport, PurchaseReport, StockMovementsReport, LoyaltyReport, SessionReport, StockReport):**

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
- StockReport special case: `plotOptions: chartType === 'bar' ? { bar: { horizontal: true } } : {}` (was horizontal-only; now regular bar/line/area when type changes).

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
