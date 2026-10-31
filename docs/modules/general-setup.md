# General Setup

**Status**: ✅ Complete (Migration 026)

**Migration**: `026_add_config_type_to_general_setup.sql`

Added two columns to `general_setup`:
- `config_type` NVARCHAR(20) NOT NULL DEFAULT 'STRING' — drives the admin UI input widget (BOOLEAN / NUMBER / STRING / DATETIME / SELECT)
- `config_options` NVARCHAR(500) NULL — comma-separated valid values for SELECT type entries

**Rule**: When adding a new `general_setup` entry (in any migration or `ZZDataInitializer`), always add the corresponding `UPDATE … SET config_type = '…'` statement.

### Type assignments

| Type | Codes |
|---|---|
| BOOLEAN | `ENABLE_SIMPLE_RETURN`, `AUTO_ADD_CASH_PAYMENT_ON_PAYMENT_PAGE`, `ENABLE_CASH_DISCREPANCY_CHECK`, `ENABLE_TAX_STAMP`, `LOYALTY_ENABLED`, `ALLOW_NEGATIVE_STOCK`, `POS_SHOW_IMAGES`, `TABLE_MANAGEMENT_ENABLED`, `ALLOW_DECIMAL_QUANTITY` (2.2.1, stores only, see `decimal-quantities.md`), `POS_HIDE_EMPTY_FAMILIES`, `POS_HIDE_EMPTY_SUB_FAMILIES`, `POS_AMOUNTS_3_DECIMALS` (2.2.2, stores only, see below), `POS_CATALOGUE_VISIBLE` (3.1.1, stores only, see below) (`ALWAYS_SHOW_BADGE_SCAN_POPUP` retired at 3.1.0, step C: `badge-permissions.md`) |
| NUMBER | `MAX_DAYS_FOR_RETURN`, `RETURN_VOUCHER_VALIDITY_DAYS`, `PAYMENT_METHOD_*_TITLE_NUMBER_LENGTH` (×4), `TAX_STAMP_VALUE_MILLIMES`, `TABLE_MANAGEMENT_TABLE_COUNT`, `TILL_GRANT_MAX_MINUTES` (3.1.0, step D, stores only, `badge-permissions.md`) |
| DATETIME | All `ERP_SYNC_LAST_*` checkpoints (×9) (the two `FRANCHISE_LAST_*` rows of an older install stay, unused since step 9) |
| SELECT | `ERP_SYNC_TRACKING_LEVEL` (options: `ERRORS_ONLY,ERRORS_AND_WARNINGS,ALL`), `CASHIER_CLOSING_TICKET` (3.1.0, stores only, options `Z,DECLARED`, see below) |
| STRING | Default — all other codes not listed above |

## POS screen: catalogue visible by default (3.1.1)

A store setting (General Setup, POS behaviour section, BOOLEAN, `false`, created at start-up on a store only, as the two
below): `POS_CATALOGUE_VISIBLE` "Catalogue visible by default in the POS screen". Sent by `GET /config` as
`posCatalogueVisible` (`false` when off or absent), read by the front's `appConfig` store; saving it refreshes `/config`.

- The POS screen (`ItemSelection.vue`) shows the scan field, the RFID button and the search in a top row over the whole
  width, with an icon at its start that shows or hides the item catalogue (families, tiles). Catalogue hidden: the
  ticket takes the left of the screen over the whole height, and a right rail holds the client ticker, the totals and
  the action buttons (Paiement last, under the total). Details: `../frontend.md`, "3.1.1, the collapsible catalogue".
- **Which state opens**: the till's own choice when it has one (the cashier tapped the icon once: the browser keeps it,
  `localStorage` key `zsretail.pos.catalogueOpen`), else this setting. Changing the setting does not override a till
  that already chose.
- **Table management** (`TABLE_MANAGEMENT_ENABLED`) always shows the catalogue, without the icon (table service works
  from the tiles).
- Default `false`: after the upgrade to 3.1.1 a till with no choice opens on the ticket alone (a scanning till sees more
  ticket lines); a store that sells from the tiles sets it to yes, or each till taps the icon once.

## POS screen: empty families and sub-families (2.2.2)

Two store settings (General Setup, POS section, BOOLEAN, `false`, created at start-up on a store only, so never shown on a
head office): `POS_HIDE_EMPTY_FAMILIES` "Hide empty families in the POS screen" and `POS_HIDE_EMPTY_SUB_FAMILIES` "Hide
empty sub-families in the POS screen".

- **Empty**: no item the POS grid would list. The grid's rule is one method, `ItemService.listedInPos` (active or not set,
  shown in the POS or not set, unit price above 0), used by `/item/by-sub-family/{id}` as in 2.2.1. A family counts its
  items whatever their sub-family, including none; an item with a sub-family counts for the sub-family's family (where the
  grid shows it), and not at all when that sub-family is inactive (the grid never shows it). The store counts its own items.
