# Conventions — 3.6.0 design and steps

Agreed with Zein on 2026-11-27 (`release/3.6.0`). An organisation (a ministry, a company) signs a convention with the
retailer and gets cards; a member who shows a card gets a benefit at the till. This file is the design and the order of
the steps; when a part is built, what it did goes to the module doc (`docs/modules/conventions.md`) and to both
CHANGELOGs. **The decisions below are the reference**: where an older plan says otherwise, they win.

## The rules that frame everything

- **The benefit is not in this module.** It is an existing promotion with one more condition, "reserved to convention
  X" (step 2). No second pricing engine, no copy of the promotion code.
- **A convention line on a ticket keeps `discount_source = PROMOTION` and its `promotion_id`**, as today. No new discount
  source. The convention and the card are kept on the ticket (later step: the sales header, and a nullable convention
  reference on the sales line).
- **Nothing about conventions is sent to any ERP.**
- **Zero impact when the module is off**, and zero change for EMTOP, Happyness, ParaFendri and ZEN when no card is on a
  ticket.
- **Nothing is deleted once used**: a convention is suspended, a card is blocked. No usage table: the usage is read
  from the tickets.

## Decisions (2026-11-27)

1. **Switch** `convention.enabled`, default `false`, on a store and on a head office. A blank value or a missing line is
   `false`. A wrong value: one WARN and the module stays off, never a startup stop (as `communication.enabled`). Off =
   no API bean = 404.
2. **Owner: the conventions follow the promotions.** A store that owns its promotions owns its conventions. A store
   whose promotions are owned by the head office (`ownership.promotions=HEAD_OFFICE`): the module is hidden there until
   step 6 (no menu; the writes answer 409). The read-only pages come with step 6.
3. **The event log is store-only**: the head office's changes are not in it in step 1. Accepted.
4. **Recognition, a setting per convention**: `CARDS` (the default for a new convention) or `NO_CARD`.
   - `CARDS`: cards generated, imported, or one card added by hand (a lost card replaced).
   - `NO_CARD`: the cashier picks the convention and types the member's number, kept on the ticket; the limits count
     per (convention, member number).
5. **No holder identity rule.** No `holder_identity` column, no `HolderIdentity` enum, no NOT_ASKED / OPTIONAL /
   REQUIRED, no refusal of a card without a name; the import never refuses a line for a missing name. The card alone
   gives the benefit. The holder's name, phone and employee number stay on the card as optional information, never
   checked anywhere.
