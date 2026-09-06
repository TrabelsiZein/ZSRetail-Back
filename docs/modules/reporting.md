# Reporting Module

**Status**: ✅ Complete

**Overview:**
- 7 admin report pages accessible under the Reports menu (ADMIN role): Sales, Purchases, Stock, Stock Movements, Loyalty, Sessions, Promotions.
- Each report page fetches aggregated data from backend, displays summary cards + chart + paginated table.
- Configurable chart type (bar/line/area), sort order (value ↓ ↑, label A→Z Z→A), and pagination (per-page: 10/25/50/100).

### Backend — `ReportService.java`

**Methods:**
- `getSalesReport(dateFrom, dateTo, groupBy)` — aggregates sales headers by DAY/MONTH/YEAR; returns `SalesReportRowDTO { label, totalHt, totalTtc, nbTickets }`
- `getPurchaseReport(dateFrom, dateTo, groupBy)` — aggregates purchase headers; returns `PurchaseReportRowDTO { label, totalTtc, nbPurchases }`
- `getStockReport(dateFrom, dateTo, familyId)` — snapshot of stock quantities per item; returns `StockReportRowDTO { itemCode, itemName, currentQty, ... }`
- `getStockMovementsReport(dateFrom, dateTo, groupBy)` — stock in/out by label; returns `StockMovementsReportRowDTO { label, qtyIn, qtyOut }`
- `getLoyaltyReport(dateFrom, dateTo, groupBy)` — loyalty points earned/redeemed; returns `LoyaltyReportRowDTO { label, pointsEarned, pointsRedeemed }`
- `getSessionReport(dateFrom, dateTo, groupBy)` — session totals; returns `SessionReportRowDTO { label, totalAmount, nbSessions }`
- `getPromotionReport(dateFrom, dateTo)` — promotion performance; returns `PromotionReportRowDTO { promotionCode, promotionName, promotionType, nbTickets, totalDiscount, revenueInfluenced }`

**CRITICAL FIX — CAST for DAY/MONTH grouping:**
- Bug: `CONCAT(YEAR(date), '-', MONTH(date))` produced arithmetic (2026 + 3 = 2029) because Hibernate treats YEAR()/MONTH()/DAY() as integers.
- Fix applied to all 5 time-based methods: `CONCAT(CAST(YEAR(date) AS string), '-', CAST(MONTH(date) AS string))` (and same for DAY).
- Without this cast every MONTH label shows the wrong year and every DAY label shows the item code sum.

**`ReportAPI.java`** endpoints:
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
- `src/main/java/com/digithink/zsretail/service/ReportService.java`
- `src/main/java/com/digithink/zsretail/controller/ReportAPI.java`

### Navigation & Router
- Reports menu group in `navigation/vertical/index.js` with 7 entries (Sales, Purchases, Stock, StockMovements, Loyalty, Sessions, Promotions).
- Routes in `router/index.js`: `admin-report-sales`, `admin-report-purchases`, `admin-report-stock`, `admin-report-stock-movements`, `admin-report-loyalty`, `admin-report-sessions`, `admin-report-promotions`.