- **Where**: `PosCatalogueService` (back end), on the three calls of the POS screen only, new endpoints used by
  `ItemSelection.vue` alone: `GET /item-family/pos`, `GET /item-sub-family/pos/by-family/{familyId}`,
  `GET /item/pos/without-sub-family/{familyId}`. Both settings off, the first two return exactly what `GET /item-family`
  and `GET /item-sub-family/by-family/{id}` return (the same code, nothing counted). Admin pages, filters, imports and
  every other caller keep the full lists (they still call the old endpoints). Head office and sync: unchanged. Scan and
  search: unchanged. 3.1.0 (ZEN): the catégories follow `POS_HIDE_EMPTY_FAMILIES` and the sections
  `POS_HIDE_EMPTY_SUB_FAMILIES` (`modules/head-office.md`, "Till levels catégorie and section").
- **Counted with one grouped query**, `ItemRepository.countPosGridItems` (JPQL, the conditions of `listedInPos`; kept the
  same by `PosGridCountQueryTest` on H2), run once per list when a setting is on. Local timing (2026-10-10, SQL Server,
  equivalent SQL): 7 806 items (copy of a store with 63 families, 193 sub-families) 19 ms cold, 7 to 8 ms warm; 3 181
  items (store B copy) 14 to 21 ms.
- **Always on (a fix, not a setting)**: until 2.2.1 an item with a family but no sub-family could not be reached from the
  grid (items were loaded by sub-family only). Now, when a family is opened, its items without sub-family are listed under
  one extra tile at the end of its sub-families ("Without sub-family" / "Sans sous-famille" / Arabic), shown only when such
  items exist; a family with no sub-family tile at all opens directly on them (breadcrumb: Families > the family; Back
  and the family chip work in both cases). One more call per family opened (cached like the others).
- Tests: `PosCatalogueServiceTest` (the four combinations, both off returns the same lists without counting, items
  without sub-family), `PosGridCountQueryTest`.
## Cashier closing ticket (3.1.0)

A store setting (General Setup, payment methods section, SELECT, created at start-up on a store only, `Z`):
`CASHIER_CLOSING_TICKET`, label "Ticket de clôture caissier", options `Z` "Ticket Z complet" and `DECLARED` "Montants
déclarés seulement" (`SetupField` prop `optionLabels`: a SELECT shows a label per value; the other SELECT shows its
values as before). Sent by `GET /config` as `cashierClosingTicket` (`DECLARED` in any case, else `Z`, also when the row
is absent; null on a head office), read in the front through the getter `appConfig/closingPrintsTicketZ`; saving the
setting refreshes `/config`, and a till reads it again at its next login. What the till prints with each value:
`cash-sessions.md`, "The cashier's closing ticket". Test: `AppConfigAPITest#cashierClosingTicket`.

## POS screens: amounts with 3 decimals (2.2.2)

**3.1.0 (test log I-06): the till always shows 3 decimals, as the receipt.** The getter `appConfig/posAmountDecimals`
returns 3 whatever the setting; the setting's field is no longer on the General Setup page. The row, the `GET /config`
key and the state stay (nothing removed from a database), unused. Since 3.1.0 the ticket amounts are also stored to the
millime (`discounts.md`, "Amounts stored to the millime"). What follows describes 2.2.2.

A store setting (General Setup, POS section, BOOLEAN, `false`, created at start-up on a store only):
`POS_AMOUNTS_3_DECIMALS` "Show amounts with 3 decimals on the POS screens". Sent by `GET /config` as
`posAmountsThreeDecimals`, read in the front through the getter `appConfig/posAmountDecimals` (2 or 3); saving the setting
refreshes `/config`.

- **Display only**: no stored value, calculation or rounding rule changes.
- **Where the 2 decimals are decided**: `formatCurrencyAmount(value, decimals = 2)` / `formatCurrency` in
  `src/@core/utils/filter.js`, shared by about 30 pages (admin, reports, head office, the receipt template asks for 3).
  The default is untouched; the POS screens pass the decimals through their own wrappers:
  `ItemSelection.vue` (`formatPrice`, `formatShortTun`, `formatTunCurrency`: item tiles, cart, lines, totals, the
  dialogs of the screen), `Payment.vue` (`formatTunCurrency`, `formatTunCurrencyWithoutSuffix`: totals, paid, remaining,
  the voucher amount, the cash-limit message; typed amounts are shown as typed), `SplitBillModal.vue` (opened from the
  payment page), `ReturnProducts.vue` (`formatPrice`: unit price, return amount, VAT, totals, the "voucher created"
  message; discount percentages stay at 2 decimals through `formatPercent`).
- **Unchanged either way**: admin pages, reports, printed documents (the ticket and the voucher already print 3; the
  session closing ticket keeps 2), the navbar cart and the table grid.
