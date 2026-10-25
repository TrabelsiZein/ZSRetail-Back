# Item variants for ZEN — check of the design against release/3.1.0

Written 2026-10-14, read only (no code changed). Branch `release/3.1.0`, back head `854dbc1`. Go-live 2026-10-30.
Rule: zero impact on EMTOP and Happyness. The design (7 points) is taken as decided; this file says where each point
lands in the code, what does not fit as it is, and an order of work.

Samples read: `D:\ZS Retail\Docs\zen\stockPoints.json` (100 rows) and `itemCategories.json` (12 rows).

## 0. What the samples say

| Fact | Value in the sample | Consequence |
|---|---|---|
| Key (locationCode, itemNo, variantCode) | unique, 100 rows, 4 locations, 15 itemNo, 90 itemNo~variantCode | one ZS item per variant, one PS row per (location, variant) |
| Longest `itemNo~variantCode` | 17 chars (`LS-10PC~LS-10PC-B`; the variant code may repeat the item number) | fits every column (see A.2) |
| `~` in itemNo or variantCode | none | the separator is safe on this sample; to confirm with ZEN (Q5) |
| Empty variantCode | none in the sample | rule for an item without variant still to decide (Q5) |
| barCode | 13 digits, 82 distinct, never shared by two variants, the same in every location | one barcode per variant, item-level data |
| publicPrice, descriptions, brand, size, colour | the same in every location for a variant | item-level data |
| clearance / clearancePrice | only on FRANCHISE rows (e.g. 14ZDU05: publicPrice 19.5, clearance true, clearancePrice 7) | PS-level data (point 4), see Q1 |
| publicPrice = 0 | 19 rows of 100 | rule needed (Q4); Happyness makes a zero price inactive |
| lastModifiedDateTime | 16 rows at `0001-01-01T00:00:00Z`; many rows share the same millisecond | delta must use `ge`, not `gt` (C.3) |
| categoryCode / familyCode / subFamilyCode / sectionCode | empty on all 100 rows | the item-to-tree link cannot be checked on this sample (Q11) |
| VAT | no field | the connector needs a default VAT setting, as Happyness (Q3) |
| itemCategories | indentation 0 = catégorie (VET, MEUBLES, DIVERS), 1 = famille (parent = catégorie), 2 = sous-famille (CHEM ← HOM), 3 = section (MANCHE ← CHEM) | the famille rows name their catégorie as parent: see A.3 |

## A. The seven points against the code

### Point 1 — `connectors/zen`

New project `connectors/zen`, package `com.digithink.zsretail.connector.zen`, built like `connectors/happyness`:

| File | Role | Model in happyness |
|---|---|---|
| `connectors/zen/pom.xml` | depends on erp-api only | `connectors/happyness/pom.xml` |
| `ZenConnectorDescriptor` + `META-INF/spring.factories` | name `zen`, abilities `HEAD_OFFICE_STOCK_POINTS` (+ `SUPPLY_INVOICES` for point 7), no old prefix | `HappynessConnectorDescriptor` |
| `config/ZenProperties` (`erp.zen.*`), `ZenConfig` (RestTemplate), `ZenCatalogueSettings` (`CatalogueSettings`: packet size, dry run, default VAT) | settings | `NavPosPagesProperties`, `NavPosPagesConfig`, `NavPosPagesCatalogueSettings` |
| `client/ZenRestClient` | GET stockPoints by location (and `lastModifiedDateTime ge` filter), itemCategories; paging by `@odata.nextLink` | `NavPosPagesRestClient` |
| `dto/ZenStockPointRow`, `ZenCategoryRow` | the JSON rows | `NavPosStockRow`, `NavPosCategoryRow` |
| `mapper/ZenMapper` | rows → `ErpItemDTO` (code `itemNo~variantCode`), categories tree → 4 levels by `indentation` | `NavPosPagesMapper` |
| `client/ZenCatalogueSource` | `ErpCatalogueSource`; `barcodesAfter` / `barcodesOfItems` answer an empty read | `NavPosPagesCatalogueSource` |
| `connector/ZenConnector` | `ErpConnector` (`@ConditionalOnErpConnector("zen")`); the push/fetch methods answer nothing or "not supported", as `NavPosPagesConnector` | `NavPosPagesConnector` |

Does not fit as is:
- **Do not copy `NavPosPagesMapper.items`**: it keeps one row per `Item_No` and leaves the other variants out
  (`VARIANT_ROW`, `connectors/happyness/.../mapper/NavPosPagesMapper.java:139-156`). ZEN's mapper must keep every row.
- A connector sees only erp-api (`ConnectorDependenciesGuardTest`): nothing of the happyness client can be shared; the
  REST client, paging and invoice code are copied, not reused (counted in the estimates).
- The sample URL (`/ZEN/api/sat/zsretail/v1.0/companies(<id>)/stockPoints`) is a custom API page (camelCase, OData v4,
  `@odata.nextLink`), not the POS pages of Happyness: authentication and paging are to confirm (Q7).
- **The item name.** Every variant of 70061 has the description `CHEMISE`. Since the cart, the ticket and the returns
  show `item.name` and are not changed (point 6), a cashier would see five lines "CHEMISE" for S/M/L/XL. The design
  allows the mapper to set `name = itemDescription + " " + variantDescription` ("CHEMISE S / Noir / 1er Choix", 77
  characters at most in the sample, the column is 255). Recommended, to decide (Q2).

### Point 2 — variants as item rows

Lands on `core/model/Item.java`, `ErpItemDTO`, `ErpItemBootstrapService.applyItemValues`
(`core/.../erp/service/ErpItemBootstrapService.java:455-480`), the engine's comparison and the store copy (all in B).

