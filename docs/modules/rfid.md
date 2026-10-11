# RFID

**Status**: release 3.1.0. A store reads the RFID tags of its pieces with Hopeland readers (HZ100, TCP/IP) on its
network, through the vendor's Java SDK. First customer: ZEN (franchise stores, clothes).

**Store application only**: the head office never has RFID. No tag information goes to an ERP: everything is in the
store's database.

| Part | State |
|---|---|
| Back end: the switch, the readers and POS machines, the Hopeland reader, the reads, the endpoints | done (RFID steps 1 to 9) |
| Front: the RFID readers page, the POS machines page with "Add this machine" | next |
| Later: the tags table (one tag = one piece, in stock / sold, linked to an item), the tag assignment screen and its button on the reception lines (`hol_delivery_line`), the RFID button of the POS (`ItemSelection.vue`), the sale and the return updating the tag status | not started |

**Package map** (store project, `com.digithink.zsretail.store.rfid`): `model` (`RfidReader`, `PosMachine`), `repository`,
`service` (`RfidReaderService`, `PosMachineService`, `RfidReadService`, `RfidAddresses`), `reader` (`RfidReaderDevice`,
`HopelandRfidReader`, `RfidTag`, `RfidTagCollector`), `controller` (`RfidReaderAPI`, `PosMachineAPI`, `RfidAPI`,
`RfidErrors`), `dto`, `RfidException`. In core: the key and the question (`NodeOwnership`, `Installation.RFID`),
`GET /config` `rfidEnabled`, the summary word.

## Turning RFID on in a store

1. The store runs 3.1.0 (`db/3.1.0/update.sql` run: the two tables).
2. Give every POS machine that will read a **fixed address** (a DHCP reservation on the store's router, or a static
   address), and the reader too. The back end (its Tomcat) must reach the reader's ip:port on the store's network.
3. In the store's outside file: `rfid.enabled=true`; restart. The startup summary says `RFID on`.
4. On the RFID readers page: add the reader (name, address, port 9090 on the HZ100, power, reading time), Test
   connection, Test read with tags in front of it. Close the vendor's tool first: the reader takes one connection.
5. On each POS machine, open the POS by the server's IPv4 address, then the POS machines page: "Add this machine"
   (`GET /rfid/my-machine` gives the address the back end sees), choose its reader.

## The switch: `rfid.enabled`

| Key | Values | When absent |
|---|---|---|
| `rfid.enabled` | `true` \| `false`, trimmed, any case | `false` |

- The store type file states `rfid.enabled=false`; an installation turns it on in its outside file
  (`configs/machine-model.properties` shows the line, commented).
- Any other value (blank included) stops the startup: `Invalid value '<v>' for property rfid.enabled: true (this store
  reads RFID tags) or false (no RFID)`. `true` on a head office stops it too: `... a head office reads no RFID tags (the
  readers belong to a store). Remove the line.` `false` on a head office is accepted.
- Read by `NodeOwnership.isRfidSet` (core), checked in `NodeOwnership.resolve` like the other keys.
- `true` gives the installation question `Installation.RFID`. Every RFID bean (the services, the Hopeland reader, the
  three controllers, the two repositories) carries `@ConditionalOnInstallation(Installation.RFID)`: with `false`, none
  of them exists (the endpoints answer 404), and
  `InstallationBeansProofTest` shows it in every file of `configs/` (its shape `store, RFID on` lists them).
- `GET /config` field `rfidEnabled` (last); the frontend shows the RFID pages and buttons only when it is `true`.
- Startup summary: `RFID on` (after the supply words, before the ERP connector) only when on; nothing when off, so an
  EMTOP or Happyness store logs the same line as before.
- What exists whatever the switch: the two tables (below; `ddl-auto=update` creates a table for every entity of the
  WAR, like the `hol_*` tables) stay empty, and the SDK jar is in the store WAR (no class of it is loaded while off).

Tests: `RfidKeyTest` (network-tests), `AppConfigAPITest` (the field), the two proofs.

## Readers and POS machines (RFID step 3)

Package `com.digithink.zsretail.store.rfid` (store project): `model`, `repository`, `service`. Both entities extend
`_BaseEntity` (`id`, `active`, `created_*`, `updated_*`). Created by `db/3.1.0/update.sql` on a store database only (a
database with `ho_store` is a head office), and by `ddl-auto` with the same names and types.

`rfid_reader` (`RfidReader`):

