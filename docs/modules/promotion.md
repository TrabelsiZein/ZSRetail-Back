# Promotion Module

**Status**: ✅ Complete

**Overview:**
- Full promotion engine covering item-level discounts (percentage, fixed amount, free quantity) and cart-level discounts.
- Promotions work on top of the SalesPrice/SalesDiscount system: SalesPrice sets the base price, then the item's promotion and the customer's SalesDiscount are compared and only the better one applies (never both on a line). Cart promotions always apply alongside. See **Resolution order** below.
- Supports promo codes (cashier-entered at ItemSelection or Payment page) and automatic promotions.
- Full audit trail: every sales line and header stores `discountSource` (MANUAL / PROMOTION / SALES_PRICE / SALES_DISCOUNT) and a `promotion` FK.

### Database — New Tables & Columns

**`promotion`** (Flyway migrations 018–021):
- `code` (UNIQUE), `name`, `description`
- `promotion_type` (SIMPLE_DISCOUNT / QUANTITY_PROMOTION / CART_DISCOUNT)
- `scope` (ITEM / ITEM_GROUP / ITEM_FAMILY / ITEM_SUBFAMILY / ALL_ITEMS / CART)
- `item_id`, `item_family_id`, `item_sub_family_id` (nullable FKs — scope determines which is used)
- `promotion_group_item` join table (1.10.0): the item set of an ITEM_GROUP promotion
- ALL_ITEMS has no target: `PromotionService.save` clears the three FKs and the group items (the per-item FK lookups have no scope filter, so a leftover FK would make it match early)
- `get_item_id` (nullable FK, 1.10.0): cross-product benefit target, QUANTITY_PROMOTION only, evaluated by the cart pass
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
- **`model/enumeration/PromotionScope.java`** — ITEM, ITEM_GROUP, ITEM_FAMILY, ITEM_SUBFAMILY, ALL_ITEMS, CART
- **`model/enumeration/PromotionBenefitType.java`** — PERCENTAGE_DISCOUNT, FIXED_DISCOUNT, FREE_QUANTITY
- **`repository/PromotionRepository.java`** — standard JpaRepository + `GET /promotion/{id}/usage-count` support
- **`service/PromotionService.java`** — CRUD + `getUsageCount(id)` (counts distinct sales headers linked to promotion)
- **`controller/PromotionAPI.java`** — full CRUD + `GET /promotion/{id}/usage-count`
- **`service/PromotionCalculationService.java`** — Core engine:
  - `calculateItemPrice(itemId, quantity, customerId, appliedCodes)`: selects one promotion for the item (see **Resolution order** below), then compares it with ERP pricing
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
  - Scope select: Item / Group of items / Family / Subfamily / All items. ALL_ITEMS shows no target picker, only a hint that more specific promotions take precedence (`admin.promotions.fields.allItemsHint`); the list shows just the scope badge
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

### Resolution order (per item)

`PromotionCalculationService.findBestItemPromotion` then `calculateItemPrice`:

1. **Scope cascade**, most specific first: ITEM > ITEM_GROUP > ITEM_SUBFAMILY > ITEM_FAMILY > ALL_ITEMS.
   Each scope loads its active, in-date promotions, then drops those failing the promo-code,
   minimum-quantity, time-window or day-of-week filters. A scope left with no candidate falls
   through to the next one. The first scope with a candidate wins; less specific scopes are not consulted.
   Cross-product promotions (`get_item_id` set) are skipped here; the cart pass handles them.
2. **Within that scope**: highest `priority`; on a tie, highest `discount_percentage`, then
   `discount_amount`, then `free_quantity` (raw field values, not converted to money).
3. **ERP comparison**: SalesPrice, if any, is the base price. FREE_QUANTITY always applies.
   Otherwise the promotion applies only if its effective % (fixed amount ÷ unit price) beats the
   customer's SalesDiscount %. If it loses, ERP pricing is kept and no other scope is tried.

ALL_ITEMS (next release after 1.11.0) is the storewide fallback. Example: "family Desserts 5%" +
"all items 10%" gives desserts 5% and every other item 10%. To give desserts 10% too, raise or
deactivate the family promotion. If the family promotion is filtered out (wrong day, code not
entered, quantity not met), desserts fall through to the 10%. Minimum quantity is checked per cart
line, as for every scope. As the buy side of a cross-product promotion, ALL_ITEMS matches every
cart line except the benefit item's own line. The TAX_STAMP line is added server-side at a fixed
price and never goes through promotion pricing.

### Key Design Decisions

- **Zero Preloading**: No Vuex preloading of promotions; all calculation is server-side per event (<5ms with DB indexes)
- **Single promotion per item**: Most specific scope wins (ITEM > ITEM_GROUP > ITEM_SUBFAMILY > ITEM_FAMILY > ALL_ITEMS), then highest priority, then best value — never stacked at item level
- **ITEM-level + CART-level can coexist**: A line can have an ITEM-scope promotion AND the cart can have a CART-scope discount simultaneously
- **Priority is absolute within a scope**: Higher `priority` value always wins regardless of discount value — intentional business control. It never overrides scope specificity
- **ERP pricing vs promotion**: SalesPrice sets the base price the promotion applies to; a percentage/fixed promotion applies only if it beats the customer's SalesDiscount; FREE_QUANTITY always applies
- **Free lines are real DB rows**: `lineTotalIncludingVat=0` ensures return flow gives 0 TND refund without special-casing
- **discountSource tracks origin**: MANUAL (cashier entered), PROMOTION (promo engine), SALES_PRICE, SALES_DISCOUNT — stored on both header and each line for audit and reporting
