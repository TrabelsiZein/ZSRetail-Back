# Bulk sale ("vente en vrac", 3.1.2)

Items sold by volume or weight at the till (Happyness: perfume "V H 52 1L" sold 0.100 L at a time). The decimal
quantities themselves exist since 2.2.1 (`decimal-quantities.md`); this feature adds what makes them quick to sell: an
item flag and a list of quick quantities the till will offer. Built in steps on `release/3.1.2`; **step 1 is done**
(2026-11-03, back end and admin screens); the till's popup is step 2 (below, "Still to do").

## Decisions (Zein, 2026-11-03)

- **The gate stays `ALLOW_DECIMAL_QUANTITY`** (General Setup, store only, `QuantityPolicy`). The flag only decides
  whether the till opens the quick quantities popup; it is not a per-item permission to use decimals. A store with
  decimals on and no flag sells as in 2.2.1 (Happyness today).
- **One quick quantities list per store**, not per item. A quick quantity may name a **linked item**: a container
  ("Flacon 100 ml") the till will add as its own line with the bulk line.
- **A bulk item scanned twice makes a new line** (till step): each pick is its own line, never "+1" on the line.
- **Invoices from tickets stay whole**: out of scope, a known limit (below).

## Step 1: the item flag `Item.soldInBulk`

- **Column** `item.sold_in_bulk` bit NULL, store and head office; null = false (every item of before stays null).
  `@JsonProperty(access = READ_ONLY)`: never read from JSON, so the full `PUT /item/{id}` (the item form) keeps the
  stored value (`ItemAPI.update` copies it from the stored item, as `ownPrice` and `clearance`), and `POST /item`
  creates the item without it.
- **`PUT /item/{id}/bulk-flag`** `{"soldInBulk": true}` (`ItemAPI.setBulkFlag`), built as `package-flag`: allowed in
  every mode, **an ERP catalogue included** (the full PUT answers 403 there: no connector reads or writes the field);
  404 for an unknown id; on a store whose catalogue is the head office's (`CatalogueEditGuard`), a head office item
  answers 409 ("This item is managed by the head office...": the head office decides). Only this field changes; false is
  stored as null. Saved through `ItemService.save`, so on a head office the catalogue hooks record the change for its
  stores (a head office without an ERP, or whose catalogue comes only from the ERP: `HoCatalogueService`). On a head
  office whose catalogue comes only from the ERP, `HeadOfficeErpCatalogueFilter` leaves `/item/{id}/bulk-flag` open,
  as the pack flag.
- **The copies down** (`CatalogueItemCopyDTO.soldInBulk`, the 3.1.1 images model): the head office sends `true` or
  `false` (`of`: its null is false), on every path (with or without points de stock: `pointCopy` builds on `of`).
  The store (`CatalogueCopyWriter`) writes it (false as null) and counts it in "unchanged" (`isUnchanged`: the
  store's null and the copy's false are the same, so a 3.1.2 head office rewrites no item of a store of before). A copy
  without the field (a head office of an older build) keeps the store's value and never makes the copy look changed.
  A store of an older build ignores the field (`ignoreUnknown`).
- **The ERP sync** never touches it: `ErpItemBootstrapService.applyItemValues` sets its fields one by one, so a flag set
  on an ERP store (EMTOP) or an ERP head office (ZEN, Happyness, ParaFendri) survives every import.
- **The till** receives it with no change: the till's item lists (`/item/pos/*`, `/item-barcode/...`, `/item/{id}`)
  give the `Item` entity. Nothing reads it there yet (step 2).
