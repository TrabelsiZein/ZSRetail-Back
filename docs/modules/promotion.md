# Promotion Module

**Status**: ✅ Complete

**Overview:**
- Full promotion engine covering item-level discounts (percentage, fixed amount, free quantity) and cart-level discounts.
- Promotions stack on top of the existing SalesPrice/SalesDiscount system with strict precedence: SalesPrice → SalesDiscount → Promotion (mutually exclusive at item level; cart promotions always apply alongside).
- Supports promo codes (cashier-entered at ItemSelection or Payment page) and automatic promotions.
- Full audit trail: every sales line and header stores `discountSource` (MANUAL / PROMOTION / SALES_PRICE / SALES_DISCOUNT) and a `promotion` FK.

### Database — New Tables & Columns

**`promotion`** (Flyway migrations 018–021):
- `code` (UNIQUE), `name`, `description`
- `promotion_type` (SIMPLE_DISCOUNT / QUANTITY_PROMOTION / CART_DISCOUNT)
- `scope` (ITEM / ITEM_FAMILY / ITEM_SUBFAMILY / CART)
- `item_id`, `item_family_id`, `item_sub_family_id` (nullable FKs — scope determines which is used)
- `minimum_quantity`, `minimum_amount`
- `benefit_type` (PERCENTAGE_DISCOUNT / FIXED_DISCOUNT / FREE_QUANTITY)
- `discount_percentage`, `discount_amount`, `free_quantity`
- `start_date`, `end_date`, `priority`, `active`
- `day_of_week` (comma-separated, e.g. "1,2,3"), `time_start`, `time_end`
- `requires_code` (boolean — if true, only activates when code is entered by cashier)
- DB indexes on: scope, active+dates, item_id, item_family_id, item_sub_family_id, promotion_type

**`sales_line`** (Flyway migration 022):
- Added `discount_source` (VARCHAR 30), `promotion_id` (FK → promotion, nullable)

**`sales_header`** (Flyway migration 022):
- Added `discount_source` (VARCHAR 30), `promotion_id` (FK → promotion, nullable)

### Backend — New Files

- **`model/Promotion.java`** — Entity with all fields above
- **`model/enumeration/PromotionType.java`** — SIMPLE_DISCOUNT, QUANTITY_PROMOTION, CART_DISCOUNT
- **`model/enumeration/PromotionScope.java`** — ITEM, ITEM_FAMILY, ITEM_SUBFAMILY, CART
- **`model/enumeration/PromotionBenefitType.java`** — PERCENTAGE_DISCOUNT, FIXED_DISCOUNT, FREE_QUANTITY
- **`repository/PromotionRepository.java`** — standard JpaRepository + `GET /promotion/{id}/usage-count` support
- **`service/PromotionService.java`** — CRUD + `getUsageCount(id)` (counts distinct sales headers linked to promotion)
- **`controller/PromotionAPI.java`** — full CRUD + `GET /promotion/{id}/usage-count`
- **`service/PromotionCalculationService.java`** — Core engine:
  - `calculateItemPrice(itemId, quantity, customerId, appliedCodes)`: SalesPrice → SalesDiscount → Promotion precedence; scope priority ITEM > ITEM_SUBFAMILY > ITEM_FAMILY; within same scope: priority field wins, tiebreak = best value; filters active, date, time, day-of-week
  - `calculateCartDiscount(cartTotal, cartItems, customerId, appliedCodes)`: finds best CART-scope promotion
  - `validatePromoCode(code)`: returns `{ valid, scope, promotionName, reason }`
- **`controller/PriceCalculateController.java`** — Three endpoints:
  - `POST /price-calculate` — per item (called on scan, qty change, customer change, item-scope code applied)
  - `POST /price-calculate/cart` — cart-level (called on proceed to payment, CART-scope code applied)
  - `GET /price-calculate/promo-code/{code}` — validates a promo code before adding it
