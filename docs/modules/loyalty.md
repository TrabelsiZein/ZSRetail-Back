# Loyalty (Fidélité) Program

**Status**: ✅ Complete (Standalone-first, ERP-ready design)

**Overview:**
- Separate `LoyaltyMember` entity (distinct from `Customer`) — a loyalty cardholder can be a walk-in "passenger" or optionally linked to an existing `Customer`.
- Cashiers can create new loyalty members on the spot at the POS.
- Program configuration (rates, limits, expiry) is managed in a dedicated `LoyaltyProgram` entity (versioned, auditable) — only the master `LOYALTY_ENABLED` toggle lives in `GeneralSetup`.
- Full point earn/redeem/reverse/adjust audit trail in `LoyaltyTransaction`.

### Database — New Tables

**`loyalty_member`**
- `id` (PK), `card_number` (UNIQUE, auto-generated format `LYL-000001`), `first_name`, `last_name`, `phone` (required, 8 digits, unique: enforced by the service, not the database; see **Member phone number**), `email`, `birth_date`
- `customer_id` (FK → `customer`, nullable — optional ERP link)
- `loyalty_points` (current balance, DEFAULT 0), `total_points_earned`, `total_points_redeemed`
- `erp_external_id` (for future ERP sync), `active`, audit fields

**`loyalty_program`**
- `id` (PK), `program_code` (UNIQUE), `name`, `description`, `start_date`, `end_date`
- `points_per_dinar` (e.g. 10 → 10 pts per 1 TND), `point_value_millimes` (e.g. 10 → 1 pt = 10 millimes = 0.010 TND)
- `minimum_redemption_points`, `maximum_redemption_percentage` (caps redemption as % of the ticket amount after other discounts, before points, fiscal stamp excluded — e.g. 30% means max 30 TND can be paid with points on a 100 TND sale; see **Redemption limit**)
- `points_expiry_days` (null = never expires), `active`
- Only ONE row can have `active = true` at a time (enforced in service)

**`loyalty_program_tier`** (optional earning tiers; created by Hibernate `ddl-auto=update`, no SQL script needed)
- `loyalty_program_id` (FK → `loyalty_program`), `threshold_amount` (TND), `points_per_dinar`
- No rows = flat program: the program's `points_per_dinar` applies to every ticket

**`loyalty_transaction`** (immutable audit log — records never updated/deleted)
- `id`, `loyalty_member_id` (FK), `loyalty_program_id` (FK, nullable), `sales_header_id` (FK, nullable), `return_header_id` (FK, nullable), `cashier_session_id` (FK, nullable)
- `type` (ENUM: `EARNED`, `REDEEMED`, `ADJUSTED`, `REVERSED`), `points` (always positive), `balance_before`, `balance_after`. An `ADJUSTED` row linked to a sale is points given back after a return (manual adjustments are never linked to a sale); the UI shows its sign from the balance before/after
- `description`, `expiry_date`, `created_at`, `created_by`

**`sales_header`** — added columns:
- `loyalty_member_id` (FK → `loyalty_member`, nullable)
- `loyalty_points_earned`, `loyalty_points_redeemed`, `loyalty_deduction_amount`

### Backend — New Files
- **Entities**: `model/LoyaltyMember.java`, `model/LoyaltyProgram.java`, `model/LoyaltyTransaction.java`, `model/enumeration/LoyaltyTransactionType.java`
- **Repositories**: `LoyaltyMemberRepository` (with `findMaxCardSequence` native SQL Server query using `SUBSTRING(col, 5, LEN(col)-4)`), `LoyaltyProgramRepository`, `LoyaltyTransactionRepository` (with `findAllFiltered` JPQL cross-member query)
- **DTOs**: `LoyaltyConfigDTO`, `LoyaltyMemberDTO` (includes `pointsValueDinars`), `CreateLoyaltyMemberRequestDTO`, `LoyaltyTransactionDTO` (includes `memberCardNumber`, `memberFullName` for cross-member queries), `LoyaltyAdjustmentRequestDTO`
- **Service**: `LoyaltyService` — methods: `getLoyaltyConfig`, `searchMembers`, `createMember`, `updateMember`, `toggleActive`, `earnPoints`, `redeemPoints`, `applyReturn`, `adjustPoints`, `activateNewProgram`, `getAllPrograms`, `getTransactionHistory`, `getAllTransactionsFiltered`, `getPointsValueInDinars`
- **Controller**: `LoyaltyAPI` (`/loyalty/*`) — endpoints for config, member CRUD/search/toggle, balance, per-member transactions, cross-member transactions (paginated + filtered), adjustment, programs CRUD

