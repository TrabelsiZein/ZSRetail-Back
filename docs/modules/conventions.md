# Conventions (3.6.0)

An organisation (a ministry, a bank, a company) signs a convention with the retailer; its members show a card (or give
their member number) at the till and get a benefit. The benefit itself is a promotion reserved to the convention (step
2); this module holds the conventions and their cards. The design, the decisions (the reference) and the order of the
steps: `docs/roadmap/conventions.md`. This file says what is built.

Built: the whole back end of step 1, parts B1 (the switch), B2 (the tables), B3 (the conventions' API, rights and
events), B4 (the cards: list, generation, one by hand, block / unblock, the holder), B5 (Excel: the template, the
import, the export for the card printer). To come in step 1: F1 to F4 (the pages). The later steps (the promotion
reserved to a convention, the till, the returns, the statement, the head office's ownership): the roadmap.

All the calls in one place:

| Call | Right | Part |
|---|---|---|
| `GET /conventions?state=&q=&page=&size=` | read | B3 |
| `GET /conventions/{id}` | read | B3 |
| `POST /conventions`, `PUT /conventions/{id}`, `PUT /conventions/{id}/status`, `DELETE /conventions/{id}` | write | B3 |
| `GET /conventions/{id}/cards?status=&q=&exported=&page=&size=` | read | B4 |
| `POST /conventions/{id}/cards/generate`, `POST /conventions/{id}/cards`, `PUT /conventions/{id}/cards/status`, `PUT /conventions/{id}/cards/{cardId}/holder` | write | B4 |
| `GET /conventions/cards/template?lang=fr\|en` | read | B5 |
| `POST /conventions/{id}/cards/import` (multipart `file`) | write | B5 |
| `POST /conventions/{id}/cards/export {status, onlyNeverExported}` | write | B5 |

Five beans, only with `convention.enabled=true`: `ConventionAPI`, `ConventionService`, `ConventionCards`,
`ConventionCardImport`, `ConventionEvents` (`ConventionCardExcel` reads and writes the files: no bean).

## The switch: `convention.enabled` (part B1)

| Key | Values | Absent |
|---|---|---|
| `convention.enabled` | `true` \| `false`, trimmed, any case | `false` |

- A store and a head office alike. `false` in core's `application.properties`; the commented line in
  `configs/machine-model.properties`.
- Blank is `false`. Any other value is one WARN at the start (`Conventions: convention.enabled='…' is neither true nor
  false: the conventions module is off`) and the module off: never a refused start (`NodeOwnership.isConventionSet`,
  as `communication.enabled`). Seen live on 2026-11-28 (a value `true,eventlog.enabled=true` given by mistake).
- `GET /config` field `conventionEnabled` (last); the startup summary says `conventions on`.
- **Off**: no bean of the module (`Installation.CONVENTION`: `ConventionAPI`, `ConventionService`, `ConventionCards`,
  `ConventionCardImport`, `ConventionEvents`),
  so every `/conventions` address answers 404. The tables and their rows stay.

## Who changes them (decision 2)

The conventions follow the promotions. `ApplicationModeService.isConventionManagedHere()`: the module on, and a head
office or a store that owns its promotions. On a store whose promotions are the head office's
(`ownership.promotions=HEAD_OFFICE`) the beans exist, the reads answer, and every change answers **409**
`CONVENTION_OWNED_BY_HEAD_OFFICE`; its ADMIN gets no Conventions right and the page is hidden there (F1) until step 6
brings the copies down and the read-only pages.

## The tables (part B2)

`convention` and `convention_card` (core: a store and a head office), delivered by `db/3.6.0/update.sql` in Hibernate's
exact form (`ConventionTableTest`). The columns, their rules and their indexes: `docs/roadmap/conventions.md`, "B2".
In short: `convention` (code unique, name, organisation, start / end dates, status `ACTIVE` / `SUSPENDED`, recognition
`CARDS` / `NO_CARD`, the three limits, card prefix and digits, the generation counter, origin); `convention_card`
(number unique in the whole installation, the convention, status `ACTIVE` / `BLOCKED`, the optional holder name / phone
/ employee number, `exported_at`). Every text column nvarchar; the amounts `float` as every amount of the application
(ticket totals, promotion amounts: `float(53)`, Java `Double`).

## The API (part B3)

`core.convention.controller.ConventionAPI`, hand-written (not `_BaseController`: its free PUT and its DELETE would skip
the rules and the events). Every change goes through `core.convention.service.ConventionService`.

| Call | Right | Answer |
|---|---|---|
| `GET /conventions?state=ACTIVE\|SUSPENDED\|EXPIRED\|UPCOMING\|all&q=&page=&size=` | `read:admin-conventions` | `{content, totalElements, totalPages, number, size}`, by code; `size` at most 100 (default 20) |
| `GET /conventions/{id}` | `read:admin-conventions` | the convention (the row below) |
| `POST /conventions` | `write:admin-conventions` | the convention created |
| `PUT /conventions/{id}` | `write:admin-conventions` | the whole form (an empty limit is no limit); the convention |
| `PUT /conventions/{id}/status` `{status: SUSPENDED\|ACTIVE}` | `write:admin-conventions` | the convention; the same status again changes nothing |
| `DELETE /conventions/{id}` | `write:admin-conventions` | `{id, code}`; only without a card |

- The form: `code`, `name`, `organisation`, `startDate`, `endDate` (`yyyy-MM-dd`), `recognition`, `maxUsesPerDay`,
  `maxUsesPerMonth`, `maxAmountPerMonth`, `cardPrefix`, `cardDigits`. Anything else (`status`, `origin`,
  `lastCardSeq`...) is ignored: the status has its own call, the origin and the counter are never read from a request.
- `state` filters on the state of today (Zein, 2026-11-28: the front's radio filter): ACTIVE = in force today (the
  status ACTIVE and today within the dates), SUSPENDED, EXPIRED, UPCOMING, or all (empty, `all`); the same rule as
  the row's `state` (`ConventionService.inState`, a Specification, and `Convention.stateOn`). `q` is found in the code,
  the name or the organisation, any case, `%` and `_` as typed.
- A row: the stored values, plus `state` (`ACTIVE`, `SUSPENDED`, `EXPIRED`, `UPCOMING`), `cardsActive`,
  `cardsBlocked` (one GROUP BY over `ix_convention_card_convention` for the whole page), `cardSettingsFixed` (the
  convention has a card: recognition, prefix and digits locked), `createdAt` / `createdBy` / `updatedAt` / `updatedBy`.
- **The state is worked out on the server** (`Convention.stateOn`, the server's date): SUSPENDED as stored, else
  UPCOMING before the start, EXPIRED after the end, ACTIVE within them, both days included. One rule for the list, the
  page and, later, the till (a convention applies only in state ACTIVE); never the browser's clock, never stored.

### The rules

- **Code**: trimmed, upper-cased, A-Z, 0-9 and `-` only (`ConventionNumbers`, the card numbers' characters), at most 30,
  unique whatever its case; never changed after the creation (a PUT giving another code is refused; the same code in
  any case, or none, is fine).
- **Name and organisation**: required (blank is empty), trimmed, at most 150.
- **Dates**: a start date; the end empty or not before the start (one day: start = end).
- **Limits**: each empty (no limit) or above 0.
- **Recognition**: `CARDS` (default) or `NO_CARD`. **Card prefix**: empty (= the code) or A-Z, 0-9, `-`, at most 30.
  **Card digits**: 4 to 9, default 5 (prefix 30 + `-` + 9 = the 40 of a card number).
- **With a card** (active or blocked): recognition, prefix in effect (empty and the code are the same prefix) and digits
  can no longer change; the other fields can.
- **Delete**: only a convention without any card (decision 9); otherwise suspend it.
- A save that changes nothing writes nothing (no event, `updated_by` kept).

### Refusals

`{error, code, params}` (`ConventionException`, a `CodedRefusal`: the front shows `serverErrors.<code>`).

| Status | Code | Params |
|---|---|---|
| 400 | `CONVENTION_CODE_REQUIRED` | |
| 400 | `CONVENTION_CODE_INVALID` | `code` |
| 400 | `CONVENTION_CODE_TAKEN` | `code` (also two creations at once: the unique key) |
| 400 | `CONVENTION_CODE_FIXED` | `code`, `asked` |
| 400 | `CONVENTION_NAME_REQUIRED`, `CONVENTION_ORGANISATION_REQUIRED`, `CONVENTION_START_REQUIRED` | |
| 400 | `CONVENTION_TOO_LONG` | `field` (`code`, `name`, `organisation`, `cardPrefix`), `max` |
| 400 | `CONVENTION_END_BEFORE_START` | `start`, `end` |
| 400 | `CONVENTION_LIMIT_INVALID` | `field` (`maxUsesPerDay`, `maxUsesPerMonth`, `maxAmountPerMonth`) |
| 400 | `CONVENTION_RECOGNITION_UNKNOWN`, `CONVENTION_STATUS_UNKNOWN` (`PUT …/status`), `CONVENTION_STATE_UNKNOWN` (the list) | `value` |
| 400 | `CONVENTION_PREFIX_INVALID` | `prefix` |
| 400 | `CONVENTION_CARD_DIGITS` | `min`, `max` |
| 400 | `CONVENTION_CARD_SETTINGS_FIXED` | `field` (`recognition`, `cardPrefix`, `cardDigits`), `code` |
| 400 | `CONVENTION_HAS_CARDS` | `code`, `cards` |
| 403 | `CONVENTION_FORBIDDEN` | `permission` |
| 404 | `CONVENTION_NOT_FOUND` | `id` |
| 409 | `CONVENTION_OWNED_BY_HEAD_OFFICE` | |

A malformed body (a date that is not `yyyy-MM-dd`, a limit that is not a number) is Spring's own 400, without a code.

## The rights (decision 11)

`read:admin-conventions` (the reads) and `write:admin-conventions` (every change), the same keys on a store and a head
office. `ZZDataInitializer.CONVENTION_ADMIN_PERMISSIONS`: in ADMIN's set of a new database and given to ADMIN at every
start where `isConventionManagedHere()` (a database of before, the module turned on later); never another role (the
Roles page, F1). The labels of the Roles page and of `AdminLabels` come with F1: `AdminLabels.java` is written by the
front's `tools/admin-labels-check.mjs --write` from the Roles page, so a key added by hand before the page has it would
fail the front's check; until then an event names the key itself.

## The cards (part B4)

`core.convention.service.ConventionCards`, on the same API, the same rights (the list: read; the rest: write), the same
404 and 409. A card is never deleted, only blocked; a blocked card can be unblocked.

| Call | Right | Answer |
|---|---|---|
| `GET /conventions/{id}/cards?status=ACTIVE\|BLOCKED\|all&q=&exported=&page=&size=` | read | a page by number; `q` in the number, the holder's name or employee number (never the phone); `exported` true / false (`exported_at`, B5) |
| `POST /conventions/{id}/cards/generate {count}` | write | `{conventionId, generated, skipped, skippedNumbers (the first 50), first, last, lastCardSeq}` |
| `POST /conventions/{id}/cards {number, holderName, holderPhone, holderEmployeeNo}` | write | the card |
| `PUT /conventions/{id}/cards/status {ids, status: BLOCKED\|ACTIVE}` | write | `{status, changed, unchanged}` |
| `PUT /conventions/{id}/cards/{cardId}/holder {holderName, holderPhone, holderEmployeeNo}` | write | the card |

A card: `id, cardNumber, status, holderName, holderPhone, holderEmployeeNo, exportedAt, createdAt, createdBy,
updatedAt, updatedBy`. The calls on several cards name their convention in the address (the roadmap's
`/conventions/cards/status` became `/conventions/{id}/cards/status`): a card of another convention is refused, 404
with its id, and nothing changes.

### Which conventions take cards

- Recognition `CARDS` only (decision 4): with `NO_CARD`, a generation and a card by hand answer `CONVENTION_NO_CARDS`.
- Not an expired one (its end before today): `CONVENTION_EXPIRED`. A suspended or upcoming one takes cards: the cards
  are prepared before the start, or during a suspension. Blocking, unblocking and the holder stay possible on any.

### The generation

- Number = the prefix in effect (`Convention.prefixInEffect`: the card prefix, else the code) + `-` + the counter on
  the convention's digits: `MIN-SANTE-00001`, `CLT-00001`. 1 to 5,000 per run (`CONVENTION_GENERATE_COUNT`).
- The counter `last_card_seq` is read under the convention's row lock (`ConventionRepository.lockById`,
  `PESSIMISTIC_WRITE`: SQL Server `updlock, holdlock, rowlock`) and written in the same transaction: two generations of
  one convention follow each other, never the same number.
- A number already used is **skipped** and counted (the counter goes past it): a card of any convention (a number
  typed by hand, another convention with the same prefix), an item code, an item barcode (`item.barcode` and
  `item_barcode.barcode`), a loyalty card number (`loyalty_member`; on a store whose loyalty is the head office's the
  copies are written there too). One query per table with `PREFIX-%`, whatever the count; those columns are varchar,
  read by a scan (cheap at a store's size, once per generation, never at the till).
- **All or nothing**: not enough numbers left on the digits (`CONVENTION_NUMBERS_EXHAUSTED`, with `left`) inserts
  nothing and keeps the counter. A number taken between the read and the insert (a rare race with a card by hand or
  another convention's generation) meets `uk_convention_card_number`: everything rolls back, 409
  `CONVENTION_CARDS_BUSY`, try again.
- Inserted in JDBC batches of 1,000 (`ConventionCardBatchInsert`, in the generation's transaction): 5,000 cards in
  0.5 s on store B (the HTTP call, measured 2026-11-28).

### One card by hand

The number typed: trimmed, upper-cased, A-Z, 0-9 and `-`, at most 40. Refused: empty, other characters, too long, a
card of any convention (`CONVENTION_CARD_NUMBER_TAKEN`, with its convention's code), a number the till already reads
(`CONVENTION_CARD_NUMBER_RESERVED`, `kind` `ITEM_CODE`, `ITEM_BARCODE` or `LOYALTY_CARD`; any case). The counter does
not move: a later generation skips that number. The holder's three fields may come with it.

### Block, unblock, the holder

- Block / unblock: 1 to 500 cards of the convention in one call (`CONVENTION_CARD_IDS`); the cards already in the asked
  status are left alone, without an event (`unchanged`); one event per card changed.
- The holder: name (150), phone (30), employee number (50), all optional, trimmed, blank is empty, never checked
  (decision 5). A save that changes nothing writes nothing.

## Refusals of the cards

| Status | Code | Params |
|---|---|---|
| 400 | `CONVENTION_NO_CARDS` | `code` |
| 400 | `CONVENTION_EXPIRED` | `code`, `end` |
| 400 | `CONVENTION_GENERATE_COUNT` | `min`, `max` |
| 400 | `CONVENTION_NUMBERS_EXHAUSTED` | `code`, `left`, `digits` |
| 400 | `CONVENTION_CARD_NUMBER_REQUIRED` | |
| 400 | `CONVENTION_CARD_NUMBER_INVALID` | `number` |
| 400 | `CONVENTION_CARD_NUMBER_TAKEN` | `number`, `convention` |
| 400 | `CONVENTION_CARD_NUMBER_RESERVED` | `number`, `kind` |
| 400 | `CONVENTION_TOO_LONG` | `field` (`cardNumber`, `holderName`, `holderPhone`, `holderEmployeeNo`), `max` |
| 400 | `CONVENTION_CARD_IDS` | `min`, `max` |
| 400 | `CONVENTION_CARD_STATUS_UNKNOWN` | `value` |
| 404 | `CONVENTION_CARD_NOT_FOUND` | `ids` |
| 409 | `CONVENTION_CARDS_BUSY` | `code` |

## Import (part B5, decision 7)

`POST /conventions/{id}/cards/import`, multipart `file` (write right). `ConventionCardImport` (the run) and
`ConventionCardExcel.read` (the file). Answer `{conventionId, lines, accepted, refused: [{row, number, reason, ...}]}`:
the good lines are kept, the refused ones answered in the file's order with their Excel row, the number as read and a
reason code. Only a convention with recognition `CARDS`, not expired (as the generation); 404, 409 as every change.
The convention's row is locked for the run (one import or generation at a time); the cards go in by JDBC batches; the
counter `last_card_seq` does not move (a later generation skips the numbers imported).

**The file.** One `.xlsx` for one convention, its first sheet. The first row with content is the header; its columns
are found by their name, in French or English, any case, accents or not, in any order, other columns ignored (an
export reads back):

| Column | Names recognised (after "lower case, no accents") | |
|---|---|---|
| card number | numéro de carte, numéro carte, n° de carte, no de carte, numéro, carte, card number, card no, card, number | required |
| holder name | titulaire, nom du titulaire, nom titulaire, nom, nom et prénom, holder, holder name, name, full name | optional |
| phone | téléphone, tél, mobile, gsm, phone, phone number | optional |
| employee number | matricule, numéro de matricule, employee number, employee no, employee id, staff number | optional |

`GET /conventions/cards/template?lang=fr|en` (read right) gives the empty file: the header "Numéro de carte,
Titulaire, Téléphone, Matricule" (English: "Card number, Holder name, Phone, Employee number"), the four columns
formatted as text, so a number typed keeps its leading zeros.

**The whole file refused** (400, `{error, code, params}`, no card, no event):

| Code | When |
|---|---|
| `CONVENTION_IMPORT_EMPTY` | no byte, no sheet, nothing, or the header only |
| `CONVENTION_IMPORT_TOO_BIG` (`maxKb`) | over 2 MB (5,000 lines of four columns are about 110 KB) |
| `CONVENTION_IMPORT_NOT_XLSX` | not an .xlsx: a .csv, an old .xls, another file renamed |
| `CONVENTION_IMPORT_NO_HEADER` (`expected`) | the first row has no card number column (a file without its header) |
| `CONVENTION_IMPORT_TOO_MANY_ROWS` (`max`) | over 5,000 lines (as a generation): split the file |

**The cells.** A text is trimmed (a non-breaking space as a space); a number cell is read as its plain digits (`12345`,
and `1.2345E4` typed as a number is `12345`; an EAN-13 stays whole); a formula gives its value; empty rows are
skipped. Excel keeps 15 significant digits: a number cell of 16 digits or more was already changed, its line is
refused (`NUMBER_CHANGED_BY_EXCEL`). A text cell holding `1.2345E4` is a text, refused for its characters. Arabic and
accented text is kept as typed.

**The reasons of a refused line** (`reason`, with its values):

| Reason | When |
|---|---|
| `NUMBER_EMPTY` | no number (a holder alone is not a card) |
| `NUMBER_CHANGED_BY_EXCEL` | a number cell over 15 digits |
| `NUMBER_TOO_LONG` (`max` 40) | over 40 characters |
| `NUMBER_INVALID` | other characters than A-Z, 0-9 and `-` (after trimming and upper-casing) |
| `DUPLICATE_IN_FILE` (`firstRow`) | the same number on an earlier line of the file (the first is kept) |
| `HOLDER_NAME_TOO_LONG`, `HOLDER_PHONE_TOO_LONG`, `HOLDER_EMPLOYEE_NO_TOO_LONG` (`max`) | over 150, 30, 50 characters |
| `CARD_TAKEN` (`convention`) | already a card, of this convention or another |
| `ITEM_CODE`, `ITEM_BARCODE`, `LOYALTY_CARD` | a number the till already reads: an item code, an item barcode (both columns), a loyalty card number (any case) |

A line is never refused for a missing holder name (decision 5). The other numbers are read once per run, each column
whole, and looked up in memory: an `upper(x) in (1,000 values)` on those varchar columns compared every row with every
value (5,000 lines took 17.5 s on store B; 2.0 s since).

## Export (part B5, decision 8)

`POST /conventions/{id}/cards/export {status, onlyNeverExported}`, the file for the card printer. **The write right**:
the export stamps the cards and gives the file that becomes physical cards; a reader may see the cards on the screen,
not have them printed. A POST, not a GET: it changes the cards.

- `status`: `ACTIVE` (the default), `BLOCKED` or `all`; `onlyNeverExported`: only the cards without `exported_at`.
- One `.xlsx`, sheet "Cartes", by card number: **Numéro de carte, Code convention, Convention, Organisation,
  Titulaire, Matricule** (decision 8; never the phone), every cell text. The file is named
  `cartes-<code>-<yyyyMMdd-HHmm>.xlsx` (`Content-Disposition`), its card count in `X-Cards-Count` (exposed to the
  browser). No card: the header only, `X-Cards-Count: 0`.
- `exported_at` is stamped on the cards written where it was empty: it is the **first** export (a card exported again
  keeps its date), so "only the cards never exported" twice gives the new cards only, then nothing.
- Any convention (an expired one too: its cards may be printed again); 404, 409 as every change.

## The events (decision 3: a store's event log only)

`core.convention.service.ConventionEvents`, all Notable, written after the commit (none for a refusal). On a head
office the event log is off: nothing is written. A convention's events have the subject `CONVENTION` (the id; the code
as the label), a card's `CONVENTION_CARD` (the id; the number as the label).

| Code | Subject | When | Values |
|---|---|---|---|
| `CONVENTION_CREATED` | convention | created | the details list its values: "Convention MIN « … » créée : Nom … ; Organisation … ; Du … ; …" |
| `CONVENTION_UPDATED` | convention | a value changed | each field before → after: "Nom : A → B ; Utilisations max par jour : 1 → 2" |
| `CONVENTION_SUSPENDED` / `CONVENTION_REACTIVATED` | convention | the status changed | old / new value `ACTIVE` / `SUSPENDED` |
| `CONVENTION_DELETED` | convention | deleted (no card) | its values in the details |
| `CONVENTION_CARDS_GENERATED` | convention | one per run | the count in the amount column (`AMOUNT_IS_COUNT`): "20 cartes générées pour la convention MIN-SANTE : MIN-SANTE-00001 à MIN-SANTE-00020 (1 numéro sauté, déjà pris)." |
| `CONVENTION_CARD_ADDED` | card | one by hand | "Carte X ajoutée à la convention Y, titulaire …, matricule …" |
| `CONVENTION_CARD_BLOCKED` / `CONVENTION_CARD_UNBLOCKED` | card | one per card changed | old / new value `ACTIVE` / `BLOCKED` |
| `CONVENTION_CARD_HOLDER_CHANGED` | card | a holder field changed | "Titulaire : A → B ; Matricule : … ; Téléphone : modifié" |
| `CONVENTION_CARDS_IMPORTED` | convention | one per import run (a run that keeps nothing too; not a file refused whole) | the cards kept in the amount column: "12 cartes importées pour la convention MIN-SANTE depuis « min-sante-30.xlsx » (17 lignes refusées)." |
| `CONVENTION_CARDS_EXPORTED` | convention | one per export (an empty one too) | the cards written in the amount column: "5000 cartes exportées pour la convention CLINIQUE-TEST (cartes actives, jamais exportées)." |

The French names are the screen's (`ConventionEvents.describe`); the Journal's names of the codes come with F4. **The
holder's phone is never in an event** (decision 15): its change is written "Téléphone : modifié", without the value.
`CONVENTION_CARD_ADDED` is in the roadmap (a lost card replaced is worth its line) though not in the B4 brief.

## Tests

- `ConventionServiceTest` (core): the rules above over the real repositories and their JPQL (`ConventionTestBed`:
  Hibernate over H2 with core's entities, the event log in memory): the code and its uniqueness, the code fixed, the
  texts, the dates, the limits, recognition / prefix / digits and their lock with a card, suspend / reactivate, delete
  only without a card, 409, the list (counts, the state filter and its boundary days, search, pages), the events'
  sentences.
- `ConventionCardsTest` (core): the generation (prefix, digits, the counter, each kind of number skipped, 1 to 5,000,
  5,000 in one run, all or nothing when the digits run out, two generations at the same moment in two sessions: no
  number twice, a number taken meanwhile: 409), which conventions take cards, a card by hand and each refusal, block /
  unblock (the unchanged left alone, a card of another convention refusing the call), the holder (the phone never in
  an event), 409, the list and its filters.
- `ConventionCardExcelTest` (core, no database): the header in French and English (variants, any order, extra columns,
  an export read back), each whole-file refusal (empty, a .csv, an old .xls, no header, the header only, over 2 MB,
  over 5,000 lines; 5,000 accepted), the cells (12345, 1.2345E4, an EAN-13, 16 digits marked, a formula, a text
  1.2345E4, Arabic, accents, a phone typed as a number), the export's columns and text cells, the template.
- `ConventionCardImportTest` (core, H2): every refusal reason in one file with the good lines kept and their holders
  (Arabic, accents, none), the event; the same file again (all `CARD_TAKEN`) and a generation after it (skips); which
  conventions, 404, 409, the wrong files without an event; 5,000 lines; the export (columns, the active cards by
  default, never the phone, `exported_at` stamped and the first export kept, "never exported" twice: the second
  empty, `all`, `BLOCKED`, the events).
- `ConventionAPITest` (core): standalone MockMvc over the real API and services: 403 per right, each refusal's status,
  code and params, 404, 409, the fields of a form that are ignored, the JSON (dates as text), the card endpoints, the
  template, the multipart import, the export's file and headers (a reader gets 403); the five beans only with
  `convention.enabled=true` (false, blank, a wrong value, no line: none).
- `ZZDataInitializerRolesTest.conventionRightsGivenToAdmin`; `ConventionKeyTest`, `AppConfigAPITest` (B1);
  `ConventionTableTest` (B2).
- `InstallationBeansProofTest` (network-tests): "decided 147"; the shapes "store, conventions on", "head office,
  conventions on", "store, promotions from the head office, conventions on" (the beans are there: the reads stay).

## Real checks (store B, `pos_store_b`, port 555, `-Set 'convention.enabled=true','eventlog.enabled=true'`)

- B3, 2026-11-28: each call once, the refusals, the rows and the events; three conventions left: `MIN-SANTE` (active,
  limits), `BANQUE-BT` (`NO_CARD`, starts 2026-12-01), `POSTE` (expired, prefix `PT`, 6 digits).
- B4, 2026-11-28: the list by state (ACTIVE `MIN-SANTE`, UPCOMING `BANQUE-BT`, EXPIRED `POSTE`); 20 cards on
  `MIN-SANTE`, `MIN-SANTE-00022` by hand, 5 more (00022 skipped: 00021 to 00026); `CLINIQUE-TEST` (prefix `CLT`) with
  5,000 cards in 484 ms (all distinct, one batch); an item barcode typed by hand refused (`ITEM_BARCODE`), a card
  number twice refused, `POSTE` refused (expired), `BANQUE-BT` refused (no cards); three blocked in one call, again
  (changed 0, unchanged 2), one unblocked; a holder changed twice (the phone never in the log); a card asked through
  another convention: 404. Left in the database: `MIN-SANTE` 26 cards (00001 and 00003 blocked, 00004 with a holder),
  `CLINIQUE-TEST` 5,000 cards.
- B5, 2026-11-28: the template in French and English (the four columns, text); 29 lines on `MIN-SANTE`, 12 kept (Arabic
  and accented holders, `77001` and `7.7002E4` typed as numbers read `77001` / `77002`) and 17 refused, each with its
  reason (empty ×2, invalid ×3 among them `MIN-SANTÉ-15`, too long, 16 digits, duplicates ×3, holder name and phone too
  long, a card of `MIN-SANTE` and one of `CLINIQUE-TEST`, an item code, a barcode, a loyalty card typed in lower case);
  a .csv, a file without header and a header-only file refused whole; 5,000 lines on `IMPORT-TEST` in 17.5 s, then,
  the lookups fixed, 5,000 on `IMPORT-TEST-2` in 2.0 s and the same file again (5,000 `CARD_TAKEN`) in 0.34 s;
  `CLINIQUE-TEST` exported twice with "never exported": 5,000 cards (117 KB, its six columns, one stamp), then 0 (the
  header only); `MIN-SANTE`'s active cards: 36 lines, the Arabic name intact in the file. Left in the database:
  `MIN-SANTE` 38 cards (36 exported), `CLINIQUE-TEST` 5,000 (exported), `IMPORT-TEST` and `IMPORT-TEST-2` 5,000 each.
