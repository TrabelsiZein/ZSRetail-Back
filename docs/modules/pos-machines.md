# POS machines

**Status**: 3.2.0, steps 1 to 3 of 5 (branch `release/3.2.0`). A POS machine ("poste de caisse", the physical PC a cashier
works on) declared with a name and the address the back end sees for it. It came with RFID in 3.1.0 (`rfid.md`), where
it links a PC to its reader; since 3.2.0 it exists on **every store**, with or without RFID. First customer asking for
it beyond RFID: ZEN.

**Store application only**: the head office has no POS machine (no table, no endpoint, no page, no permission).

| Step | What | State |
|---|---|---|
| 1 | POS machines on every store (this page) | done |
| 2 | A: the session keeps the POS machine it was opened on (its tickets and returns through it); B: one choice, "the drawer belongs to" the cashier or the POS machine | done |
| 3 | The day per POS machine: the Journal's day grouped, the store ticket X / Z of one machine | done |
| 4 | Optional controls in General setup (which user on which machine) | to come; "declared machines only" and "one open session per machine" came with step 2 B, as fixed rules of one choice |
| 5 | Cash taken out of the drawer | to come |

Step 1 changes no session, ticket, ticket X or Z, or POS screen: selling behaves as before. Step 2 part A records and
shows the machine of each session and refuses nothing at the till. Part B refuses only when a store chooses "the drawer
belongs to the POS machine"; with the default (the cashier) nothing changes, and a store that registered no POS machine
sees no difference at all.

## The session's POS machine (step 2, part A)