Does not fit as is:
1. **`brand` already exists** on `Item` (`Item.java`, `private String brand`), travels in `CatalogueItemCopyDTO`, is
   saved by the store (`CatalogueCopyWriter.java:174`), is a free text field of the item form
   (`ItemManagement.vue:440`), of the data import (`DataImport.vue:372`) and of the stock point details
   (`StockPointItems.vue:298`). Add no column: ZEN's `brandCode` goes into `item.brand` (or the description, Q8).
2. **`category` already exists** on `Item` as a plain string with the same paths (store copy, form, import). The new
   link to the catégorie table cannot be named `category`. Recommended: `category_code` and `section_code` as codes
   (as `ho_stock_point_item` keeps the family by code), not foreign keys: the store then needs no catégorie/section rows,
   so no new `CatalogueKind` (a 3.1.0 store would not know a `CATEGORY:` record code) and no waiting logic at the
   store. The existing `category` string stays untouched for the other customers.
3. **`barcode` exists twice**: the old single field `item.barcode` and the table `item_barcode`. The scan reads
   `item_barcode` (`ItemBarcodeService.java:116`, POS `GET /item-barcode/barcode/{q}`; `item.barcode` is only a
   fallback on the loaded tiles, `ItemSelection.vue:2113`). ZEN's barcode must go to `item_barcode`.
4. **Writing nulls.** `applyItemValues` is also the import of EMTOP (`IMPORT_ITEMS`) and of Happyness. Setting the new
   fields from a DTO that does not carry them would write null over values; for `brand` (already a column) it would
   wipe a brand typed by hand on an EMTOP or Happyness item. Rule: apply the variant fields only when the DTO is a
   variant (`navItemNo` set), and compare them in the engine the same way.