| Column | Type | Rule (`RfidReaderService`) |
|---|---|---|
| `name` | varchar(100) NOT NULL | required, trimmed, at most 100 characters, unique in any case (`uk_rfid_reader_name`) |
| `ip_address` | varchar(45) NOT NULL | an IPv4 address, four numbers 0 to 255 without leading zeros; never a name |
| `port` | int NOT NULL | 1 to 65535; the address and port pair unique (`uk_rfid_reader_address`) |
| `power` | int NOT NULL | 0 to 30 (the vendor's limit for the HZ100 is not known yet), default 30 |
| `read_time_ms` | int NOT NULL | 100 to 10000, default 1500 |
| `active` | bit | an inactive reader is never read; default true |

A reader still linked to a POS machine is not deleted: `This reader is used by the POS machines A, B: give them another
reader, or none, first.` Antenna 1 is a constant of the reading, not a column.

`pos_machine` (`PosMachine`):

| Column | Type | Rule (`PosMachineService`) |
|---|---|---|
| `name` | varchar(100) NOT NULL | required, trimmed, at most 100 characters, unique in any case (`uk_pos_machine_name`) |
| `ip_address` | varchar(45) NOT NULL | the address of the machine's requests, normalised; unique (`uk_pos_machine_ip`) |
| `reader_id` | bigint NULL, `fk_pos_machine_reader` | its reader, which must exist; none: the machine cannot read yet |
| `active` | bit | an inactive machine cannot read; default true |

**The address of a POS machine** (`RfidAddresses.normalise`): the back end recognises a POS machine by the address of
its request (`request.getRemoteAddr()`: the frontend calls `/zsretailws/` on the same Tomcat, nothing in between), kept
in one form so that a machine always gives the same text: every loopback (`127.x.x.x`, `::1`, Tomcat's
`0:0:0:0:0:0:0:1`) is `127.0.0.1` (the browser on the PC of the back end); `::ffff:192.168.1.5` is `192.168.1.5`; an
IPv6 address loses its zone (`fe80::1%12`) and is written in Java's full form (`fe80:0:0:0:0:0:0:1`). A name is refused,
never looked up.

**Fixed addresses (installation rule)**: every POS machine that reads RFID needs a fixed address, a DHCP reservation on
the store's router or a static address. If two machines swap addresses, each one reads the other's reader without any
error. Open the POS by the server's IPv4 address (`http://192.168.x.y:...`), not by its name: by name, Windows may use
an IPv6 address that changes. Every read result names the POS machine and the reader, so a cashier sees a wrong link.
A reverse proxy in front of Tomcat would give every machine the proxy's address: not supported.

Every refusal is a `RfidException` with a reason (`INVALID`, `NOT_FOUND`, `NOT_READY`, `BUSY`, `UNREACHABLE`,
`READER_ERROR`) and its message; the endpoints (step 7) turn the reason into the HTTP status.

Tests: `RfidAddressesTest`, `RfidReaderServiceTest`, `PosMachineServiceTest` (over `store.support.InMemoryRfid`),
`RfidBeansTest` (no service and no repository without `rfid.enabled=true`: Spring Data's repository scan evaluates the
condition too).

## Reading (RFID step 4)

`store.rfid.reader.RfidReaderDevice`, what the back end asks of a reader, antenna 1 always:
- `testConnection(reader)`: connect, ask the reader's information, disconnect;
- `read(reader, millis)`: connect, set the power when it differs, read for the time, stop, disconnect: every tag once
  (`RfidTag`: EPC, best signal in dB, times seen; `RfidTagCollector` merges the many reports of a tag, from any thread).

Failures are `RfidException` `UNREACHABLE` or `READER_ERROR`. Two implementations: the Hopeland one (step 5, the only
class that uses the SDK) and `store.support.FakeRfidReader` in the store's test sources (tag reports per address, an
unreachable or failing address, a read held open). No key chooses between them: production has only the Hopeland one.

`RfidReadService` (conditional, like every RFID bean):

| Call | What | Refusals |
|---|---|---|
| `testConnection(readerId)` | the reader answers: `RfidConnectionResult` (reader, ip:port, its information, duration); allowed on an inactive reader | `NOT_FOUND` |
| `testRead(readerId)` | a read for the reader's reading time: `RfidReadResult` without a POS machine | `NOT_FOUND`; `NOT_READY` inactive reader |
| `readForRequest(remoteAddress)` | a read with the reader of the POS machine whose address the request comes from | below |

`RfidReadResult`: `posMachineName` (null for a test read), `readerName`, `readTimeMs`, `durationMs`, `tags`. Every read
names the reader, and the POS machine when there is one.

Refusals of `readForRequest`, the reader never called:
- `NOT_FOUND`: `No POS machine has the address 192.168.1.99: add this machine on the RFID POS machines page (it needs a
  fixed address).` (the address as normalised: `127.0.0.1` for the PC of the back end);
- `NOT_READY`: `The POS machine 'X' is inactive.`, `The POS machine 'X' has no RFID reader.`, `The RFID reader 'R' of the
  POS machine 'X' is inactive.`