### Backend — Modified Files
- `ProcessSaleRequestDTO`: added `loyaltyMemberId`, `loyaltyPointsToRedeem`
- `SalesHeaderService`: both ways of completing a sale, `processCompleteSale()` (direct sale) and `completePendingSale()` (parked ticket completed later), attach the card and call `redeemPoints` then `earnPoints` through the shared helpers `attachLoyaltyMember` / `processLoyaltyPoints`, before the NAV export. Before 2026-09, parked tickets ignored the card: no points earned, and a conversion was discounted without debiting the points. Split bills (`splitAndPay`, restaurant tables) have no loyalty.
- `SalesHeaderAPI`: `POST /sales-header/process-sale` and `POST /sales-header/complete-pending/{id}` both return `loyaltyMember` (with the new balance), `loyaltyPointsEarned`, `loyaltyPointsRedeemed`, `loyaltyDeductionAmount` and the receipt flags `showLoyaltyBalance` / `showLoyaltyEarned` (settings `TICKET_SHOW_LOYALTY_BALANCE` / `TICKET_SHOW_LOYALTY_EARNED`, default true). Before 2026-09 the parked-ticket response had none of them, so its receipt printed no loyalty block.
- `ReturnHeaderService.processReturn()`: calls `applyReturn` (see **Returns**); for a ticket paid partly with points, refunds only the money paid (`loyaltyRefundFactor`). `ReturnHeaderAPI /ticket-details` sends `loyaltyPointsRedeemed`, `loyaltyDeductionAmount`, `loyaltyRefundFactor`, `loyaltyHeaderFactor` so `ReturnProducts.vue` shows the same refund, with the points share on its own line.
- `ZZDataInitializer`: seeds `LOYALTY_ENABLED=false` in `GeneralSetup`
- `AppConfigDTO` / `AppConfigAPI` (`GET /config`): exposes `loyaltyEnabled` flag
- `LoyaltyTransactionRepository`: `findMaxCardSequence` uses **native SQL Server query** (`SUBSTRING(card_number, 5, LEN(card_number)-4)`) because SQL Server's `SUBSTRING` requires 3 arguments (unlike MySQL/H2)

### Frontend — New Files
- `src/views/admin/LoyaltyMembersManagement.vue`: paginated member table with search + status filter; detail modal with member info, edit form, points summary, last 5 transactions + "View All" button; adjust points modal; create member modal
- `src/views/admin/LoyaltyProgramManagement.vue`: list of all programs (active highlighted); create new program form (auto-deactivates current); immutable past programs; detail modal; optional earning-tier editor ("above X TND → Y pts/TND") with a live range preview, tiers shown in the detail modal and as a badge in the list
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

### Earning tiers (points by ticket amount)

- A program may define tiers "above X TND → Y points per TND". The ticket amount picks the highest tier it is **strictly above**, and that rate applies to the **whole ticket** (not progressive). A ticket exactly on a limit stays in the lower tier.
- Amount used: the sale total sent by the POS (after discounts and after points redeemed on the same ticket) **minus the fiscal stamp**; `SalesHeaderService` passes the stamp amount returned by `addTaxStampLineIfEnabled` to `earnPoints`. Points are rounded down.
- Example: base 1, above 200 → 1.5, above 500 → 2 gives 150 TND → 150 pts, 200 → 200, 350 → 525, 500 → 750, 650 → 1300.
- **Flat programs are unchanged**: without tiers, `earnPoints` keeps the previous formula `floor(total × points_per_dinar)` on the total including the stamp, with the same history text. Tiered EARNED rows add the rate used: `Points earned from sale #X (1.5 pts/TND)`.
- Validation (`LoyaltyService.normalizeTiers`): amount > 0 (rounded to the millime), rate > 0, no two tiers on the same amount; stored sorted. Invalid tiers return HTTP 400 before the current program is closed.
- Locking follows the rate fields: editable while the program has no transactions, then any change returns HTTP 409 (resending the same tiers is accepted). In `PUT /loyalty/programs/{id}`, `earningTiers` absent or null = unchanged, `[]` = remove all tiers.
- Mapping: `@ElementCollection(fetch = EAGER)` + `@Fetch(FetchMode.SELECT)`, so tiers are never join-fetched with another collection (no `MultipleBagFetchException` at startup).
- Tests: `src/test/java/com/digithink/zsretail/service/LoyaltyEarningTiersTest.java` (plain JUnit 5 with in-memory stubs, no Spring context).