5. **The barcode with the item row** (the connector's barcode read is empty). The engine is fine with an empty barcode
   read: the cursor stays 0, so no item is put in "needs its barcodes" (`CatalogueEngine.java:376-389`) and the run is
   "caught up" at once. But the barcode must be saved by someone. Two ways:
   - inside `importItems`: the recorder (`HeadOfficeErpCatalogueRecorder` → `HoCatalogueService.erpItemsSaved`,
     `HoCatalogueService.java:579-605`) sends the item's barcodes again to its stores, **but a changed or moved barcode
     is not sent as removed** (only the barcode import reads the values before, `barcodesBefore`);
   - **recommended**: the items run hands, after each items packet, the barcodes of the changed variants to
     `importer.barcodes(...)` (the existing barcode import and its recorder path, which handles moved and changed
     values). `ErpItemDTO` gets a `barcode` field; Happyness leaves it null and nothing happens.
6. Unique constraints: `item.item_code` unique (fine, `itemNo~variantCode` is unique); `item_barcode.barcode` unique
   (fine on the sample, every barcode belongs to one variant).
7. Editing a ZEN item cannot wipe the new columns: `PUT /item/{id}` is refused when the catalogue comes from the ERP
   (`ItemAPI.java:210-213`). If that ever changes, the new fields go in the "preserve" list at `ItemAPI.java:226`.

### Point 3 — tables catégorie and section

| Part | File |
|---|---|
| Entities + repositories | `core/model/ItemCategory`, `core/model/ItemSection` (+ `core/repository`) — in core because the import (`ErpItemBootstrapService`) is in core, as for families. No name clash: no `Category`/`Section` class or table exists |
| erp-api | two DTOs (`ErpItemCategoryDTO`, `ErpItemSectionDTO`, shaped like the family DTOs; section with its sous-famille code); `ErpCategories` gets the two reads |
| Import | `ErpItemBootstrapService.importItemCategories` / `importItemSections` |
| Engine and job | `CatalogueEngine` two parts like `families()`; `missingFamilies` (`:201-216`) also waits for them; `CatalogueJob.syncCatalogue` two parts |
| Head office read | `HeadOfficeCatalogue` + `JpaHeadOfficeCatalogue`: `categories()`, `sections()` |
| Front (head office) | read-only lists beside families/sub-families (`ItemFamiliesManagement.vue` as model), menu `navigation/headoffice/index.js:35-46` |

Does not fit as is:
- **Keep the 2-argument `ErpCategories` constructor** (answering the two new reads empty) and add a 4-argument one.
  Then `NavPosPagesCatalogueSource` (`categories()`, its one call) does not change: "Happyness returns them empty"
  with no line touched in its connector.
- **The job's summary.** `CatalogueJob` writes every part in the communications log; adding two parts always would
  change Happyness's log and the tests that read it. Add the catégorie and section parts only when the read has rows.
- **The famille → catégorie link.** In the tree a famille's parent is a catégorie (HOM ← VET); `ErpItemFamilyDTO` and
  `ItemFamily` have no parent. The item row carries its own `categoryCode`, so the item does not need it. If the head
  office pages must show the tree, `item_family` would need a nullable `category_code`; otherwise nothing changes on
  families (zero impact). To decide (Q9). The section keeps its sous-famille code (its parent).
- The store does not receive the two tables (see point 2.2: the item copy carries the codes).

### Point 4 — clearance on the PS row

| Part | File |
|---|---|
| Entity | `headoffice/model/HoStockPointItem.java`: `clearance` (boolean), `clearance_price` |
| Engine port | `core/erp/engine/StockPoints.Row` (+ `inactive()`), `CatalogueEngine.rowOf` (`:505-519`), `differs(Row, Row)` (`:528-535`), `fromStored` (`:542-558`) |
| erp-api | `ErpItemDTO`: `clearance`, `clearancePrice` (the PS row's values, the item copy ignores them) |
| Write/read | `headoffice/service/HoStockPointRows.java:68-74` (read), `:103-108` (write) |
| Page | `headoffice/dto/StockPointItemDTO`, `HoStockPointItemsService`; front `views/admin/headoffice/StockPointItems.vue` (columns `:370-381`, price block of the details `:247-281`) |

Does not fit as is:
- **Null against false.** Existing Happyness rows will have `clearance` null after the upgrade; the new value from its
  mapper is false. If `differs` compares them as they are, **every Happyness row becomes "changed" on the first run**:
  all rows rewritten and every item sent again to every store. Compare null as false (and a null price as null), or
  fill the column with 0 in the SQL script.
- **What clearance does at the store is not said.** The store's price comes from the row (`HoCatalogueService.pointCopy`,
  `:250-265`, `row.getUnitPrice()`). With the design as written, a FRANCHISE store sells 14ZDU05 at 19.5 even though
  NAV says clearance at 7. If the till must sell at the clearance price, it is one line in `pointCopy` (price =
  clearancePrice when clearance), no POS change. To decide before go-live (Q1).
- `StockPoints.Row` is built in 3 main places only (no test builds it): the constructor change is cheap.

### Point 5 — delta catalogue job

See C. Lands on `ErpCatalogueSource` (erp-api), `CatalogueEngine.readAndApplyItems` (`:243-436`), `CatalogueState`
(no DDL). Does not fit as is: the engine is a **full-compare engine**; three of its rules are wrong on a delta read and
must be switched off in delta mode (C.2).

### Point 6 — head office → store copy

| Part | File |
|---|---|
| Copy DTO | `core/link/dto/CatalogueItemCopyDTO.java`: new fields + `of(...)` |
| Head office | `HoCatalogueService.pointCopy` builds on `of(...)`: nothing else to change (the row overrides name, description, family, sub-family, price, active only) |
| Store | `store/holink/service/CatalogueCopyWriter.saveItem` (`:167-186`): the setters; `isUnchanged` (`:207-218`) compares `of(storeItem)` with the copy by `equals`, so it follows the new fields by itself once the store saves them |

Fits: the DTO has `@JsonIgnoreProperties(ignoreUnknown = true)`, so a 3.1.0 store that is not upgraded ignores the new
fields, and an upgraded store receiving copies from an old head office gets nulls. Record codes are 100 characters,
`CatalogueKind.MAX_CODE_LENGTH` 90: `ITEM:70061~001` is far below. Scan, cart, ticket, stock, returns, counts and sales
to the head office all work by item id or item code and need nothing (see A.2 below).

### Point 7 — supply invoices

The head office matches invoice lines by the whole code (`HoErpInvoiceService.java:365-377`, `findByItemCodeIn`) and
keeps it in `ho_erp_invoice_line.item_code` (100 characters). So `No~Variant_Code` built by the connector matches the
variant item with nothing changed in the head office or the store. Fits.

But the invoice reader of Happyness is in its connector (`sync/NavPosPagesInvoices`, the invoice pages, FVV numbers by
year): ZEN needs its own (`fetchSupplyInvoices`, `fetchSupplyInvoicesByNumbers`, `supplyInvoiceSellerName` of
`ErpConnector`) and the ability `SUPPLY_INVOICES` in its descriptor. Which NAV page/API ZEN exposes for invoices is not
known (Q10). The rule `itemNo~variantCode` (and the no-variant case, Q5) must be one helper of the connector used by
both the catalogue mapper and the invoice mapper.

### A.2 — places that could assume a code is a NAV number, lengths, parsing

Checked across every project's `src/main`:

| Check | Result |
|---|---|
| Length of item code columns | `item.item_code` 255 (Hibernate default); every copy table 100: `ho_delivery_line`, `ho_erp_invoice_line`, `ho_store_stock`, `ho_supply_invoice_line`, `received_delivery_line`, `stock_copy`; record codes 100 (`HoDownChange`, `DownRecord`). No SQL script sets a smaller size |
| Point de stock code | `HoStockPoint.CODE_LENGTH` 20 (sample locations: 9 at most) |
| `~` used anywhere as a separator, `split`, numeric parse of an item code | none (back); front: no regex, split or maxlength on item code (`Deliveries.vue:738-743` splits a 409 message on `': '` and `;`, not on `~`) |
| Code = NAV number | EMTOP only: ticket/return lines send `item.erpExternalId` as `No` (`connectors/emtop/.../DynamicsNavMapper.java:450,542`); sales prices match `SalesPrice.itemNo` + `variant_code` (EMTOP's job). Neither runs for ZEN. If ZEN's head office later sends sales to NAV, `nav_item_no` + `variant_code` are what the line needs |
| Scan | barcode table first (`ItemBarcodeService.java:116`; POS search `ItemService.java:138`: barcode, then whole code). A search on `70061` finds all its variants (contains) |
| Store guard / copy | by code; nothing parses it |

## B. Every place the new item columns must be carried

New columns (nullable, empty for other customers): `nav_item_no`, `variant_code`, `size_code`, `size_description`,
`size_sort_order`, `color_code`, `color_description`, `choice_code`, `choice_description`, `season_code`,
`division_code`, `category_code`, `section_code`. Brand: the existing `brand` column (A, point 2.1).

There is **no Liquibase** in this repo: schema changes are `spring.jpa.hibernate.ddl-auto=update` plus the hand-run,
numbered script. 3.1.0 is not tagged (last tag `v2.3.0`), so the statements go into
`core/src/main/resources/db/3.1.0/update.sql` (re-runnable, `IF COL_LENGTH(...) IS NULL`, same names and types as
Hibernate).

| # | Layer | Where | What |
|---|---|---|---|
| 1 | SQL | `db/3.1.0/update.sql` | `ALTER TABLE item ADD` the 13 columns (store and head office); `CREATE TABLE item_category`, `item_section` (both, as families); `ALTER TABLE ho_stock_point_item ADD clearance bit`, `clearance_price float` guarded by `IF OBJECT_ID(N'ho_stock_point_item') IS NOT NULL` (head office only) |
| 2 | Entity | `core/model/Item.java` | the 13 fields |
| 3 | Entity | `core/model/ItemCategory.java`, `ItemSection.java` + repositories | new |
| 4 | Entity | `headoffice/model/HoStockPointItem.java` | clearance, clearancePrice |
| 5 | erp-api | `dto/ErpItemDTO.java` | the 13 values + `barcode` + `clearance`, `clearancePrice`; new `ErpItemCategoryDTO`, `ErpItemSectionDTO`; `ErpCategories` |
| 6 | Import | `ErpItemBootstrapService.applyItemValues` (`:455`) | set the fields when the DTO is a variant (A.2.4); `importItemCategories`, `importItemSections` |
| 7 | Engine read | `HeadOfficeCatalogue.Item` + `JpaHeadOfficeCatalogue.items()` JPQL | the new fields; **keep the 11-argument constructor** (used by `NavPosPagesFixture`, `NavPosPagesCatalogueSourceTest`) |
| 8 | Engine compare | `CatalogueEngine.differs(ErpItemDTO, Item, ...)` (`:590-611`) | compare the new fields when the DTO carries them |
| 9 | Engine copies | `CatalogueEngine.inactiveCopy` (`:620-634`) | carry the fields, otherwise deactivating a variant would empty them |
| 10 | Engine rows | `StockPoints.Row`, `rowOf`, `differs(Row,Row)`, `fromStored`, `HoStockPointRows` | clearance (null = false) |
| 11 | Copy down | `core/link/dto/CatalogueItemCopyDTO` (+ `of`) | the 13 fields |
| 12 | Store | `store/holink/service/CatalogueCopyWriter.saveItem` | the 13 setters |
| 13 | Head office page | `headoffice/dto/StockPointItemDTO`, `HoStockPointItemsService` (`:170-172` already copies brand, category) | clearance, variant fields, category/section |
| 14 | REST | `ItemAPI` returns the entity itself (`_BaseController`): the fields reach the front with no DTO | — |
| 15 | Front | `views/admin/ItemManagement.vue` | read-only "Variant" block in the form (`:261-522`), optional list column; `buildItemPayload` (`:1043-1076`) need not send them (PUT refused with an ERP catalogue) |
| 16 | Front | `views/admin/headoffice/StockPointItems.vue` | clearance column and price block; variant fields in the details "from item" (`:283-303`) |
| 17 | Front | catégorie and section read-only pages + routes `router/headoffice-routes.js`, menu `navigation/headoffice/index.js` | new |
| 18 | Front i18n | `libs/i18n/locales/en.json`, `fr.json`, `ar.json` (`admin.itemManagement` `:2753`, `admin.headoffice.stockPointItems` `:4030`; ar has no stockPoint keys today) | labels |
| 19 | Docs | `docs/modules/erp-sync.md` (abilities table, how to add a connector), `head-office.md` (stock points), `deployment-modes.md` | |
| — | Not needed | data import (`DataImportService`, `DataImport.vue`), POS (`ItemSelection.vue`), tickets, returns, counts, sales copies | point 6 says unchanged; nothing breaks |

## C. Point 5: checkpoint per PS

### C.1 What exists

- `ErpSyncCheckpointService` (`core/erp/service`) keeps **one timestamp per job type** in General Setup
  (`ERP_SYNC_LAST_ITEM`, ...), for the EMTOP import jobs, shown on the jobs page (`ErpSyncJobAdminController:186-190`).
  The catalogue engine never uses it. It cannot hold a value per PS without new General Setup rows per point, and
  touching it touches EMTOP. **Do not use it.**
- `CatalogueState` (`erp_catalogue_state`: `state_key` / `state_value` NVARCHAR(200)) already holds the engine's
  values between runs (`items.run`, `items.pending`, `barcodes.cursor`, `needs-barcodes:<code>`). An ISO timestamp fits.

### C.2 Why the engine cannot take a delta read as it is

`CatalogueEngine.readAndApplyItems` assumes each point's read is the whole point:
1. `PointRead.compare` (`:490-495`): an active stored row not in the read becomes **inactive**. On a delta read, every
   unchanged row would be deactivated.
2. Items of the ERP "in no point any more" become **inactive** (`:338-358`). On a delta, every unchanged item.
3. A point that answered 0 rows is "no answer" (`:269-271`, `READ_NO_ANSWER` on the points page `:430`) and blocks the
   deactivation for all. On a delta, 0 rows means "nothing changed".
4. The item's default copy is chosen among the rows read (`:287-297`); on a delta, a variant changed only in point B
   takes B's row as its default even when A comes first (same price and data in the sample, but an `active` flag
   from NAV later would make a variant inactive because one point changed).

### C.3 Smallest change

erp-api (Happyness's connector unchanged, no test fake changed):
```java
// ErpCatalogueSource
default ErpRead<ErpItemDTO> items(String locationCode, OffsetDateTime since) { return items(locationCode); }
/** True when items(location, since) answers only the rows changed since then (a delta). */
default boolean readsChangesOnly() { return false; }
```
(The design says "`items` gets a since parameter"; an overload with a default does the same and leaves
`NavPosPagesCatalogueSource` untouched. ZEN implements both and answers `true`.)

Engine, only when `source.readsChangesOnly()`:
- key `items.since:<point id>` in `CatalogueState` (by id: a point's code may be edited); absent = first run = full
  read (`since` null), and then the full rules apply as today for that point;
- read with `since`; NAV filter `lastModifiedDateTime ge since` (**ge**: rows commit in the same millisecond; the
  boundary rows are read again and compare as unchanged);
- in delta mode: no deactivation of rows (1) or items (2); a 0-row read is "OK, no change" (3); merge rule (4): an item
  is active when its row is active in at least one point (stored rows of the other points counted);
- after the point's rows are written with no failure: save max(`lastModifiedAt`) of its rows (ignore `0001-01-01`);
  never in a dry run; a failed packet leaves the checkpoint where it was (the next run reads the same rows again).
- The admin can force a full read by deleting the key (a "reset" button is optional, not for go-live).

Notes:
- Removed rows are not handled (as the design says): a variant blocked in NAV stays active until NAV gives the flag.
- Ask NAV's developer whether `lastModifiedDateTime` moves on a change of price, clearance, barcode or category, not
  only of the item card (Q6). If not, a delta misses those changes.
- Volume: the delta cuts the NAV read, not the head office work: each run still loads every item
  (`JpaHeadOfficeCatalogue.items()`) and every row of each point (`stockPoints.rows`). Fine for thousands of variants;
  to measure if ZEN has tens of thousands × several points.
- Tests: a fake delta source in `CatalogueEngineTest` (first full run, delta run, 0-row delta, failed packet keeps the
  checkpoint, dry run writes nothing); the Happyness tests stay as they are.

## D. Is "a folder + two lines in `connectors/pom.xml`" enough?

For Maven and the guards that scan folders: yes. `ProjectSortingGuardTest` and `ConnectorDependenciesGuardTest` find
`connectors/<name>` by folder, `ConnectorNamesGuardTest` reads the descriptors, the WARs take `zsretail-connectors`, and
`network-tests` depends on `zsretail-connectors`, so the new descriptor is on its class path.

Not enough for the tests that **pin the list of connectors**; they fail as soon as `zen` is on the class path
(`ErpConnectors.descriptors()` sorts by name: none, emtop, happyness, zen):

| Test | Line | Why it fails |
|---|---|---|
| `network-tests/.../core/config/ErpConnectorTest` | `:41` (`CHOSEN_BY`), `:119`, `:122` | message "none \| emtop \| happyness." becomes "... \| happyness \| zen." |
| `network-tests/.../erp/ThirdConnectorTest` | `:125`, `:134`, `:140` | names list, message, `connectorsWith(SUPPLY_INVOICES)` |
| `network-tests/.../erp/api/ErpAbilitiesTest` | `:50-52` | `connectorsWith(...)` is `[happyness]` |

Once a ZEN deploy file is added under `configs/customers/` (step 10), these read every file of `configs/` and need their
records or counts updated: `ErpAbilitiesTest.existingFiles` (`:116-125`, it equates "has stock points" with
"happyness"), `ConfigurationKeysProofTest` (`configuration-keys.txt`), `InstallationBeansProofTest`
(`installation-beans.txt`), `ErpAbilityBeansTest`, `ErpCatalogueHeadOfficeTest`, `DynamicsNavRestClientValuesTest`.

Two more things:
- `ConnectorNamesGuardTest.NAMED` (`:96`) is `(?i)emtop|dynamicsnav|happyness|navpospages|zen` **with no word
  boundary**: from now on any string literal outside the connectors containing "zen" (frozen, dozen, citizen) fails
  the guard. None exists today; worth a `\b` around the words in the same commit.
- `AspectPointcutsGuardTest` needs nothing: no new pointcut (catégories and sections are not sent to the stores).

## E. Order of work

Each step = one commit per repo touched, `mvn clean test --fail-at-end` green, EMTOP and Happyness behaviour unchanged
(their DTOs carry nulls, their reads answer empty, every new branch is behind "the DTO is a variant" or
`readsChangesOnly()`).

| # | Step | Projects | Estimate |
|---|---|---|---|
| 1 | Schema: `Item` 13 columns, `ItemCategory`/`ItemSection` + repositories, `HoStockPointItem` clearance; `db/3.1.0/update.sql`; docs | core, headoffice | 0.5–1 d |
| 2 | erp-api contracts: `ErpItemDTO` fields (+ barcode, clearance), category/section DTOs, `ErpCategories` 4-arg (2-arg kept), `ErpCatalogueSource` defaults `items(location, since)` and `readsChangesOnly()` | erp-api | 0.5 d |
| 3 | Import + engine for variant items: `applyItemValues` (variant only), `HeadOfficeCatalogue.Item` fields (old constructor kept), `differs`, `inactiveCopy`; item barcodes handed to `importer.barcodes` after each items packet; engine tests with a variant fake source | core | 1.5 d |
| 4 | Catégories and sections: import, engine parts, job parts (only when rows), items wait for them; tests | core | 1 d |
| 5 | PS clearance: `StockPoints.Row`, `rowOf`, `differs` (null = false), `HoStockPointRows`, page DTO; price rule of Q1 in `pointCopy` if chosen; test that Happyness rows with null clearance are not "changed" | core, headoffice | 0.5–1 d |
| 6 | Delta checkpoint per PS (C.3); tests | core | 1–1.5 d |
| 7 | Copy down: `CatalogueItemCopyDTO` + `CatalogueCopyWriter`; round trip test in network-tests | core, store | 0.5 d |
| 8 | `connectors/zen`: project, descriptor, properties, client, mapper (code helper, tree, name, VAT, zero price), source, settings, `ErpConnector`; tests on the two sample files; update the pinned tests of D; `\b` in `NAMED` | connectors/zen, network-tests | 2–3 d |
| 9 | ZEN supply invoices in the connector (Q10) | connectors/zen | 1–1.5 d |
| 10 | Deploy files `configs/customers/zen-headoffice.properties` (+ store); proof records of D | configs, network-tests | 0.5 d |
| 11 | Front: item form "Variant" block, stock point items clearance, catégorie/section read-only pages, i18n en/fr/ar | ZSRetail-Front | 1–1.5 d |
| 12 | Read against ZEN's NAV test instance (GET only, live read test as `NavPosPagesLiveReadTest`), rehearsal head office + store, docs | all | 1 d |
| | **Total** | | **11–14.5 days** |

From 2026-10-15 to 2026-10-29 there are 11 working days: no slack. If time runs short, what can follow after go-live
without blocking sales: step 11's catégorie/section pages (the data is saved), step 9 if invoices can wait, the "reset
checkpoint" button. Steps 1–8, 10 and 12 are the go-live path. Steps 1–7 do not need ZEN's answers; step 8 needs Q2–Q7.

## Questions to settle (ZEN / NAV developer / Zein)

| # | Question | Blocks |
|---|---|---|
| Q1 | Clearance at the till: does a store sell at `clearancePrice` when `clearance` is true? (One line in `pointCopy` if yes.) **Answered 2026-10-17: yes, when the clearance price is above 0** (step 11b's "no price logic" reversed; section F) | step 5 |
| Q2 | Item name = item description + variant description? | step 8 |
| Q3 | VAT: one default rate, or per item (not in the API)? Is `publicPrice` with VAT? | step 8 |
| Q4 | `publicPrice` = 0 (19 % of the sample): inactive, as Happyness, or sold at 0? | step 8 |
| Q5 | Item without variant: is it in the API with an empty `variantCode`? Proposed code: `itemNo` alone. Can `~` appear in a NAV code? | steps 8, 9 |
| Q6 | Does `lastModifiedDateTime` move on price, clearance, barcode and category changes? Filter syntax (`ge`) accepted? | step 6 |
| Q7 | Authentication and paging of the API; the test instance address | step 8 |
| Q8 | `brand`: code or description in `item.brand`? Season and division descriptions (not in the API) needed? | step 1 |
| Q9 | Should a famille point to its catégorie at the head office (one nullable column on `item_family`)? | steps 1, 4 |
| Q10 | Which NAV API gives ZEN's posted invoices to the franchise (as Happyness's invoice pages)? **Answered 2026-10-23: NAV's standard OData V4 page `SalesInvoice` of the company `ESP` (lines `SalesInvoiceSalesInvLines`), every posted sales invoice of the company: only the stores' customers are read** (section F, step 9a). **2026-10-24: moved by ZEN's NAV team to their custom API `api/sat/franchise/v1.0`, page `salesInvoices` (lines `salesInvoiceLines`), same `companies(<id>)`** (section F, step 9a-bis) | step 9 |
| Q11 | A sample where `categoryCode` … `sectionCode` are filled (all empty today) | step 8 tests |
| Q12 | ZEN stores: `erp.connector=none` (sales go to the head office only)? Head office sales to NAV: out of scope? | step 10 |
| Q13 | Page `barCodes`, `unitOfMeasureCode`: can a barcode be of a unit other than the piece (a box of 6)? Today every row is kept whatever its unit, so such a barcode would sell one piece of the variant (step 12a) | before go-live |
| Q14 | Page `barCodes`: is `lastModifiedDateTime` NAV's `SystemModifiedAt`, and does a key cover it? Every barcode run filters and sorts on it (`$filter ge`, `$orderby`): without a key, a whole scan of the table per call (step 12a) | volume |
| Q15 | Page `salesInvoices`: can ZEN add `cancelled` and `corrective` to it (FlowFields of the posted sales invoice)? Without them a cancelled invoice is read and sent to its store (step 9a-bis) | before `headoffice.supply.source=ERP` |
| Q16 | Does ZEN use invoice discounts on the invoices to the stores? The store's cost is `lineAmount` ÷ quantity (before an invoice discount); today such an invoice only gets the warning "the lines add up to X, amount is Y" (step 9a-bis) | before `headoffice.supply.source=ERP` |

## F. Done and to do

Steps 1–10: the commits "Item variants (ZEN) step 1" … "variants: ZEN config files + company name (step 10)" on
`release/3.1.0`.

**Step 11a (2026-10-16), front part A**: the read-only page "NAV items" (store and head office, one line per NAV item
number, its variants on opening, the variant × point de stock price grid with clearance at the head office, stock only
where the installation keeps its own) and the "Variant" block of the item form. Decisions: the Items permission shared,
`navItems` of `GET /config` read on each call (no cache), a View action only for the rows with a NAV item number, status
Active / All (Active by default). The index `ix_item_nav_item_no` is plain, not filtered (a filtered index on `item`
would refuse sqlcmd writes with `QUOTED_IDENTIFIER OFF` for every customer). Details: `docs/modules/head-office.md`,
"NAV items".

**Step 11b (2026-10-16), front part B**: the clearance in the price cell and the details of "Items by point de stock"
(no new column), the read-only pages "Catégories" (families per catégorie, a line opens on its families) and
"Sections" (path catégorie › famille › sous-famille), their menu entries by `GET /config` `itemCategories` /
`itemSections` (a row in the table; no cache), the Items permission, the catégorie column of the famille page (only
with catégories; no section column on the sous-famille page). `CHANGELOG.md` in both repos, backfilled to 1.12.0.
Details: `docs/modules/head-office.md`, "Catégories, sections and clearance".

**Step 11c (2026-10-16), store display of variants**: one label rule, "name — variant description" when the variant
is not empty, differs from the name and the name does not already end with it as whole words (case and spaces ignored),
on the till (tiles, cart, recall, recap, split bill, returns), the printed tickets and vouchers, the admin ticket and
return details, the A4 invoice and the warranty slip; the return endpoints give `variantDescription`
(`ReturnHeaderAPI.itemData`). Not the head office history (later). Details: `docs/modules/head-office.md`, "Variant
label".

**Step 12a (2026-10-16), connectors/zen on ZEN's real barcodes page**: ZEN's test NAV
(`http://192.168.10.124:7048/BC280`, company id `1d6c4f00-6ac9-f111-9db8-ad3d0c589c9c`, NTLM) read for the first time.
Its barcodes are the custom page `barCodes` (`id` GUID, `itemNo`, `variantCode`, `unitOfMeasureCode`, `barCode`,
`description`, `lastModifiedDateTime`), not Happyness's `ItemBarCodePOS`: no `Entry_No`. `stockPoints` has no `active`
and no `vat` (the mapper already reads them as active and `erp.zen.default-vat`; only comments changed);
`itemCategories` is empty for now. Changes, in `connectors/zen` only (engine and the other connectors untouched):
- `ZenBarcodeRow` with ZEN's names, `$select=id,itemNo,variantCode,barCode,lastModifiedDateTime`; default page
  `erp.zen.page.barcodes=barCodes`; the barcodes of some items by `(itemNo eq '..' and variantCode eq '..')`.
- The engine's barcode position (a long, "the rows above it") is the row's `lastModifiedDateTime` in epoch
  milliseconds, the instant rounded down to the millisecond (`ZenMapper.position`); NAV's empty date 0001-01-01, a date
  before 1970 or one not readable: position 1 (a date not readable is also left out). Cursor 0 reads with no filter,
  after it `$filter=lastModifiedDateTime gt <cursor, UTC>`, `$orderby=lastModifiedDateTime`, `$top`.
- **A full page is completed to its last time**: when a page has `$top` rows, every row of the time of its last row
  is read too (`eq P`; for position 1 `lt 1970-01-01T00:00:00Z`; next links followed), a row already on the page (same
  `id`) once. Why: with a plain `ge <cursor>` the engine's guard ("rows at or below the
  cursor: cursor kept") would end every run as not caught up, and a millisecond with more rows than a page (a bulk
  import, NAV's empty date) would hold the cursor for ever; with `gt`, the rows of a millisecond cut by `$top` would be
  skipped. A page ending on a whole millisecond keeps the engine's contract exactly (the rows above the cursor, an
  empty answer when caught up). Cost: one more GET per full page.
- A barcode changed in NAV (moved to another variant) gets a new date and is read again: the engine keeps the latest.
  Not seen, as with `Entry_No`: a deleted barcode; a NAV transaction committed after the cursor passed its time.
- An `Entry_No` cursor already in a ZEN head office database (tests) reads as a time in 1970: one whole read again,
  harmless.
- **Follow-up (live run, 2026-10-16)**: the first filters were `ge <cursor + 1 ms>` and `ge P and lt P+1ms`. The run
  against ZEN's NAV ended with "the ERP answered rows at or below the cursor 1792170044207: cursor kept"
  (17:00:44.207Z): NAV keeps `lastModifiedDateTime` in SQL `datetime` (ticks of 1/300 s, `.000 .003 .007`, shown to
  the millisecond) and SQL rounds the time of a filter to a tick, so `ge .208` was `ge .207` and the rows at the
  cursor came back. Now `gt <cursor>` and `eq P`, on times NAV gave (they land on their own tick); the position is
  unchanged. Rule: never a time computed from a NAV date (+ 1 ms, a half-open range). The `stockPoints` checkpoint is
  the highest date NAV gave, read with `ge` (the rows at it read again, unchanged: harmless); no other date is computed.
- Tests: `ZenRestClientTest`, `ZenMapperTest`, `ZenCatalogueSourceTest`; `ZenBarcodeCursorTest` (network-tests: the
  engine on the real client, mapper and source against a fake `barCodes` page answering the filters); the startup line
  of `ZenStartupCheckTest`.

**Speed with ZEN's catalogue (2026-10-16, back 2fe0b34 and the next commit)**: measured on ZEN's test head office and
store (21,903 items, 21,940 barcodes, 43,843 records pulled). Fixed in code: the "Items by point de stock" query without
a search (the barcode `exists` only with a search; index `ix_item_barcode_item`), the store's tracking rows read once
per page, a page of barcodes read with its items, the pull's `findChanged` on the index `ix_ho_down_change_pull`, and
the store's barcode apply (the old `item.barcode` field read once, on the index `ix_item_barcode`).
Not code, the development machine (left to Zein): the machine's commit charge is at its limit (39.9 GB of 42.7 GB:
Eclipse 4.9 GB, `vmmem` WSL/Docker 4.1 GB, the JVMs without `-Xmx`), Windows tells SQL Server "physical memory low"
about every minute and SQL Server gives its memory back (buffer pool 16 MB of a 3.5 GB target, page life expectancy
38 s, the plan cache emptied): every query reads from disk again. Remedies: `min server memory` 2-4 GB, or less
committed memory (`vmmem` stopped when WSL/Docker is not needed, `-Xmx` on the Eclipse launches). Measure speed only
once that is done.

**Variant description built by the connector (2026-10-17)**: on ZEN's real platform `stockPoints.variantDescription`
is always the item description (NAV never composes it; the 2026-10-11 sample's "S / Noir / 1er Choix" was not what ZEN
gives). `ZenMapper.variantDescription` builds it from `sizeDescription`, `colorDescription`, `choiceDescription`
(trimmed, the blank ones left out, " / "; "" when none) and NAV's field is no longer read (removed from
`ZenStockPointRow`). `item.name` stays the item description. The engine compares it with the other variant fields, so a
colour description changed at ZEN is a change of the item (`CatalogueEngineVariantsTest`). `db/3.1.0/update.sql`
rebuilds `item.variant_description` for the items with a `nav_item_no` (store and head office), so the first items run
after the upgrade sees no change. Front unchanged ("name — variant description").

