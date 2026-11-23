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
- **Step 4 (done): the till's queue and `POST /event-log/batch`.** The front keeps its events in localStorage and sends
  them in groups; no gesture is logged yet (step 5). See "Step 4" below.
- **Step 5 (done): the till's gestures.** One line beside each gesture of the front (the cart, the discounts, a pending
  ticket taken back, the reprints, the tickets X / Z and the session tickets, the logouts) and the cart's ID; see
  "Step 5" below. No screen has changed.

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
| `client_address`, `machine_sequence_no`, `install_id` | The request's address; the till's own number of its events, per browser install (a hole within one install shows events never sent; step 4). |
| `doubt` | Step 4: what the server could not confirm of a till event's claims (codes, comma separated); null when nothing. |
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
| `LOGIN_REFUSED` (Notable since step 4), `LOGIN_REFUSED_REPEATED` (Alert) | `JWTAuthenticationFilter.attemptAuthentication`, its catch (the refusal never reaches `unsuccessfulAuthentication`: the filter answers the 403 itself) | see below |
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
- Step 4: one refusal is Notable. From the 5th refusal in a row on one existing user's login, or from one address, the
  event is `LOGIN_REFUSED_REPEATED` (Alert), the count as the amount, and the details say the run ("5e refus de suite
  pour cet identifiant depuis 10:02:20"). A run ends at a successful login of that login or from that address, or after
  15 minutes without a refusal: a typo a day never adds up. The counter is in memory (`LoginTries`, bounded at 10,000
  runs), lost at a restart. The grouping on the page comes with step 6.
- Step 4: `backend.log` never writes the login typed either. A refusal is one WARN line, its kind and the address
  ("Login refused (BadCredentialsException) from 192.168.1.30"); only a server failure keeps its stack trace.
  `UserDetailsServiceImpl`'s "User not found" no longer names the login. Account lockout is not in this module.

**Logout.** `SecurityConfig` turns off Spring's default `LogoutFilter`, which answered `/logout` with a redirect to
`/login?logout`. Nothing used it. `POST /logout` needs a logged user, as any endpoint outside `permitAll`. The API is
stateless: the token stays valid until it expires and the browser drops it.

**Wiring.**

- The filter gets `AccessEvents` through a `@Lazy` field of `SecurityConfig`, so it is resolved at the first login and
  the security setup never needs the repositories at the start.
- `BadgeService`, `BadgeAPI` and the store's controllers take theirs with `@Autowired(required = false)`, so a test
  that builds them without it writes none.
- `badge_scan_log` is still written exactly as before; CashWatch reads it.

## Step 4: the till's queue and the batch endpoint

**The front (`ZSRetail-Front`).**

- `logEvent(code, fields)` (`src/services/eventLog.js`) is the one call of a gesture, from step 5. It returns at once and
  never throws: it writes one small localStorage key and sends nothing.
- Nothing is queued when `GET /config` `eventLogEnabled` is false, or for a code not in `eventLogTillCodes`.
- The user (his login: the till's `userData` has no id), the open session and the till's clock come by themselves. The
  gesture says the rest.
- The queue (`src/libs/event-queue.js`):
  - one key per event, `zsrEvent:<importance>:<time>:<uuid>`, so adding never rewrites the queue and two tabs never
    overwrite each other;
  - the install (`zsrEventInstall`): the browser's UUID and its own count. A cleared browser is a new install;
  - none of these keys is removed at the logout.
- Full at 5,000 events, the oldest Step event goes, then the oldest Notable one, never an Alert. One
  `EVENTLOG_EVENTS_DROPPED` (Alert) waits in the queue with the count and the first and last time lost; it grows while
  it waits, and a new one starts once it is sent. A browser storage that refuses (its quota) drops one event and tries
  once more.
- The sender starts once in `main.js`. Every 5 s, when someone is logged in and the browser is online, the oldest 50
  (Alerts first) go to `POST /event-log/batch`. It also sends when the network comes back and when the app is opened
  again.
- The sender uses its own axios instance, so no error popup, no token refresh and no logout. A failure waits twice as
  long each time, up to a minute. A 404 (off on the server) stops it until the app is opened again.

**The server (store, `store.eventlog`: `EventLogBatchAPI`, `TillEventBatch`).**

- `POST /event-log/batch` `{installId, events: [{uuid, code, at, seq, userId | userLogin, sessionId, posMachineId,
  cartId, subjectId, subjectLabel, oldValue, newValue, amount, details}]}`.
- The answers:
  - 404 when the module is off;
  - 400 above 100 events;
  - 200 `{stored, duplicates, refused: [{uuid, reason}]}`: the till drops all three;
  - 503 when the database refused the group: the till keeps it.
- A logged user is needed. The licence is not: `LicenseFilter` lets `/event-log/batch` and `/logout` pass, as `/login`.
- **Codes.** Only `EventCode.FROM_TILL` is accepted: the cart events, the discount gestures, `PENDING_TAKEN_BACK`, the
  reprints, the tickets X / Z, the session tickets and `EVENTLOG_EVENTS_DROPPED`.
  - Any other code is refused `NOT_FROM_TILL` (a money event above all), an unknown one `UNKNOWN_CODE`, a missing UUID
    `NO_UUID`; each refusal is one WARN in `backend.log`.
  - The importance and the subject type come from the catalogue.
- **Duplicates.** A UUID stored already, or twice in the group, is answered as a duplicate, as stored.
- **Claims.** The user, the session and the machine the till sends are kept.
  - The user is claimed by id, else by login (any case), else he is the sender.
  - The machine, when not claimed, is the one registered at the request's address.
  - What the server cannot confirm goes in `doubt` and in the details ("À vérifier : …"), and the event is stored all
    the same:
    - `SESSION_OF_ANOTHER_USER`: the session is not the user's;
    - `USER_UNKNOWN`, `SESSION_UNKNOWN`, `MACHINE_UNKNOWN`;
    - `FRONT_TIME_INVALID`: the till's clock could not be read.
- **The server's own values.** `sent_by` is the logged user who sent the group (after a logout, another user may send a
  colleague's events), with the request's address and the server's clock beside the till's.
- **Writing.**
  - The group is sorted by the till's numbers.
  - `EventLogWriter.insertAll` runs in its own transaction: one counter update per importance reserves the group's
    numbers (`reserve`), then one JDBC batch insert (`EventLogBatchInsert`).
  - A UUID stored meanwhile (the same group sent twice at once) rolls it back: it is tried again once without it.
- **`GET /config` `eventLogTillCodes`**: `{code: importance}` of `FROM_TILL`, empty when off.
- **`db/3.4.0/update.sql` 3**: `event_log.install_id`, `event_log.doubt` and `ix_event_log_install` (install, number:
  the holes).

## Step 5: the till's gestures (front)

One call beside each gesture, `src/services/tillEvents.js`; each builds the French text (the item's code and label, the
quantity, the amount) and hands it to `logEvent`, which takes the event and its time at once and writes the storage just
after the gesture: a scan, online or offline, waits for nothing.

| Event | Where (front) | Values |
|---|---|---|
| `CART_LINE_ADDED` (Step) | `ItemSelection.addToCart`: every scan, tile, search, kit component, RFID read and bulk line (`pickItem(item, via)`) | the item, the quantity added, the line's quantity and amount, how |
| `CART_QTY_RAISED` (Step) / `CART_QTY_LOWERED` (Notable) | `increaseQuantity`, `decreaseQuantity`, `setQuantity` (typed) | old → new quantity, the line's amount |
| `CART_LINE_DELETED` (Notable) | `removeFromCart` (its bin, or − at the last piece) | the quantity, the amount |
| `CART_CLEARED` (Notable) | `confirmClearCart` | lines, amount |
| `LINE_DISCOUNT` (Notable) | `applyLineDiscount` | old → new (percent and amount), on the line's amount |
| `CART_DISCOUNT` (Notable) | `Payment.applyHeaderDiscount`, `clearHeaderDiscount` | old → new, on the total |
| `PENDING_TAKEN_BACK` (Step) | `ItemSelection.retrievePendingTicket`, `TableSelection` (a table's ticket) | the ticket, lines, amount; a cart with lines it replaced |
| `RECEIPT_REPRINTED` (Alert) | `TicketsHistory.printTicketData` (the till's and the back office's page) | the ticket, its amount, from the till or the back office |
| `RETURN_VOUCHER_REPRINTED` (Alert) | `ReturnsManagement.printVoucher` | the voucher, its amount |
| `TICKET_X_PRINTED` (Step) / `TICKET_Z_PRINTED` (Notable) | `StoreDay.print` | the day, the POS machine or the store |
| `SESSION_TICKET_PRINTED` (Step) | `SessionDetailsModal.printReport` (X / Z of a session), the till's closing ticket | the session |
| `CART_LEFT_AT_LOGOUT` (Notable, new) | every logout (`Navbar`, `UserDropdown`, `LicenseExpired`, `OpenSession`, after a closing) | lines, amount; the cart is not emptied (the gap kept for later) |
| `CART_LOST_ON_RELOAD` (Notable, new) | `main.js`, when the app opens | lines, amount, started at; a pending ticket it came from stays pending |
| `PROMO_CODE_ADDED` / `PROMO_CODE_REMOVED` (Notable, step 5b) | `applyPromoCode`, `removePromoCode` (`ItemSelection` and `Payment`) | the code, the cart's total before → after |
| `CART_CUSTOMER_CHANGED` (Notable, step 5b) | the till when it finds the customer changed with lines in the cart (it prices them again), and `Payment.onCustomerChange` | old → new customer (names read in the background), the cart's total before → after |

**The cart's ID** (`src/libs/till-cart.js`, localStorage `zsrCart`).

- Made at the first line and kept by the pos store at every `SET_CART` / `CLEAR_CART`, with the lines count; the amount
  comes from the till's `totalAmount` watcher.
- Sent as `cartId` with process-sale, complete-pending, save-pending and update-pending (`Payment`, and
  `ItemSelection.backToTables` for a table).
- Ended when the cart is emptied in memory (a sale, a pending save, the button, the last line deleted) and after a
  table's ticket is saved.
- A pending ticket taken back starts its own cart.
- It survives a reload, the cart does not (Vuex): a record still holding lines when the app opens is a cart lost on
  reload, said once. Scan + refresh is never an invisible way to drop a cart. A cart told at a logout is not told again.

**The logout.** `tillLogout()`, first in every logout: `CART_LEFT_AT_LOGOUT` when the cart has lines, then
`POST /logout` with the leaving user's token, read before it is removed, never waited for.

**The customer (step 5b).** The till prices the cart again when it comes back from the customers page: the event takes
the total then and again 2.5 s later, keeps the gesture's time, and reads the two names with a silent GET. The same
change seen twice for one cart (the payment page, then the till) is told once.

**Not logged (decided or for later).**

- The split payment of a table ticket: the server writes its `SALE_VALIDATED`.
- The price passes of the cart (promotions worked out again), which are no gesture.

## Fixes after the first test round (2026-11-23)

- A search result clicked in the grid is a line added "par la recherche" (it went through the tile path).
- **The till's "Retour à l'administration" popup** checks the password with the login request, marked by the header
  `login-purpose: TILL_ADMIN_RETURN` (front `useJwt.login(..., { purpose })`, allowed in the CORS headers).
  - The right password writes `ADMIN_RETURN_VERIFIED` (Step, "Retour à l'administration — mot de passe vérifié"), never
    a `LOGIN_OK`.
  - A wrong one stays a `LOGIN_REFUSED` whose text adds "(retour à l'administration)". It still counts toward
    `LOGIN_REFUSED_REPEATED`.
  - The marker is the till's claim: it changes only the event, never the answer (a token as before).
- `SESSION_OPENED` says what the drawer's last clôture left: "fond 20,000 (tiroir laissé à 0,000 à la clôture de
  SESSION…)", via `DrawerHandoverService.lastClosingOf`, read only when the module is on. There is no gap event for
  it: the 3.2.0 écart is unchanged (nothing proposed when 0 was left).
- A refused login has no session (nobody is logged in); the machine is kept.
- Texts:
  - the lost cart and the events dropped give the till's local time ("le 23/11/2026 à 21:29");
  - real plurals ("1 ligne", "2 lignes");
  - `EVENTLOG_STARTED` reads "Journal des événements actif, version …".
- **For the Journal page (step 6):** `amount` is a float column (21.899999…), so the page rounds it to 3 decimals.

**The measure of a validated sale** (2026-11-23).

- The sale path in Java (the real `SalesHeaderService`, `TillEvents`, `EventLog`; the database in memory), 20
  sales each after a warm-up:
  - off: 0.53 ms on average, 1.04 ms worst;
  - on: 0.73 ms on average, 1.31 ms worst;
  - so building the event costs about 0.2 ms.
- The event's reads on SQL Server (pos_franchise_customer, read-only, 20 rounds): 3.2 ms on average, 4.1 ms worst.
- The write (counter update, insert, commit) was not measured: it would change data. Estimated at 1 to 3 ms.
- So a sale's request waits about 5 to 7 ms more. The event is written after the sale's commit, in the request's
  thread, in its own transaction, on a second connection of the pool.
- **When the write is slow** (the counter row locked, a slow disk): the request waits up to the 5 s timeout. Then the
  event is lost (one WARN) and the sale stays done: the cashier sees a slow sale, never a failed one.

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
- Step 4:
  - `TillEventBatchTest`: a group stored (importance, subject, clocks, claims, sender, numbers consecutive per
    importance after the server's), duplicates (stored and twice in the group), refused codes (money, unknown, no
    UUID), the catalogue `FROM_TILL` (no money code), the doubts (another user's session, unknown user, session,
    machine, an unreadable clock), the user by login, no claims (the sender, the machine at the address), a group sent
    twice at once, the endpoint (404, 400, 200, 503).
  - `EventLogTableTest.batch`: `reserve` and `storedUuids` as written, the batch SQL on the table Hibernate makes
    (every column, nulls included).
  - `AccessEventsTest`: Notable, the 5th in a row on one login and from one address, a login or 15 minutes ending
    the run.
  - `JWTAuthenticationFilterTest.logWithoutLogin`: the server log of a refused login holds no login typed.
  - `AppConfigAPITest`: `eventLogTillCodes`.
  - Front: `node tools/event-queue-check.mjs` (order, numbers, install, cap and drop event, Alerts kept, quota, group,
    answer).
- Fixes after the test round:
  - `AccessEventsTest.adminReturn` (verified, refused, counted), `startText`.
  - `JWTAuthenticationFilterTest.adminReturnHeader`.
  - `StoreEventContextTest.refusedLoginNoSession`.
  - `TillEventsTest.openingSaysTheDrawer`, `DrawerHandoverTest.openingAfterAllTaken`.
  - Front: `event-queue-check` (local time, plurals).
- Step 5:
  - Front: `node tools/till-cart-check.mjs` (a cart's ID from its first line to its end, the amount, a pending ticket's
    own cart, the logout mark, the cart lost on reload told once, a storage that refuses).
  - `TillEventBatchTest.tillCodes`: the two new codes the till may send.

## Next steps

| Step | What |
|---|---|
| 6 | The Journal page. |
| 7 | The old badge scans copied in. |
| 8 | General setup and the cleanup. |
| 9 | Admin gestures. |
