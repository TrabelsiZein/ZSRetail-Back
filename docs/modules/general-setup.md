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
| BOOLEAN | `ENABLE_SIMPLE_RETURN`, `ALWAYS_SHOW_BADGE_SCAN_POPUP`, `AUTO_ADD_CASH_PAYMENT_ON_PAYMENT_PAGE`, `ENABLE_CASH_DISCREPANCY_CHECK`, `ENABLE_TAX_STAMP`, `LOYALTY_ENABLED`, `ALLOW_NEGATIVE_STOCK`, `POS_SHOW_IMAGES`, `TABLE_MANAGEMENT_ENABLED`, `ALLOW_DECIMAL_QUANTITY` (2.2.1, stores only, see `decimal-quantities.md`), `POS_HIDE_EMPTY_FAMILIES`, `POS_HIDE_EMPTY_SUB_FAMILIES` (2.2.2, stores only, see below) |
| NUMBER | `MAX_DAYS_FOR_RETURN`, `RETURN_VOUCHER_VALIDITY_DAYS`, `PAYMENT_METHOD_*_TITLE_NUMBER_LENGTH` (×4), `TAX_STAMP_VALUE_MILLIMES`, `TABLE_MANAGEMENT_TABLE_COUNT` |
| DATETIME | All `ERP_SYNC_LAST_*` checkpoints (×9) (the two `FRANCHISE_LAST_*` rows of an older install stay, unused since step 9) |
| SELECT | `ERP_SYNC_TRACKING_LEVEL` (options: `ERRORS_ONLY,ERRORS_AND_WARNINGS,ALL`) |
| STRING | Default — all other codes not listed above |

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
  search: unchanged.
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