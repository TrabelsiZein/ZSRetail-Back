# Loyalty (Fidélité) Program

**Status**: ✅ Complete (Standalone-first, ERP-ready design)

**Overview:**
- Separate `LoyaltyMember` entity (distinct from `Customer`) — a loyalty cardholder can be a walk-in "passenger" or optionally linked to an existing `Customer`.
- Cashiers can create new loyalty members on the spot at the POS.
- Program configuration (rates, limits, expiry) is managed in a dedicated `LoyaltyProgram` entity (versioned, auditable) — only the master `LOYALTY_ENABLED` toggle lives in `GeneralSetup`.
- Full point earn/redeem/reverse/adjust audit trail in `LoyaltyTransaction`.

### Database — New Tables

**`loyalty_member`**
- `id` (PK), `card_number` (UNIQUE, auto-generated format `LYL-000001`), `first_name`, `last_name`, `phone`, `email`, `birth_date`
- `customer_id` (FK → `customer`, nullable — optional ERP link)
- `loyalty_points` (current balance, DEFAULT 0), `total_points_earned`, `total_points_redeemed`
- `erp_external_id` (for future ERP sync), `active`, audit fields

**`loyalty_program`**
- `id` (PK), `program_code` (UNIQUE), `name`, `description`, `start_date`, `end_date`
- `points_per_dinar` (e.g. 10 → 10 pts per 1 TND), `point_value_millimes` (e.g. 10 → 1 pt = 10 millimes = 0.010 TND)
- `minimum_redemption_points`, `maximum_redemption_percentage` (caps redemption as % of sale total — e.g. 30% means max 30 TND can be paid with points on a 100 TND sale)
- `points_expiry_days` (null = never expires), `active`
- Only ONE row can have `active = true` at a time (enforced in service)

**`loyalty_transaction`** (immutable audit log — records never updated/deleted)
- `id`, `loyalty_member_id` (FK), `loyalty_program_id` (FK, nullable), `sales_header_id` (FK, nullable), `return_header_id` (FK, nullable), `cashier_session_id` (FK, nullable)
- `type` (ENUM: `EARNED`, `REDEEMED`, `ADJUSTED`, `REVERSED`), `points` (always positive), `balance_before`, `balance_after`
- `description`, `expiry_date`, `created_at`, `created_by`

**`sales_header`** — added columns:
- `loyalty_member_id` (FK → `loyalty_member`, nullable)
- `loyalty_points_earned`, `loyalty_points_redeemed`, `loyalty_deduction_amount`

### Backend — New Files
- **Entities**: `model/LoyaltyMember.java`, `model/LoyaltyProgram.java`, `model/LoyaltyTransaction.java`, `model/enumeration/LoyaltyTransactionType.java`
- **Repositories**: `LoyaltyMemberRepository` (with `findMaxCardSequence` native SQL Server query using `SUBSTRING(col, 5, LEN(col)-4)`), `LoyaltyProgramRepository`, `LoyaltyTransactionRepository` (with `findAllFiltered` JPQL cross-member query)
- **DTOs**: `LoyaltyConfigDTO`, `LoyaltyMemberDTO` (includes `pointsValueDinars`), `CreateLoyaltyMemberRequestDTO`, `LoyaltyTransactionDTO` (includes `memberCardNumber`, `memberFullName` for cross-member queries), `LoyaltyAdjustmentRequestDTO`
- **Service**: `LoyaltyService` — methods: `getLoyaltyConfig`, `searchMembers`, `createMember`, `updateMember`, `toggleActive`, `earnPoints`, `redeemPoints`, `reversePoints`, `adjustPoints`, `activateNewProgram`, `getAllPrograms`, `getTransactionHistory`, `getAllTransactionsFiltered`, `getPointsValueInDinars`
- **Controller**: `LoyaltyAPI` (`/loyalty/*`) — endpoints for config, member CRUD/search/toggle, balance, per-member transactions, cross-member transactions (paginated + filtered), adjustment, programs CRUD

