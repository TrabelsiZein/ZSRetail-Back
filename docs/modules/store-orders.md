# Orders to the head office (Commandes centrale)

**Status**: steps 1 to 4 of 5 done (3.1.1): the store's back end, the head office's back end and the zen connector, the
store's screens (`admin-pages.md`, "Commandes centrale") and the head office's ("Commandes des magasins"). Nothing
outside `connectors/zen` is specific to ZEN.

A franchise store prepares an order of ERP items to its head office: it opens an ERP item, sees the live NAV stock of
each variant for it (ZEN: what NAV makes available to the store's ERP customer), fills quantities in its draft, and
sends it. The head office receives it and posts it
into NAV; what became of it comes back to the store. **Online only**: without the head office's answer the store sees no
stock and cannot send (a clear error, nothing saved).

## Where it exists

Only on a **store whose goods come from its head office** (`Installation.SUPPLY_FROM_HEAD_OFFICE`: `headoffice.url` set
and `ownership.supply=HEAD_OFFICE`). Elsewhere (EMTOP, a store without a head office, a head office) the beans do not
exist and the URLs answer 404; the tables exist, empty, on every store. A Happyness store is supply-from-head-office too:
it has the beans, but its items carry no NAV item number, so its catalogue is empty. The front shows the menu entry
where `GET /config` gives `supplyFromHeadOffice` **and** `navItems` (no new flag).

Nothing in the sales, returns, BL, stock or copies-down paths changed. The only shared code that changed:
`NavItemService` / `ItemRepository.NAV_ITEMS` (level filters, barcode match and sort, see below), `HeadOfficeClient`
(two calls), `SupplyDownHandler` (one record kind), the permissions.

## Classes

| Class | Role |
|---|---|
| `store.holink.model.StoreOrder` | `hol_order`: number, status, sent at, NAV order number, error, note, line count, total quantity |
| `store.holink.model.StoreOrderLine` | `hol_order_line`: one item (variant) of an order, its codes and descriptions copied |
| `store.holink.enumeration.StoreOrderStatus` | `DRAFT`, `SENT`, `POSTED`, `ERROR`, `CANCELLED` |
| `store.holink.repository.StoreOrderRepository` | the draft, the row lock, the last number of a year, the list page |
| `store.holink.service.StoreOrderService` | the rules: catalogue, variants with stock, draft, send, cancel, reads |
| `store.holink.service.StoreOrderStatusWriter` | what the head office did with an order (copies down) |
| `store.holink.service.StoreOrderDraftIndex` | creates `ux_hol_order_draft` at the start when missing |
| `store.holink.controller.StoreOrderAPI` | REST, `store-order` |
| `core.model.ItemImage`, `core.repository.ItemImageRepository`, `core.controller.ItemImageAPI` | `item_image`, any number of images per item, read only |
| `core.link.dto.StoreOrderDTO`, `StoreOrderStatusCopyDTO`, `VariantStockDTO` | the link DTOs (below) |

## Tables

`hol_order` (store only): `number` varchar(30) unique (`uk_hol_order_number`), `status` varchar(20), `sent_at`
datetime2, `nav_order_no` varchar(50), `error_message` varchar(1000), `note` varchar(500), `line_count` int, `total_qty`
numeric(18,3), plus the `_BaseEntity` columns (`created_at`, `created_by` = the user who made the draft). Index
`ix_hol_order_status` (`status`, `created_at`); filtered unique index `ux_hol_order_draft` on `status` where
`status = 'DRAFT'`: one draft at a time.

`hol_order_line` (store only): `order_id` (FK `fk_hol_order_line_order`), `item_id` (no foreign key, as the other
`hol_` tables), `item_code` varchar(100), `nav_item_no` varchar(255), `variant_code` varchar(50), `description` and
`variant_description` varchar(255), `quantity` numeric(18,3) (the stock's precision); unique (`order_id`, `item_id`):
one line per variant.

`item_image` (store and head office): `item_id`, `source` varchar(10) (`FILE`: a file name of the uploads; `URL`),
`path` varchar(1000), `sort_order` int, `is_main` bit; index `ix_item_image_item` (`item_id`, `sort_order`). Nothing
writes it yet (no upload, no sync). `item.image_url` is untouched and keeps meaning "the main image" for the till, the
admin pages and the copies down.

Created by `db/3.1.1/store-orders.sql` (guarded, re-runnable; run before `db/3.1.1/update.sql`, which stamps
`APP_VERSION` 3.1.1). `ddl-auto` creates the same tables when the script did not run; the filtered index is then made at
the start by `StoreOrderDraftIndex`.

## Statuses

| Status | Meaning | Set by |
|---|---|---|
| `DRAFT` | the store's cart, the only order that changes; one at a time | the first line added |
| `SENT` | the head office answered that it has the order (`sent_at`: the store's clock) | the send; also the head office, down |
| `POSTED` | in NAV, `nav_order_no` set | the head office, down |
| `ERROR` | the head office could not post it, `error_message` says why | the head office, down |
| `CANCELLED` | a draft given up, never sent | the store |

A status from the head office is written only on an order the store sent (`SENT`, `POSTED`, `ERROR`); a draft, a
cancelled order or an unknown number is left as it is, noted on the tracking row.

## Rules

- **Number**: `<store code>-<year>-<sequence of 6 digits>`, e.g. `S01-2026-000012`, given when the draft is made (the
  store code is `DEFAULT_LOCATION`; the sequence follows the highest number of that store and year). Given at the draft
  and not at the send, so an order sent again after a lost answer keeps its number and the head office takes it once.
- **Draft**: the first line added makes it; one line per item (variant); adding the item again replaces its quantity;
  0 removes the line (and makes no draft). Only an active ERP item (a `nav_item_no`), not a pack. Quantity ≥ 0, whole
  unless the store allows decimal quantities (`QuantityPolicy`, at most 3 decimals). Note at most 500 characters.
- **Send** (only a draft with at least one line): `POST /ho/orders` with the order, the draft's row locked until the
  answer (its edits wait). Accepted: `SENT`. Refused by the head office (`accepted: false`): 409 with its reason. Not
  answered (unreachable, timeout, 401/402, an HTTP error): 503. Refused or not answered, the order stays a draft and
  nothing is saved.
- **Cancel**: only a draft.
- **Concurrency**: the edits and the send lock the draft's row (`findForUpdate`); two first lines at the same time make
  one draft (the second one meets `ux_hol_order_draft` and goes into the first's draft).

## Endpoints (`store-order`, JWT)

| Method and path | Answer |
|---|---|
| `GET /store-order/catalogue?page&size&search&categoryCode&familyId&subFamilyId&sectionCode&sort&dir` | `{content, page, size, totalElements, totalPages}`, one line per NAV item number with an active variant: `navItemNo`, `name`, `mainImage`, `mainImageItemId`, `variantCount`, `price`, `vatRate`, `familyName`, `subFamilyName`, `categoryCode` / `Name`, `sectionCode` / `Name` |
| `GET /store-order/variants?navItemNo` | `{navItemNo, name, variants: [{itemId, itemCode, variantCode, variantDescription, sizeCode, sizeDescription, colorCode, colorDescription, choiceCode, choiceDescription, price, vatRate, mainImage, images, stock, details, sizeOrder, draftQuantity}]}`; 404 for a number no active item carries; **409** with the head office's reason when it refuses the store (no ERP customer number); **503** without the head office |
| `GET /store-order/draft` | the draft with its lines; 204 when there is none |
| `POST /store-order/draft/lines` `{itemId, quantity}` | the draft (204 for a 0 without a draft) |
| `DELETE /store-order/draft/lines/{itemId}` | the draft; 404 without a draft |
| `PUT /store-order/draft/note` `{note}` | the draft; 404 without a draft |
| `POST /store-order/{id}/send` | the order `SENT`; 409 not a draft, no line, or refused; 503 not answered |
| `POST /store-order/{id}/cancel` | the order `CANCELLED`; 409 not a draft |
| `GET /store-order?status&from&to&page&size` | `{content, totalElements, totalPages, number, size}`, newest first, without lines; `status` one of the statuses or `ALL`; `from`, `to` `yyyy-MM-dd`, the creation day, both included |
| `GET /store-order/{id}` | the order with its lines |
| `GET /item/{id}/images` | `[{id, source, path, sortOrder, main}]`, the main one first (core: store and head office) |

An order is `{id, number, status, createdAt, createdBy, sentAt, navOrderNo, errorMessage, note, lineCount, totalQty,
lines: [{id, itemId, itemCode, navItemNo, variantCode, description, variantDescription, quantity}]}`. Errors are
`{"error": "..."}`: 400 invalid data, 404, 409, 503.

**Catalogue**: the grouped read of the "NAV items" page (`NavItemService.page(NavItemFilter, ...)`,
`ItemRepository.NAV_ITEMS`), active variants only. Search: NAV item number or name, and the NAV items of the barcodes
that match (the items found by `ItemBarcodeRepository.findItemIdsByBarcodeLike`, as `/item/lookup`); a line always
counts all its active variants. Levels: `categoryCode` (the item's own catégorie, else its famille's, as the promotions
match it), `familyId`, `subFamilyId`, `sectionCode`. Sort: `navItemNo` (the default) or `name` (then number), `dir`
`asc` or `desc`. `price` is the first variant's `publicPrice` (before VAT, sent by the head office), else its
`unitPrice`; `vatRate` its `defaultVAT`; `mainImage` the `image_url` of the first variant that has one, `mainImageItemId` that variant (its image at `item-image/item/{id}`). The variants'
order is the "NAV items" page's (size order, colour, choice, code).

**Variants**: the local active variants, then one live call to the head office; the stock is merged by variant code (any
case); a variant NAV gives no row (or no quantity) for has a stock null (unknown, the screen shows "—"), a row of another
NAV item is ignored. `details` (label → value, in order) and `sizeOrder` are the first row's of the variant, as the
connector gave them (null otherwise; only ZEN fills them): informational, nothing is computed from them. Nothing is
answered without the head office (no partial answer); a refusal of the head office (409) is relayed as a 409 with its
reason ("The head office refused the stock of <number>: ..."), as for a send.

**Permissions**: `read:admin-store-orders` and `write:admin-store-orders` ("Commandes centrale"), in
`GET /app-roles/permissions`; given to the ADMIN role at every start of a store whose goods come from the head office,
as the BL reception page (`ZZDataInitializer.STORE_ORDER_ADMIN_PERMISSIONS`); never to another role by default. As
everywhere, the API itself checks no role.

## The link (the head office side is step 2)

All through `HeadOfficeClient`, on the `/ho/**` chain with `X-Store-Code` / `X-Store-Key`, on its own RestTemplate:
connect 2 s, read 15 s (the head office asks NAV meanwhile).

**Variant stock**: `GET /ho/orders/variant-stock?navItemNo=` → `[VariantStockDTO]` `{itemNo, variantCode, quantity,
details, sizeOrder}` (the last two optional: an older head office or store ignores them). The head office knows the store
from the authenticated `X-Store-Code` (no parameter: a store cannot read another store's stock) and asks NAV through its
connector with the store's code and ERP customer number.

**Send**: `POST /ho/orders` with `StoreOrderDTO` `{number, locationCode, note, createdAt, sentAt, lines: [{itemCode,
navItemNo, variantCode, description, variantDescription, quantity}]}` (`locationCode` is the store code, the NAV
location; dates `yyyy-MM-ddTHH:mm:ss`); answered by a `SalesCopyResultDTO` `{documentNumber, accepted, message}`. A
200 must name the same number; `accepted: false` is a refusal with its reason.

**Statuses down**: copies down of the **SUPPLY** domain, record `ORD:<number>`, `StoreOrderStatusCopyDTO` `{kind:
"STORE_ORDER", orderNumber, status, navOrderNo, errorMessage, statusAt}`, to that store only. The number is
`orderNumber`, never `number`: a store of an older build reads no BL number in it and refuses the record instead of
taking it for a BL. Applied by `SupplyDownHandler` → `StoreOrderStatusWriter` in its own transaction, tracked in
`hol_down_record` (`APPLIED`, or `ERROR` and retried at the next cycles).

## The head office (step 2)

### Where it exists

Only on a head office with **`headoffice.storeorders.post`** in its outside file (`NodeOwnership.STORE_ORDERS_POST_KEY`;
`Installation.HEAD_OFFICE_STORE_ORDERS`):

| Value | Meaning |
|---|---|
| absent | the head office takes no store order: no bean, `/ho/orders` answers 404 (the store shows 503) |
| `MANUAL` | the orders are received; the head office types the ERP's order number of each one ("mark posted") |
| `ERP` | the orders are received and the job `POST_STORE_ORDERS` posts them into the ERP (`Installation.HEAD_OFFICE_STORE_ORDERS_ERP`); needs a connector with `ErpAbility.STORE_ORDERS` (zen only), else the startup stops naming the key |

Any other value stops the startup; a store never reads the key. `configs/customers/zen-headoffice.properties` (and the
local test file of the ZEN head office) say `ERP`; the Happyness, EMTOP and dev head offices have no line (no new bean:
`InstallationBeansProofTest`).

### Classes

| Class | Role |
|---|---|
| `headoffice.model.HoStoreOrder`, `HoStoreOrderLine` | `ho_store_order`, `ho_store_order_line` |
| `headoffice.enumeration.HoStoreOrderStatus` | `SENT`, `POSTED`, `ERROR`, `REJECTED` |
| `headoffice.enumeration.PostedMode` | `ERP`, `MANUAL` |
| `headoffice.repository.HoStoreOrderRepository` | by store and number, the row lock, the job's ids, the copies down, the list page |
| `headoffice.service.HoStoreOrderService` | reception, variant stock, "mark posted", "reject", reads; the SUPPLY provider of the `ORD:` records |
| `headoffice.service.HoStoreOrderPosting` | the job `POST_STORE_ORDERS` and "retry now" (ERP mode), implements erp-api `ErpStoreOrderPosting` |
| `headoffice.controller.HeadOfficeOrdersAPI` | `/ho/orders` (store key) |
| `headoffice.controller.HoStoreOrderAPI` | `admin/headoffice/store-orders` (JWT) |
| `headoffice.dto.HoStoreOrderDTO` | an order as the page reads it |

Shared code that changed: `CopiesDownFeed` takes several providers per domain (each answers only its own codes: `BL:`,
`INV:`, `ERPINV:`, `ORD:`); `HeadOfficeErpJobs` offers `POST_STORE_ORDERS` in ERP mode only; core's ERP jobs
(`ErpSyncJobType.POST_STORE_ORDERS`, `ErpSyncOperation.EXPORT_SALES_ORDER`, the runner, the seed);
`ErpSynchronizationManager.postSalesOrder` (one row of the communications log per posting).

### Tables (head office only)

`ho_store_order`: `store_id` (FK `fk_ho_store_order_store`), `number` varchar(30), unique (`store_id`, `number`)
(`uk_ho_store_order_number`), `status` varchar(20), `location_code` varchar(50), `sent_at` (the store's clock),
`received_at` (the head office's), `nav_order_no` varchar(50), `posted_at`, `posted_mode` varchar(10), `error_message`
varchar(1000), `post_attempts` int, `last_attempt_at`, `note` varchar(500), `line_count` int, `total_qty`
numeric(18,3), plus the `_BaseEntity` columns (`created_by` = the store code, `updated_by` = who posted by hand or
rejected). Index `ix_ho_store_order_status` (`status`, `received_at`).

`ho_store_order_line`: `order_id` (FK), `item_code` varchar(100), `nav_item_no`, `variant_code`, `description`,
`variant_description`, `quantity` numeric(18,3), as the store sent them (no item id: the store's means nothing here).

Created by `db/3.1.1/store-orders.sql` when the database has `ho_store`; `ddl-auto` creates the same tables.

### Statuses at the head office

| Status | Meaning | The store sees |
|---|---|---|
| `SENT` | received, waiting for the ERP | `SENT` (nothing goes down: the store has it already) |
| `POSTED` | in the ERP, `nav_order_no`, `posted_at`, `posted_mode` ERP or MANUAL | `POSTED` with the number |
| `ERROR` | the posting failed, `error_message`; ERP mode: posted again at each run, no limit | `ERROR` with the message |
| `REJECTED` | refused by the head office with a message; never posted, by the job or by hand | `ERROR` with the message |

Each change the store sees (status, ERP number, message) records `ORD:<number>` in the same transaction, for that store
only; the same failure again records nothing.

### Rules

- **Reception** (`POST /ho/orders`): the store is the key's (`X-Store-Code`); taken once per store and number (the same
  number again answers `accepted: true`, the order already here unchanged). Refused with `accepted: false` and the
  reason, nothing saved (the store shows 409 with it): the store has no ERP customer number (Stores page), a
  `locationCode` that is not the store's code, no line, a line without item code, a quantity of 0 or less or with more
  than 3 decimals.
- **Variant stock** (`GET /ho/orders/variant-stock?navItemNo=`): `ErpConnector.fetchVariantStock(navItemNo, store code,
  ERP customer number)`, each connector using what its ERP filters by (ZEN: the customer). A store without an ERP customer
  number is refused before the ERP is asked, with the reception's reason: 409 `{"error": "Store <code> has no ERP customer
  number at the head office: ..."}`. A connector that cannot (`ErpNotSupportedException`) or an ERP error answers 503
  `{"error"}`, never a partial list. The quantity stays null when the connector gives none; `details` and `sizeOrder`
  pass through unchanged.
- **Posting (ERP mode)**: the job takes up to 50 orders `SENT` or `ERROR`, oldest first. Each is posted outside any
  transaction as `ErpSalesOrderDTO`: `locationCode` = the store code, `customerNo` = the store's ERP customer number
  (none any more: ERROR without calling the ERP), `externalDocumentNo` = the order number, `orderDate` = the store's
  send date (else the reception's), lines `itemNo` (the line's ERP item number, else its item code up to `~`),
  `variantCode` (empty for none), `quantity`. Success: `POSTED`; failure: `ERROR`, and the job ends WARNING naming the
  first one. An order being posted is not posted a second time at once (the job and "retry now").
- **Idempotence**: the connector looks up the ERP's order of the same `externalDocumentNo` first and reuses its number;
  when the lookup cannot be answered, nothing is posted (ERROR, retried).
- **Admin actions**: "mark posted" `{navOrderNo}` (MANUAL mode only: 409 in ERP mode), "reject" `{message}` (both
  modes), "retry now" (ERP mode only: 409 in MANUAL mode); each only on an order `SENT` or `ERROR` (409 otherwise). No
  deletion.

### Endpoints

| Method and path | Answer |
|---|---|
| `POST /ho/orders` (store key) `StoreOrderDTO` | `{documentNumber, accepted, message}` |
| `GET /ho/orders/variant-stock?navItemNo` (store key) | `[{itemNo, variantCode, quantity, details, sizeOrder}]`; 400 without a number; 409 without the store's ERP customer number; 503 |
| `GET admin/headoffice/store-orders?storeId&status&from&to&sort&dir&page&size` | `{content, totalElements, totalPages, number, size, postMode}`, without lines; `status` one of the four or `ALL`; `from`, `to` the reception day, both included; `sort` `receivedAt` (default), `number`, `status`, `postedAt`, `store`; `dir` `desc` (default) or `asc`; `postMode` `ERP` or `MANUAL` |
| `GET admin/headoffice/store-orders/{id}` | the order with its lines |
| `POST admin/headoffice/store-orders/{id}/mark-posted` `{navOrderNo}` | the order `POSTED`, mode MANUAL |
| `POST admin/headoffice/store-orders/{id}/reject` `{message}` | the order `REJECTED` |
| `POST admin/headoffice/store-orders/{id}/retry` | the order after the posting (`POSTED`, or `ERROR` with the reason) |

An order is `{id, storeId, storeCode, storeName, number, status, locationCode, sentAt, receivedAt, navOrderNo, postedAt,
postedMode, errorMessage, postAttempts, lastAttemptAt, note, lineCount, totalQty, updatedBy, lines: [{id, itemCode,
navItemNo, variantCode, description, variantDescription, quantity}]}`.

### The job

`POST_STORE_ORDERS`, seeded **disabled** like the other ERP jobs, every 5 minutes (`0 */5 * * * *`), on a head office
with `headoffice.storeorders.post=ERP` only (refused, switched off and hidden on every other head office): switch it on
in the ERP jobs page. Each posting is one row `EXPORT_SALES_ORDER` of the communications log (the body sent, NAV's
answer, the address).

### The connector (erp-api and connectors/zen)

`ErpConnector.fetchVariantStock(itemNo, locationCode, customerNo)` (default: `ErpNotSupportedException`) and
`postSalesOrder(ErpSalesOrderDTO)` (default: a failure "does not take the stores' orders"); EMTOP and Happyness keep
the defaults. `ErpVariantStockDTO` `{itemNo, variantCode, quantity, details, sizeOrder}`: `details` (an ordered map
label → value, strings) and `sizeOrder` are optional, filled by a connector with what its ERP gives. ZEN
(`ZenStoreOrders`, fields in `ZenOrderFields`):

- **the stock, ZEN's real page** `infoArticles` (2026-10-27): `GET <erp.zen.variantstock.path>?$filter=article eq
  '<item>' and client eq '<ERP customer number>'` (a quote doubled) → `{"value": [...]}`, one row per variant: `article,
  codeVariante, client, designation, marque, unite, devise, prixUnitaire, remisePourcent, prixNet, vracAutorise,
  codeCouleur, libelleCouleur, codeChoix, codeTaille, ordreTaille, codeBarres, qteSerieType, contenanceSerie,
  colisPossibles, stock, qteVrac, disponible, calculeLe`. The page has **no location**: the stock is NAV's for the store's
  ERP customer (the store code is not sent). Mapping: `itemNo` = `article`, `variantCode` = `codeVariante` (the same codes as
  the catalogue's, `001`...), **`quantity` = `disponible`, never `stock`** (null when the row has none), `sizeOrder` =
  `ordreTaille`, `details` in this order: Prix unitaire, Remise %, Prix net, Stock, Qté vrac, Vrac autorisé (Oui / Non),
  Contenance série, Colis possibles, Calculé le (`calculeLe` as `dd/MM/yyyy HH:mm` in the head office's time zone); an
  empty value is left out. Sample: `connectors/zen/src/test/resources/zen/infoArticles.json` (43ZBPND01, client
  4110001: `disponible` 203 or 101 where `stock` is 1 to 69; size 36 has `ordreTaille` 220, after 46's 200, so its column comes last);
- lookup `GET <erp.zen.salesorders.path>?$filter=externalDocumentNo eq '<number>'&$select=orderNo` → `{"value":
  [{orderNo}]}`: **ZEN must support this filter** (asked 2026-10-28); until then every posting fails and stays ERROR;
- `POST <erp.zen.salesorders.path>` `{locationCode, customerNo, externalDocumentNo, orderDate (yyyy-MM-dd), lines:
  [{itemNo, variantCode, quantity}]}` → `{orderNo}`.

The sales orders are still on the contract sent to ZEN (`commandesVente` not published yet).

`erp.zen.variantstock.path` (default `infoArticles`) and `erp.zen.salesorders.path` (default `salesOrders`): a name under
`companies(id)` of `erp.zen.api-path`, or a whole address; blank refused at the start. The connector's RestTemplate still
refuses every method but GET, except a POST to the sales orders page. A field ZEN renames is one line of
`ZenOrderFields`.

## The store's screens (step 3)

Front `views/admin/store-orders/` and `components/admin/store-orders/` (`admin-pages.md`, "Commandes centrale"): the
Catalogue (tiles, filters in the URL, the variants modal with the NAV stock, the cart beside it) and Mes commandes (the
list, one order). Shown only where `/config` gives `supplyFromHeadOffice` and `navItems`. Two fields serve them:
`mainImageItemId` on a catalogue line (the tile's image is read at `item-image/item/{id}`), and a variant's `stock`
**null** when NAV gives no row for it (shown "—", never 0). In the variants matrix, the size columns follow `sizeOrder`
when the variants carry one (a size's: the smallest of its variants; a size without one after them), else the back end's
order; a cell with `details` shows an info icon whose tooltip lists "label : value" (`store-orders-api.js`
`variantMatrix`, `detailLines`; `tools/store-orders-check.mjs`).

## The head office's screen (step 4)

"Commandes des magasins" (`admin-pages.md`): the list and an order's page with its actions, in the Network menu, only on a
head office whose `GET /config` gives **`storeOrdersPost`** (`ERP` or `MANUAL`, from `headoffice.storeorders.post`;
null without the key and on every store). Permissions **`read:admin-headoffice-store-orders`** (the pages) and
**`write:admin-headoffice-store-orders`** (mark posted, reject, retry), given to ADMIN at every start only on such a head
office (`ZZDataInitializer.HEAD_OFFICE_STORE_ORDER_PERMISSIONS`, apart from `HEAD_OFFICE_ADMIN_PERMISSIONS`: the
frontend's head office routes are both sets). The head office routes' `meta.writePermission` lists the write permission
on the Roles page.

**Without ZEN** (dev helper): `node tools/zen-mock.mjs [port=9999] [fail=1] [delay=ms]` in ZSRetail-Front fakes the pages:
`infoArticles` with ZEN's field names (the rows read from ZEN's test NAV in `tools/zen-mock-info-articles.json` for
43ZBPND01 and 43ZFCHD17, else rows built from the editable `tools/zen-mock-stock.json`), the lookup, the POST (`fail=1`
answering 500). It prints the `erp.zen.salesorders.path` line for the ZEN head office file, and the
`erp.zen.variantstock.path` line to use only without the VPN.

## Still to come

- ZEN: the sales orders page `commandesVente` and its lookup by `externalDocumentNo` (a change of `erp.zen.salesorders.path`
  and of the fields in `ZenOrderFields`).
