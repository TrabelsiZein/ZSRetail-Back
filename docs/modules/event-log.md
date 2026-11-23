# Event log — "Journal des événements" (3.4.0, store)

What the cashiers, the responsibles and the admins do, written so that a theft can be investigated.

**The rule:** an event is written when somebody does something that changes money, stock or rights, or tries to and is
refused. Looking (a search, a page opened, a report read) is never an event. Only explicit business events are written,
never every database change.

The build is in small steps.

- **Step 1 (done): the foundation.** The switch, the two tables, the catalogue, the helper and the start event.
- **Step 2 (done): the server's till events.** One call at the end of each gesture's service method; see "Step 2"
  below. No screen has changed.
- **Step 3 (done): the badge and the logins.** One line beside what already happens (the login filter, `POST /logout`,
  `badge_scan_log`, the 403 `TILL_RIGHT_REQUIRED`); see "Step 3" below. No screen has changed.

## The switch: `eventlog.enabled`

Built as `warranty.enabled` (`NodeOwnership.isEventLogSet`):

| Key | Values | Absent |
|---|---|---|
| `eventlog.enabled` | `true` / `false`, trimmed, any case | `false` |

- Any other value, an empty one included, stops the start naming the key. So does `true` on a head office: in version 1
  the events stay in the store.
- The store type file says `false`. The machine model has the line commented.
- `GET /config` `eventLogEnabled` is `true` on a store with the line set to `true`, and `false` everywhere else.
- The startup summary adds "event log on".
- Off, nothing is written; the tables and their rows stay.

## The tables (core, `core.eventlog`; `db/3.4.0/update.sql` steps 1 and 2; every database)

**`event_log` (`EventLogEntry`)**

- One row per event, inserted once and never updated. The entity is `@Immutable`: a change through JPA is not written.
- No foreign key. The subject is a type and an id as text, and the names are copied as text, so a row stays readable
  after a rename or a delete.
- Every text column is nvarchar.

| Columns | What |
|---|---|
| `id`, `event_uuid` (unique) | The UUID is made by the till for its events (a resend is stored once) and by the server for the others. |
| `importance`, `sequence_no` | 10 STEP, 20 NOTABLE, 30 ALERT. The number runs 1, 2, 3... within each importance, so a missing number is a deleted row. It is null on a row imported from before the module. |
| `code`, `subject_type`, `subject_id`, `subject_label` | `EventCode` and `EventSubject` as text; the subject's id and what a person reads for it. |
| `event_at`, `front_at` | The server's clock when the event was written, and the till's clock (till events only). |
| `pos_machine_id` / `_name`, `session_id` / `_number`, `cart_id` | Where it happened. `cart_id` is the cart's UUID, made by the till at the cart's first line. |
| `user_id`, `user_login`, `user_name`, `user_role` | Who did it. |
| `authorised_by_id` / `_login` / `_name` | The owner of the badge that allowed it. Empty when nobody's badge was needed. |
| `sent_by_id`, `sent_by_login` | Till events only: the user whose login sent them. |
| `client_address`, `machine_sequence_no` | The request's address; the till's own number of its events (a hole shows events never sent). |
| `old_value`, `new_value`, `amount`, `details` | Before and after, an amount, one free text. |
| `imported` | A row copied from `badge_scan_log` (step 7). |

Indexes:

