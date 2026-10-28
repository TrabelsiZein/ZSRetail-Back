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
| `store.holink.enumeration.StoreOrderStatus` | `DRAFT`, `SENT`, `POSTED`, `ERROR`, `CANCELLED`, `DELIVERED` |
| `store.holink.repository.StoreOrderRepository` | the draft, the row lock, the last number of a year, the list page |
| `store.holink.service.StoreOrderService` | the rules: catalogue, variants with stock, draft, send, cancel, reads |
| `store.holink.service.StoreOrderStatusWriter` | what the head office did with an order (copies down) |
| `store.holink.service.StoreOrderDraftIndex` | creates `ux_hol_order_draft` at the start when missing |
| `store.holink.controller.StoreOrderAPI` | REST, `store-order` |
| `core.model.ItemImage`, `core.repository.ItemImageRepository`, `core.controller.ItemImageAPI` | `item_image`, any number of images per item, read only |
| `core.link.dto.StoreOrderDTO`, `StoreOrderStatusCopyDTO`, `VariantStockDTO` | the link DTOs (below) |

## Tables

`hol_order` (store only): `number` varchar(30) unique (`uk_hol_order_number`), `status` varchar(20), `sent_at`
datetime2, `nav_order_no` varchar(50), `error_message` varchar(1000), `nav_message` varchar(500) (NAV's message when the
head office posted it), `invoice_no` varchar(30) and `delivered_at` date (NAV's invoice of a `DELIVERED` order and its
posting date), `note` varchar(500), `line_count` int, `total_qty` numeric(18,3), plus the `_BaseEntity` columns (`created_at`, `created_by` = the user who made the draft). Index
`ix_hol_order_status` (`status`, `created_at`); filtered unique index `ux_hol_order_draft` on `status` where
`status = 'DRAFT'`: one draft at a time.