- **`dto/report/PromotionReportRowDTO.java`** — `{ promotionCode, promotionName, promotionType, nbTickets, totalDiscount, revenueInfluenced }`

### Backend — Modified Files

- **`model/SalesLine.java`** — added `discountSource` (String), `promotion` (ManyToOne FK)
- **`model/SalesHeader.java`** — added `discountSource` (String), `promotion` (ManyToOne FK)
- **`dto/ProcessSaleRequestDTO.java`** — added `discountSource`, `promotionId` to header DTO and each line DTO; added `freeQuantity` to line DTO
- **`service/SalesHeaderService.java`**:
  - `processCompleteSale()`: persists `discountSource` + `promotion` on header and each line; creates FREE_QUANTITY free lines (discountPercentage=100, lineTotalIncludingVat=0, discountSource=PROMOTION)
  - `completePendingSale()`: mirrored to match `processCompleteSale` — same discountSource/promotionId persistence and free line creation
- **`service/ReportService.java`** — added `getPromotionReport(dateFrom, dateTo)`: two JPQL queries (line-level + header-level), merged by promotionCode, sorted by totalDiscount DESC
- **`controller/ReportAPI.java`** — added `GET /report/promotions?dateFrom=&dateTo=`

### FREE_QUANTITY Lines

When a promotion grants free items (`benefit_type = FREE_QUANTITY`):
- Backend creates **two** `SalesLine` rows per cart item with freeQuantity > 0:
  1. Normal paid line (regular price, regular discount)
  2. Free line: `quantity = freeQuantity`, `discountPercentage = 100`, `discountSource = PROMOTION`, `lineTotalIncludingVat = 0`, `vatAmount = 0`
- Free lines return amount is correctly 0 TND (lineTotalIncludingVat = 0 flows through return calculation naturally)

### Frontend — New Files

- **`src/views/admin/PromotionsManagement.vue`** — Full CRUD admin page:
  - Type picker (3 cards: Simple Discount, Quantity Promotion, Cart Discount)
  - Two-column modal with all fields; item search autocomplete for ITEM scope
  - Fields locked when `usageCount > 0`: code, requiresCode, scope, item/family/subfamily, min quantity/amount, benefitType, discount/freeQuantity fields (name, dates, active, priority, time/day remain editable)
  - Deactivate (Pause) button on active promotions — calls `PUT /promotion/{id}` with `active: false`
  - Yellow warning banner in modal when promotion has been used
- **`src/views/admin/reports/PromotionReport.vue`** — Performance report:
  - Date range filter
  - 4 summary cards: promotions used, ticket uses, total discount given, revenue influenced
  - Bar chart (top 15 by discount, orange)
  - Table: code, name, type badge, nbTickets, totalDiscount, revenueInfluenced, discountRate (%)
  - Export Excel + Print

### Frontend — Modified Files

- **`src/views/pos/ItemSelection.vue`**:
  - Calls `POST /price-calculate` instead of `GET /item/{id}/calculate-price` (new unified endpoint)
  - Promo code input row at bottom of cart panel; applied codes displayed as badges with × to remove
  - `applyPromoCode()`: validates code, adds to `appliedCodes`, recalculates CART-scope via `/price-calculate/cart` or ITEM-scope via `/price-calculate` per matching item
  - `removePromoCode()`: removes from list, recalculates all items
  - `refreshCartPromotion()`: calls `/price-calculate/cart`, stores result as `cartPromotion`
  - Cart summary shows promotion name + discount row when `cartPromotion.cartDiscountAmount > 0`
  - `proceedToPayment()`: calls `/price-calculate/cart` with full cart, passes `cartPromotionId/Discount/Name/Percentage` in `orderSummary` to store
  - Cart items carry `discountSource`, `promotionId`, `promotionName`, `freeQuantity`
  - `appliedCodes` persisted in Vuex store (`pos/appliedCodes`) across navigation

