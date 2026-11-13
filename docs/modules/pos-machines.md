# POS machines

**Status**: 3.2.0, step 1 of 5 (branch `release/3.2.0`). A POS machine ("poste de caisse", the physical PC a cashier
works on) declared with a name and the address the back end sees for it. It came with RFID in 3.1.0 (`rfid.md`), where
it links a PC to its reader; since 3.2.0 it exists on **every store**, with or without RFID. First customer asking for
it beyond RFID: ZEN.

**Store application only**: the head office has no POS machine (no table, no endpoint, no page, no permission).

| Step | What | State |
|---|---|---|
| 1 | POS machines on every store (this page) | done |
| 2 | The session and each ticket keep the POS machine they were made on | to come |
| 3 | Tickets X and Z per POS machine | to come |
| 4 | Optional controls in General setup (which user on which machine, declared machines only, one open session per machine) | to come |
| 5 | Cash taken out of the drawer | to come |

Step 1 changes no session, ticket, ticket X or Z, or POS screen: selling behaves as before.

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
taken, a reader that does not exist), `NOT_FOUND` 404 (`No POS machine number N.`); the body `{"error", "reason"}` as the
RFID endpoints, the same messages as in 3.1.0.

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
| `DELETE /pos-machine/{id}` | 204; 404 when none |
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