- **Screens** (`ZSRetail-Front`): the store's item card (`StoreItemCard`, a switch in the band, saved at once; on a head
  office item of a store fed by its head office the value only, "Décidé par le siège"; rule `bulkFlagEditable` in
  `libs/item-card.js`, `tools/store-item-card-check.mjs`), the item form (`ItemManagement`, a box beside "Afficher en
  caisse", sent by `bulk-flag` after the item's save, only when changed), the head office's item card
  (`ItemCatalogueCard`: a switch for a single item, "Vendu en vrac" in each variant's menu for a NAV item; the card's
  `Variant.soldInBulk`).

## Step 1: the quick quantities `QuickQuantity` (store only)

- **Table** `quick_quantity` (store only: a head office database never has it), entity `store.model.QuickQuantity`:
  `label` varchar(100) NOT NULL ("100 ml"), `quantity` DECIMAL(18,3) NOT NULL (in the item's unit: 0.1 for 100 ml of
  an item sold by the litre; read without trailing zeros), `linked_item_id` (FK `item`, NULL: no container),
  `display_order` int (lowest first), and `_BaseEntity`'s `active` and audit columns.
- **API** `/quick-quantity` (`QuickQuantityAPI`, the generic CRUD as `PaymentMethodAPI`): `GET`, `GET /{id}`, `POST`,
  `PUT /{id}`, `DELETE /{id}`. `QuickQuantityService.check` before any write: a label (trimmed, at most 100 characters),
  a quantity above 0 with at most 3 decimals (normalized: 0.100 is 0.1), the linked item read by its id (only
  `{ "id": n }` is sent; an unknown id refused), a missing order 0. A refusal answers **400** with the reason, nothing
  written. Not checked: whether `ALLOW_DECIMAL_QUANTITY` is on (a list may be prepared before), duplicates.
- **An item used as a linked item is not deleted**: `QuickQuantityItemDeletionCheck` (an `ItemDeletionCheck`, as
  RFID's) answers 409 "This item is the linked item of 2 quick quantities: change or delete them first (Settings,
  Quick quantities)." instead of the foreign key error.
- **Page** Settings > "Quantités rapides" (`QuickQuantitiesManagement.vue`, route `/admin/quick-quantities`, copied
  from the payment methods page with creation and deletion; the linked item chosen with `ItemPicker`; the quantity read
  by the till's parser `parseQuantity`, "0,1" or "0.1"). **Shown only with `ALLOW_DECIMAL_QUANTITY` on**
  (`BULK_SALE_ROUTES` in `navigation/store-mode-routes.js`: the menu, the router guard and the search bar; false on a
  head office). **No new permission**: the menu entry reads `read:admin-payment-methods`, the page's changes
  `write:admin-payment-methods`, so no role table changes on any installation.

## Database

`db/3.1.2/bulk-sale.sql` (before `db/3.1.2/update.sql`, the same file on a store and a head office; re-runnable):
`item.sold_in_bulk` added when missing (`COL_LENGTH`), `quick_quantity` created when missing on a store only
(`OBJECT_ID(N'ho_store')` null), with the names and types Hibernate gives (a start creates both too). No data
changed. `update.sql` stops while the column, or on a store the table, is missing.

## Customers without the flag

EMTOP, ZEN, ParaFendri (and Happyness until an item is flagged): every item stays null, so nothing changes: the till,
the sale, the exports, the reports and the copies behave as before (the copy carries `soldInBulk: false`, equal to
the store's null). No connector reads the flag. The quick quantities page is hidden while `ALLOW_DECIMAL_QUANTITY` is
off (EMTOP keeps it off); its table stays empty. No permission is added to any role. The only new behaviours anywhere:
the item card's switch, the form's box, and the 409 on deleting an item used as a linked item (none exists until a
quick quantity is made).

## Still to do (step 2, the till)

- `ItemSelection.addToCart`: an item with `soldInBulk` and `allowDecimalQuantity` on opens a popup before pricing
  (the line discount modal's look): the active quick quantities by display order (`GET /quick-quantity`, read once per
  screen) and a free box read by `parseQuantity`; the choice calls the pricing with that quantity. Setting off: the
  item is added as today (1).
- A bulk pick is **always a new line** (the merge by item id of `addToCart` skipped for it); `+` / `-` disabled on a
  bulk line (today `+` turns 0.1 into 1.1); the typed box stays.
- A quick quantity with a linked item also adds the container as its own line (quantity 1, its own price and
  promotions), after the bulk line.
- Pending tickets, split bill and the till's other paths keep a bulk line as any decimal line (already supported).
- Tests: the popup's rules in a `tools/*.mjs` check (no popup without the flag or the setting, a new line each time,
  the container line).

## Known limits

- **Invoice from a ticket**: a ticket with a decimal line cannot be invoiced (`InvoiceService`, `InvoiceLine.quantity`
  `Integer`: 400 naming the ticket and the item, `decimal-quantities.md`, "Still whole"). Out of scope of this feature
  (Zein, 2026-11-03).
- Purchases, packs, the head office's own BLs and supply invoices, the Excel item import's stock column: still whole
  (`decimal-quantities.md`). A bulk item is bought through the ERP invoices or the inventory count (both decimal).
- Promotions with a minimum or a free quantity never apply to a decimal line (2.2.1 rule).
- A ParaFendri (Business Central) store selling a bulk item: BC refuses a quantity finer than the item unit's rounding
  precision; to check on BC before any go-live there.

## Tests

`ItemAPICatalogueGuardTest` (`bulkFlagWithTheGuard`: 409 on a head office item, nothing written; an own item set and
cleared, false as null; 404. `bulkFlagEveryMode`: allowed without the guard and with an ERP catalogue, whose full PUT
stays 403. `fullUpdateKeepsBulkFlag`: the full PUT keeps it, JSON never read). `CatalogueCopyBulkFlagTest` (written,
unchanged, cleared; an older head office's copy keeps it and stays unchanged; the upgrade rewrites nothing; `of`).
`QuickQuantityServiceTest` (saved and normalized, the refusals, the API's 400, the deletion check). Front:
`tools/store-item-card-check.mjs` (`bulkFlagEditable`).