- `ix_event_log_at` (the page is always filtered by a period)
- `ix_event_log_subject` (a subject's history)
- `ix_event_log_session`
- `ix_event_log_user`
- `ix_event_log_sequence` (the holes)

**`event_log_sequence` (`EventLogSequence`)**

- The last number of each importance, one row per importance (unique). The script seeds 10, 20 and 30 at 0.
- A number is taken by adding 1 to the row inside the event's own transaction. Two events never share a number, and
  an event that is not written gives its number back.
- Kept apart from the events, so a row deleted at the end of `event_log` still shows: the counter is higher than the
  last row's number.

## The catalogue: `EventCode`

- Each code carries its subject type (`EventSubject`) and its importance (`EventImportance`). The importance is
  decided here, never by the caller or the till.
- A new event is one entry in the catalogue plus one call where the gesture happens.
- A fourth importance can be added between the three (they are ten apart) without touching the table.
- The entries come from the design's table. Additions:
  - `RETURN_VOUCHER_REPRINTED`
  - `SESSION_TICKET_PRINTED`
  - `USER_ENABLED`, `USER_ROLE_CHANGED`
  - `ROLE_CREATED`, `ROLE_DELETED`
  - `ERP_SYNC_RUN`, `HEAD_OFFICE_SYNC_RUN`
- `RECEIPT_REPRINTED` is an Alert, because the ticket printer opens the drawer on every print.

## The helper: `EventLog`

The one call is `eventLog.write(NewEvent.of(EventCode.X).subject(id, label)...)`. The helper fills in:

- the importance and the subject type, from the catalogue;
- the server's clock;
- a UUID, unless the till gave one;
- the logged user, unless the caller named one;
- every text, cut to its column.

It never breaks or blocks the gesture:

- Off, it does nothing.
- A failure is one WARN, and the sale, the return or the save goes on.
- The insert runs in its own transaction (`EventLogWriter`, `REQUIRES_NEW`, 5 s timeout), never the caller's.

The two ways to write:

- `write`: called inside a transaction, the event is written once that transaction has committed. A sale that rolls
  back leaves no "sale validated".
- `writeNow`: written at once, whatever becomes of the caller's transaction. This is for refusals.

On a database without its number rows, two first events of one importance at once would both try to create the row.
The second one is tried again and takes number 2.

**`EventLogStart`** writes `EVENTLOG_STARTED` (Notable since step 2: a store restarts every morning) at each start with
the module on. A long time between two of them, with no event in between, shows the module was off.

**What the gesture's transaction sees (step 2).** At the gesture only memory is read: the logged login, the request's
address, and the contexts' `atCall`. No query runs there: a failing query inside the gesture's transaction would mark
it rollback-only. The user's id, name and role, and the contexts' `complete` (the session, the machine, the names), are
read in the event's own transaction, before it is numbered. A completion that fails leaves those columns empty; the
event is written all the same.

## Step 2: the server's till events (store)

**One call per gesture.** Each gesture's service method ends with one call to `TillEvents` (store, `store.eventlog`),
guarded by `if (tillEvents != null)` so a unit test that builds the service without it writes none. `TillEvents` builds
the event and a French text a store manager reads (amounts with 3 decimals). Off, it does nothing at all, not even the
text. A mistake in the making (a missing value) is one WARN and costs the event, never the gesture.

**Filled by itself (`StoreEventContext`).** A value the caller gave is never replaced.

- **The badge's owner.** `TillRightsGuard` keeps each grant it uses in the request's attributes (`TillRequestGrants`,
  one line in `useGrant` and in `checkCashMovement`). The event names the first owner; the others are named in the
  details ("Autorisé aussi par : …"). A pass by the user's own role keeps nothing: nobody authorised it.
- **The session**: the user's open session, and its number when only the id is given.
- **The POS machine**: the one registered at the request's address. A request from a PC that is no registered POS
  machine (the back office) leaves the machine empty and keeps the address (step 3: no more the session's till).
- A refusal (`TILL_RIGHT_REFUSED`) is never "authorised by": the grants the request used before it are not its own.

**`cartId`.** `ProcessSaleRequestDTO.cartId` is optional; the till sends it from step 5. It is kept on the sale and
pending events.

| Event | Where (store) | Subject | Values |
|---|---|---|---|
| `SALE_VALIDATED` (Step) | `SalesHeaderService.processCompleteSale`, `completePendingSale`, `splitAndPay` (the part paid apart) | ticket | total; lines, payments, change in the text; the cart |
| `PENDING_SAVED` (Step) | `savePendingSale`, `updatePendingSale` ("mis à jour") | ticket | total, lines, table |
| `PENDING_DELETED` (Notable) | `cancelPendingSale` (also the tables screen emptied) | ticket | total, lines (counted only when the module is on) |
| `PAYMENT_CHANGED` (Alert) | `changePaymentMethod` | ticket | old → new method names, the payment's amount, the reason |
| `RETURN_DONE` (Notable) | `ReturnHeaderService.processReturn` | return | amount, lines, ticket returned, cash or voucher; the badge's owner (MAKE_RETURN) |
| `CASH_MOVEMENT` (Notable) | `CashMovementService.record` | movement | type, amount, note; the badge's owner when a badge allowed it |
| `SESSION_OPENED` (Step), `SESSION_OPENING_GAP` (Notable) | `CashierSessionService.openSession` | session | the float; the gap: float left → float typed, the gap as the amount |
| `SESSION_CLOSED` (Step) / `SESSION_CLOSED_WITH_DIFFERENCE` (Alert) | `closeSessionWithCashCount` | session | expected → counted cash; the difference (counted − expected) as the amount, above 0.01 whatever `ENABLE_CASH_DISCREPANCY_CHECK` says |
| `SESSION_VERIFIED` (Notable) | `verifySession` | session | the cashier's count → the responsible's; the drawer's withdrawal; the responsible as the user |

A gesture refused (an exception before its end) writes none of these. A refusal of a till right is step 3's
`TILL_RIGHT_REFUSED`.

## Step 3: the badge and the logins

**`AccessEvents` (core, `core.eventlog.service`)** builds these events and their French texts. It never throws, and
when the module is off it builds nothing and reads nothing. A badge's code is never written: the badge is named by its
owner.

| Event | Where | Values |
|---|---|---|
| `LOGIN_OK` (Step) | `JWTAuthenticationFilter.successfulAuthentication`, after the answer is sent | the user, his role, the address |
| `LOGIN_REFUSED` (Alert) | `JWTAuthenticationFilter.attemptAuthentication`, its catch (the refusal never reaches `unsuccessfulAuthentication`: the filter answers the 403 itself) | see below |
| `LOGOUT` (Step) | `POST /logout` (`LogoutAPI`, core): it only writes the event, 204. The till calls it from step 5. | the user |
| `TILL_RIGHT_USED` (Step) | `BadgeService.logPass` for a badge pass (the request used the scan's grant), and `logBadgeScan` for a successful scan of the customer list. A pass on the user's own role writes none (`badge_scan_log` still keeps it). | the action; the badge's owner as the authoriser; the user who scanned; the session |
| `SESSION_OPENED_PREVIOUS_UNVERIFIED` (Notable) | `CashierSessionService.openSession`, when the drawer's previous session is closed but not verified (allowed when `VERIFY_PREVIOUS_SESSION_BEFORE_OPENING` is off) | previous → new session; the previous cashier in the text |

`TILL_RIGHT_REFUSED` (Alert) has three cases. Its new value is the reason's code, and the details say which case and
why:

| Case | Where | Reason codes |
|---|---|---|
| Scan refused | `BadgeService.logBadgeScan` | `BADGE_NOT_EXISTS`, `BADGE_REVOKED`, `BADGE_EXPIRED`, `BADGE_OWNER_INACTIVE`, `BADGE_NO_ACCESS` (the owner is named, an unknown badge is not) |
| Request refused 403 `TILL_RIGHT_REQUIRED` | the catch of each endpoint: process-sale ("Vente"), save-pending, update-pending, complete-pending, process-return, close, cash-movement. Written at once, because the request's transaction has rolled back. | `NO_GRANT`, `GRANT_UNKNOWN`, `GRANT_USED`, `GRANT_EXPIRED`, `GRANT_OTHER_SESSION`, `GRANT_OTHER_ACTION`; the line (from 1) of a line discount |
| `POST /badge/pass` refused | `BadgeAPI.rolePass` | `ROLE_WITHOUT_RIGHT` (the role changed since the login) |

The till asks for the badge before it sends the request, so a 403 is not the normal path: it means the role changed,
the grant expired or was used, or the request did not come from the till.

**A refused login and the login typed.**

- The login typed is looked up. It names a user only when it is an existing user's login: his id and his login as
  stored (never the text typed), and the reason (wrong password, account disabled, server error).
- Anything else is written as "Identifiant inconnu" with the request's address. No column holds the text typed, so a
  password typed in the login field never lands in the log.
- The answer to the till is unchanged ("Incorrect login or password" in both cases).
- Several refusals in a row give one event each: each is numbered and kept. A run on one login or from one address is
  found by the login or address filter (step 6).

**Logout.** `SecurityConfig` turns off Spring's default `LogoutFilter`, which answered `/logout` with a redirect to
`/login?logout`. Nothing used it. `POST /logout` needs a logged user, as any endpoint outside `permitAll`. The API is
stateless: the token stays valid until it expires and the browser drops it.

**Wiring.**

- The filter gets `AccessEvents` through a `@Lazy` field of `SecurityConfig`, so it is resolved at the first login and
  the security setup never needs the repositories at the start.
- `BadgeService`, `BadgeAPI` and the store's controllers take theirs with `@Autowired(required = false)`, so a test
  that builds them without it writes none.
- `badge_scan_log` is still written exactly as before; CashWatch reads it.

## Tests

- `EventLogKeyTest`: the switch.
- `EventLogTest`: the catalogue, off and on, the given values, nobody logged, the start event, the numbering per
  importance and the first-row race, a failure that never reaches the caller, and the caller's commit and rollback.
- `EventLogTableTest`: the two tables on H2, the counter's queries as written, the unique UUID, one row per importance,
  and a row never updated.
- `AppConfigAPITest`: `eventLogEnabled`.
- `ConfigurationKeysProofTest`: keys 123.
- Step 2:
  - `EventLogTest.contexts`: `atCall` at the gesture, `complete` in the event's transaction, a failing context.
  - `TillEventsTest`: each event's code, importance, values and text; off; a mistake never thrown.
  - `StoreEventContextTest`: the badge's owners, the session, the machine, the given values kept.
  - The real services: `SaleUnitCostTest.eventsOfTheSales` (sale, pending saved / again / completed / deleted, split),
    `CashMovementTest.events` (a movement by badge; a refused closing writes nothing; a closing with a difference),
    `DrawerHandoverTest.events` (verification, opening, gap), `ReturnRefundLoyaltyTest.returnEvent` (the badge's owner).
  - `PAYMENT_CHANGED` has no test on the real service (none exists for `changePaymentMethod`): its text and values are
    in `TillEventsTest`.
- Step 3:
  - `AccessEventsTest`: the logins (a known user, an unknown login whose text is in no column, a failing lookup), the
    logout, the badge used and refused (each reason), the role pass refused, off (nothing read).
  - `JWTAuthenticationFilterTest.loginEvents`: the real filter, the 403 answer unchanged.
  - `LogoutAPITest`: 204 and LOGOUT; off; the user not read.
  - `TillRightsEndToEndTest.events`: over HTTP, a refused scan, a badge pass, the same grant again (403, the line,
    `GRANT_USED`), the customer list, `/badge/pass` refused, a role pass writing none, and no badge code in any column.
  - `TillEventsTest`: `SESSION_OPENED_PREVIOUS_UNVERIFIED` and `TILL_RIGHT_REFUSED` (each reason in French).
  - `DrawerHandoverTest.unverifiedPreviousEvent`: the real opening, refused (none) and allowed (one).
  - `StoreEventContextTest`: no machine from a back-office PC; a refusal not authorised.

## Next steps

| Step | What |
|---|---|
| 4 | The batch endpoint and the till's queue. |
| 5 | The till's gestures. |
| 6 | The Journal page. |
| 7 | The old badge scans copied in. |
| 8 | General setup and the cleanup. |
| 9 | Admin gestures. |
