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
- `scope` (ITEM / ITEM_GROUP / ITEM_FAMILY / ITEM_SUBFAMILY / SECTION / CATEGORY / ALL_ITEMS / CART)
- `item_id`, `item_family_id`, `item_sub_family_id` (nullable FKs — scope determines which is used)
- `item_category_id`, `item_section_id` (3.1.0, ZEN, nullable FKs to `item_category` / `item_section`): the target of a CATEGORY / SECTION promotion; see **Catégorie and section scopes**
- `nav_item_no` (3.1.0, ZEN, nullable varchar(255)): an ITEM promotion on every variant of a NAV item, instead of `item_id`; see **Every variant of a NAV item**
- `promotion_group_item` join table (1.10.0): the item set of an ITEM_GROUP promotion
- `promotion_group_nav_item` table (3.1.0, ZEN; `promotion_id`, `nav_item_no`): the NAV item numbers of an ITEM_GROUP promotion
- ALL_ITEMS has no target: `PromotionService.save` clears the three FKs and the group items (the per-item FK lookups have no scope filter, so a leftover FK would make it match early)
- `get_item_id` (nullable FK, 1.10.0): cross-product benefit target, QUANTITY_PROMOTION only, evaluated by the cart pass
- `get_nav_item_no` (3.1.0, ZEN, nullable): the cross-product benefit on any variant of a NAV item (step 2)
- `minimum_quantity`, `minimum_amount`
- `benefit_type` (PERCENTAGE_DISCOUNT / FIXED_DISCOUNT / FREE_QUANTITY)
- `discount_percentage`, `discount_amount`, `free_quantity`
- `start_date`, `end_date`, `priority`, `active`
- `day_of_week` (comma-separated, e.g. "1,2,3"), `time_start`, `time_end`
- `requires_code` (boolean — if true, only activates when code is entered by cashier)
- DB indexes on: scope, active+dates, item_id, item_family_id, item_sub_family_id, item_category_id, item_section_id, nav_item_no, promotion_group_nav_item.nav_item_no, promotion_type

**`sales_line`** (Flyway migration 022):
- Added `discount_source` (VARCHAR 30), `promotion_id` (FK → promotion, nullable)

**`sales_header`** (Flyway migration 022):
- Added `discount_source` (VARCHAR 30), `promotion_id` (FK → promotion, nullable)

### Backend — New Files

- **`model/Promotion.java`** — Entity with all fields above
- **`core/model/enumeration/PromotionType.java`** — SIMPLE_DISCOUNT, QUANTITY_PROMOTION, CART_DISCOUNT
- **`core/model/enumeration/PromotionScope.java`** — ITEM, ITEM_GROUP, ITEM_FAMILY, ITEM_SUBFAMILY, SECTION, CATEGORY (3.1.0), ALL_ITEMS, CART
- **`core/model/enumeration/PromotionBenefitType.java`** — PERCENTAGE_DISCOUNT, FIXED_DISCOUNT, FREE_QUANTITY
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
- **`core/dto/report/PromotionReportRowDTO.java`** — `{ promotionCode, promotionName, promotionType, nbTickets, totalDiscount, revenueInfluenced }`

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

### Free pieces only when scanned (3.1.0, decision of 2026-10-12)

"A free piece is never added on top. It is free only when it is scanned or read. Stock taken = pieces scanned." For every
store and every cart line, with or without RFID tags.

**Same item, "buy N, get M free"**, repeated for each group of G = N + M pieces (decision of Zein, 2026-10-12, every
store, no setting): Q = all the pieces of the line (scanned, typed or read), free = floor(Q / G) x M + min(M, max(0,
(Q mod G) - N)), paid = Q - free. N = 2, M = 1: Q = 1 to 9 → 0, 0, 1, 1, 1, 2, 2, 2, 3 free (6 pieces: 4 paid + 2 free).
`PromotionCalculationService.freePieces`; `POST /price-calculate` answers `freeQuantity` (the free pieces among the
quantity asked), `freeQuantityAvailable` (the free pieces of the group in progress not scanned yet: with r = Q mod G,
M - max(0, r - N) once r reaches N, else 0; buy 2 get 1: Q = 2 or 5 → 1, Q = 3, 4 or 6 → 0; `freeAvailable`) and
`freePromotionId` / `freePromotionName` (the free promotion they speak of).