### Backend — Modified Files
- `ProcessSaleRequestDTO`: added `loyaltyMemberId`, `loyaltyPointsToRedeem`
- `SalesHeaderService.processCompleteSale()`: calls `earnPoints` and `redeemPoints`
- `ReturnHeaderService.processReturn()`: calls `reversePoints`
- `ZZDataInitializer`: seeds `LOYALTY_ENABLED=false` in `GeneralSetup`
- `AppConfigDTO` / `AppConfigAPI` (`GET /config`): exposes `loyaltyEnabled` flag
- `LoyaltyTransactionRepository`: `findMaxCardSequence` uses **native SQL Server query** (`SUBSTRING(card_number, 5, LEN(card_number)-4)`) because SQL Server's `SUBSTRING` requires 3 arguments (unlike MySQL/H2)

### Frontend — New Files
- `src/views/admin/LoyaltyMembersManagement.vue`: paginated member table with search + status filter; detail modal with member info, edit form, points summary, last 5 transactions + "View All" button; adjust points modal; create member modal
- `src/views/admin/LoyaltyProgramManagement.vue`: list of all programs (active highlighted); create new program form (auto-deactivates current); immutable past programs; detail modal
- `src/views/admin/LoyaltyTransactions.vue`: full audit page — search (card/name/phone), type filter (EARNED/REDEEMED/ADJUSTED/REVERSED), date-from/to range, Member column (card # + name, clickable → navigates to member detail), paginated; supports `?memberId=X` query param from member popup "View All" link

### Frontend — Modified Files
- **`ItemSelection.vue`**: "★ Fidélité" action button (shown only when `loyaltyEnabled`); `LoyaltyMemberModal` (search/create modal); loyalty badge in cart showing member name + points; loyalty member stored in component state and passed to Payment via `sessionStorage`
- **`Payment.vue`**: loyalty redemption panel showing current balance, points-to-redeem input (validated against min/max program rules), loyalty deduction shown in summary; `loyaltyMemberId` and `loyaltyPointsToRedeem` sent in `ProcessSaleRequestDTO`
- **`ReceiptTemplate.vue`**: loyalty summary block (shown only on regular sales, not vouchers/warranties) — card number, member name, points earned (+), points redeemed (-), deduction amount, new balance
- **`src/navigation/vertical/index.js`**: three loyalty entries under Sales group (header + Members + Programs + Transactions)
- **`src/router/index.js`**: routes `admin-loyalty-members`, `admin-loyalty-programs`, `admin-loyalty-transactions`
- **`src/views/Login.vue`**: ADMIN and RESPONSIBLE abilities include `admin-loyalty-members`, `admin-loyalty-programs`, `admin-loyalty-transactions` (read + write)
- **`src/store/app-config/index.js`**: `loyaltyEnabled` state fetched from `GET /config`
- **i18n** (`en.json`, `fr.json`, `ar.json`): added `pos.loyalty.*`, `admin.loyaltyMembers.*`, `admin.loyaltyPrograms.*`, `admin.loyaltyTransactions.*`, `admin.loyaltyMembersMenu`, `admin.loyaltyProgramsMenu`, `admin.loyaltyTransactionsMenu`; also added `common.all`, `common.active`, `common.inactive` (were missing from `common` block — caused raw key display in status dropdowns)

### Key Design Decisions
- **LoyaltyMember ≠ Customer**: Loyalty is lightweight — any walk-in can get a card; linking to a formal ERP customer is optional via nullable FK
- **LOYALTY_ENABLED only in GeneralSetup**: Full program config (rates, limits, expiry) lives in `loyalty_program` table
- **Immutable transaction log**: `loyalty_transaction` rows are never edited; adjustments create new `ADJUSTED` rows
- **ERP-ready**: `erp_external_id` on `LoyaltyMember`; `LoyaltyService` is modular for future ERP sync without changing core logic
- **SQL Server compatibility**: Card number sequence query uses native 3-arg `SUBSTRING` instead of JPQL 2-arg form

### Migration
- `012_add_loyalty_general_setup.sql` — idempotent insert of `LOYALTY_ENABLED=false` into `general_setup` (SQL Server T-SQL)
- New tables (`loyalty_member`, `loyalty_program`, `loyalty_transaction`) and new columns on `sales_header` are created by Hibernate `ddl-auto` or must be added manually via migration if using `validate` mode