### Returns

`LoyaltyService.applyReturn` runs on every return of a ticket with a member card. It is cumulative over all returns of the ticket (it reads the ticket's EARNED / REDEEMED / ADJUSTED / REVERSED rows), so nothing is given back or removed twice:
- **Converted points go back** in proportion to the goods returned so far (`ADJUSTED` row linked to the sale and the return). A full return gives all of them back.
- **Earned points are recalculated** with the sale's program on the money kept for the goods not returned (flat programs on that amount plus the stamp, tiered programs without it); only the difference is removed (`REVERSED`). Example: 650 TND ticket, 1300 pts; returning 200 TND keeps 450 TND = 675 pts at 1.5, so 625 are removed. The balance never goes below 0 (unchanged).
- **Money refund** (`ReturnHeaderService`): for a ticket with `loyaltyDeductionAmount > 0`, refund = returned goods TTC × (total − stamp) / goods TTC, i.e. only the money actually paid. The return's `discountPercentage` stores the combined rate (header discount + points share, 5 decimals) so NAV reconciles lines × (1 − pct) = refund, as the sales export already does. Tickets without points keep the previous pct / ratio rules unchanged.
- Before 2026-09: any return removed all of the ticket's earned points (again on each later return), converted points were never given back, and with a percentage header discount the refund paid the points share back in money.
- Tests: `src/test/java/com/digithink/zsretail/service/ReturnRefundLoyaltyTest.java` (also pins the unchanged refunds of tickets without points).

### Redemption limit

- The POS (`Payment.vue`, computed `maxRedeemablePoints`) and the server (`LoyaltyService.redeemPoints`) use the same base: ticket amount after other discounts and before points, **fiscal stamp excluded**. Points never pay the stamp, so with a stamp enabled there is always something left to pay.
- The POS sends `totalAmount` already net of the points deduction, so the server rebuilds the base as `totalAmount + deduction - stamp` (the stamp comes from `addTaxStampLineIfEnabled`), with a half-millime tolerance.
- Before 2026-09 the server applied the percentage to the total net of the deduction: at 100%, any conversion over half the ticket was refused.
- Edge case: with no fiscal stamp and points covering the whole ticket, nothing remains to pay and the POS cannot complete the sale; the cashier converts slightly fewer points.
- Tests: `SaleCompletionLoyaltyStampTest` (conversion tests) and `LoyaltyEarningTiersTest#redeemWithoutStampOverload`.

### Member phone number

Requested by Groupe Hammami (2026-09): the phone is required when a member is created and must be unique, so a cashier who can't find a member by name (spelling variants) can't give them a second card.
- `LoyaltyService.normalizePhone` removes spaces, dots, dashes, slashes, brackets and a `+216` / `00216` prefix. The number is saved in that form and must then be exactly 8 digits.
- Unique across **all** members, deactivated ones included. `LoyaltyService.validatePhone` rejects a duplicate by naming the card that holds the number, the active one first: « Ce numéro est déjà utilisé par la carte LYL-000090 (ALI KHARAT) », followed by « , carte désactivée » when that card is inactive.
- Create (`POST /loyalty/member`): always checked. Edit (`PUT /loyalty/member/{id}`): checked only when the normalized number changes, so members saved before the rule (shared or 7-digit numbers) can still be edited without retyping the number. A member saved with no phone must be given one on the next edit.
- HTTP: missing or badly formatted number → 400, duplicate → 409 (`IllegalStateException`). `POST /loyalty/member` used to answer 500 for every validation error.
- Frontend: phone marked `*`, `required` and `type="tel"` in the POS modal (`ItemSelection.vue`) and in the admin create and edit forms (`LoyaltyMembersManagement.vue`). The admin create button is disabled while the request runs.
- No database constraint: members that already share a number would block a unique index. In Hammami production (2026-09-25, 250 members) 9 pairs share a number, almost all the same person entered twice. Merge them by hand: **Adjust points** to move the balance to the card being kept, then deactivate the other card. With loyalty `LOCAL` uniqueness holds per database only; members are not synced between installations. With loyalty owned by the head office (step 4) it holds across the network: see "Shared loyalty".
- Tests: `src/test/java/com/digithink/zsretail/service/LoyaltyMemberPhoneTest.java`.

### Shared loyalty (head office plan, step 4)

With `ownership.loyalty=HEAD_OFFICE` (store with `headoffice.url`), one member register for the network, held by the head office. Full description: `docs/modules/head-office.md`, "Shared loyalty (step 4)". With loyalty `LOCAL` (every existing install) nothing below applies and `LoyaltyService` runs exactly as described above.
- `LoyaltyService` calls `LoyaltyNetworkHooks` only when such a bean exists (head office, or store with loyalty owned by the head office): card number of a new member, which members count for the phone uniqueness, after a member or a program is saved. The sale methods (`earnPoints`, `redeemPoints`, `applyReturn`) do not call it. `normalizePhone` is public, and the duplicate message comes from `phoneTakenMessage` (same text, also used by the head office).
- Card numbers: `LYL-HO-000001` at the head office, `LYL-<store code>-000001` at a store whose loyalty is owned by the head office, each its own sequence (`LoyaltyCardNumbers`). `LYL-000001` stays the format of loyalty `LOCAL`.
- `loyalty_member.origin` and `loyalty_program.origin` (`RecordOrigin`, null = `LOCAL`): `HEAD_OFFICE` marks the network register at a store.
- At the head office the existing pages manage the program, the members and the ledger; every change reaches every store (copies down). The stores' movements arrive as `loyalty_transaction` rows created by `STORE:<code>`, with the store and the sale in the description.
- At the store (`LoyaltyAPI`): enrolling checks the phone across the network first when the head office answers within 3 s (a phone with a card: today's 409 naming that card, the member saved here), otherwise checks here and creates here; the member is sent up by the job `LOYALTY_PUSH`. Edit, deactivate and link a customer go through the head office and need the store's right (`canEditMembers`); the program and the manual adjustments answer 409. Search, the POS, earning, spending and returns are unchanged and work with the head office stopped; their `loyalty_transaction` rows are sent up by the job.
- The balance shown at the store is the head office balance plus the store's movements not applied there yet: it never goes backwards because of the order of sync.
- The members and the program made at the store before the switch are switched off at the first pull (kept); their points are not moved to the network (importing an existing member list is a later tool).
- `GET /loyalty/network` (only then): `{ownedByHeadOffice, linkState, canEditMembers, canAdjustPoints, programEditable: false, pointsAdjustable: false}` for the pages.

### Key Design Decisions
- **Loyalty failures refuse the sale with their reason**: `redeemPoints` / `earnPoints` are `@Transactional` and join the sale's transaction, so any failure there rolls the whole sale back. `SalesHeaderService.processLoyaltyPoints` therefore does not catch: the real reason (e.g. "Insufficient points", "Minimum redemption is 600 points", "Redemption exceeds maximum allowed") reaches the POS as HTTP 400/409. Before 2026-09 it caught the exception, and the cashier got Spring's generic `UnexpectedRollbackException` instead.
- **LoyaltyMember ≠ Customer**: Loyalty is lightweight — any walk-in can get a card; linking to a formal ERP customer is optional via nullable FK
- **LOYALTY_ENABLED only in GeneralSetup**: Full program config (rates, limits, expiry) lives in `loyalty_program` table
- **Immutable transaction log**: `loyalty_transaction` rows are never edited; adjustments create new `ADJUSTED` rows
- **ERP-ready**: `erp_external_id` on `LoyaltyMember`; `LoyaltyService` is modular for future ERP sync without changing core logic
- **SQL Server compatibility**: Card number sequence query uses native 3-arg `SUBSTRING` instead of JPQL 2-arg form

### Migration
- `012_add_loyalty_general_setup.sql` — idempotent insert of `LOYALTY_ENABLED=false` into `general_setup` (SQL Server T-SQL)
- New tables (`loyalty_member`, `loyalty_program`, `loyalty_transaction`) and new columns on `sales_header` are created by Hibernate `ddl-auto` or must be added manually via migration if using `validate` mode