**A free promotion takes the line only when it gives at least 1 free piece** (Q >= N + 1, correction of 2026-10-12):
with 0 free piece the line is priced exactly as if that promotion did not exist (the cascade picks another promotion if
one applies, else the ERP SalesPrice / SalesDiscount applies), and the answer still carries the hint: `freeQuantity` 0,
`freeQuantityAvailable` M and `freePromotionName`, while `promotionId` is the other promotion or none. Example: NAV
discount 10 %, "2 → 1 free": 1 piece → 10 %, no hint; 2 pieces → 10 %, 0 free, hint 1; 3 pieces → 1 free, no 10 % (a
FREE_QUANTITY promotion that takes the line never compares with the SalesDiscount, as before). The same cascade finds
both (`findBestItemPromotion`, each scope read once): the promotion applied, and the winner had a free promotion with 0
free piece been allowed; when that winner is such a free promotion, it is the hint. Below N: nothing from the free
promotion, no hint. A FREE_QUANTITY promotion without N (or N = 0) gives no free piece, is never selected and gives no
hint (the line keeps its other promotion or ERP price). A quantity not whole never has a
free piece (`passesQuantityFilter`, and `checkQuantities` refuses a free quantity on a decimal line).

**Cross-product, "buy N of X, get M of Y free"** (per group, as before): `POST /price-calculate/cart` adjustments carry
`freeQuantity` = min(freeUnits, Y's whole pieces in the cart lines) and `freeQuantityAvailable` = freeUnits -
freeQuantity. `freeUnits` (entitled x M) keeps its meaning so a till of before 3.1.0 still running (cached, no version
check) behaves as before. The free pieces of Y apply whatever Y's price (SalesPrice or SalesDiscount); a manual discount
on Y's line as for a same-item free promotion; Y with its own same-item free promotion too: the larger of the two, never
the sum (front).

**The split paid / free stays in the front** (decision of 2026-10-12): the payment payload keeps its meaning (line
`quantity` = paid pieces, `freeQuantity` = free pieces) and `SalesHeaderService` is unchanged: a paid line and a free
line, `decrementStockForSalesLines` takes both (stock taken = paid + free = pieces scanned). A till of before 3.1.0
against this back end keeps the old behaviour and the stock stays right. Pending and table tickets save the cart line
(all Q pieces); the recall re-prices, so the free pieces come back.

**Front (3.1.0, ZSRetail-Front)**:
- **The cart line** (`ItemSelection.vue`): its quantity is all its pieces; `freeQuantity` comes from the back end and
  is never computed in the front. `paidQuantity(line)` (`libs/quantity.js`) = quantity - freeQuantity counts the
  line's amounts and the cart totals (`getLineTotalWithVat`, `lineTotalExclVat`), and the payment's line quantity
  (`Payment.vue`: `quantity` = paid pieces, `freeQuantity` = free ones). A pending or table ticket saves the cart line
  (all the pieces) with the paid amounts. The line shows "N free" (also collapsed), the full price struck through then
  the total, "N free (included)" with the free promotion in the breakdown, and "<promotion>: N more piece(s) free" while
  `freeQuantityAvailable` > 0 (no toast). `addToCart` counts a free quantity as a discount, as `applyPriceResult` does.
- **Cross-product**: no free line of Y is added any more (`autoFreePromotionId`, `dismissedFreePromos` and the "added
  for free" toast are gone). `reconcileCrossAdjustments` puts the adjustment's `freeQuantity` on Y's own line
  (`crossFreePromotionId` / `crossFreePromotionName`), whatever Y's price; Y with its own same-item free promotion: the
  larger of the two (`itemFreeQuantity` keeps the line's own), never the sum. The pieces of Y still to scan are a hint at
  the top of the cart ("<promotion>: 2 × <Y> free, scan them"). The cart pass sends every line with all its pieces. At
  payment the free line carries the cross-product's promotion when Y's line has no discount of its own, else the line's
  own promotion (one promotion per payload line). A cross-product discount counts the paid pieces of Y.
- **Recall** (`libs/free-pieces.js`, `retrievePendingTicket`, `TableSelection.loadOccupiedTable`): each whole line asks
  its price again and takes back its free pieces, the ones still to scan and the free promotion; the saved price and
  discount are kept (a manual discount on a parked ticket survives, as before); the cart pass gives a cross-product's
  free pieces back.
- **Split bill** (`SplitBillModal`): selecting a line asks its free pieces (the cart's, else its price asked again); a
  line with free pieces is not selected: "This line has free pieces: pay the table normally" (`splitAndPay` copies the
  saved line, whose amount excluding VAT counts all Q pieces, and the completed ticket goes to NAV).
- **Pending tickets list**: each line shows its amount excluding VAT from the till's amounts (including VAT minus its
  VAT), the paid pieces, as the ticket's subtotal; the saved `lineTotal` counts every piece. A saved line keeps no free
  count: when the list opens each whole line asks its price again (`askFreeAnswer`, as the recall) and each ticket runs the
  cart pass on its lines (a cross-product's free pieces; the larger of the two, as in the cart), and a line with free
  pieces reads "3 x TND 27.65 (1 free) = TND 55.29" (the free pieces of the promotions of today).

**For the cashiers** (3.1.0): a free piece is never added by the till any more. Scan or read **every** piece the customer
takes, the free ones too: the till makes the free ones free ("1 free" on the line) and the receipt shows them on their
own line at 0. When the line says "1 more piece free", the customer may take one more piece of it for free: scan it. A
"buy X, get Y free" asks to scan Y as well (a hint at the top of the cart names it). On a table, a line with free pieces
is paid with the whole table, not by a split bill. Before the upgrade, pay or cancel every parked and table ticket.

Open points (decisions of 2026-10-12, nothing changed for them): a return refunds each paid piece at its price and a
free line is returned on its own for 0, so a customer can return the paid pieces at full price and keep the free one;
a pending or table line's `line_total` (excluding VAT, computed by the server from all Q pieces) counts the free pieces
until the ticket is paid; "Gratuit" on the free row of the printed ticket (logged, not in 3.1.0). Deploy: close the pending and table tickets before upgrading (`docs/deploy-note.md`).

Tests: `PromotionAllItemsScopeTest` (`freeTable`: buy 2 get 1 and buy 3 get 2, the free pieces and those to scan, `quantityPromotionPerLine`, `freeWithoutMinimum`,
`freeAtThresholdKeepsSalesDiscount`, `freeAtThresholdLetsAnotherPromotionApply`,
`freeAboveThresholdReplacesSalesDiscount`, `freeBelowThresholdNoHint`,
`crossProductFreeOnlyWhenScanned`), `DecimalSafetyNetTest.crossProductIgnoresDecimalLines`; `SaleDecimalQuantityTest`
(`stockTakenPaidPlusFree`, `stockTakenPaidPlusFreeParked`: today's payload paid 3 + free 1 takes 4 from the stock;
`freeQuantityRefusedOnDecimalLine`).

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

### Catégorie and section scopes (3.1.0, ZEN, 2026-10-22)

ZEN's tree is catégorie > famille > sous-famille > section. A promotion can target a catégorie (scope `CATEGORY`,
`promotion.item_category_id`) or a section (scope `SECTION`, `promotion.item_section_id`). Zero change where the two
tables are empty (EMTOP, Happyness): the scopes are not offered and an item without the codes never reads them.

- **Matching, at ticket time** (nothing is expanded at save): an item carries its catégorie and section as codes
  (`item.category_code`, `item.section_code`, no foreign key). The item's section = its `sectionCode`; its catégorie =
  its own `categoryCode`, else its famille's `categoryCode` (decision of 2026-10-22). Blank counts as none.
  `PromotionCalculationService.sectionCodeOf` / `categoryCodeOf`; `PromotionRepository.findActiveBySectionCode` /
  `findActiveByCategoryCode` join on the target's code and filter on the scope.
- **Order** (decision of 2026-10-22): SECTION right after ITEM_GROUP (the section is below the sous-famille), CATEGORY
  right after ITEM_FAMILY; each branch is added only when the item has the code, the existing branches unchanged.
- **Cart pass**: `matchesBuyTarget` matches the same codes, so a cross-product promotion can have a catégorie or section
  buy side. Free pieces and minimum quantity count the pieces of every cart line of the target (rule 6a, step 2 of
  the promotions for variants), as for a family.
- **Save** (`PromotionService.normalise`): CATEGORY needs its catégorie, SECTION its section (400 otherwise); each owns
  its target alone (item, family and sub-family cleared); every other scope has both new keys cleared. Both targets
  are locked once the promotion is used. The existing scopes are validated as before.
- **Head office to store**: `PromotionCopyDTO.itemCategoryCode` / `itemSectionCode`; the store resolves them by code in
  the catégorie and section rows the head office copies (`PromotionDownHandler.resolve`). Missing: WAITING ("not in
  this store: catégorie X" / "section X"), an existing copy deactivated, retried each cycle, as a family. A store of
  a build before this change cannot read the new scope: the record is in ERROR there and skipped (accepted).
- **Promo code** (`GET /price-calculate/promo-code/{code}`): also answers `itemCategoryCode` / `itemSectionCode`; the
  till already re-prices every line for a scope other than ITEM, so no till change.
- **Front** (`PromotionsManagement.vue`, store and head office): reads `GET /item-category` and `GET /item-section` once
  on load (their entries now carry the row `id`); a scope is offered only when its list has rows, in tree order
  (Catégorie before Famille, Section after Sous-famille). Pickers as the famille one (a section shows its sous-famille);
  the list's target column and the read-only consult view show `[code] name`. The promotion report shows no target.
- **SQL**: `db/3.1.0/update.sql`, section "Promotions by catégorie and section": the two columns, their foreign keys
  (only when the column has none) and indexes; no data changed.
- **Data**: on 2026-10-22 ZEN's test databases had 1 catégorie, no section, and no item or famille carrying a code
  (analysis question Q11), so these scopes match nothing there until ZEN fills the codes.
- Tests: `PromotionCategorySectionScopeTest` (order, own vs famille catégorie, no codes no reads, scope filter,
  cross-product, save rules), `PromotionDownHandlerTest` (`categoryAndSection`, `missingCategoryAndSection`);
  `PromotionAllItemsScopeTest` unchanged (its stubs throw on any other lookup).

### Every variant of a NAV item (3.1.0, ZEN), step 1

At ZEN every variant is its own item row; the variants of one ERP item share `item.nav_item_no`. EMTOP and Happyness
have no NAV item number on any row (only the ZEN connector fills it): nothing below ever happens there. Design and
steps (step 0, 2026-10-23): 1 data and matching, 1b the "toutes les variantes" box of the promotion form, 3 the head
office copy and `Item` equality, 2 counting across cart lines (every scope, decision 6a), 4 the lookup popup.

- **Targets**: an ITEM promotion targets one item row (`item`, as before) or one NAV item number (`navItemNo`); an
  ITEM_GROUP holds item rows, NAV item numbers (`groupNavItemNos`) or both. A NAV item number stands for every item
  carrying it at ticket time, also the variants synced after the promotion was made: nothing is expanded at save.
  `getNavItemNo` (the benefit of a cross-product on any variant) is in the entity and the table only.
- **Matching** (`findBestItemPromotion`): for an item carrying a NAV item number, ITEM on its row > ITEM on its NAV
  number (`findActiveByNavItemNo`) > its groups, the groups of rows (`findActiveByGroupItemId`) and of NAV numbers
  (`findActiveByGroupNavItemNo`) read as one level where priority decides (decisions 4 of 2026-10-23); then the scopes
  below as before. An item without one reads exactly what it read before (blank counts as none). Both lookups filter
  on the scope. `matchesBuyTarget` (cart pass, cross-product buy side) matches a NAV number the same way; a group's
  NAV numbers are read only for an item carrying one. A promotion with `getNavItemNo` never discounts the buy line
  (`selectBestFromList`, as `getItem`); the cart pass gives it to the lines of the NAV number's variants (step 2).
  Quantities count across the cart lines since step 2 (**Counted across the cart lines** below).
- **Save** (`PromotionService.normalise`, also for a copy received from the head office): the three trimmed, blank =
  none; an ITEM with both `item` and `navItemNo` is refused; `navItemNo` kept on ITEM only, `groupNavItemNos` on
  ITEM_GROUP only (a group needs at least one row or NAV number), `getNavItemNo` cleared outside QUANTITY_PROMOTION as
  `getItem`; a benefit on both `getItem` and `getNavItemNo` is refused; an ITEM promotion whose `getNavItemNo` equals
  its `navItemNo` is a same-item promotion (`getNavItemNo` cleared, decision 6c). Steps 1 to 3 refused NAV targets on
  a QUANTITY_PROMOTION; step 2 lifted it. All three locked once the promotion is used. `POST /promotion` and
  `PUT /promotion/{id}` answer a refusal with 400 and its reason (500 before 3.1.0).
- **SQL**: `db/3.1.0/update.sql`, section "Promotions on every variant of a NAV item"; no data changed.
- **Head office to store (step 3)**: `PromotionCopyDTO.navItemNo`, `getNavItemNo` and `groupNavItemNos` (sorted;
  null / empty when none, as a copy of before reads, so an existing promotion compares equal and is not rewritten).
  `PromotionDownHandler.build` copies them as they are: a NAV target never makes a promotion WAITING (the variants
  synced later match it); a group is missing only when none of its rows is here and it holds no NAV number. A number no
  item of the store carries yet is one INFO log line when the promotion is written ("no item of this store has ERP
  item number X yet"). A store of a build before this step ignores the fields (unknown fields ignored) and saves the
  promotion without that target: it matches nothing there. Tests: `PromotionDownHandlerTest` (`navTargets`,
  `copyOfBeforeEqual`), `HoPromotionServiceTest.payloadNavTargets`.
- **`Item` equality (step 3, root cause of the group saved with one item)**: `Item.equals` / `hashCode` are written
  by hand: equal by id read through `getId()` (a Hibernate proxy equals its item, and an item compares with a proxy not
  loaded without loading it), an item without an id equal only to itself, one hash for every item (it never changes
  when the id comes at save; the sets and maps of items are small: a promotion group, an invoice's lines). Lombok's
  `@EqualsAndHashCode(callSuper = false)` left the id out. The other maps keyed by `Item` hold saved rows only
  (`InvoiceService` and `PurchaseInvoiceService`, the lines of saved tickets / purchases; `HoSupplyPriceService`, items
  loaded by code or id), so they group as before. The group workarounds (`GroupItemsDeserializer`, `loadGroupItems`)
  stay. Test: `ItemEqualityTest`.
- **Form (step 1b, `PromotionsManagement.vue`)**: the item search is unchanged; `GET /item/search` gives each item its
  `navItemNo`. A chosen item that has one shows "Toutes les variantes de cet item (<n°>)" under the field; checked, the
  form sends `navItemNo` and no `item`. The same box on each chip of a group (checked chips go as `groupNavItemNos`,
  the others as `groupItems`) and under the free item (`getNavItemNo`). The boxes are hidden on a quantity promotion
  until step 2 (`NAV_TARGETS_FOR_QUANTITY`, true since step 2). An edit loads the three targets and
  sends them back unchanged (a saved NAV target shows its label, its box checked and disabled; X clears it); they lock
  with the other targets once used. The list's target and benefit columns and the consult view read "<name> —
  toutes variantes (<n°>)"; a group counts rows and NAV numbers. The names come from `GET /nav-item/names` (one read per
  list page, for the numbers not known yet; none when the page has no NAV target).
  `NavItemService.namesOf`, `ItemRepository.NAV_ITEM_NAMES` (a seek on `ix_item_nav_item_no` on ZEN's French_CI_AS,
  plan checked); test `NavItemQueryTest.names`.
- **Target lookup (step 4, 2026-10-23)**: a list button beside each target field of the form (item, free item, group
  items, famille, sous-famille, catégorie, section) opens `LookupModal.vue` (`components/admin`): a search (300 ms), a
  page of 20 lines, the total, the pages; single = a click picks and closes, multiple (group items) = a box per line
  and "Add (n)". The typed searches are unchanged. API `GET /item/lookup?scope&q&page&size` (core, read only, size at
  most 50; `ItemService.lookup`, `ItemLookupQuery`, native SQL): scope ITEM gives, in one UNION ALL, a `NAV_ITEM` line
  per ERP item number of the active items (`id` its lowest active variant, `variantCount`) and a `ROW` line per active
  item, by name, ERP item number (two ERP items of one name: each NAV line right above its own variants), the NAV line
  before its variants, then by code; packs (PACKAGE) and inactive items left out (active 1 or null); search on code, name, ERP item number and barcodes (their item ids read first, as `GET /item/search`), a
  NAV line matching when one of its variants does (a derived table of the matching numbers joined to the group).
  FAMILY, SUBFAMILY (its famille), CATEGORY, SECTION (its sous-famille, by code): code and name of the active rows. The
  total comes with the lines (`COUNT(*) OVER()`). The labels are built by the screen (i18n): "<name> — toutes variantes
  (<n°>)", "<name> — <variant> (<code>)", "[code] name (parent)". A customer without ERP item numbers gets ROW lines
  only, from the same statement. Picked: a ROW as the typed search; a NAV_ITEM on the item or the free item = its
  variant with "toutes les variantes" ticked (unticking it clears the field: no single item was chosen); in the group a
  chip on the ERP item number (`groupNavItemNos`) or the item, duplicates skipped; a sous-famille also sets the famille
  filter. Speed on ZEN's head office (21,904 items, SHOWPLAN and runs of 2026-10-23): a scan of the item table per half
  (a search `LIKE '%…%'` can use no index), about 260 ms for a page and its total with a search, 200 ms without; no
  index would help. Test: `ItemLookupQueryTest` (H2, SQL Server mode).
- Tests: `PromotionNavTargetTest` (every variant, a variant synced later, order, groups one level, no NAV number no
  reads, scope filter, benefit on a NAV number, buy side), `PromotionGroupItemsTest` (save rules, lock);
  `PromotionAllItemsScopeTest` and `PromotionCategorySectionScopeTest` unchanged (their stubs throw on any other lookup);
  `QueryParameterBindingTest` binds the two new queries.

### Counted across the cart lines (3.1.0, rule 6a, step 2 of the promotions for variants)

Decisions of 2026-10-23, every customer, no setting: a quantity promotion (a minimum quantity, "buy N, get M free",
the buy side of a cross-product) counts Q = the whole pieces of **every cart line of its target** (an item row, a NAV
item, a group, a section, a sous-famille, a famille, a catégorie, all items), not each line alone. A cart of one line
is priced exactly as before.

- **Levels** (decision B): row, NAV item, group (rows and NAV numbers), section, sous-famille, famille, catégorie, all
  items. Each level counts only the lines no earlier level took: a line taken by its own row promotion does not count
  in its NAV item's Q (6b), nor a line taken by a NAV promotion in its famille's. Within a level each line takes its
  best promotion as the cascade does; Q counts every open line of the level that the promotion targets.
- **Free pieces**: F = freePieces(N, M, Q), repeated per group (6 pieces of "2 → 1" = 2 free), given to the lines it
  counts cheapest unit price first, then cart order, at most each line's pieces; never a piece added (only scanned
  pieces are free). With at least 1 free piece the promotion takes every line it counts (decision C): a line given 0
  is at full price with that promotion and no other one. Below N + 1 it takes no line and the hint is as before; the
  pieces still to scan ("N more free") are on the cart's last line of the target only.
- **Engine** (`PromotionCalculationService.resolveCart`): reads each level's promotions once per key for the cart,
  `selectBestFromList` with each promotion's own Q, the SalesPrice / SalesDiscount comparison per line as before. A line
  not whole keeps its own quantity (never a quantity promotion, 2.2.1).
- **API** (decision A): `POST /price-calculate` takes `cartLines` (`{itemId, quantity, unitPrice}`, the cart in order,
  this line included; absent: first; none or this line alone: priced alone, exactly as before) and answers
  `acrossLines` (the answer differs from the line priced alone). The cart pass (`POST /price-calculate/cart`, with
  `customerId`) adds `lineResults`: every line priced with the cart around it, with `acrossLines`; a cart of one item,
  its line priced alone (screen test case 6 of 2026-10-23: a line left alone by a removal must lose the free pieces of
  the group it was in). A till of before sends no lines and ignores `lineResults`: single-line pricing as before.
- **Cross-product**: entitled = floor(Q / N) over every buy line (before: the sum of each line's floor); the benefit
  on an item or on any variant of an ERP item number (`getNavItemNo`): `freeByItemId` (free pieces per line) or
  `entitledByItemId` (discounted units per line), cheapest first; `getItemName` of a NAV benefit with no variant in
  the cart from `ItemRepository.findNavItemNames`. Promo code (`GET /price-calculate/promo-code/{code}`):
  also `navItemNo`, `groupNavItemNos`, `getNavItemNo`.
- **Till** (`ItemSelection.vue`): every price question carries the cart's lines (`priceLines`, `libs/free-pieces.js`);
  the cart pass applies `lineResults` (`applyLineResults`) to every line whose answer differs from what it shows, as a
  per-line answer (the hint included), never to a line with a manual discount (a cross-product's discount on a line is
  compared apart), then the cross-product adjustments (`freeByItemId`, `entitledByItemId`). A line taken by a free
  promotion with 0 free piece of its own (decision C) shows the promotion's name as a badge (`freeGroupLine`). A line
  keeps `navItemNo` (an ITEM code on an ERP item number re-prices its variants' lines), `freePromotionId` and
  `acrossLines`. Recall and pending list: the ticket's lines go with each question. Payment: its price questions carry
  the cart; a code with `getNavItemNo` is refused there as one with `getItemId`. Split bill (decision D): a line that
  counts in a free promotion giving a free piece to another line (taken by it, 0 of its own) is not paid alone either.
- Tests: `PromotionAcrossLinesTest` (NAV item two variants, repeat per group, hint on the last line, 6b, group,
  section, famille and levels, all items, decision C, one line as before, unrelated lines, cross buy side, free item on
  any variant); `PromotionAllItemsScopeTest`, `PromotionCategorySectionScopeTest`, `DecimalSafetyNetTest` unchanged.
  Before deploying: check EMTOP and Happyness for quantity promotions with a scope other than ITEM (their counting
  changes).

### Resolution order (per item)

`PromotionCalculationService.findBestItemPromotion` then `calculateItemPrice`:

1. **Scope cascade**, most specific first: ITEM > ITEM_GROUP > SECTION > ITEM_SUBFAMILY > ITEM_FAMILY > CATEGORY > ALL_ITEMS
   (SECTION and CATEGORY since 3.1.0, each skipped for an item without a section / catégorie code; for an item carrying
   a NAV item number, ITEM on its row > ITEM on its NAV number > groups of rows and of NAV numbers, see **Every variant
   of a NAV item**).
   Each scope loads its active, in-date promotions, then drops those failing the promo-code,
   minimum-quantity, time-window or day-of-week filters. A scope left with no candidate falls
   through to the next one. The first scope with a candidate wins; less specific scopes are not consulted.
   Cross-product promotions (`get_item_id` set) are skipped here; the cart pass handles them.
2. **Within that scope**: highest `priority`; on a tie, highest `discount_percentage`, then
   `discount_amount`, then `free_quantity` (raw field values, not converted to money).
3. **ERP comparison**: SalesPrice, if any, is the base price. FREE_QUANTITY always applies (with its N; free pieces
   only among the pieces scanned, 3.1.0).
   Otherwise the promotion applies only if its effective % (fixed amount ÷ unit price) beats the
   customer's SalesDiscount %. If it loses, ERP pricing is kept and no other scope is tried.

ALL_ITEMS (next release after 1.11.0) is the storewide fallback. Example: "family Desserts 5%" +
"all items 10%" gives desserts 5% and every other item 10%. To give desserts 10% too, raise or
deactivate the family promotion. If the family promotion is filtered out (wrong day, code not
entered, quantity not met), desserts fall through to the 10%. Minimum quantity is checked on the pieces of every
cart line of the target since step 2 of the promotions for variants (rule 6a), as for every scope. As the buy side of a cross-product promotion, ALL_ITEMS matches every
cart line except the benefit item's own line. The TAX_STAMP line is added server-side at a fixed
price and never goes through promotion pricing.

### Key Design Decisions

- **Zero Preloading**: No Vuex preloading of promotions; all calculation is server-side per event (<5ms with DB indexes)
- **Single promotion per item**: Most specific scope wins (ITEM > ITEM_GROUP > SECTION > ITEM_SUBFAMILY > ITEM_FAMILY > CATEGORY > ALL_ITEMS), then highest priority, then best value — never stacked at item level
- **ITEM-level + CART-level can coexist**: A line can have an ITEM-scope promotion AND the cart can have a CART-scope discount simultaneously
- **Priority is absolute within a scope**: Higher `priority` value always wins regardless of discount value — intentional business control. It never overrides scope specificity
- **ERP pricing vs promotion**: SalesPrice sets the base price the promotion applies to; a percentage/fixed promotion applies only if it beats the customer's SalesDiscount; FREE_QUANTITY always applies
- **Free lines are real DB rows**: `lineTotalIncludingVat=0` ensures return flow gives 0 TND refund without special-casing
- **discountSource tracks origin**: MANUAL (cashier entered), PROMOTION (promo engine), SALES_PRICE, SALES_DISCOUNT — stored on both header and each line for audit and reporting
- **`getItem` is LAZY, so an Item can reach JSON as a Hibernate proxy**: when a page of promotions holds a cross-product promotion whose benefit item is also the `item` (or a group item) of a later promotion, both fields share one proxy. `Item` ignores `hibernateLazyInitializer` / `handler` at class level, so the proxy serializes like a loaded Item. Before 1.12.0 the promotion list returned HTTP 500 in that case ("No serializer found for class ByteBuddyInterceptor"). Test: `core/src/test/java/com/digithink/zsretail/core/model/ItemJsonProxyTest.java`

### Promotions owned by the head office (step 3)

A store with `headoffice.url` and `ownership.promotions=HEAD_OFFICE` receives its promotions from the head office as copies down (task 3.1, `docs/modules/head-office.md`, "Copies down"): the job `COPIES_DOWN` pulls the promotions changed since its cursor. The calculation engine is not changed: it keeps reading the local `promotion` table. Without `headoffice.url`, or with `ownership.promotions` absent or `LOCAL`, nothing changes.

**Origin** (task 3.2): column `promotion.origin` (`VARCHAR(20)`, nullable; entity field `Promotion.origin`, enum `RecordOrigin`): `HEAD_OFFICE` for a promotion received from the head office, `LOCAL` or null for a promotion made at the store. Null means local, so existing rows need no update. Hibernate adds the column (`ddl-auto=update`); for `db/2.1.0/update.sql` (written once at the end of the plan):

```sql
IF COL_LENGTH('promotion', 'origin') IS NULL
    ALTER TABLE promotion ADD origin VARCHAR(20) NULL;
```

The origin is sent in the promotion JSON and never read from a request (`@JsonProperty(access = READ_ONLY)`): the client can never set it. Only the pull job writes `HEAD_OFFICE`; a write through the API keeps the origin the promotion has.

**Write guards** (`PromotionAPI`, task 3.2, with Zein's correction of 2026-10-03: no `promotions.allow-local` setting). The generic CRUD of `_BaseController` is covered: `create`, `update` and `deleteById` are overridden. Reads (`GET /promotion`, `/{id}`, `/paginated`, `/findByField`, `/{id}/usage-count`) are unchanged. `/paginated` takes `active` (true: active, a null flag counts as active; false: inactive; none: all, 2026-10-17): the list page's Active / Inactive / All filter, default All.

| Store | Request | Answer |
|---|---|---|
| Promotions owned by the head office (`ApplicationModeService.isPromotionsOwnedByHeadOffice()`) | `POST /promotion`, `PUT /promotion/{id}` (deactivating included), `DELETE /promotion/{id}`, for every promotion whatever its origin | 409 `Promotions are managed by the head office: on this store they can only be consulted.` (`PUT` on an unknown id: 404) |
| Promotions local | Every promotion of the table, whatever its origin (rule fix of step 3, Zein, 2026-10-03: the store owns them all) | As before: usage lock (409 with the locked fields), delete refused once used (409). The origin is kept: a promotion that came from the head office can be edited, deactivated and deleted, and still shows where it came from |

The page is read-only when `ownership.PROMOTIONS` from `GET /config` is `HEAD_OFFICE` (no new field); `origin` on each promotion is information for a badge only. The guard "origin `HEAD_OFFICE` is always read-only" of the first version of task 3.2 is removed.

**Received promotions** (task 3.3): the head office sends each promotion by codes (`PromotionCopyDTO`: item, family, sub-family, group item and benefit item codes, never an id); the store saves it by its code with `origin=HEAD_OFFICE` through `PromotionService.saveReceived` (same normalisation as `save`, no usage lock, audit user `HEAD_OFFICE`), its codes resolved to the store's own records. Removed or no longer addressed to the store: deleted when never used in a sale here, otherwise set inactive and kept. While promotions are owned by the head office, the store's local promotions are set inactive by the pull job (kept, origin `LOCAL`). Details and the store/head office tables: `docs/modules/head-office.md`, "Promotions owned by the head office".

**Head office** (task 3.3): `PromotionService` calls `PromotionHeadOfficeHooks` (head office only bean): every save and delete is recorded for the promotion's target stores, and `getUsageCount` adds the tickets and lines received from every store with the promotion code, so the edit lock and the delete refusal of a used promotion work for the whole network. Target stores: `/admin/headoffice/promotions/...`.

`PromotionService` changes for this, outside the engine: `normalise` (extracted from `save`, unchanged), `saveReceived`, `getLocalUsageCount`, `deleteById` (transactional, calls the hook on a head office). `PromotionRepository` gains `findByCodeIn` and `findActiveNotFrom`. `PromotionCalculationService`, `PricingService`, `SalesHeaderService` and `ReturnHeaderService` are unchanged.

**Missing targets** (task 3.5): a received promotion whose item, family, sub-family or benefit item is not in the store (or an ITEM_GROUP with none of its items) is not applied: `WAITING` with the reason in `hol_down_record`, and a promotion the store already had becomes inactive. It is retried at every cycle and applies by itself once the record exists. An ITEM_GROUP with some items missing is saved with the items the store has (the missing codes are shown as information). The list and the counts are on the store's Head office link page (`GET admin/holink/received/PROMOTIONS`, `received` in `GET admin/holink/status`).

**Pages** (step 3 frontend, `PromotionsManagement.vue`): on a head office, a Stores section in the form (all stores or a chosen list; a store that owns its promotions cannot be chosen) and a Stores column in the list; on a store whose promotions are owned by the head office, consult only (no create, edit, pause or delete, the form opens read-only, a banner); a "Head office" badge on a promotion whose origin is `HEAD_OFFICE`. A store without a head office sees the page as before. Details: `docs/modules/head-office.md`, "Step 3 pages (frontend)".

**Item groups saved with one item (fixed 2026-10-03, found at L2 of step 3)**: the promotions page sent each group item as `{id}` only. `Promotion.groupItems` is a `Set<Item>` and `Item` uses `@EqualsAndHashCode(callSuper = false)`, which leaves out the id (it is in `_BaseEntity`): items with only an id are all equal, so the set kept one item. Every ITEM_GROUP promotion created or edited from the page before the fix holds one item (two such promotions in `pos_db_prod`, ids from 1.10.0 on). The page now sends `{id, itemCode}` (frontend `PromotionsManagement.vue`). An existing group promotion is corrected by editing it and saving its items again.

**Back end hardened (3.1.0, 2026-10-22)**: the group no longer depends on what the caller sends nor on `Item` equality (made by id since, step 3 of the promotions for variants, see **Every variant of a NAV item**). `Promotion.groupItems` is read by `GroupItemsDeserializer` (`core.utils`): one entry per distinct id, an element `{ id, ... }` or a bare id, an element without an id refused (400). `PromotionService.save` (`loadGroupItems`, before `normalise`) loads those ids with `ItemRepository.findAllById` and refuses an unknown id ("Unknown item id(s) in the group: [..]"), so `POST /promotion`, `PUT /promotion/{id}` and the head office create (`PromotionWithTargetsDTO`) all give the same group for `{ id }`, `{ id, itemCode }` or full items. The store's copy from the head office is unchanged (group items by code, loaded by `PromotionDownHandler`). Test: `PromotionGroupItemsTest`.