**When a session is opened** (`POST /cashier-session/open`, `CashierSessionService.openSession(cashier, openingCash,
remoteAddress)`), it keeps the POS machine registered at the request's address (`SessionPosMachineService
.machineForOpening`: active or not, the machine is information); none when that PC is not registered (or the address
cannot be read). Nothing is added on the sale or the return: they get their machine **through their session**. The
sessions opened before 3.2.0 have none. The cashier's closing, the responsible's verification, the tickets, the returns
and the till are unchanged.

**Where it is kept: its own store table, the link and not a copy of the name.** `cashier_session_pos_machine`
(`store.posmachine.model.SessionPosMachine`): `cashier_session_id` (the key, `fk_cashier_session_pos_machine_session` →
`cashier_session`), `pos_machine_id` (`fk_cashier_session_pos_machine_machine` → `pos_machine`), index
`ix_cashier_session_pos_machine_machine`; one row per session that has a machine. Why not a column of `cashier_session`:
`CashierSession` is core's and the head office has it too (its copies), `PosMachine` is the store's, and core never sees
an application (`ProjectSortingGuardTest`); a column would also have been added to every head office's table. So the
head office, its tables and the session copies it receives are unchanged. The link, not the name: a machine renamed shows
its new name everywhere, and a machine with sessions cannot be deleted (below). `db/3.2.0/update.sql` step 14 creates
the table and its index on a store (with `quick_quantity` and `pos_machine`); Hibernate creates it at a start too
(without the index). No data changed.

**A machine with sessions cannot be deleted**: `DELETE /pos-machine/{id}` answers 400 `POS_MACHINE_HAS_SESSIONS` ("The
POS machine 'X' has sessions: it cannot be deleted. Deactivate it instead."). One without sessions is deleted as before.

**Where the machine is shown** (store admin and responsible screens; never on the sale receipt):

| Screen | Back end | Front |
|---|---|---|
| Sessions list | `GET /cashier-session/history`: `posMachineName` per row (`SessionDashboardDTO`), filter `posMachineId` | `SessionHistory.vue`: column "Poste de caisse" and a filter |
| Session details | `GET /cashier-session/{id}/details`: `session.posMachineName` | a line "Poste de caisse" |
| Ticket X and Z of a session | `GET /cashier-session/{id}/report-ticket`: `posMachineName` (`SessionReportTicketDTO`) | `printSessionReport.js`: a header line when there is one (the Z printed at the closing too) |
| Sales history | `GET /sales-header/history`: `posMachineName` per ticket, filter `posMachineId` (also `/history/export`) | `TicketsHistory.vue`: column and filter |
| Returns list | `GET /return-header/history`: `posMachineName` per return | `ReturnsManagement.vue`: column |
| Journal, a day | the store ticket's `sessions[].posMachineName` (`StoreReportTicketDTO.SessionRow`) | `StoreDay.vue`, "Sessions du jour": column |

An empty machine is shown as "—". The columns and the filters appear only on a store that has registered a POS machine
(`loadPosMachines`, `GET /pos-machine`): a store without one sees its lists as before. The names of a page come in one
query (`SessionPosMachineService.namesBySession`). The filter is a subquery on the link table (`openedOn`,
`ofSessionOn`): the machine's sessions, then the sales through `ix_sales_header_session_status` (3.1.1); the history list
has no index on `sales_date` anyway, so the filter makes the query smaller, not larger.

**Refusals in the language of the screen.** Every refusal of the POS machines page now carries a code and its values
(`PosMachineException`: `{"error", "reason", "code", "params"}`): `POS_MACHINE_NAME_REQUIRED`, `_NAME_TOO_LONG` (max),
`_NAME_TAKEN` (name), `_ADDRESS_INVALID` (address), `_ADDRESS_TAKEN` (name, address), `_READER_NOT_FOUND` (id),
`_NOT_FOUND` (id), `_HAS_SESSIONS` (name). The front's API client translates a coded refusal before any screen reads it
(`libs/server-messages.js`, registered in `libs/axios.js`): `data.error` becomes `serverErrors.<code>` with the values,
in fr, en or ar; a code without a text keeps the English message (the logs keep it too).

## The drawer belongs to (step 2, part B)

One General setup choice of a store, tab "Caisse et clôture" (`CASH_DRAWER_OWNER`, `general-setup.md`): "Le
tiroir-caisse appartient" **au caissier** (`CASHIER`, the default) or **au poste de caisse** (`POS_MACHINE`). No other
switch: the rules of `POS_MACHINE` go together, none is configurable alone.

**`CASHIER`**: exactly the behaviour of before. Nothing is refused; the session follows the cashier on any PC; the POS
machine of the opening (part A) is information only. A store that registered no POS machine sees no difference.

**`POS_MACHINE`**, the fixed rules:

| Rule | Where | Refusal (code, values) |
|---|---|---|
| 1. A session opens only from a registered, **active** POS machine | `SessionPosMachineService.machineForOpening`, from `openSession` | 409 `POS_MACHINE_NOT_REGISTERED` (address: the one the server sees, to register or correct) or `POS_MACHINE_INACTIVE` (name, address) |
| 2. One OPENED session per POS machine | same | 409 `POS_MACHINE_IN_USE` (name, cashier: whose session holds it) |
| 3. Selling, putting on hold and returning only from the session's POS machine | `PosMachineSellingGuard` (below) | 409 `OTHER_POS_MACHINE` (name; also `posMachineName`) |
| 4. Closing is never restricted: the cashier closes their own session from any PC, a responsible or an admin closes and verifies from any PC | `CashierSessionAPI` is outside the guard | — |
| 5. The choice does not change while any session is open; a POS machine with an open session is not deactivated (nor deleted: it has sessions); its address can be corrected | `CashDrawerSettingCheck`; `PosMachineService.check` | 400 `CASH_DRAWER_OWNER_SESSIONS_OPEN` (count); 400 `POS_MACHINE_OPEN_SESSION` (name, cashier) |

The opening checks the cashier's own open session first (as before: "Cashier already has an open session"), then rules 1
and 2; a refusal opens nothing. The body of every refusal: `{error (English), reason (the code), code, params}`; the
front shows `serverErrors.<code>` in the language of the screen.

**Rule 3: one check in front of the selling endpoints, the sale code untouched.** `PosMachineSellingGuard`, a Spring MVC
`HandlerInterceptor` of the store (registered by `PosMachineWebConfig` on `/sales-header/**` and `/return-header/**`; a
head office never has it), runs after the JWT filter and before the controller. It refuses only when all hold: the
method is one of `RESTRICTED`; the drawer belongs to the POS machine; the logged user has an OPENED session; that session
has a POS machine; the request's address (normalised) is not that machine's **current** address (an address corrected on
the POS machines page counts at once). Otherwise the request goes on as before (no user read, no session: the endpoint
answers as it always did).

| Endpoint (method) | Restricted? |
|---|---|
| `POST /sales-header/process-sale` (`processSale`), `/complete-pending/{id}`, `/split-and-pay/{id}` | yes: selling |
| `POST /sales-header/save-pending`, `/update-pending/{id}`, `/cancel-pending/{id}`, `PATCH /{id}/table-number` (`transferTable`) | yes: a sale on hold (moving a held table ticket included) |
| `POST /return-header/process-return` (`processReturn`) | yes: a return |
| `POST /sales-header/{id}/change-payment-method`, `/{id}/prepare-invoice` | no: after the sale (admin) |
| the generic `POST`, `PUT /{id}`, `DELETE /{id}` of both controllers (`_BaseController`) | no: not used by the till |
| every read (`GET`), and all of `CashierSessionAPI` (open, close, verify...), RFID, badges | no |

The endpoint guard: `PosMachineSellingGuardTest.everyEndpointClassed` reads every mapped method of `SalesHeaderAPI` and
`ReturnHeaderAPI` but the reads; each must be in `RESTRICTED` or `NOT_RESTRICTED` (not both), and every name classed must
be an endpoint, so a new endpoint is classed when it is written. The classes are by method name, not by path: a path
changed keeps its class.

**The till** (`GET /cashier-session/current/pos-machine`, `TillPosMachine`: `drawerOwner`, `address`, `posMachineName`
of the open session, `blocked`): the POS screen, the payment screen and the return screen ask it when they open
(`services/posMachines.js` `loadTillMachineRefusal`). Blocked: a red line at the top of the POS screen (an alert on the
return screen, the reason under the pay button) says on which POS machine the session is; Paiement, Retours, "Retour aux
tables" with a ticket in progress, the hold button, split-and-pay, the pay button (and its Enter key) and the return
button are greyed. Closing the session stays available. The guard refuses anyway if the screen did not ask (an old
screen, a refresh). The session opening page shows a refusal of rules 1 and 2 in a red alert above the form.

**The admin selling through the POS button** (till mode) follows the same rules as any user: the guard and the opening
look at the user's session and the request's address, never at the role.

**Not supported with `POS_MACHINE`: a reverse proxy** in front of Tomcat. Every request would carry the proxy's address:
all sessions would be on the proxy's machine (rule 1 needs it registered) and rule 3 would never refuse. The POS machines
need the address Tomcat sees directly, fixed (a DHCP reservation); the POS is opened by the server's IPv4 address.

**Sessions opened before this step** have no POS machine; rule 3 never refuses a session without one. Rule 5 makes sure
none is open when a store chooses `POS_MACHINE`, so every session opened under the rules has its machine.

**Known limits.** Two openings on the same machine at the same instant could both pass rule 2 (the check and the save
are not one statement); one PC opens one session at a time, so it is not guarded further. `split-and-pay` puts the
split sale in the session of the table ticket (as before); the till only shows a cashier the tables of their own session.

## The day per POS machine (step 3)

**Only with the drawer of the POS machine** (`CASH_DRAWER_OWNER` = `POS_MACHINE`, read as it is now, not as it was on
that day: a day before the switch is grouped too, its sessions of before 3.2.0 under "—"; back to `CASHIER`, every day
as before). With `CASHIER` no screen, ticket or file changes and the store ticket's JSON is the one of before. Nothing at
the head office, on the sale receipt, in the amounts or in the closing.

**The store ticket grouped** (`StoreReportService`, `StoreReportTicketDTO.posMachines`, absent from the JSON otherwise):
the store ticket X or Z of the day (the sessions of "Store ticket X / Z", `cash-sessions.md`) gives its sessions grouped
per POS machine, `PosMachineGroup`: `posMachineId`, `posMachineName` (both null for the sessions without a machine, the
last group, "—"), `sessions`, and the blocks of the ticket over them (`sales`, `returns`, `netSales`, `payments`,
`openingCash`, `control`, `scannedTickets`, `modifiedAfterClosingCount` on a Z). The machines by name, the sessions by
opening. The groups are added up by the ticket's own code (`blocks`) from the session tickets already built, and the
machines read in one query (`SessionPosMachineService.machinesBySession`): no query per group; they add up to the
store's blocks, which are unchanged.

**The X / Z of one POS machine**: `GET /cashier-session/store-report-ticket/x?posMachineId=` and
`/z?date=&posMachineId=` (`buildX(id)`, `buildZ(date, id)`): the same ticket over that machine's sessions (the same date
rules), `posMachineId` and `posMachineName` in its header, no groups. 404 with the reason for an unknown machine ("No POS
machine number N.") or one without a session that day ("No cashier session of the POS machine 'X' closed on D: no ticket
Z."). It answers whatever the drawer (the page offers it only with `POS_MACHINE`).

**The Journal's day** (`StoreDay.vue`):
- "Sessions de la journée": one group per POS machine (name, number of sessions, its sessions, then its subtotal:
  Ventes (tickets, TTC, retours, net), Paiements per method, Contrôle (fond de caisse, attendu / compté / écart per
  method)); "—" for the sessions without one. The store's cards below (Ventes, Paiements, Contrôle) stay the total.
- Before the print buttons, a choice: "Tout le magasin" (the default, the ticket of before) or a POS machine that has a
  session in the ticket shown ("—" is not offered: under the rules every new session has a machine). A machine chosen,
  its X (today) and Z are loaded at once, so the print window opens on the click; the 80 mm ticket names the machine
  under the date (`printSessionReport.js`). The Z of one machine waits only for that machine's open sessions (I-19, by
  the machine's name on the X's session rows).
- The journal's A4 print (`printDayJournal.js`, `machineGroupsHtml`) adds, after the totals per method, the block "Par
  poste de caisse" (ticket X or Z of the day): per machine its sessions, a line (tickets, TTC, retours, net, fond de
  caisse) and a table per method (paiements, attendu, compté, écart), then "Total magasin". The journal's lines are
  unchanged.
- The day's Excel (`GET /cashier-session/day-journal/excel`, `JournalExcel`): a second sheet "Postes de caisse"
  (`StoreReportService.dayTicketByMachine`: the X on today, the Z on another day, as the page; none without a session or
  with `CASHIER`): three tables, the sessions per machine; the subtotals per machine (sessions, tickets, annulés, HT, TVA,
  timbre, TTC, retours, net, fond de caisse) and "Total magasin"; the payments and the control per machine and method
  (paiements, attendu, compté, écart, and for a scanned-tickets method the numbers of tickets) and the store's. The
  journal sheet is unchanged.

**Two date rules on one page.** The groups follow the store ticket (sessions by their closing); the journal's lines
follow their own date (`cash-sessions.md`, "Journal"). A session crossing midnight is whole in the groups of its closing
day and split in the journals: the block and the sheet are the page's subtotals, not the lines added up.

## The table and the rules

`pos_machine` (`store.posmachine.model.PosMachine`, extends `_BaseEntity`), unchanged since 3.1.0:

| Column | Type | Rule (`PosMachineService`) |
|---|---|---|
| `name` | varchar(100) NOT NULL | required, trimmed, at most 100 characters, unique in any case (`uk_pos_machine_name`) |
| `ip_address` | varchar(45) NOT NULL | the address of the machine's requests, normalised (below); unique (`uk_pos_machine_ip`) |
| `reader_id` | bigint NULL, `fk_pos_machine_reader` → `rfid_reader` | its RFID reader (RFID only, below) |
| `active` | bit | default true; with RFID an inactive machine cannot read |

**Where the table comes from.** `db/3.1.0/update.sql` created `rfid_reader` and `pos_machine` only on a database without
`ho_store`; a store database of the single WAR era has an empty `ho_store` (`pos_db_prod`, the Happyness stores,
`pos_franchise_customer` on the dev PC): there Hibernate (`ddl-auto=update`) made the two tables at a start, without
`ix_pos_machine_reader`. `db/3.2.0/update.sql` (step 13) creates them on a store (a database with `quick_quantity`) when
missing, as 3.1.0 did, and the index where it is missing. Hibernate also creates them at a start, as for every entity of
the store WAR. `rfid_reader` stays empty without RFID.

**The address of a POS machine** (`PosMachineAddresses.normalise`): the back end recognises a machine by the address of
its request (`request.getRemoteAddr()`: the frontend calls `/zsretailws/` on the same Tomcat, nothing in between), kept in
one form: every loopback (`127.x.x.x`, `::1`, Tomcat's `0:0:0:0:0:0:0:1`) is `127.0.0.1` (the browser on the PC of the
back end); `::ffff:192.168.1.5` is `192.168.1.5`; an IPv6 address loses its zone and is written in Java's full form. A
name is refused, never looked up. Every machine needs a **fixed address** (a DHCP reservation or a static address), and
the POS is opened by the server's IPv4 address, not its name (by name Windows may use an IPv6 address that changes). A
reverse proxy in front of Tomcat would give every machine the proxy's address: not supported. `RfidAddresses` (RFID)
gives the same answers with its own exception.

**Refusals** (`PosMachineException`): `INVALID` 400 (a name missing, too long or taken, not an IP address, an address
taken, a reader that does not exist; step 2: a machine with sessions deleted), `NOT_FOUND` 404 (`No POS machine number
N.`); the body `{"error", "reason"}` as the RFID endpoints, the same messages as in 3.1.0, plus `code` and `params` since
step 2 (above).

## The reader: RFID's

`PosMachineService` takes the RFID reader repository as `Optional<RfidReaderRepository>`: it exists only with
`rfid.enabled=true` (`@ConditionalOnInstallation(Installation.RFID)`). The POS machines need no RFID bean.

- **RFID on**: as in 3.1.0. The reader is sent as `{"reader": {"id": n}}` or null; an id that does not exist is refused
  (`No RFID reader number 42.`).
- **RFID off**: a reader sent is **ignored** (decision of 2026-11-13, step 1): a new machine has none, a machine saved
  again keeps the one it has. So a store that turned RFID off loses no link by renaming a machine, and nothing outside
  RFID can link a reader. It is not refused, so an old screen that sends `reader: null` still saves.
- A reader still linked to machines is not deleted (`RfidReaderService`, RFID only).

## The endpoints (store, logged users; none in `SecurityConfig`'s `permitAll`)

`PosMachineAPI` (`store.posmachine.controller`), on every store:

| Method and path | Answer |
|---|---|
| `GET /pos-machine` | every POS machine (`id`, `name`, `ipAddress`, `reader` (the whole reader or null), `active`, ...) |
| `GET /pos-machine/{id}` | one machine; 404 when none |
| `POST /pos-machine` | 201 and the machine saved (address normalised); 400 |
| `PUT /pos-machine/{id}` | 200 and the machine saved; 400; 404 |
| `DELETE /pos-machine/{id}` | 204; 404 when none; 400 `POS_MACHINE_HAS_SESSIONS` (step 2) |
| `PUT /pos-machine/{id}` with `active: false` | step 2 B, the drawer of the POS machine: 400 `POS_MACHINE_OPEN_SESSION` while it has an open session |
| `GET /pos-machine/my-machine` | `MyPosMachine`: `address` (the request's, normalised), `posMachineId`, `posMachineName`, `posMachineActive` (null when no machine has that address) |

Plus the generic reads of `_BaseController` (`/findByField`, `/{id}/exists`, `/count`). `GET /pos-machine/my-machine` is
"which address do you see for me, and which POS machine is it": the "Add this machine" button now, the session and the
ticket at step 2. `GET /rfid/my-machine` (RFID: the reader and whether it can read) is unchanged and exists only with
RFID.

## Permission

The page keeps the key of 3.1.0, `read:admin-rfid-pos-machines` (decision of 2026-11-13: no migration of the roles; the
name is only a key). `ZZDataInitializer.POS_MACHINES_ADMIN_PERMISSIONS`: in ADMIN's set of a new store database and given
to ADMIN at every start of a store (`addMissingPermissions`: a database of before gets it once; an RFID store's ADMIN has
it already and is not saved again). Never on a head office. RESPONSIBLE and the other roles: ticked on the Roles page,
which lists it on every store (group "Vente", "Postes de caisse"); `AppRoleAPI` lists it apart from the RFID keys. As
everywhere, the API does not check roles: the permission drives the menu and the route guard. Test:
`ZZDataInitializerRolesTest.posMachinesPageGivenToAdmin`.

## The page (ZSRetail-Front)

Route `admin-pos-machines`, path `/admin/pos-machines` (the 3.1.0 path `/admin/rfid/pos-machines` sends to it), view
`views/admin/session/PosMachines.vue`, permission `read:admin-rfid-pos-machines`. Menu **Caisse**
(`admin.cashRegisterMenu`), after Sessions; on every store (not in `RFID_ROUTES` any more: the router guard, the menu
and the search bar agree); a head office never (its own menu; a store route there goes to the head office home).

- The admin list page (gradient banner, the list with name, IP address, active, and the reader column only with
  `rfidEnabled`).
- **This PC** above the list (`GET /pos-machine/my-machine`; with RFID also `/rfid/my-machine` for the reader and
  "Ready to read"): the address seen, its machine, **Add this machine** (when no machine has that address) opens the
  form with the address filled in. From the dev server the address is `127.0.0.1`.
- The add / edit popup: wide, centred, sections (`payment-method-modal.scss`, `.pm-modal`): "POS machine" (name, IP
  address, active) and, with `rfidEnabled` only, "RFID" (the reader among the active ones). Field checks as in 3.1.0
  (vee-validate, `Ipv4Input`); the back end's refusal shown above the fields.
- Labels `admin.posMachines.*` in fr, en, ar (moved from `admin.rfid.machines.*`).

## Tests

`PosMachineServiceTest` (with and without RFID), `PosMachineAPITest` (standalone `MockMvc`, with and without RFID,
`/my-machine`), `PosMachineAddressesTest`; `RfidBeansTest.posMachinesWithoutRfid` (the service and the API exist RFID off
or on and ask no installation question); the RFID tests unchanged (`RfidAPITest`, `RfidReadServiceTest`,
`RfidReaderServiceTest`, `RfidAddressesTest`...). Proof record `installation-beans.txt`: `PosMachineAPI` and
`PosMachineService` are no longer decided by a question (`decided 110`, `store, RFID on | 12`); no other line changed.

Step 2, part A: `CashierSessionPosMachineTest` (opened from a registered machine, from an address not registered, from an
inactive machine; the names of a page), `PosMachineServiceTest.codes` and `.deleteWithSessions`,
`PosMachineAPITest.deleteWithSessions` (and the codes in the bodies); `CashierSessionHeadOfficeTest` opens with an
address. `InMemoryRfid` holds the links in memory (`sessionMachines`, `session(...)`).

Step 2, part B: `CashierSessionPosMachineTest` (the drawer of the cashier refuses nothing; rule 1 unregistered and
inactive, rule 2 with the cashier named and free again once closed, the cashier's own session first, the till's
question), `PosMachineSellingGuardTest` (the endpoint guard; the drawer of the cashier; the same machine; another machine:
every restricted endpoint 409 with its body, the not restricted ones pass; the address corrected; a session without a
machine, a user without a session, an admin; rule 4: closing and verifying outside the guard, its paths),
`PosMachineServiceTest.deactivateWithOpenSession` (rule 5), `CashDrawerSettingCheckTest` (rule 5, the setting read),
`GeneralSetupCodedRefusalTest` (core).

Step 3: `StoreReportServiceTest` (`cashierUnchanged`: no group and no machine in the JSON, the Excel's journal alone;
`groupsAndSubtotals`: two machines and "—", each group's figures, the groups adding up to the store; `zOfOneMachine`,
`xOfOneMachine` (closed today and open now, counted null); `machineWithoutSession`: the 404s; `excelPerMachine`: the sheet
and its subtotal rows, the X today and the Z another day).
