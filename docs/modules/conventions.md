# Conventions (3.6.0)

An organisation (a ministry, a bank, a company) signs a convention with the retailer; its members show a card (or give
their member number) at the till and get a benefit. The benefit itself is a promotion reserved to the convention (step
2); this module holds the conventions and their cards. The design, the decisions (the reference) and the order of the
steps: `docs/roadmap/conventions.md`. This file says what is built.

Built: step 1, parts B1 (the switch), B2 (the tables), B3 (the conventions' API, rights and events). To come in step 1:
B4 (the cards), B5 (Excel import and export), F1 to F4 (the pages).

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
- **Off**: no bean of the module (`Installation.CONVENTION`: `ConventionAPI`, `ConventionService`, `ConventionEvents`),
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
| `GET /conventions?status=ACTIVE\|SUSPENDED\|all&q=&page=&size=` | `read:admin-conventions` | `{content, totalElements, totalPages, number, size}`, by code; `size` at most 100 (default 20) |
| `GET /conventions/{id}` | `read:admin-conventions` | the convention (the row below) |
| `POST /conventions` | `write:admin-conventions` | the convention created |
| `PUT /conventions/{id}` | `write:admin-conventions` | the whole form (an empty limit is no limit); the convention |
| `PUT /conventions/{id}/status` `{status: SUSPENDED\|ACTIVE}` | `write:admin-conventions` | the convention; the same status again changes nothing |
| `DELETE /conventions/{id}` | `write:admin-conventions` | `{id, code}`; only without a card |

- The form: `code`, `name`, `organisation`, `startDate`, `endDate` (`yyyy-MM-dd`), `recognition`, `maxUsesPerDay`,
  `maxUsesPerMonth`, `maxAmountPerMonth`, `cardPrefix`, `cardDigits`. Anything else (`status`, `origin`,
  `lastCardSeq`...) is ignored: the status has its own call, the origin and the counter are never read from a request.
- `status` filters the stored status; `q` is found in the code, the name or the organisation, any case, `%` and `_` as
  typed.
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
| 400 | `CONVENTION_RECOGNITION_UNKNOWN`, `CONVENTION_STATUS_UNKNOWN` | `value` |
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

## The events (decision 3: a store's event log only)

`core.convention.service.ConventionEvents`, subject `CONVENTION` (the id; the code as the label), all Notable, written
after the commit (none for a refusal). On a head office the event log is off: nothing is written.

| Code | When | Values |
|---|---|---|
| `CONVENTION_CREATED` | created | the details list its values: "Convention MIN « … » créée : Nom … ; Organisation … ; Du … ; …" |
| `CONVENTION_UPDATED` | a value changed | each field before → after: "Nom : A → B ; Utilisations max par jour : 1 → 2" |
| `CONVENTION_SUSPENDED` / `CONVENTION_REACTIVATED` | the status changed | old / new value `ACTIVE` / `SUSPENDED` |
| `CONVENTION_DELETED` | deleted (no card) | its values in the details |

The French names are the screen's (`ConventionEvents.describe`); the Journal's names of the codes come with F4. The
holder's phone is never in an event (decision 15; the cards' events: B4).

## Tests

- `ConventionServiceTest` (core): the rules above over the real repositories and their JPQL (`ConventionTestBed`:
  Hibernate over H2, the event log in memory): the code and its uniqueness, the code fixed, the texts, the dates, the
  limits, recognition / prefix / digits and their lock with a card, suspend / reactivate, delete only without a card,
  409, the list (counts, states, filter, search, pages), the events' sentences.
- `ConventionAPITest` (core): standalone MockMvc over the real API and service: 403 per right, each refusal's status,
  code and params, 404, 409, the fields of a form that are ignored, the JSON (dates as text); the three beans only with
  `convention.enabled=true` (false, blank, a wrong value, no line: none).
- `ZZDataInitializerRolesTest.conventionRightsGivenToAdmin`; `ConventionKeyTest`, `AppConfigAPITest` (B1);
  `ConventionTableTest` (B2).
- `InstallationBeansProofTest` (network-tests): "decided 145"; the shapes "store, conventions on", "head office,
  conventions on", "store, promotions from the head office, conventions on" (the beans are there: the reads stay).

Real check, 2026-11-28, store B (`pos_store_b`, port 555, `-Set 'convention.enabled=true','eventlog.enabled=true'`):
each call once, the refusals, the rows and the events as above; three conventions left for the next parts
(`MIN-SANTE` active with limits, `BANQUE-BT` `NO_CARD` to come, `POSTE` expired with prefix `PT` and 6 digits).
