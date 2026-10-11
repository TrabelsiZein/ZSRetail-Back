# RFID

**Status**: release 3.1.0, being built (back end steps 1 to 4 done). A store reads the RFID tags of its pieces with
Hopeland readers (HZ100, TCP/IP) on its network, through the vendor's Java SDK. First customer: ZEN (franchise stores,
clothes).

**Store application only**: the head office never has RFID. No tag information goes to an ERP: everything is in the
store's database.

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
- `true` gives the installation question `Installation.RFID`. Every RFID bean (services, the Hopeland reader, later the
  endpoints) carries `@ConditionalOnInstallation(Installation.RFID)`: with `false`, none of them exists, and
  `InstallationBeansProofTest` shows it in every file of `configs/` (its shape `store, RFID on` lists them).
- `GET /config` field `rfidEnabled` (last); the frontend shows the RFID pages and buttons only when it is `true`.
- Startup summary: `RFID on` (after the supply words, before the ERP connector) only when on; nothing when off, so an
  EMTOP or Happyness store logs the same line as before.
- What exists whatever the switch: the two tables (below; `ddl-auto=update` creates a table for every entity of the
  WAR, like the `hol_*` tables) stay empty, and the SDK jar is in the store WAR (no class of it is loaded while off).

Tests: `RfidKeyTest` (network-tests), `AppConfigAPITest` (the field), the two proofs.

## The SDK

`com.hopeland:rfid-reader-api:2.11-zs1`, a cleaned copy of the vendor's jar in the file repository `libs/maven`, taken by
the store pom only. Why cleaned, the hashes and how to make it again: `libs/hopeland/README.md`.

Measured on the dev desk reader (RFID step 0, 2026-10-11): connect 10 to 16 ms; a read of 1500 ms takes about 1850 ms
in all (connect, stop, start, 1500 ms, stop, 300 ms for the last tags, close); 15 tags every time; a connect to an
address with no reader fails after 5 s (the SDK's own timeout, with a stack trace on the standard error).
