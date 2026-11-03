# Bulk sale ("vente en vrac", 3.1.2)

Items sold by volume or weight at the till (Happyness: perfume "V H 52 1L" sold 0.100 L at a time). The decimal
quantities themselves exist since 2.2.1 (`decimal-quantities.md`); this feature adds what makes them quick to sell: an
item flag and a list of quick quantities the till offers. Built in steps on `release/3.1.2`: **step 1** (2026-11-03,
back end and admin screens) and **step 2** (2026-11-03, the till's popup, below) are done; neither has been seen
running yet (the manual tests of step 2 are below).

## Decisions (Zein, 2026-11-03)

- **The gate stays `ALLOW_DECIMAL_QUANTITY`** (General Setup, store only, `QuantityPolicy`). The flag only decides
  whether the till opens the quick quantities popup; it is not a per-item permission to use decimals. A store with
  decimals on and no flag sells as in 2.2.1 (Happyness today).
- **One quick quantities list per store**, not per item. A quick quantity may name a **linked item**: a container
  ("Flacon 100 ml") the till will add as its own line with the bulk line.
- **A bulk item scanned twice makes a new line** (till step): each pick is its own line, never "+1" on the line.
- **Invoices from tickets stay whole**: out of scope, a known limit (below).
- **The flag is each store's own decision**, a head office item's included (Zein, 2026-11-03, a correction of step 1,
  which had the head office decide): a store flags any item it sells, and the copies down never change it on an item the
  store has. The head office's value is only where a new store item starts.

## Step 1: the item flag `Item.soldInBulk`

- **Column** `item.sold_in_bulk` bit NULL, store and head office; null = false (every item of before stays null).
  `@JsonProperty(access = READ_ONLY)`: never read from JSON, so the full `PUT /item/{id}` (the item form) keeps the
  stored value (`ItemAPI.update` copies it from the stored item, as `ownPrice` and `clearance`), and `POST /item`
  creates the item without it.
- **`PUT /item/{id}/bulk-flag`** `{"soldInBulk": true}` (`ItemAPI.setBulkFlag`), built as `package-flag`: allowed in
  every mode, **an ERP catalogue included** (the full PUT answers 403 there: no connector reads or writes the field);
  404 for an unknown id; **no catalogue guard**: on a store whose catalogue is the head office's, a head office item
  takes it as an own item does (unlike the pack flag, which stays the head office's there: 409). Only this field
  changes; false is stored as null. Saved through `ItemService.save`, so on a head office the catalogue hooks record
  the change for its stores (a head office without an ERP, or whose catalogue comes only from the ERP:
  `HoCatalogueService`), which take it only for an item they do not have yet (below). On a head office whose catalogue
  comes only from the ERP, `HeadOfficeErpCatalogueFilter` leaves `/item/{id}/bulk-flag` open, as the pack flag.
- **The copies down** (`CatalogueItemCopyDTO.soldInBulk`): the head office sends `true` or `false` (`of`: its null is
  false), on every path (with or without points de stock: `pointCopy` builds on `of`). The store
  (`CatalogueCopyWriter`) writes it **only on the item it creates** from the copy (true flagged, false or absent: null);
  an item it has (a head office item, or a local item of the same code taken over) keeps its own value, whatever the
  copy says. `isUnchanged` never counts it (the copy's value is put in the comparison), so a change of the flag at the
  head office rewrites no item, and an upgrade from a head office of an older build rewrites nothing. A store of an
  older build ignores the field (`ignoreUnknown`).
- **The ERP sync** never touches it: `ErpItemBootstrapService.applyItemValues` sets its fields one by one, so a flag set
  on an ERP store (EMTOP) or an ERP head office (ZEN, Happyness, ParaFendri) survives every import.
- **The till** receives it with no change: the till's item lists (`/item/pos/*`, `/item-barcode/...`, `/item/{id}`)
  give the `Item` entity; step 2 reads it there (the popup, below).
- **Screens** (`ZSRetail-Front`): the store's item card (`StoreItemCard`, a switch at the end of the band's facts line,
  after "Quantité en stock", in the facts' size, for every item, a head office item's included, saved at once; the
  General tab's row follows it; "Afficher en caisse" stays plain text), the item form (`ItemManagement`, a box beside
  "Afficher en caisse", sent by `bulk-flag` after the item's save, only when changed), the head office's item card
  (`ItemCatalogueCard`: a switch for a single item, "Vendu en vrac" in each variant's menu for a NAV item; the card's
  `Variant.soldInBulk`; there it sets the head office's own items and the value a new store item starts with, said
  under the switch and on the menu entry: "Valeur initiale pour les nouveaux magasins — chaque magasin décide
  ensuite").

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
the sale, the exports, the reports and the copies behave as before (the copy carries `soldInBulk: false`, never
compared and never written on an item the store has). No connector reads the flag. The quick quantities page is hidden while `ALLOW_DECIMAL_QUANTITY` is
off (EMTOP keeps it off); its table stays empty. No permission is added to any role. The only new behaviours anywhere:
the item card's switch, the form's box, and the 409 on deleting an item used as a linked item (none exists until a
quick quantity is made). At the till (step 2) an item without the flag, or any item with the setting off, is added as in
3.1.1: same merge into its line, same + and -; the only code they pass through anew is the cart pass's pairing of its
answers, which gives the same line when an item has one line.

## Step 2: the till (`ItemSelection.vue`, rules in `libs/bulk-sale.js`)

- **When the popup opens** (`opensBulkPopup`): an item with `soldInBulk`, `appConfig.allowDecimalQuantity` on, not a
  pack, picked by a scan (`handleBarcodeScan`), a tile (`onGridEntryClick`) or Enter in the search (`onSearchEnter`):
  the three go through `pickItem`, which opens the popup instead of `addToCart`. Setting off, or any other item:
  `addToCart` as before. A pack's components and an RFID read keep calling `addToCart` with their own quantity (no
  popup).
- **The popup** (`#bulk-sale-modal`, the line discount modal's look): the item and its unit price TTC (first the
  item's own price, then the engine's answer for 1 unit, `POST /price-calculate` unchanged, so a price list or an own
  price counts), "/ L" when the item has a unit; the active quick quantities as buttons by display order (label and
  amount; `GET /quick-quantity`, read at the first popup of the screen, again only after a reload); a quantity box
  (`parseQuantity`: "0,1" or "0.1", above 0, at most 3 decimals); an amount box TTC (`quantityForAmount`: quantity =
  amount / unit TTC rounded half up to 3 decimals, then the amount of that quantity, as the cart computes it: 10 TND
  at 50 the litre is "0.200 — 10.000 TND"; 7.33 is 0.147 L, 7.350). The three are alternatives: the last one pressed
  or typed wins (`bulkChoice`); the summary shows its quantity and amount, a refusal under it (more than 3 decimals,
  0, an amount too small for 0.001, an item without price). A keypad (the payment keypad's keys and C) types into the
  last box focused, the quantity at first; the floating keyboard of the till stays as it is (its feature flag is off
  and it follows only the scan and search fields). Validate is off until the choice is good; Cancel adds nothing.
- **A scan while the popup is open** (`onBulkScanKeydown`, rule `watchScanKey`): a scanner types its characters a few
  ms apart, then Enter; a character within 30 ms of the previous one continues a burst, and 4 of them (or a fast burst
  of 2 ended by Enter) make a scan. A burst's characters after its first are kept out, the boxes are put back as they
  were before its first one, its Enter is swallowed (it would press Validate), no item is added, and the toast
  "Fermez la vente en vrac avant de scanner" (the quantity error's look) is shown once per scan. A person typing, and
  the popup's keypad, pass as before; two keys a person presses within 30 ms lose the second. Nothing changes while
  the popup is closed.
- **The container** (`containerFor`): a quick quantity's linked item; for a quantity or an amount, the linked item of
  the smallest active quick quantity at least as large (0.07 L: the 100 ml one's; 0.5 L when the next is "1 L" without
  one: none); an inactive item never. Shown as a switch "Avec flacon" (on by default, on again for another container).
  On Validate the container is added after the bulk line by `addToCart` as an ordinary item: quantity 1, its own price
  and promotions, the one-line-per-item rule (a second flacon goes on the flacon's line), changed or removed as any line.
- **A new line at each Validate**: `addToCart(item, quantity, null, { bulkLine: true, bulkLabel })` never looks for a
  line to merge into; the line carries `bulkLine: true`, `lineKey` `bulk-<itemId>-<n>` (a counter of the screen) and
  the quick quantity's label (`bulkLabel`, shown as the line's badge, "Vrac" without one). A line of another kind never
  merges into a bulk line (`findIndex` skips them; for an item without the flag nothing changes). Browser only: the
  server keeps neither the key nor the label.
- **On a bulk line**: `+` and `-` are off (`increaseQuantity` / `decreaseQuantity` return); the quantity is typed in
  its box as any decimal line; discount, removal and the rest as any line. The cart's flash after an add follows the
  line's key (`highlightedItemId === (lineKey || id)`: an item without the flag as before).
- **Park / resume, table tickets**: the lines of a pending ticket taken back (`retrievePendingTicket`) or of a table
  (`TableSelection`) are rebuilt from the saved lines; a bulk item's line (flag and setting on) gets
  `savedLineBulkFields`: `bulkLine` and the key `bulk-<itemId>-L<sales line id>` (unique; a pending update rebuilds the
  lines on the server, the next resume gives new keys). The quick quantity's label is not saved: a resumed line shows
  "Vrac".
- **The cart pass** (`applyLineResults`): its answers came back to the first line of their item; now `pairLineResults`
  gives the k-th answer of an item to its k-th line (the cart sends its lines in order, one answer each, never merged:
  `PromotionCalculationService.cartLines`), so two bulk lines of one item each keep their own. One line per item: the
  same line as before.
- **The server** takes two lines of one item on its four paths (sale, park, update, complete parked): each line on its
  own, no unique constraint on the item of a ticket (`SaleDecimalQuantityTest.twoLinesOfOneItemSold`,
  `twoLinesOfOneItemParkedUpdatedCompleted`). Nothing changed there. Known nuance, not changed: in the one-line question
  `POST /price-calculate`, the cart's other lines of the asked item are not counted (`cartLines`, "the line asked
  counts once"): only quantity promotions read them, and a decimal line is never eligible for those.

### Manual tests (a store with "Autoriser les quantités décimales" on, an item "Vendu en vrac" at 50.000 TTC the litre)

1. Scan the item, click its tile, type its code and Enter: each opens the popup; Cancel adds nothing. An item without
   the flag: added at once as before (a second scan: quantity 2 on its line). Setting off: the bulk item is added at
   once with 1, + and - work, as in 3.1.1.
2. Quick quantity "100 ml" (linked "Flacon 100 ml"): summary "0.100 — 5.000 TND", "Avec flacon" on; Validate: a line
   0.1 with the badge "100 ml" and a flacon line of 1. Again: a second 0.1 line (not 0.2), the flacon line at 2.
3. Amount 10: "0.200 — 10.000 TND", the container of the smallest quick quantity at least 0.2 (none if it has none);
   amount 7.33: 0.147, 7.350; amount 0.01: refused ("Montant trop petit").
4. Quantity "0,25", then a quick quantity, then the amount: the last one wins each time; "0.1234": refused.
5. "Avec flacon" off: no flacon line. The flacon line is changed (+, -, typed) and removed as any line.
6. On a bulk line: + and - are off, the typed quantity works (0.3: amount 15.000), a line discount works.
7. Two bulk lines of the item, park the ticket, take it back: two lines (badge "Vrac"), still not merged, + and - off;
   add one more, save again (update), take it back: three lines; pay: the ticket and the stock take every line.
8. Table ticket (table management on): the same with a table.
9. A customer with a price list on the item: the popup's unit price is the engine's; the amount mode uses it.
10. Popup open, the focus in the quantity box, then in the amount box, then on Validate: scan any barcode each time:
    the boxes stay as they were, nothing is added, the popup stays open, the toast "Fermez la vente en vrac avant de
    scanner" once per scan; typing 0,25 by hand afterwards works.

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

`ItemAPICatalogueGuardTest` (`bulkFlagWithTheGuard`: on a store fed by its head office, a head office item set and
cleared, only the flag changing, its pack flag still 409; an own item set and cleared, false as null; 404.
`bulkFlagEveryMode`: allowed without the guard and with an ERP catalogue, whose full PUT stays 403.
`fullUpdateKeepsBulkFlag`: the full PUT keeps it, JSON never read). `CatalogueCopyBulkFlagTest` (a new item takes the
head office's value; the store's true kept against a copy's false, and its null against a copy's true, the copy
unchanged each time; another change written with the flag kept; the upgrade rewrites nothing; `of`).
`QuickQuantityServiceTest` (saved and normalized, the refusals, the API's 400, the deletion check). Step 2:
`SaleDecimalQuantityTest` (`twoLinesOfOneItemSold`, `twoLinesOfOneItemParkedUpdatedCompleted`). Front:
step 2 `tools/bulk-sale-check.mjs` (when the popup opens, the
quick quantities' order, the amounts, the amount mode and its rounding, the choice, the container, the line keys,
the cart pass's answers per line).