- **`src/views/pos/Payment.vue`**:
  - Promo code panel (green bar below summary): same input/badge UX as ItemSelection; always shows when `pricingEnabled`
  - `applyPromoCode()` / `removePromoCode()`: CART-scope updates `orderSummary` in store via `updateOrderSummary`; ITEM-scope recalculates items via `POST /price-calculate`, recomputes cart total with `computeCartTotalFromItems()`, then calls `refreshCartPromotion()`
  - `refreshCartPromotion()` reads from store directly (not `this.orderSummary` which updates async via watcher) so totals update immediately
  - Summary panel shows cart promotion discount row when `orderSummary.cartPromotionDiscount > 0`
  - Manual header discount blocked if a cart promotion is already applied
  - `completePayment()` / `saveAsPending()`: send `discountSource`, `promotionId`, `freeQuantity` per line and on header
  - CSS grid updated to 4 rows: summary / promo / main / actions

- **`src/views/admin/TicketsHistory.vue`**:
  - FREE badge (green) on lines with `discountPercentage=100 && discountSource=PROMOTION` (shows instead of "100%")
  - Discount source badge on each line: PROMOTION=success, SALES_PRICE=info, SALES_DISCOUNT=primary, MANUAL=warning
  - Promotion code shown under source badge when `discountSource=PROMOTION`
  - Same source badge + promo code shown on header discount summary row

- **`src/views/pos/ReturnProducts.vue`**:
  - FREE badge on free lines (instead of "-100%")
  - Info banner when ticket contains free lines: "can be returned physically but carry no refund value"
  - Return amount cell shows "Free item — no refund" label for free lines (amount stays 0 TND)

- **`src/navigation/vertical/index.js`** — Added promotions management entry (TagIcon) and promotion report entry under Reports group
- **`src/router/index.js`** — Added routes `admin-promotions` and `admin-report-promotions`
- **`src/views/Login.vue`** — Added ACL abilities: `admin-promotions` (read+write), `admin-report-promotions` (read) for ADMIN role
- **`src/store/pos/index.js`** — Added `appliedCodes: []` state, `SET_APPLIED_CODES` / `CLEAR_APPLIED_CODES` mutations, `setAppliedCodes` / `clearAppliedCodes` actions
- **i18n** (`en.json`, `fr.json`, `ar.json`):
  - `admin.promotions.*` (all form labels, type/scope/benefit options, messages, locked banner, deactivate action)
  - `admin.reports.promotionsMenu`, `admin.reports.promotions.*` (report page labels)
  - `admin.ticketsHistory.modal.discountSource.{MANUAL,PROMOTION,SALES_PRICE,SALES_DISCOUNT}`
  - `admin.ticketsHistory.modal.free`
  - `pos.itemSelection.promoCode.*` (placeholder, applied, alreadyApplied, invalid, reason.*)
  - `pos.returnProducts.freeItem`, `pos.returnProducts.freeNoRefund`, `pos.returnProducts.freeLinesInfo`, `pos.returnProducts.promotion`

### Key Design Decisions

- **Zero Preloading**: No Vuex preloading of promotions; all calculation is server-side per event (<5ms with DB indexes)
- **Single promotion per item**: Most specific scope wins (ITEM > ITEM_SUBFAMILY > ITEM_FAMILY), then highest priority, then best value — never stacked at item level
- **ITEM-level + CART-level can coexist**: A line can have an ITEM-scope promotion AND the cart can have a CART-scope discount simultaneously
- **Priority is absolute**: Higher `priority` value always wins regardless of discount value — intentional business control
- **SalesPrice/SalesDiscount blocks promotion**: If a SalesPrice or SalesDiscount exists for the customer+item, promotions are skipped for that item
- **Free lines are real DB rows**: `lineTotalIncludingVat=0` ensures return flow gives 0 TND refund without special-casing
- **discountSource tracks origin**: MANUAL (cashier entered), PROMOTION (promo engine), SALES_PRICE, SALES_DISCOUNT — stored on both header and each line for audit and reporting