6. **Card numbers**: A-Z, 0-9 and `-`, at most 40 characters, trimmed and upper-cased in code (SQL Server's collation
   ignores case, the tests' H2 does not). Unique in the whole installation, not per convention.
   - Generated: prefix (default the convention code) + `-` + 5 digits (`MIN-00001`). Prefix and padding are fixed once
     the convention has a card. At most 5,000 per generation. No check digit.
   - Generation, import and the card added by hand refuse a number equal to an existing item code, item barcode or
     loyalty card number (the till's scan must never hesitate).
7. **Import (Excel)**: the good lines are kept; the refused lines are answered with their reason. Columns: number,
   then optional holder name, phone, employee number.
8. **Export (Excel, for the card printer)**: writes one event. `exported_at` on the card, and the option "only the
   cards never exported". Columns: card number, convention code, convention name, organisation, holder name, employee
   number.
9. **Delete**: only a convention with no card. A card is never deleted in step 1, only blocked.
10. **A convention has a name** (its own label) **and an organisation** (text, required).
11. **Two rights**: `read:admin-conventions` and `write:admin-conventions`, the same keys on both applications (as
    `read:admin-communication`; no `admin-headoffice-` twin key).
12. **Limits** (for the later steps, written now), counted per card (per member number with `NO_CARD`):
    - max uses per day, max uses per month, max amount per month; empty = no limit.
    - "Max amount per month" = the total paid of the validated tickets that carry the card; a return gives its amount
      back; parked tickets do not count.
    - When the head office owns the conventions, the limits hold for the network: the store asks the head office when
      it answers, otherwise it uses its own count. A sale is never blocked because the head office is unreachable; the
      overruns go to a report.
13. **"Buy N, get M free" is not offered with conventions**: the form does not offer it and the back end refuses it.
    Built only if a customer asks.
14. **The convention line keeps the PROMOTION discount source**; the convention and the card are kept on the ticket.
    Nothing new goes to any ERP.
15. **The holder's phone never appears in event values.**
16. **Kept for their steps**:
    - a promotion reserved to a convention is never sent to a store older than 3.6.0 (it ignores the unknown field and
      would give the benefit to everyone) and never applies where the module is off (off = not eligible);
    - `BLOCK_RETURN_FOR_PROMOTION` against convention lines (step 4);
    - the sales copy and the head office's ticket tables carry the convention code and the card number, never ids
      (step 6).

## Step 1 — the conventions and their cards (admin only)

Back end first, then the front. Each part is testable alone and has its commit.

### B1 — the switch (done 2026-11-27)

- `NodeOwnership.CONVENTION_KEY`, `isConventionSet` (the `communication.enabled` rule: blank or missing false, a wrong
  value one WARN and off), read in `resolve`.
- `Installation.CONVENTION` (store and head office), `ApplicationModeService.isConventionEnabled`, the startup summary
  "conventions on".
- `GET /config` `conventionEnabled` (`AppConfigDTO`, last field).
- `convention.enabled=false` in core's `application.properties` (both applications read it), the commented line in
  `configs/machine-model.properties`.
- `db/3.6.0/update.sql`: the version step only (refuses a database not at 3.5.0 or 3.6.0; a second run is harmless).
- Tests: `ConventionKeyTest`, `AppConfigAPITest`, the configuration keys record ("keys 124"). No bean depends on the
  switch yet: the installation beans record is unchanged.

### B2 — the tables (core, both applications)

Delivered by `db/3.6.0/update.sql`, run by hand before the first start of the code (the tables in Hibernate's form, the
constraint names of the entities, guarded `IF OBJECT_ID`, the same script on a store and a head office). `ddl-auto=update`
stays: run the script first, or Hibernate creates the tables in its own form. Every text column is nvarchar (JDBC sends
nvarchar: a varchar index would be scanned; the till's card lookup must seek).

`convention` (`Convention`, `_BaseEntity`; `active` written true and never read, `status` is the truth):

| Column | What |
|---|---|
| `code` nvarchar(30), not null | A-Z, 0-9, `-`; fixed once created (the copies down go by code, step 6). Unique `uk_convention_code` |
| `name` nvarchar(150), not null | The convention's own label |
| `organisation` nvarchar(150), not null | The organisation that signed |
| `start_date` date not null, `end_date` date null | End on or after the start; empty = no end |
| `status` nvarchar(20) not null, default `ACTIVE` | `ACTIVE` / `SUSPENDED`. "Expired" and "not started" are worked out from the dates, never stored |
| `recognition` nvarchar(20) not null, default `CARDS` | `CARDS` / `NO_CARD` |
| `max_uses_per_day`, `max_uses_per_month` int null; `max_amount_per_month` float null | Empty = no limit, otherwise above 0 (float as the sales amounts) |
| `card_prefix` nvarchar(30) null | Empty = the code; fixed once a card exists |
| `last_card_seq` bigint not null, default 0 | The generation counter, taken under a row lock in the generation's transaction; a number already used anywhere is skipped. Never read from the highest card number (imported numbers and two admins at once would break it) |
| `origin` nvarchar(20) not null, default `LOCAL` | `RecordOrigin` (HEAD_OFFICE: a copy received, step 6); never read from a request |

`convention_card` (`ConventionCard`):

| Column | What |
|---|---|
| `card_number` nvarchar(40), not null | Unique `uk_convention_card_number` (the whole installation; also the till's lookup later). Fixed once created |
| `convention_id` bigint not null | `fk_convention_card_convention` |
| `status` nvarchar(20) not null, default `ACTIVE` | `ACTIVE` / `BLOCKED` |
| `holder_name` nvarchar(150), `holder_phone` nvarchar(30), `holder_employee_no` nvarchar(50), null | Optional information, never checked |
| `exported_at` datetime2 null | The first export; "only the cards never exported" |

Index `ix_convention_card_convention` (`convention_id`, `status`): the list's active / blocked counts in one GROUP BY,
the Cards tab's paging and filter. No index on the holder columns (the search runs inside one convention).

What step 6 needs is already there: the code and the card number unique and fixed (the cards go down as their own
records, the record code their number, at most 100 characters as `ho_down_change.record_code`; a convention of 5,000
cards is never one record), `origin` on the convention only (a card follows its convention), `updated_at`, no delete
of anything used.

Tests: `ConventionTableTest` (H2: the two unique keys, one number refused in two conventions, the defaults).

### B3 — the conventions: API, service, rights, events

- `ConventionAPI` (`core.convention.controller`, `@ConditionalOnInstallation(Installation.CONVENTION)`: off, no bean,
  404). Hand-written, not `_BaseController` (its `DELETE /{id}` and its PUT that writes any field skip the rules, the
  events and step 6's change hook): every write goes through `ConventionService`.
- `GET /conventions?status=ACTIVE|SUSPENDED|all&q=&page=&size=` (each row with its active / blocked card counts),
  `GET /conventions/{id}`, `POST /conventions`, `PUT /conventions/{id}`, `PUT /conventions/{id}/status`,
  `DELETE /conventions/{id}` (only without a card).
- Every read needs `read:admin-conventions`, every change `write:admin-conventions` (403 otherwise). Refusals: 400
  `{error, code, params}` (`ConventionException`, the front's `serverErrors.<code>`), 404 for a row not found, 409 on a
  store whose promotions are the head office's.
- `ZZDataInitializer.CONVENTION_ADMIN_PERMISSIONS`, given to ADMIN where the module is on and the page exists (a store
  that owns its promotions, a head office), new database and every start; `AdminLabels` names the two rights.
- Events (subject `CONVENTION`, Notable): `CONVENTION_CREATED`, `CONVENTION_UPDATED` (before → after),
  `CONVENTION_SUSPENDED`, `CONVENTION_REACTIVATED`, `CONVENTION_DELETED`.
- Tests: `ConventionServiceTest` (the code rule and its uniqueness, the code fixed, the dates, the limits, suspend /
  reactivate, delete only without a card), `ConventionAPITest` (403 per right, 400 codes, 404, 409), the events, the
  roles (`ZZDataInitializerRolesTest`), the installation beans record (a "convention on" environment).

### B4 — the cards

- `GET /conventions/{id}/cards?status=&q=&exported=&page=&size=` (q: number, holder name, employee number),
  `POST /conventions/{id}/cards/generate {count}`, `POST /conventions/{id}/cards {number, holder...}` (one by hand),
  `PUT /conventions/cards/status {ids, status}`, `PUT /conventions/cards/{id}/holder`. Only with `CARDS`.
- Events (subject `CONVENTION_CARD`, Notable): `CONVENTION_CARDS_GENERATED` (one per run, the amount column a count:
  `AMOUNT_IS_COUNT`), `CONVENTION_CARD_ADDED`, `CONVENTION_CARD_BLOCKED` / `CONVENTION_CARD_UNBLOCKED` (one per card: a
  card's history), `CONVENTION_CARD_HOLDER_CHANGED` (name and employee number before → after; never the phone).
- Tests: `ConventionCardsTest` (the format, the prefix and padding fixed, the counter, a number taken skipped, an item
  code / barcode / loyalty card number refused, 5,000 at most, two generations at once, block / unblock only what
  changes, the holder free).

### B5 — Excel import and export

- `POST /conventions/{id}/cards/import` (multipart, .xlsx): `{accepted, refused: [{row, number, reason}]}`; the good
  lines kept. Refusals: empty, bad characters, too long, twice in the file, existing in any convention, equal to an item
  code / barcode / loyalty card number.
- `GET /conventions/{id}/cards/excel?onlyNeverExported=`: the columns of decision 8; sets `exported_at` where empty.
- Events: `CONVENTION_CARDS_IMPORTED` (accepted count; refused count in the details), `CONVENTION_CARDS_EXPORTED`.
- POI is in core (`DataImportService`). Tests: `ConventionCardImportTest` (an .xlsx built in the test),
  `ConventionCardExcelTest` (the export read back). The module doc complete.

### F1 — the switch, the menus, the list

- `store/app-config`: `conventionEnabled` (state, mutation, commit, the catch fallback), a getter that is true on a
  store and on a head office; hidden on a store whose promotions are the head office's (`ownerOf('PROMOTIONS')`).
- `navigation/store-mode-routes.js` (`CONVENTION_ROUTES`, `CONVENTION_PERMISSIONS`, `hiddenStoreRoutes`), the guard in
  `router/index.js`, the twins in `router/headoffice-routes.js` (`twinOf`, a `beforeEnter` as `byCommunication`).
- Menu "Conventions" after "Promotions" (`navigation/vertical/index.js`, `navigation/headoffice/index.js` and its
  `linkShown`), `search-and-bookmark-data.js`; the Roles pages (`RoleManagement.vue`, `HeadOfficeRoles.vue`).
- `views/admin/conventions/ConventionsList.vue` on `AdminListPage` (the gradient banner): code, name, organisation,
  dates, status (with "Expirée" / "À venir" from the dates), cards active / blocked; filter Actives / Suspendues /
  Toutes (radio buttons) and a search.
- fr / en / ar; `store-menu-check`, `headoffice-menu-check`, `admin-labels-check`.

### F2 — the convention page and "Informations"

- `ConventionPage.vue`, a full page (`/admin/conventions/:id` and `/new`), never a popup. The `AdminPageHeader` banner
  carries the back button and Suspendre / Réactiver / Supprimer; no back link between the banner and the first card.
- One card with `b-tabs` (as `CommunicationPage`). The tabs come from one list (`TABS`: informations, cartes hidden
  with `NO_CARD`, then promotions (step 2) and relevé (later), listed but not rendered yet); the tab is kept in the
  address (`?tab=cards`), so a later step adds one component and one entry.
- `ConventionInfoTab.vue`: sections in two columns (identity, period and status, recognition, limits).

### F3 — "Cartes"

- `ConventionCardsTab.vue`: the table paged by the server, filters (status, never exported) and the search; several
  cards selected → Bloquer / Débloquer; the holder in a small modal (three fields); "Ajouter une carte";
  `ConventionGenerateModal.vue` (the count, the first and last numbers shown).

### F4 — import, export, the Journal

- `ConventionImportModal.vue` (the file, then the accepted count and the refused lines with their reasons; a model
  file), the export (`libs/download.js`, the option "only the cards never exported").
- The Journal's names of the new codes (`admin.eventLog.codes`), `libs/conventions.js` and `tools/conventions-check.mjs`,
  CHANGELOG.

## The later steps (to be detailed when they start)

- Step 2: the promotion's condition "reserved to convention X" (decisions 13, 16).
- The till: the convention and the card (or the member number) on the ticket, the limits (decision 12).
- Step 4: returns (`BLOCK_RETURN_FOR_PROMOTION`, the amount given back).
- The "Relevé" tab: the usage read from the tickets.
- Step 6: the head office owns the conventions where it owns the promotions (copies down, the stores' read-only pages,
  the network's limits, decision 16's sales copy).