**One request at a time per reader**: a lock per reader id, taken without waiting; a second request for a busy reader
answers at once `BUSY` (`The RFID reader 'R' is busy with another read: try again in a moment.`), never queues. Two
readers read at the same time. The lock is freed whatever happens (a failure included).

Tests: `RfidTagCollectorTest`, `RfidReadServiceTest` (with `FakeRfidReader`), `RfidBeansTest`.

## The Hopeland reader (RFID step 5)

`store.rfid.reader.HopelandRfidReader`, the `RfidReaderDevice` of the WAR (conditional), the only class that uses the
SDK (`com.rfidread`):
- **TCP check first**: a plain TCP connection to ip:port, 2000 ms at most, closed at once. No answer: `UNREACHABLE`
  (`The RFID reader 'R' does not answer at ip:port (no connection within 2 s): is it on, and on this network?`) without
  calling the SDK, so no 5 s wait and no SDK stack trace in the log. The reader accepts the SDK's connection right after.
- **A connection for each request**, closed in a `finally`. The SDK keeps its connections in one static map that is not
  thread-safe: every connect and close holds one lock for the whole SDK; the reads themselves run in parallel, one per
  reader (`RfidReadService`). The SDK refusing the connection (another program connected to the reader, such as the
  vendor's tool): `UNREACHABLE` `... answers at ip:port but refuses the connection ...`.
- **A read**: stop, set the power of antenna 1 only when it differs from the reader's (`GetANTPowerParam`, then
  `SetANTPowerParam`: a change may be written to the reader's memory), `GetEPC` on antenna 1 (`Inventory`), wait the
  reading time, stop, wait 300 ms for the last reports, close. One INFO line per read (reader, tags, time).
- **Shutdown**: every connection still open is closed (`@PreDestroy`).

Tests: `HopelandRfidReaderTest` (no reader: a closed port is `UNREACHABLE` at once and the SDK is never called; an
address that never answers is `UNREACHABLE` within the TCP check). **Manual test, out of the normal suite**,
`HopelandRfidReaderManualTest`, runs only with the reader's address:

```
mvn test -pl store -am -Dtest=HopelandRfidReaderManualTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Drfid.reader=192.168.100.200:9090 [-Drfid.reader.power=30] [-Drfid.reader.absent=192.168.100.201:9090]
```

(with the JDK and settings of the root `CLAUDE.md`). Result on the dev desk reader, 2026-10-11, 15 tags in front of
it: test connection 83 ms (`V5.4.0.1|Ex10APN32G4FR build: 23110711`); two reads of 1500 ms, 15 tags each, 1853 ms
each; the absent address `UNREACHABLE` after 2016 ms.

## The address I see for you (RFID step 6)

`GET /rfid/my-machine` (`RfidAPI`, logged users): `RfidMachineStatus` = `address` (the request's address, normalised:
`127.0.0.1` for the browser on the PC of the back end), `posMachineId` and `posMachineName` (the machine recorded at that
address, null when none), `readerName` (its reader, null when none), `canRead`, and `message` (why it cannot read: the
refusal a read would give, null when it can). The reader is never called. Used by the "Add this machine" button (the
address to record) and the POS (show the RFID button only when `canRead`).

`RfidReadService.machineStatus` and `readForRequest` share one check (`readyMachine`): the same address, the same
messages.

**Refusals of every RFID endpoint** (`RfidErrors`): `{"error": "<message>", "reason": "<reason>"}`, status by reason:
`INVALID` 400, `NOT_FOUND` 404, `NOT_READY` 409, `BUSY` 409, `READER_ERROR` 502, `UNREACHABLE` 503. The project's other
endpoints answer `{"error"}` too; `reason` is added so the frontend can tell a busy reader from a wrong link.

The endpoints answer JSON to a request that asks for it (`Accept: application/json`, as axios sends); `jackson-dataformat-xml`
is in the WAR, so a request with no `Accept` header may get XML, as for the other endpoints.

Tests: `RfidAPITest` (Spring MVC over the controller, standalone `MockMvc`, the remote address set as Tomcat gives it).

## The endpoints (RFID step 7)

All exist only with `rfid.enabled=true` (otherwise 404), for logged users (none is in `SecurityConfig`'s `permitAll`).
Under the context path: `/zsretailws/...` in Tomcat, `/zsretail/api/...` from the IDE. Refusals: `{"error", "reason"}`
(above).

| Method and path | Answer |
|---|---|
| `GET /rfid-reader` | every reader (`id`, `name`, `ipAddress`, `port`, `power`, `readTimeMs`, `active`, `created*`, `updated*`), last changed first |
| `GET /rfid-reader/{id}` | one reader; 404 when none |
| `POST /rfid-reader` | 201 and the reader saved, defaults filled in; 400 a broken rule |
| `PUT /rfid-reader/{id}` | 200 and the reader saved; 400 a broken rule; 404 when none |
| `DELETE /rfid-reader/{id}` | 204; 400 while POS machines read with it (named); 404 when none |
| `POST /rfid-reader/{id}/test-connection` | `RfidConnectionResult`: `readerName`, `address` (ip:port), `information`, `durationMs`; 404, 409 busy, 503, 502 |
| `POST /rfid-reader/{id}/test-read` | `RfidReadResult` with `posMachineName` null; 404, 409 inactive or busy, 503, 502 |
| `GET /pos-machine` | every POS machine (`id`, `name`, `ipAddress`, `reader` (the whole reader or null), `active`, ...) |
| `GET /pos-machine/{id}` | one machine; 404 when none |
| `POST /pos-machine` | 201 and the machine saved (address normalised; the reader sent as `{"reader": {"id": n}}` or null); 400 |
| `PUT /pos-machine/{id}` | 200 and the machine saved; 400; 404 |
| `DELETE /pos-machine/{id}` | 204; 404 when none |
| `GET /rfid/my-machine` | `RfidMachineStatus` (step 6): the address seen, the machine there, its reader, `canRead`, `message` |
| `POST /rfid/read` | `RfidReadResult` with the reader of my machine: `posMachineName`, `readerName`, `readTimeMs`, `durationMs`, `tags` [`epc`, `bestRssi`, `seen`]; 404 no machine at my address (named), 409 not ready or busy, 503, 502 |

The generic reads of `_BaseController` also exist on both CRUD paths (`/findByField`, `/{id}/exists`, `/count`).
Create, update and delete are the controllers' own (a broken rule answers 400 or 404 with its message, not the generic
500).

**Guard**: `RfidSdkConfinementGuardTest` (network-tests): `com.rfidread` is named by `HopelandRfidReader` only, in the
main code of every project, and only the store's pom takes `rfid-reader-api`.

Tests: `RfidReaderAPITest`, `PosMachineAPITest`, `RfidAPITest` (standalone `MockMvc`, `RfidMvc`).

## Permissions (the front pages)

Two role permissions, one per page: `read:admin-rfid-readers` (RFID readers) and `read:admin-rfid-pos-machines` (POS
machines). Like the Licence page, ADMIN gets them without anyone ticking them (`ZZDataInitializer`,
`RFID_ADMIN_PERMISSIONS`), but only on a store with `rfid.enabled=true`: in ADMIN's set of a new database, and given to
ADMIN at the first start with RFID on for a database of before (once; nothing else added or removed). With RFID off
nothing is added, so an EMTOP or Happyness role table does not change. The Roles page lists them (group RFID) only when
`rfidEnabled`; `AppRoleAPI` lists them. As everywhere, the API does not check roles: the permissions drive the menu and
the route guard. Test: `ZZDataInitializerRolesTest.rfidPagesGivenToAdmin`.

## The SDK

`com.hopeland:rfid-reader-api:2.11-zs1`, a cleaned copy of the vendor's jar in the file repository `libs/maven`, taken by
the store pom only. Why cleaned, the hashes and how to make it again: `libs/hopeland/README.md`.

Measured on the dev desk reader (RFID step 0, 2026-10-11): connect 10 to 16 ms; a read of 1500 ms takes about 1850 ms
in all (connect, stop, start, 1500 ms, stop, 300 ms for the last tags, close); 15 tags every time; a connect to an
address with no reader fails after 5 s (the SDK's own timeout, with a stack trace on the standard error).

## Proofs and tests (RFID step 8)

- `ConfigurationKeysProofTest`: the record gained the key `rfid.enabled` (`keys 52`; `false` in the 14 store
  configurations, `(none)` in the 6 head office ones); no other line changed.
- `InstallationBeansProofTest`: the 7 RFID beans are decided by `Installation.RFID` (`decided 90`), present only in the
  shape `store, RFID on`; every file of `configs/` and every other shape has none of them (each row unchanged).
- `RfidSdkConfinementGuardTest`, and the existing guards (`ProjectSortingGuardTest`: every RFID class under the store's
  package root).
- Store tests: `HopelandSdkJarTest`, `RfidBeansTest`, `RfidAddressesTest`, `RfidReaderServiceTest`,
  `PosMachineServiceTest`, `RfidTagCollectorTest`, `RfidReadServiceTest`, `HopelandRfidReaderTest`, `RfidAPITest`,
  `RfidReaderAPITest`, `PosMachineAPITest`; network-tests: `RfidKeyTest`; core: `AppConfigAPITest` (`rfidEnabled`).
  Test helpers in `store.support`: `InMemoryRfid` (the two tables), `FakeRfidReader`.
- `HopelandRfidReaderManualTest`: the real reader, only with `-Drfid.reader=ip:port` (step 5 above); skipped otherwise.

Full suite on 2026-10-11 after step 7: 974 tests, 0 failures, 2 skipped (the two live tests that need hardware).
