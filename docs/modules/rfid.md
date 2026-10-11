# RFID

**Status**: release 3.1.0. A store reads the RFID tags of its pieces with Hopeland readers (HZ100, TCP/IP) on its
network, through the vendor's Java SDK. First customer: ZEN (franchise stores, clothes).

**Store application only**: the head office never has RFID. No tag information goes to an ERP: everything is in the
store's database.

| Part | State |
|---|---|
| Back end: the switch, the readers and POS machines, the Hopeland reader, the reads, the endpoints | done (RFID steps 1 to 9) |
| Front: the RFID menu, the Readers page, the POS machines page with "Add this machine" | done (below, "The front pages") |
| Tags: the table (one tag = one piece, in stock / sold, linked to an item), the Tags page, the Tag assignment page and its button on the reception lines | done ("Tags", "Tag assignment") |
| Later: the RFID button of the POS (`ItemSelection.vue`), the sale and the return updating the tag status (`sold_at`, `ticket_number`) | not started |

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
| `min_signal` | int NOT NULL, default -50 (`df_rfid_reader_min_signal`) | -90 to 0 dB: a tag whose best signal is below it is **weak** ("A tag below this signal is marked weak") |
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

`RfidReadResult`: `posMachineName` (null for a test read), `readerName`, `readTimeMs`, `durationMs`, `tags` (each with
`weak`), `minSignal`, `lowestRssi`, `highestRssi` (the lowest and highest best signal of the read, null without a tag:
to tune the reader's minimum signal). Every read names the reader, and the POS machine when there is one.

**Weak tags** (decision of 2026-10-11, replacing "10 dB below the best tag of the read", which marked 2 of 5 tags lying
on the reader, -23 -25 -25 -37 -44, as weak): a tag is weak when its best signal is below the reader's `min_signal`
(default -50; equal is not weak). One rule, `RfidReadService.isWeak`, applied to every read: the Test read shows it, the
assignment takes it. The 3.1.0 script adds the column to a `rfid_reader` made before it, -50 for the readers there.

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

## Tags (RFID tags step)

`rfid_tag` (`RfidTag`), one row per tag, one piece of an item of the store; created by `db/3.1.0/update.sql` on a store
database only, like the other two tables:

| Column | Type | Rule |
|---|---|---|
| `epc` | varchar(128) NOT NULL, `uk_rfid_tag_epc` | unique; kept trimmed, upper case, hexadecimal digits only, at most 128 (`RfidTagService.normaliseEpc`) |
| `item_id` | bigint NOT NULL, `fk_rfid_tag_item` → `item`, `ix_rfid_tag_item` | the item of the piece |
| `status` | varchar(10) NOT NULL, `ix_rfid_tag_status` | `IN_STOCK` (at its assignment) or `SOLD` (set by the sale, a later step) |
| `source_type` | varchar(10) NOT NULL | `MANUAL` (the Tag assignment page) or `RECEPTION` (a delivery reception line) |
| `source_document`, `source_line` | varchar(30), int NULL | the delivery number and line of a reception |
| `sold_at`, `ticket_number` | datetime2, varchar(100) NULL | filled by the sale (a later step): when, and `sales_header.sales_number` |

Plus the `_BaseEntity` columns (`created_by` is who assigned the tag).

**The Tags page** (`RfidTagService`, `RfidTagAPI`, `/rfid-tag`): a page of tags newest first with each tag's item, the
filters item, status, a part of the EPC (any case). **Move** a tag to another item: an explicit action, `IN_STOCK` tags
only (`SOLD`: 409 `The tag <epc> is sold: it is not moved.`). **Delete**: `IN_STOCK` tags only (decision of 2026-10-11:
a `SOLD` tag is the record of the piece sold: 409 `... it is kept as the record of the piece sold.`). A tag is never
created by these endpoints: only an assignment creates one.

**An item with tags is not deleted**: `DELETE /item/{id}` asks the core hook `ItemDeletionCheck` (any bean; none by
default); RFID's `RfidItemDeletionCheck` answers 409 `This item has N RFID tags: move them to another item or delete
them first (RFID, Tags page).`, instead of the foreign key error (decision of 2026-10-11). Only a local item can be
deleted on a store: a head office or ERP item is made inactive, never deleted. With RFID turned off after tags were
assigned, the check does not exist and the database's foreign key refuses the deletion (500 with its message, as for an
item used in sales).

Tests: `RfidTagServiceTest`, `RfidItemDeletionTest` (the real `ItemAPI`), `RfidTagAPITest`.

### Tag assignment (`RfidAssignmentService`)

**A pack holds no tag** (decision of 2026-10-11, RFID POS step): the POS sells a pack (`ItemType.PACKAGE`) as its pieces,
so the read, the save and a move to a pack are refused, INVALID `The item <code> is a pack: a pack holds no RFID tag. Link
the tags to the items of its pieces.` (`RfidTagService.notAPack`).

**Read** (`POST /rfid-tag/assignment/read {readerId, itemId, expectedQuantity}`): the item exists and is active; the
expected quantity none or 0 to 100000. The reader is read like a Test read (`RfidReadService.testRead`: the same
refusals, inactive 409, busy 409, unreachable 503, error 502). The tags seen, each with its best signal, times seen and
**weak** (its best signal below the reader's minimum signal: likely a piece further away), in four groups for the item:

| Group | The tag | On save |
|---|---|---|
| `newTags` (NEW) | unknown | linked to the item |
| `sameItem` (SAME_ITEM) | already on this item | left as it is |
| `otherItem` (OTHER_ITEM) | on another item (`otherItemId`, `otherItemCode`, `otherItemName`) | refused, never changed (the Tags page moves a tag) |
| `sold` (SOLD) | sold (with its item) | refused, never changed |

Plus `newCount`, `sameItemCount`, `otherItemCount`, `soldCount`, `expectedQuantity`, `bestRssi`, `minSignal`,
`readerName`, `readTimeMs`, `durationMs`. A read writes nothing.

**Save** (`POST /rfid-tag/assignment/save {itemId, epcs, sourceType, sourceDocument, sourceLine}`): checked again on the
server, whatever the read said: each EPC normalised and taken once; an unknown tag is created `IN_STOCK` on the item
(with the source, the user, the time); a tag already on the item is left as it is (`alreadyOnItem`: saving twice changes
nothing); a tag now on another item or sold, and a value that is not an EPC, are reported in `refused` (`reason`
`OTHER_ITEM`, `SOLD`, `INVALID`, with the item and a message), never changed. `sourceType` `MANUAL` (default: no
document) or `RECEPTION` (the delivery number, at most 30, and the line, both required, else 400). At most 5000 EPCs a
save. One save at a time in the store (a lock around one transaction), the unique EPC as the last guard.

**Reception**: each line of `GET /admin/deliveries/{id}` gives `itemId`, this store's item (null while the item is not
here, with `itemHere` false): the tag button of the line opens the assignment on that item, the source `RECEPTION` with
the delivery number and the line number (`DeliveryReceptionServiceTest`).

Tests: `RfidAssignmentServiceTest` (the groups, weak, the refusals, the save rules, saving twice, a tag linked elsewhere
between the read and the save, the reception source), `RfidTagAPITest`.

## The front pages (ZSRetail-Front)

A store menu **RFID** (after Inventory, icon Radio) with two pages, only when `GET /config` `rfidEnabled` is true
(never on a head office: its menu is its own, and the getter `appConfig/rfidEnabled` is false there).
`RFID_ROUTES` (`navigation/store-mode-routes.js`): the menu (`VerticalNavMenu`, the group removed when empty), the
router guard (home) and the search bar hide them, like the other store-mode pages.

| Route | Path | Page | Permission |
|---|---|---|---|
| `admin-rfid-readers` | `/admin/rfid/readers` | `views/admin/rfid/RfidReaders.vue` | `read:admin-rfid-readers` |
| `admin-rfid-pos-machines` | `/admin/rfid/pos-machines` | `views/admin/rfid/RfidPosMachines.vue` | `read:admin-rfid-pos-machines` |
| `admin-rfid-tags` | `/admin/rfid/tags` | `views/admin/rfid/RfidTags.vue` | `read:admin-rfid-tags` |
| `admin-rfid-assignment` | `/admin/rfid/assignment` | `views/admin/rfid/RfidTagAssignment.vue` | `read:admin-rfid-assignment` |

- **Readers**: the list (name, IP address, port, power, reading time, active), add / edit (a modal; the back end's
  message shown in it as it comes) / delete (a refusal in a toast, e.g. a reader still used by POS machines). Per
  reader: **Test connection** (a spinner, then under the buttons "Connected in N ms: <information>" in green, or the
  error in red) and **Test read** (`components/admin/rfid/RfidReadModal.vue`: reads at once, a spinner, then the tags,
  EPC, best signal, times seen, how many different tags and the duration, or the error; **Read again**). Test read is
  off for an inactive reader.
- **POS machines**: the list (name, IP address, its reader with its address), add / edit / delete, the reader chosen in
  a dropdown of the active readers (the machine's own reader stays in it when it became inactive). At the top, **This
  PC** from `GET /rfid/my-machine`: the address the back end sees, its POS machine and reader and "Ready to read", or
  why it cannot read; **Add this machine** (when no machine has that address) opens the add form with the address
  filled in. From the dev server the address is `127.0.0.1` (the browser calls the back end on the same PC).
- **Field checks in the two popups** (front d8947ec, vee-validate as the Roles page, `@validations`): each field while
  typing and on leaving it, the message under it in red, Save off while a field is wrong or a required one is empty; the
  back end's own refusal still shown above the fields. IP address: `components/inputs/Ipv4Input.vue` (reusable: digits
  and dots only, typed or pasted, four parts of at most 3 digits, the dot added after 3 digits, no fifth part) and the
  rule `ipv4` (four parts 0 to 255, no leading zeros); a POS machine is entered in IPv4 only. Port, power, reading time:
  digits only, rule `int-between` (1-65535, 0-30, 100-10000, "ms" at the right). Name: required, at most 100
  characters, trimmed. Messages `common.validation.*` in the five languages.
- Every request passes `skipErrorPopup` (`components/admin/rfid/rfid-api.js`, timeout 30 s): the page shows the back
  end's `{"error"}` itself, once.
- Labels: `admin.rfid.*` in en, fr, ar, de, pt (Reader / Lecteur / قارئ / Lesegerät / Leitor; POS machine / Poste de
  caisse / جهاز نقطة البيع / Kassenplatz / Posto de caixa; Tag / Étiquette / وسم / Tag / Etiqueta). The Roles page
  (fixed French labels, like its other groups): group RFID, "Lecteurs RFID", "Postes de caisse RFID", only when
  `rfidEnabled`.
- **Tags**: a page of tags (newest first) with the filters item (`components/inputs/ItemPicker.vue`), status, a part of
  the EPC; Move to another item (a popup with the item picker) and Delete, both off for a sold tag (tooltip: kept as the
  record of the piece sold).
- **Tag assignment** (`components/admin/rfid/RfidAssignmentPanel.vue`, the page and the reception popup
  `RfidAssignmentModal.vue`): the reader among the active ones; by default the one marked **Use as default on this PC**
  (`localStorage` key `zsretail.rfid.defaultReaderId`, read and written in try/catch), else the reader of this POS
  machine (`/rfid/my-machine`, matched by name), else none. The item by scanning a barcode or by search (`ItemPicker`:
  `/item/search` while typing; on Enter the exact barcode `/item-barcode/barcode/{code}` first, then the only result or
  the exact code). The expected quantity (optional). **Read**: the counts, NEW ticked except the weak ones (marked
  "weak", tooltip with the reader's minimum signal), OTHER_ITEM in red with their item, SAME_ITEM and SOLD shown; a warning when the
  ticked count differs from the expected quantity. **Save** ("Link N tags"): the ticked NEW tags, then what was linked,
  already on the item, and each refusal with the back end's message.
- **Delivery reception** (`views/admin/holink/DeliveryReception.vue`): on each item line of the four tables (to receive
  and received, BL and ERP invoice) a tag button that opens the assignment as a popup over the reception (the quantities
  being typed are kept): the item fixed, the expected quantity of the line (received, else typed, else sent; a whole
  number only), the source `RECEPTION` with the delivery number and the line. Off (tooltip) while the item is not in this
  store (`itemId` null); hidden without `rfidEnabled` or the Tag assignment permission. It never blocks or changes Receive.
- The POS screens (`src/views/pos`) do not read RFID yet.

## Permissions (the front pages)

Four role permissions, one per page: `read:admin-rfid-readers` (RFID readers), `read:admin-rfid-pos-machines` (POS
machines), `read:admin-rfid-tags` (Tags) and `read:admin-rfid-assignment` (Tag assignment, also the tag button of a
delivery reception line). Like the Licence page, ADMIN gets them without anyone ticking them (`ZZDataInitializer`,
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
