# Dynamic Pricing (SalesPrice & SalesDiscount)

**Status**: ✅ Complete

**Overview:**
- Integrated `SalesPrice` and `SalesDiscount` tables for dynamic pricing and discount calculation
- Implements best-value selection logic (lowest price, highest discount) without priority
- Configuration-driven feature toggle via `pos.pricing.enable-sales-price-group` property
- Only calculates prices when adding items to cart (not for display in POS grid)

**Key Features:**

### Pricing Logic (SalesPrice)
- **Best-Value Selection**: Finds all matching records and selects the **lowest price** (best for customer)
- **Match Criteria**: Customer Price Group → Customer → All Customers (all checked, best wins)
- **Date Validation**: `startingDate <= currentDate` and `endingDate >= currentDate` (null = always valid)
- **VAT Handling**: `Price_Includes_VAT` field determines if price is HT or TTC
  - Backend always returns HT prices (converts TTC to HT if needed)
  - Frontend receives HT prices and applies VAT for display
- **Default Customer**: Uses default customer from `GeneralSetup` (PASSENGER_CUSTOMER) if `customerId` is null

### Discount Logic (SalesDiscount)
- **Best-Value Selection**: Finds all matching records and selects the **highest discount percentage**
- **Type Priority**: Item Disc. Group → Item (both checked, best wins)
- **Sales Type Matching**: Customer Disc. Group → Customer → All Customers (all checked, best wins)
- **Date Validation**: Same as SalesPrice (null dates = always valid)

### Implementation Details

**Backend:**
- **New Enums**: `SalesPriceType`, `SalesDiscountType`, `SalesDiscountSalesType`
- **Entities**: 
  - `SalesPrice`: Added `priceIncludesVat` (Boolean), dates changed to `LocalDate`, `salesType` as enum
  - `SalesDiscount`: Dates changed to `LocalDate`, `type` and `salesType` as enums
  - `Item`: Added `itemDiscGroup` field
- **Service**: `PricingService` with `calculateItemPrice()` method
  - Single query approach: Finds all matches, database sorts, returns best value
  - Optimized indexes for best-value selection queries
- **Repository Queries**:
  - `SalesPriceRepository.findAllMatchingSalesPrices()`: Returns all matches ordered by `unitPrice ASC`
  - `SalesDiscountRepository.findAllMatchingDiscounts()`: Returns all matches ordered by `lineDiscount DESC`
- **API Endpoints**:
  - `GET /item/{itemId}/calculate-price`: Calculates price for specific item with customer
  - `GET /item/pricing-config`: Returns pricing feature enabled status
- **ERP Sync**: 
  - `ErpItemBootstrapService` handles `priceIncludesVat`, `itemDiscGroup`, enum conversion
  - Skips saving records if enum conversion fails (logs warning)
  - Defaults `startingDate` to `LocalDate.now()` if null from ERP

**Frontend:**
- **ItemSelection.vue**: 
  - Calls `/item/{itemId}/calculate-price` when adding items to cart (if pricing enabled)
  - Loads default customer on mount to prevent false "customer change" detection
  - Auto-recalculates cart when customer changes (detected via sessionStorage)
- **CustomerList.vue** (POS):
  - Server-side pagination with search (only active customers)
  - Visual selection indicator: Green highlighted row + checkmark icon
  - Info alert when selected customer not in current page: "Selected customer: [name] ([code])"
  - Always returns to ItemSelection when customer is changed (ensures recalculation)
- **CustomerManagement.vue** (Admin):
  - Server-side pagination with search and status filter
  - Prevents setting inactive customers as default (frontend + backend validation)
  - Shows status column (active/inactive)

**Performance Optimizations:**
- Database indexes optimized for best-value queries:
  - `idx_sales_price_best_value`: `(item_no, unit_price, starting_date)`
  - `idx_sales_discount_best_value`: `(type, code, line_discount, starting_date)`
- Early exit when pricing is disabled (no database calls)
- Cached default customer in `CustomerService.getDefaultCustomer()`

**Customer Change Flow:**
1. User selects customer in CustomerList
2. If pricing enabled and cart has items → Shows confirmation dialog
3. On confirm → Customer selected, navigates to ItemSelection
4. ItemSelection detects customer change → Auto-recalculates all cart items
5. User sees updated prices → Can proceed to Payment

**Key Files:**
- `src/main/java/com/digithink/zsretail/service/PricingService.java` - Core pricing logic
- `src/main/java/com/digithink/zsretail/model/SalesPrice.java` - Entity with enums and indexes
- `src/main/java/com/digithink/zsretail/model/SalesDiscount.java` - Entity with enums and indexes
- `src/main/java/com/digithink/zsretail/repository/SalesPriceRepository.java` - Best-value query
- `src/main/java/com/digithink/zsretail/repository/SalesDiscountRepository.java` - Best-value query
- `src/main/java/com/digithink/zsretail/controller/ItemAPI.java` - Price calculation endpoint
- `src/views/pos/ItemSelection.vue` - Frontend integration
- `src/views/pos/CustomerList.vue` - Customer selection with pagination