**Clearance price at the till (2026-10-17), decision reversed**: Q1 answered and step 11b's "no price logic changed"
reversed. A point's row with clearance true and a clearance price above 0 sells at the clearance price (HT, the item's
VAT); otherwise the public price, as before. The rule is applied at the head office, where a store's price is already
decided (`HoCatalogueService.pointCopy`, one rule `headoffice/service/StockPointPrice`), not at the store, which has no
point rows: the store sells the price it receives, so scan, cart, ticket and price lookup need no change; returns and
reprints use the sale line's prices. The copy also carries `clearance` and `publicPrice` (store columns
`item.clearance`, `item.public_price`, db/3.1.0) for the till's display: the public price struck through, the price
sold and the "Clearance" badge on the tile and the cart line; the ticket prints the price sold. The head office pages
use the back end's `clearanceApplies` (a clearance price of 0 is no longer shown struck). Details:
`docs/modules/erp-sync.md`, "Clearance price at the till". To do at the upgrade: items already copied to a store are
sent again only on a row change or a change of the store's point; ZEN's test store needs one (its point taken away and
given back) to receive its clearance prices.

To do:
- Development machine: SQL Server's memory (above), before any speed measurement.
- Step 12a: the `$filter` / `$orderby` on `lastModifiedDateTime` against the real `barCodes` page (tested in parallel);
  Q13 (`unitOfMeasureCode`) and Q14 (a key on the date) with ZEN.
