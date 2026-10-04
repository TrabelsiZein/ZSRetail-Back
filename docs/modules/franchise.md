# Franchise

**Status**: the franchise profiles `franchise-admin` and `franchise-customer` were **removed** at the head office plan, step 9 (task 9.4a, 2026-10-04). No install ran them with real data (step 8). A franchise network runs on the head office model: a head office and stores, installed from two presets (procedure below). "Franchise" is not something the application knows: a franchise store is a store whose deliveries are invoiced (design, sections 2.2 and 3.6).

### What was removed (task 9.4a)

| Kind | Removed |
|---|---|
| Profiles and properties | `application-franchise-admin.properties`, `application-franchise-customer.properties`; `franchise.admin`, `franchise.customer`, `franchise.api-key`, `franchise.remote.url`, `franchise.sync.sales.cron`, `franchise.customer.allow-local-items` |
| Endpoints | `/franchise/items`, `/franchise/invoices` (and `/{id}/acknowledge`), `/franchise/sales`, `/franchise/client/sync/{status,items,supplies}`, `/admin/franchise/sales`; the `X-Franchise-Api-Key` filter and the `/franchise/**` entry of `SecurityConfig` (such a path now needs a login like any other) |
| Classes | `controller/franchise/*`, `service/franchise/*` (item sync, supply reception, sales push job), `security/FranchiseApiKeyFilter`, `dto/franchise/*`, `FranchiseSalesHeader`, `FranchiseSalesLine` and their repositories |
| Branches of shared classes | `ItemAPI` (franchise client item rules), `ItemService` (required franchise price), `InvoiceService` (invoice tagged with the customer's location), `InvoiceAPI` / `InvoiceListDTO` (two franchise fields), `InvoiceHeaderRepository` (two queries), `ItemRepository` (items changed since a date), `ZZDataInitializer` (vendor `FRANCHISE_ADMIN`, settings `FRANCHISE_LAST_ITEM_SYNC` and `FRANCHISE_LAST_SUPPLY_RECEPTION_SYNC`, three permissions), `AppRoleAPI` (three permissions), `LicenseFilter` (the `/franchise` exception), `ApplicationModeService` (the franchise flags and their three methods), `NodeOwnership` (the franchise rows of the derivation and their startup checks) |
| Entity fields | `Item.franchiseSalesPrice`, `Item.fromFranchiseAdmin`, `Customer.defaultLocation`, `InvoiceHeader.franchiseLocationCode`, `InvoiceHeader.franchiseReceivedAt` |
| `GET /config` | `franchiseAdmin`, `franchiseCustomer`, `allowLocalItems` (the frontend reads a missing one as false); `standalone` stays |

**Older screens** still send `franchiseSalesPrice` and `fromFranchiseAdmin` in an item, and `defaultLocation` in a customer: they are ignored, never refused (`@JsonIgnoreProperties` on `Item` and `Customer`, `LegacyFranchiseFieldsTest`). The answers no longer carry them.

**A leftover `franchise.admin=true` or `franchise.customer=true`** (an old profile file) stops the startup: `Invalid value 'true' for property franchise.customer: the franchise profiles were removed (head office plan, step 9). A franchise network runs as a head office and stores, from the presets headoffice and network-store (docs/modules/franchise.md); remove franchise.customer.` The key set to `false` or absent is accepted.

**Kept in the database** (no column or table dropped, design 5.2 rule 3; nothing writes them any more): tables `franchise_sales_header`, `franchise_sales_line`; columns `item.franchise_sales_price`, `item.from_franchise_admin`, `customer.default_location`, `invoice_header.franchise_location_code`, `invoice_header.franchise_received_at` (all nullable: a new row leaves them NULL); the `general_setup` rows `FRANCHISE_LAST_ITEM_SYNC` and `FRANCHISE_LAST_SUPPLY_RECEPTION_SYNC`, the vendor `FRANCHISE_ADMIN` and the role permissions `read:admin-franchise*` where they exist. `SalesHeader.synchronizationStatus` is the ERP field the franchise push reused; it stays.

**Frontend**: the franchise pages, menu group and flags are removed on the frontend side (task 9.2).

### Installing a franchise network on the model
Head office plan, step 8 (2026-10-04). A new franchise network runs as a head office and stores, from the presets `headoffice` and `network-store`, each installation with its machine file (task 9.3; `docs/deployment-modes.md`, "Presets" and "Machine file"). It never used the franchise profiles. The store-row invoicing settings, the supply prices and their pages come with step 7B (merged into release/2.1.0).

1. **Head office.** Create an empty database. Make its machine file from `deploy/machine-model.properties`: `spring.profiles.active=headoffice`, the database, its own log file and image folder (so a store can run on the same server). Log in as `admin`. Fill **Company information**: it is the seller on the supply invoices. Tax stamp on supply invoices: head office setting `SUPPLY_INVOICE_TAX_STAMP` (off by default).
2. **Items at the head office.** Create families, sub-families and items with their barcodes. The item's price is the **base selling price**, imposed on every store. Enter the **base supply price** of each item on the Supply prices page: it is what a store pays. Buy the goods from vendors at the head office (purchases, stock).
3. **One store row per store** on **Network → Stores**:
   - code = the store's future `DEFAULT_LOCATION`;
   - copy the key from the dialog (shown once, otherwise use regenerate-key);
   - price right and purchase right **off** (the default): imposed selling price, no own suppliers;
   - deliveries invoiced **on**, with the billing details (legal name, tax number, address);
   - supply price mode `PRICE_LIST` with no supply price list (base supply price), invoice rhythm `PER_BL`;
   - no selling price list (base selling price).
4. **Each store.** Create an empty database. Make its machine file from `deploy/machine-model.properties`: `spring.profiles.active=network-store`, the database, the head office address in `headoffice.url` and the key of step 3 in `headoffice.api-key`; start it. Set `DEFAULT_LOCATION` to the store's code in General Setup, and upload the license. The **Head office link** page shows ONLINE; the items arrive with the copies down.
5. **Check the chain once.** Validate a BL for the store at the head office. The store confirms the received quantities on its reception page. The invoice is created at the confirmation, at the supply price, and arrives at the store as a consult-only purchase invoice from vendor `HEAD_OFFICE`. The store's sales appear in the head office tickets.

Every answer of step 3 is a setting of the store row and can be changed per store later: a selling price list, a supply price list or a percentage off the selling price, grouped invoices, or the rights.