`hol_order_line` (store only): `order_id` (FK `fk_hol_order_line_order`), `item_id` (no foreign key, as the other
`hol_` tables), `item_code` varchar(100), `nav_item_no` varchar(255), `variant_code` varchar(50), `description` and
`variant_description` varchar(255), `quantity` numeric(18,3) (the stock's precision), `available_qty` numeric(18,3)
nullable (the quantity available the line was ordered against, below), `nav_accepted_qty` numeric(18,3) and
`nav_line_message` varchar(500), both nullable (NAV's answer on the line when the head office posted the order); unique
(`order_id`, `item_id`): one line per variant.

`item_image` (store and head office): `item_id`, `source` varchar(10) (`FILE`: a file name of the uploads; `URL`),
`path` varchar(1000), `sort_order` int, `is_main` bit; index `ix_item_image_item` (`item_id`, `sort_order`). 3.1.1,
item images: on a ZEN head office the catalogue job writes the URL rows from ZEN's products API (`erp-sync.md`, "Item
images") and the item copies carry them down to the stores (`head-office.md`, "Item images"); no upload writes FILE rows
yet. `item.image_url` is untouched and keeps meaning "the main image" for the till and the admin pages.

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
| `DELIVERED` | NAV invoiced it: `invoice_no` (the invoice the store receives on "Réceptions"), `delivered_at` (its posting date); `nav_order_no` kept | the head office, down |

A status from the head office is written only on an order the store sent (`SENT`, `POSTED`, `ERROR`, `DELIVERED`); a
draft, a cancelled order or an unknown number is left as it is, noted on the tracking row. `invoice_no` and
`delivered_at` are kept only on a `DELIVERED` order (another status from the head office clears them).

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
- **Quantity ≤ available**: a line's quantity may not exceed the quantity the head office made available for it.
  The variants window sends each line with the quantity available shown on its cell (`availableQty`; null: none known,
  no limit) and the line keeps it (`hol_order_line.available_qty`, replaced at each add from the window); the cart sends
  none and the line's own is the limit. Above it: 409 ("Item X: 25 asked but only 20 available at the head office"),
  nothing saved. 0 always removes. **Send** refuses a draft with any line above its availability (409, nothing sent).
  The screens: a cell above it turns red with "Max n" in its title, the footer counts them and "Ajouter à la commande"
  waits until they are fixed (no clamping); in the cart a line above it turns red, its save is refused, and "quantity /
  available" shows on hover ("—" when none is known).
- **Cancel**: only a draft.
- **Concurrency**: the edits and the send lock the draft's row (`findForUpdate`); two first lines at the same time make
  one draft (the second one meets `ux_hol_order_draft` and goes into the first's draft).

## Endpoints (`store-order`, JWT)

| Method and path | Answer |
|---|---|
| `GET /store-order/catalogue?page&size&search&categoryCode&familyId&subFamilyId&sectionCode&sort&dir` | `{content, page, size, totalElements, totalPages}`, one line per NAV item number with an active variant: `navItemNo`, `name`, `mainImage`, `mainImageItemId`, `variantCount`, `price`, `vatRate`, `familyName`, `subFamilyName`, `categoryCode` / `Name`, `sectionCode` / `Name` |
| `GET /store-order/variants?navItemNo` | `{navItemNo, name, variants: [{itemId, itemCode, variantCode, variantDescription, sizeCode, sizeDescription, colorCode, colorDescription, choiceCode, choiceDescription, price, vatRate, mainImage, images, stock, details, sizeOrder, draftQuantity}]}`; 404 for a number no active item carries; **409** with the head office's reason when it refuses the store (no ERP customer number); **503** without the head office |
| `GET /store-order/last?navItemNo` | the last order of this store **sent** with a line of that ERP item (`SENT`, `POSTED`, `ERROR` or `DELIVERED`: not a draft, not cancelled; the highest id): `{id, number, status, date, lines: [{itemId, variantCode, quantity}]}`, `date` the send's else the creation's, `lines` its lines of that item only; 204 when there is none; 400 without a number |
| `GET /store-order/draft` | the draft with its lines; 204 when there is none |
| `POST /store-order/draft/lines` `{itemId, quantity, availableQty?}` | the draft (204 for a 0 without a draft); 409 above the availability (the one sent, else the line's) |
| `DELETE /store-order/draft/lines/{itemId}` | the draft; 404 without a draft |
| `PUT /store-order/draft/note` `{note}` | the draft; 404 without a draft |
| `POST /store-order/{id}/send` | the order `SENT`; 409 not a draft, no line, a line above its availability, or refused; 503 not answered |
| `POST /store-order/{id}/cancel` | the order `CANCELLED`; 409 not a draft |
| `GET /store-order?status&from&to&search&page&size` | `{content, totalElements, totalPages, number, size}`, newest first, without lines; `status` one of the statuses or `ALL`; `from`, `to` `yyyy-MM-dd`, the creation day, both included; `search` in the order number, the head office's order number, a line's item code, ERP item number or name (any case, a `%` or `_` a character; blank: every order) |
| `GET /store-order/{id}` | the order with its lines |
| `GET /item/{id}/images` | `[{id, source, path, sortOrder, main}]`, the main one first (core: store and head office) |

An order is `{id, number, status, createdAt, createdBy, sentAt, navOrderNo, errorMessage, navMessage, invoiceNo,
deliveredAt, note, lineCount, totalQty, lines: [{id, itemId, itemCode, navItemNo, variantCode, description, variantDescription, quantity,
availableQty, navAcceptedQty, navLineMessage}]}`. Errors are
`{"error": "..."}`: 400 invalid data, 404, 409, 503.

**Catalogue**: the grouped read of the "NAV items" page (`NavItemService.page(NavItemFilter, ...)`,
`ItemRepository.NAV_ITEMS`), active variants only. Search: NAV item number or name, and the NAV items of the barcodes
that match (the items found by `ItemBarcodeRepository.findItemIdsByBarcodeLike`, as `/item/lookup`); a line always
counts all its active variants. Levels: `categoryCode` (the item's own catégorie, else its famille's, as the promotions
match it), `familyId`, `subFamilyId`, `sectionCode`. Sort: `navItemNo` (the default) or `name` (then number), `dir`
`asc` or `desc`. `price` is the first variant's `publicPrice` (before VAT, sent by the head office), else its
`unitPrice`; `vatRate` its `defaultVAT`; `mainImage` the `image_url` of the first variant that has one, `mainImageItemId` that variant (its image at `item-image/item/{id}`); none (3.1.1): the main URL image (`item_image`, source URL) of the first variant that has one, a full address the screen shows as it is. The variants'
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
"STORE_ORDER", orderNumber, status, navOrderNo, errorMessage, navMessage, lines, invoiceNo, deliveredAt, statusAt}`
(`invoiceNo` and `deliveredAt` `yyyy-MM-dd` on a `DELIVERED` order only), to that store only:
`navMessage` and `lines` `[{itemCode, acceptedQty, message}]` are NAV's answer when the head office posted the order
(`lines` null when NAV answered none: not posted yet, posted by hand); the store writes them on the order and on its
lines by item code (a line not in the copy keeps none), and a change of the answer alone is written too. The number is
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
| `headoffice.enumeration.HoStoreOrderStatus` | `SENT`, `POSTED`, `ERROR`, `REJECTED`, `DELIVERED` |
| `headoffice.enumeration.PostedMode` | `ERP`, `MANUAL` |
| `headoffice.repository.HoStoreOrderRepository` | by store and number, the row lock, the job's ids, the copies down, the list page |
| `headoffice.service.HoStoreOrderService` | reception, variant stock, "mark posted", "reject", reads; the SUPPLY provider of the `ORD:` records |
| `headoffice.service.StoreOrderDeliveries` | what the invoice import tells of an invoice naming a sales order (implemented by `HoStoreOrderService`; absent without store orders) |
| `headoffice.repository.HoErpInvoiceRepository.invoicesOfOrder` | the invoices already imported that name an ERP order number (an order posted after its invoice) |
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
varchar(1000), `nav_message` varchar(500) (NAV's message on the order when posted), `invoice_no` varchar(30) and
`delivered_at` date (the ERP invoice that delivered it, its posting date), `post_attempts` int,
`last_attempt_at`, `note` varchar(500), `line_count` int, `total_qty` numeric(18,3), plus the `_BaseEntity` columns (`created_by` = the store code, `updated_by` = who posted by hand or
rejected). Index `ix_ho_store_order_status` (`status`, `received_at`).

`ho_store_order_line`: `order_id` (FK), `item_code` varchar(100), `nav_item_no`, `variant_code`, `description`,
`variant_description`, `quantity` numeric(18,3), as the store sent them (no item id: the store's means nothing here);
`nav_accepted_qty` numeric(18,3) and `nav_line_message` varchar(500), nullable: NAV's answer on the line when posted.

Created by `db/3.1.1/store-orders.sql` when the database has `ho_store`; `ddl-auto` creates the same tables. The same
script adds `ho_erp_invoice.order_no` varchar(50) and its index `ix_ho_erp_invoice_order` (Delivered, below).

### Statuses at the head office

| Status | Meaning | The store sees |
|---|---|---|
| `SENT` | received, waiting for the ERP | `SENT` (nothing goes down: the store has it already) |
| `POSTED` | in the ERP, `nav_order_no`, `posted_at`, `posted_mode` ERP or MANUAL | `POSTED` with the number |
| `ERROR` | the posting failed, `error_message`; ERP mode: posted again at each run, no limit | `ERROR` with the message |
| `REJECTED` | refused by the head office with a message; never posted, by the job or by hand | `ERROR` with the message |
| `DELIVERED` | a `POSTED` order an ERP invoice names (below, "Delivered"): `invoice_no`, `delivered_at` | `DELIVERED` with the invoice |

Each change the store sees (status, ERP number, message, NAV's answer) records `ORD:<number>` in the same transaction, for that store
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
  `variantCode` (empty for none), `quantity`. Success: `POSTED`, and the ERP's answer (`ErpSalesOrderResult`) kept: its
  message on the order in `nav_message`, per line (matched by ERP item number and variant; a line given twice added up,
  its messages joined) the quantity accepted in `nav_accepted_qty` (to 3 decimals) and its message; a line the ERP did
  not answer keeps none. Failure: `ERROR`, and the job ends WARNING naming the
  first one. An order being posted is not posted a second time at once (the job and "retry now").
- **Idempotence**: the connector looks up the ERP's order of the same `externalDocumentNo` first and reuses its number
  (ZEN: and posts the lines it lacks); when the lookup cannot be answered, nothing is posted (ERROR, retried).
- **Admin actions**: "mark posted" `{navOrderNo}` (MANUAL mode only: 409 in ERP mode), "reject" `{message}` (both
  modes), "retry now" (ERP mode only: 409 in MANUAL mode); each only on an order `SENT` or `ERROR` (409 otherwise). No
  deletion.

### Endpoints

| Method and path | Answer |
|---|---|
| `POST /ho/orders` (store key) `StoreOrderDTO` | `{documentNumber, accepted, message}` |
| `GET /ho/orders/variant-stock?navItemNo` (store key) | `[{itemNo, variantCode, quantity, details, sizeOrder}]`; 400 without a number; 409 without the store's ERP customer number; 503 |
| `GET admin/headoffice/store-orders?storeId&status&from&to&search&sort&dir&page&size` | `{content, totalElements, totalPages, number, size, postMode}`, without lines; `status` one of the four or `ALL`; `from`, `to` the reception day, both included; `search` in the order number, the ERP order number, a line's item code, ERP item number or name (as the store's); `sort` `receivedAt` (default), `number`, `status`, `postedAt`, `store`; `dir` `desc` (default) or `asc`; `postMode` `ERP` or `MANUAL` |
| `GET admin/headoffice/store-orders/{id}` | the order with its lines |
| `POST admin/headoffice/store-orders/{id}/mark-posted` `{navOrderNo}` | the order `POSTED`, mode MANUAL |
| `POST admin/headoffice/store-orders/{id}/reject` `{message}` | the order `REJECTED` |
| `POST admin/headoffice/store-orders/{id}/retry` | the order after the posting (`POSTED`, or `ERROR` with the reason) |

An order is `{id, storeId, storeCode, storeName, number, status, locationCode, sentAt, receivedAt, navOrderNo, postedAt,
postedMode, errorMessage, navMessage, invoiceNo, deliveredAt, postAttempts, lastAttemptAt, note, lineCount, totalQty, updatedBy, lines: [{id,
itemCode, navItemNo, variantCode, description, variantDescription, quantity, navAcceptedQty, navLineMessage}]}`.

### Delivered (3.1.1)

The ERP's invoice of a posted order makes it `DELIVERED`, on both sides. Generic: `ErpSupplyInvoiceDTO.orderNo` (erp-api)
is the ERP's sales order an invoice delivers, null when the connector gives none (EMTOP and Happyness: no change).
ZEN fills it from `orderNo` of the franchise page `salesInvoices` (the `commandesVente` `numero`, e.g. `CV26000004`;
`ZenInvoiceRow.ORDER_NO`; the sample `salesInvoices.json` has `CV26000002`).

- **Where**: a head office with `headoffice.supply.source=ERP` (the invoice import, job `IMPORT_SUPPLY_INVOICES`) **and**
  `headoffice.storeorders.post` set (`StoreOrderDeliveries` is `HoStoreOrderService`); with one of them only, nothing.
- **When**: an invoice is saved by the import (`HoErpInvoiceService.saveOne`), held or not, before its store is matched:
  in **the invoice's transaction**, every `POSTED` `ho_store_order` whose `nav_order_no` is the invoice's `orderNo` (the
  rows locked) becomes `DELIVERED`, `invoice_no` = the invoice number, `delivered_at` = its posting date (the head
  office's day when it has none), and `ORD:<number>` is recorded for its store. Saved together or not at all: a failure
  leaves the invoice unsaved, read again at the next run. The run's summary counts `ordersDelivered`.
- **Not delivered**: an order of another store (the invoice's customer and the store's ERP customer number both known and
  different: a number typed by hand in MANUAL mode may be wrong); an order not `POSTED` (waiting, rejected, delivered
  already). A second invoice of the same order changes nothing: the first invoice is kept.
- **Posted after its invoice**: `ho_erp_invoice.order_no` keeps each invoice's `orderNo` (index
  `ix_ho_erp_invoice_order`). When an order becomes `POSTED` (the job, "retry now", "mark posted"), the invoices already
  imported under its ERP number are looked up (`HoErpInvoiceRepository.invoicesOfOrder`, native, the number cast to
  varchar: a seek), in the posting's transaction: the first of its store's customer, by invoice number, makes it
  `DELIVERED` at once (the store then sees `DELIVERED` directly). Typical in MANUAL mode, when NAV invoiced the order
  before the head office typed its number. The invoices read before this column existed keep none.
- **No action** takes a `DELIVERED` order (mark posted, reject, retry: 409, as on any order not waiting).
- **The store**: `StoreOrderStatusWriter` writes `DELIVERED`, `invoice_no`, `delivered_at`. A store of an earlier
  3.1.1 build refuses `DELIVERED` (the record in error, retried at each cycle) until it is upgraded. The invoice itself
  reaches the store as before (`ERPINV:<number>`, once the import matched its store), on "Réceptions";
  `GET /admin/deliveries/by-number?number=` answers it as `GET /admin/deliveries/{id}` (404 when not here yet).
- **Screens**: "Livrée" (badge `light-primary`, a status of the filters) on both lists and order pages; the invoice
  number under the ERP number in the lists; on the order's page (group Suivi / Traitement) the invoice and "Livrée le".
  At the store the invoice links to "Réceptions" with `?number=` (the page opens it: to receive, or its detail; a toast
  when it has not arrived); at the head office to the ERP invoices page with `?search=`.

### Colis (3.1.1)

ZEN sells some items by série (`infoArticles`: `contenanceSerie` pieces per colis, `colisPossibles`, `qteSerieType`,
`vracAutorise`, `qteVrac`, passed down as details). A store may order **colis of an item** beside or instead of pieces of
its variants. A colis is of the item, never of a variant: it lives **apart from the lines**, one row per item and order.

- **Tables** (`db/3.1.1/store-orders.sql`, guarded; `ddl-auto` makes the same): store `hol_order_colis` (`order_id`
  FK, `nav_item_no`, `description` (the item's name), `nb_colis`, `contenance`, `colis_possibles`,
  `nav_accepted_colis`, `nav_message`; unique `uk_hol_order_colis_item` (order, item)); head office
  `ho_store_order_colis` (the same columns, index on `order_id`); `total_colis` int on `hol_order` and
  `ho_store_order` (null on older rows, read as 0). `total_qty` counts the pieces of the lines only. No line, no unique
  key changed.
- **Store**: `POST /store-order/draft/colis {navItemNo, nbColis, colisPossibles?, contenance?}` replaces the item's colis (0
  removes them; a missing draft is made, not for a 0; 204 for a 0 without a draft); only a number an active ERP item
  carries (400); `nbColis` above `colisPossibles` 409 ("6 colis asked but only 5 possible"), as pieces above the
  availability. `DELETE /store-order/draft/colis?navItemNo=`. The send refuses colis above their possibles (409) and a
  draft with neither line nor colis. The variants answer gives `draftColis`; an order gives `totalColis` and `colis:
  [{id, navItemNo, description, nbColis, contenance, colisPossibles, navAcceptedColis, navMessage}]`; the last order
  (`GET /store-order/last`) counts an order holding only colis of the item, and gives its `nbColis`.
- **The link**: `StoreOrderDTO.colis [{navItemNo, description, nbColis, contenance, colisPossibles}]`; the head office
  refuses a colis without item number, with `nbColis` under 1, or the same item twice; an order of colis alone is taken.
  Posted as `ErpSalesOrderDTO.Line.colis(item, nbColis, pieces)` (`nbColis` set, variant empty, quantity = colis ×
  contenance, informative). NAV's answer of a colis line (`ErpSalesOrderResult.Line` `colis` true, `acceptedColis`,
  `message`) is kept on the colis row (summed per item, messages joined), never on a pieces line; down in
  `StoreOrderStatusCopyDTO.colis [{navItemNo, acceptedColis, message}]` (only once NAV answered one), written on the
  store's colis rows by item.
- **Vrac**: `vracAutorise` false is shown as a warning only ("Vrac non autorisé (ZEN)"): NAV accepted pieces of such an
  item (`CV26000004`, 2026-10-28), so NAV is the judge; the cells stay open.
- **If NAV refuses "colis"**: the order is ERROR with NAV's message, as any refusal (then ask ZEN the exact value).

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

- **the stock, ZEN's real page** `infoArticles` (2026-10-27): `GET infoArticles?$filter=article eq
  '<item>' and client eq '<ERP customer number>'` (a quote doubled) → `{"value": [...]}`, one row per variant: `article,
  codeVariante, client, designation, marque, unite, devise, prixUnitaire, remisePourcent, prixNet, vracAutorise,
  codeCouleur, libelleCouleur, codeChoix, codeTaille, ordreTaille, codeBarres, qteSerieType, contenanceSerie,
  colisPossibles, stock, qteVrac, disponible, calculeLe`. The page has **no location**: the stock is NAV's for the store's
  ERP customer (the store code is not sent). Mapping: `itemNo` = `article`, `variantCode` = `codeVariante` (the same codes as
  the catalogue's, `001`...), **`quantity` = `disponible`, never `stock`** (null when the row has none), `sizeOrder` =
  `ordreTaille` (carried, no longer used by the screens: 3.1.1 they sort the sizes themselves), `details` in this order:
  Prix unitaire, Remise %, Prix net, Stock, Qté vrac, Vrac autorisé (Oui / Non), Contenance série, Colis possibles,
  Types de série (`qteSerieType`, 3.1.1), Calculé le (`calculeLe` as `dd/MM/yyyy HH:mm` in the head office's time
  zone); an empty value is left out. Sample: `connectors/zen/src/test/resources/zen/infoArticles.json` (43ZBPND01, client
  4110001: `disponible` 203 or 101 where `stock` is 1 to 69; size 36 has `ordreTaille` 220, after 46's 200;
  `contenanceSerie` 10, `colisPossibles` 101 and `vracAutorise` false on every variant, `qteVrac` and `qteSerieType`
  per variant);
- **the sales orders, ZEN's real page** `commandesVente` (2026-10-28), under the same `companies(id)`:
  - lookup, required: `GET commandesVente?$filter=referenceZS eq '<number>'&$expand=lignesCommande` (a quote
    doubled); when it cannot be answered nothing is posted (ERROR, retried). One exception: NAV's answer says
    `Field 'referenceZS' is not filterable` (read in its body, any HTTP status): the lookup is skipped, the order posted
    as when none is found, and the communications log message ends with "duplicate check skipped: referenceZS not
    filterable" (on a failure too). ZEN made the field filterable on 2026-10-28, so the exception should no longer
    fire; while it does, an order whose POST answer is lost (timeout) is ERROR and the next run posts it again (NAV
    may hold it twice);
  - found: reused; the lines NAV does not have (by `article` and `codeVariante`: a posting that stopped half-way) are
    posted one by one, then the order read again (`GET commandesVente(<id>)?$expand=lignesCommande`);
  - none: one `POST commandesVente` with the lines nested (deep insert): header `referenceZS` (the order number),
    `commandeZS` always true, `client` (the store's ERP customer number), `dateCommande` (the send date,
    `yyyy-MM-dd`), no `dateLivraisonSouhaitee`. **No `numeroDocExterne`**: read-only on ZEN's side (NAV refused it:
    "Control 'numeroDocExterne' is read-only", 2026-10-28), ZEN put our number in `referenceZS`. The store code is not sent: no
    other writable header field fits it (`ordrePrelevement` is NAV's picking order, `statut` / `message` are NAV's
    answer), and `client` already names the store; lines `lignesCommande: [{article, codeVariante, mode,
    quantiteDemandee}]` (pieces; `mode` from `erp.zen.order.mode`, `vrac` by default, left out when set empty; no `nbColis`);
    3.1.1, a colis line `{article, mode: "colis", nbColis}` (no `codeVariante`, no `quantiteDemandee`; "colis" is
    `ZenOrderFields.MODE_COLIS`, whatever `erp.zen.order.mode` says). When NAV answers a 4xx
    whose body names `lignesCommande`, a navigation property or a deep insert, the header is posted alone, then one
    `POST commandesVente(<id>)/lignesCommande` per line; any other refusal is the order's failure. An answer without
    its lines is followed by the read by id (when that read fails: success with `numero`, without the lines' answer);
  - NAV's answer: `numero` (the ERP's number; none: failure), `statut`, `message`, `montantTTC`; per line
    `quantite` (the quantity accepted), `colisAcceptes`, `message`, `prixUnitaire`, `remisePourcent`, `montantTTC`. The
    connector gives `numero`, `message` and per line `article`, `codeVariante`, `quantite`, `message`, and 3.1.1
    for a line whose `mode` is colis `colis` true with `colisAcceptes` (`ErpSalesOrderResult.Line`); completing a
    reused order, a colis line is missing until NAV has a colis line of the item (never matched with a pieces line of no
    variant); the whole answer is in the communications log, whose message names the path taken ("posted
    with its n lines in one call (deep insert)", "NAV refused the nested lines: header then n lines posted one by one",
    "already exists: reused, nothing posted" or "its n missing lines posted"). An order NAV answers with a `numero` is
    POSTED whatever `statut` says. Sample: `connectors/zen/src/test/resources/zen/commandesVente.json` (CV26000003,
    made by hand in NAV: `commandeZS` false, `referenceZS` empty, `quantiteDemandee` 0, `quantite` 10 per line; the
    tests put the order number in its `referenceZS` where NAV answers our order).

The page names (`infoArticles`, `commandesVente`, `lignesCommande`) are constants of `ZenOrderFields`, not settings.
`erp.zen.order.mode` is `vrac` by default (NAV requires `colis` or `vrac` on every line, 2026-10-28: "vrac for now");
set to `colis` it sends that, set empty the field is left out. The connector's RestTemplate still refuses every method but GET, except a POST to `commandesVente` and to
`commandesVente(<id>)/lignesCommande`. A field ZEN renames is one line of `ZenOrderFields`.

## The store's screens (step 3)

Front `views/admin/store-orders/` and `components/admin/store-orders/` (`admin-pages.md`, "Commandes centrale"): the
Catalogue (tiles, filters in the URL, the variants modal with the NAV stock, the cart beside it) and Mes commandes (the
list, one order). Shown only where `/config` gives `supplyFromHeadOffice` and `navItems`. Two fields serve them:
`mainImageItemId` on a catalogue line (the tile's image is read at `item-image/item/{id}`), and a variant's `stock`
**null** when NAV gives no row for it (shown "—", never 0). In the variants matrix, the size columns are in our order
(3.1.1, `compareSizes`: numeric codes by value, then the letter scale XXS, XS, S, M, L, XL, XXL / 2XL, 3XL, 4XL..., then
anything else alphabetically; the ERP's `sizeOrder` is not used); a cell with `details` shows an info icon whose tooltip lists "label : value" (`store-orders-api.js`
`variantMatrix`, `detailLines`; `tools/store-orders-check.mjs`).

**The variants window** (3.1.1 enhancements, `StoreOrderVariantsModal.vue`, rules in `store-orders-api.js`, proved by
`tools/store-orders-check.mjs`):

- **Headers**: a size, colour or choice whose description is empty or shared by another code of the item shows its code
  (`dimensionLabels`: belts with three sizes "TAILLE" show 85, 90, 95).
- **Totals**: a column and a row "Total" (per colour row, per size, in all), live as the cells are typed (`matrixTotals`,
  the cells counted as the footer counts them).
- **Prices** (this window only, under the images): purchase net price (`Prix net` of the connector's details, ZEN's
  `prixNet`), discount (`Remise %`, `remisePourcent`), selling price with VAT (the variant's `price` before VAT ×
  (1 + `vatRate` %)), margin = selling price before VAT − purchase net price, in TND and in % **of the selling price before
  VAT**. Each shown once when every variant that has it agrees, else "variable", the values of each variant in its cell's
  tooltip (`variantPrices`, `priceBlock`, `cellTooltipLines`). Hidden when no variant has any.
- **Last order**: under the title "Dernière commande : date · number" (`GET /store-order/last`, a link to the order), and in
  each cell the quantity of that variant in it, greyed. Read beside the variants; a failure shows nothing.
- **Previous / next item**: the catalogue's lines of its current page (its filters, `neighbourItem`), by the footer's
  arrows ("n / total") or ← → when no field has the focus; the cart is kept. With quantities typed and not added: "Ajouter
  avant de passer ?" (Ajouter: added, then the next item; Ignorer: the next item, nothing added; Annuler: stays).
- **Série and colis** (3.1.1, from the head office's details, ZEN only): a série block (Contenance série "10 pièces",
  Colis possibles, Types de série, Vrac autorisé, Qté vrac), once when the variants agree, else "variable" (`serieBlock`);
  "Vrac non autorisé (ZEN)" as a warning badge on the matrix when a variant says Non (`vracRefused`), the cells open; a
  "Nombre de colis" field (whole, 0 or more; red above the colis possibles, the smallest the variants give, `colisLimit`;
  "≈ n pièces" when the contenance is agreed, `colisContenance`), its last order's colis greyed beside it; "Ajouter"
  sends it with the figures shown (`POST /draft/colis`). The cart lists the colis apart ("N colis (≈ M pièces)", the number
  editable up to the colis possibles, removable) and totals them; both order pages show them in their own block above
  the lines (`OrderColisTable`, NAV's colis accepted once posted) and "Colis" in the totals.
- **Ajouter et suivant**: beside "Ajouter à la commande" when the page has a next item: adds the cells changed (none: just
  moves), then opens the next item; an error keeps the window on the item.

**The lists** "Mes commandes" and "Commandes des magasins" have a search (the order number, the ERP / head office
number, a line's code or name; debounced 400 ms, Enter at once) and keep their filters in the URL (`q`, `storeId`,
`status`, `from`, `to`; `store-orders-api.js` `listFiltersFromQuery`, `queryOfListFilters`, `listParams`). **An order's
page**, the same on both sides: a header card (`OrderHeaderCard`) in three groups, Document (number, store, dates),
Processing (status, ERP order number, the head office's posting mode and attempts, the messages) and Totals (lines,
pieces), the note in its own block; the lines (`OrderLinesTable`) with their number in the order and a sortable code.

**NAV's answer** on both order pages (the store's and the head office's): the message on the order in an info box, and
once a line carries an answer the columns accepted and message; a line accepted below its quantity is highlighted
(`store-orders-api.js` `hasNavAnswer`, `acceptedShort`). The store's page says "Réponse de la centrale" and "Acceptée",
never "NAV".

## The head office's screen (step 4)

"Commandes des magasins" (`admin-pages.md`): the list and an order's page with its actions, in the Network menu, only on a
head office whose `GET /config` gives **`storeOrdersPost`** (`ERP` or `MANUAL`, from `headoffice.storeorders.post`;
null without the key and on every store). Permissions **`read:admin-headoffice-store-orders`** (the pages) and
**`write:admin-headoffice-store-orders`** (mark posted, reject, retry), given to ADMIN at every start only on such a head
office (`ZZDataInitializer.HEAD_OFFICE_STORE_ORDER_PERMISSIONS`, apart from `HEAD_OFFICE_ADMIN_PERMISSIONS`: the
frontend's head office routes are both sets). The head office routes' `meta.writePermission` lists the write permission
on the Roles page.

## Still to come

- ZEN: whether `mode` stays `vrac` on every line (`colis` would need the packs on the order lines); `referenceZS`
  filterable (then the "not filterable" exception of the lookup removed); what NAV fills in `statut`, `message` and `quantite` on a ZS order, read at the first live posting
  (if `quantite` comes later than the POST, every line would read short until then).