- To confirm with ZEN: `clearancePrice` is HT (before VAT), like `publicPrice`; the pages show it with VAT as
  `clearancePrice × (1 + VAT)`.
- When 3.1.0 is closed: write its `APP_RELEASE_NOTES` in `db/3.1.0/update.sql` from `CHANGELOG.md` (only two rows
  today; RFID, tickets X / Z, the journal and the variants are missing).
- (a) `ZenStartupCheck` must accept `erp.connector=zen` on a store whose catalogue is the ERP's (ZEN's own stores).
  Not for the 30/10 go-live. The stock rule already answers "no stock" for such a store (`KeepsOwnStockTest`).
- (b) The stores' stock per variant at the head office, from `ho_store_stock` (the network stock), on the NAV items
  page. Later.

**Step 9a (2026-10-23), the supply invoices read in connectors/zen**: NAV's standard OData V4 page `SalesInvoice` under
`Company('ESP')` (the sample `SalesInvoice.json` saved by Zein), read as Happyness's invoices (`ZenInvoices`, a copy of
`NavPosPagesInvoices`: by year prefix `FVE26` after the head office's highest number, the General Setup "Read ERP
invoices after number" as the starting point), item code `No~Variant_Code` (`ZenMapper.itemCode`, the catalogue's),
ability `SUPPLY_INVOICES`, settings `erp.zen.invoices.*` all optional. Decisions (2026-10-23, with Zein): the page holds
every sales invoice of the company, so only those of the stores' ERP customer numbers are read, **every store that has
one, active or not** (an invoice left out at the read is never read again: the assignment keeps its own rules); the
numbers come from the head office through a new erp-api interface `ErpInvoiceCustomers` (`HoErpInvoiceCustomers` on
`StoreRepository`), not from core (core reads no head office table); a cancelled invoice is read past and left out, a
corrective one read with a warning. Details: `docs/modules/head-office.md`, "Invoices from the ERP, ZEN". Still open:
the live read (step 9b, `headoffice.supply.source=ERP` on ZEN's test head office, with Zein); an invoice to a customer
whose store gets its ERP customer number later is passed over once a later invoice is saved; `VAT_Percent` is not kept
(a head office invoice line has no VAT rate).

**Step 9a-bis (2026-10-25), the invoices on ZEN's franchise API**: ZEN's NAV team moved the invoices to a second custom
API, `api/sat/franchise/v1.0/companies(<id>)/salesInvoices?$expand=salesInvoiceLines` (the sample
`NewsalesInvoices.json` saved by Zein, in the tests `salesInvoices.json`: `FVE2600002`, twelve variants of `43ZBPND01`
and the G/L rounding line at -0.002). The connector reads it instead of `SalesInvoice`: the same company id as the
zsretail API (`erp.zen.company` alone is enough again), the key `erp.zen.invoices.api-path` instead of `odata-path`, the
camelCase fields (`number`, `sellToCustomerNo`, `lineType` with NAV's enum values `Item`, `G_x002F_L_x0020_Account`,
`_x0020_`, ...). Decisions (2026-10-25, with Zein): the totals from the header (`amount`, `amountIncludingVAT`, the VAT
their difference), `TOTAL_TOLERANCE` kept against the header's `amount`; no `$select` on the header, so `cancelled` and
`corrective` are read with no code change once ZEN adds them (until then every invoice is read and sent to its store);
a `currencyCode` that is not blank gives a warning. Unchanged: the selling price never from the invoice, the store's
costs (last direct cost = `unitPrice`, cost = `lineAmount` ÷ quantity), `ErpSupplyInvoiceDTO`, the head office, the copy
down and the store. Questions for ZEN: Q15 (`cancelled` and `corrective` on `salesInvoices`) and Q16 (invoice
discounts). Still open: the live read (step 9b, with the NAV user's permission on the franchise API).
