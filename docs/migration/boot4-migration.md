# Spring Boot 4 migration

Branch `feature/boot4-migration`, from `release/3.1.2`. Product version 4.0.0. Plan approved by Zein on 2026-11-08.

**Status: step 1.8 done. Next: step 1.9.** Each step starts only on Zein's go.

| | At the start (2026-11-08) | Target |
|---|---|---|
| Spring Boot | 2.3.9 | 4.0.x, then the latest 4.1.x (the first deployment is on 4.1) |
| Stops inside the branch, never deployed | | 2.7.18, then 3.5.16 (the compile checkpoint), then 4.0.x |
| Compile | JDK 21, target 11, no `--release` | JDK 25, `--release 21` |
| Runtime | not confirmed per site | JDK 25, external Tomcat 11, WAR |
| Stack | Spring 5.2, Hibernate 5.4, Jackson 2.11, Security 5.3 | Spring 7, Hibernate 7, Jackson 3, Security 7, Jakarta EE 11 |

## Working rules

- Every step compiles and the full suite is green before the next one. The two exceptions are stated in the tables.
- A step with skipped startup tests is **not green**. They skip when `zsretail_migtest` is unreachable, and the
  `MSSQLSERVER` service on the dev machine is Manual, so a stopped service would otherwise pass unnoticed. Every suite of
  a step runs with `-Dzsretail.migtest.required=true`, which turns that skip into a failure. The progress log says that
  they ran.
- Before every `mvn`: no `javaw` of ours running on `target/classes`. During a suite: no edit of any file in the repo
  (Eclipse rebuilds with its own JDK and the build then fails on the class version). Eclipse left open holds its JVM inside
  `eclipse.exe`, so no `javaw` shows, and its automatic build rewrites `target/classes` when files change or a branch
  switches: a test that then fails on "class path resource [...] does not exist", at a random class, is this. Let it
  settle and run from `clean` (seen on 2026-11-09: the class file's time was the failure's second).
- The progress log below is updated at the end of every step, with the commit hashes.
- `spring-boot-properties-migrator`, the classic starters and `spring-boot-jackson2` are migration aids only: none of
  them stays.

## Decisions

Taken by Zein on 2026-11-08, on the inventory and plan of the same day. Rows 4 and 13 to 15 are his second answers; rows
2 and 13 were changed, and rows 16 and 17 added, by his answers to the report of step 1.1; row 18 by his answers to the
report of step 1.2; rows 22 to 25 by his answers to the report of step 1.7.

**The schema rule, from 4.0.0: a schema change = one V script next to the Java change; never edit an applied script.**

| # | Decision | Step |
|---|---|---|
| 1 | Build on Boot 4.0.x as planned. A final step 2.9 bumps to the latest 4.1.x before any deployment. **The first deployment is on 4.1.** | 2.9 |
| 2 | Every customer gets JDK 25 and Tomcat 11 on its upgrade night, so the current JDK and Tomcat per site do not matter. Every tracked connection string and every customer config example gets `encrypt=true;trustServerCertificate=true`. **The minimum SQL Server for 4.0.0 is 2017: a startup check refuses to start below it, with a clear message** (this replaces the WARN below 2014). Zein verifies each site's version himself before its upgrade night. | 1.3, 1.4 |
| 3 | The head office and the stores of one customer upgrade together, the same night. No mixed-version tests; one line in the deploy guide. | 2.8 |
| 4 | `ddl-auto=validate` in every tracked config, store and head office. Schema changes ship only through V scripts (decision 13). Steps 1.8 and 2.2 produce, with `hibernate.hbm2ddl.scripts.action=update`, the catch-up SQL Hibernate would have run, reviewed, per database type: it becomes the `V4.0.0.x` scripts. Developer-local throwaway databases may keep `update`, documented. Enum CHECK constraints stay out through the review of the baseline and catch-up scripts, confirmed at 1.8. | 1.8, 2.2 |
| 5 | NTLM on HttpClient 5, registered explicitly in **one shared helper in erp-api**, used by all four connectors. Connect and read timeouts move to the connection manager with today's values: the scheduler-hang fix of 2026-10-22 must keep working. NTLM POST is proved live on ZEN's test NAV (`commandesVente`; orders were posted there on 2026-10-28), no fake server. Done at 1.7: `erp.api.ErpHttp`, `ErpHttpTest`; the live proof waits for 2.7. | 1.7, 2.7 |
| 6 | Startup tests are allowed, against a throwaway local SQL Server database named `zsretail_migtest` **only**, created and dropped by the tests. Never Store A, never a customer database, never an existing local database. Its connection details are in a test-only properties file. When it is unreachable the startup tests are skipped with a clear message, not failed. | 1.2 |
| 7, 8 | Removed: springdoc, springfox, spring-security-oauth2, jasperreports (with iText, castor, jfreechart), firebase-admin, jsoup, modelmapper, commons-io 1.4, starter-webflux, reactor-core, starter-mail, starter-thymeleaf (and the `/thymeleaf` entries in `SecurityConfig` and `LicenseFilter`), jackson-dataformat-xml. The lone `@NotBlank` on `ErpSyncJob` is deleted; starter-validation is not added. Nothing is planned for Firebase or Jasper. | 1.1 |
| 9 | Base branch `feature/boot4-migration` from `release/3.1.2`. Product version 4.0.0. Customer fixes continue on `release/3.1.2` and are merged into this branch at the end of each phase. A freeze applies only during step 1.7. | all |
| 10 | The 2.7.18 internal stop is approved. Build on JDK 25 with `--release 21`. Lombok goes in the pom at step 1.5, so `-Dlombok.version` disappears on this branch (1.18.48). The release branches keep the flag: their Lombok 1.18.18 fails on JDK 21. | 1.4, 1.5, 2.6 |
| 11 | The `spring.jackson.use-jackson2-defaults` decisions are taken at step 2.4. | 2.4 |
| 12 | The charset of the licence fingerprint is covered by a test at step 2.6. | 2.6 |
| 13 | **Flyway**, added at step 1.8. `db/migration/{common,store,headoffice}/V<version>__<description>.sql` in core resources (split approved: both WARs carry core, and Flyway refuses two scripts of one version in its locations), `spring.flyway.locations` in each type file, one script per change. `V4.0.0__baseline.sql` is the full schema, generated per database type at 1.8 (`scripts.action=create`, reviewed), frozen forever. The 4.0.0 catch-up deltas are separate `V4.0.0.x` scripts. `baseline-on-migrate`: an existing 3.1.2 database marks the baseline as applied on its first start. Flyway replaces the manual `update.sql` run and `AppVersionGuard`; the guard stays only for what Flyway cannot check. The `db/<version>` folders stay as history, untouched. | 1.8 |
| 14 | ZEN's test NAV (SRV-NAV) allows GET and POST. EMTOP's test NAV is GET only. Production NAV instances are never called, by tests or by Claude. In the root `CLAUDE.md`. | all |
| 15 | The pom says 4.0.0 from step 1.1, with the CHANGELOG section opened there. | 1.1 |
| 16 | POI is upgraded at step 1.4, for the jars of open point 8. Done: 5.5.1, the newest 5.x (6.0.0 not taken), with `commons-lang3` 3.18.0 above Boot's BOM, because commons-compress 1.28.0 calls classes that 3.12.0 does not have. | 1.4 |
| 17 | `commons-lang3` stays in the store at `runtime` scope, for the RFID SDK's USB path. | 1.1 |
| 18 | **The JWT secret becomes a per-installation line `security.jwt.secret`, required at startup**, delivered in step 2.8: `SecurityParams.SECRET` was one constant in the source for every installation. Every site is logged out on the upgrade night anyway. Nothing before 2.8. | 2.8 |
| 19 | **The security fixes of 2026-11-09 live on `release/3.1.2`, no hotfix branches.** The sites on 2.2.2, 3.1.0 and 3.1.1 get them by moving to 3.1.2. | all |
| 20 | A badly written address (not empty, not `http://` or `https://`, in any case) of `emtop`, `happyness` or `headoffice.url` gives one ERROR line at the start, naming the key. It refuses nothing. | 1.3b |
| 21 | **Before each site's upgrade night, a small read-only connection test**, with JDK 25 and the WAR's driver, showing `encrypt_option`. Written at 2.8, nothing before. **The two connection options go in once, on the upgrade night**: never on a site still on 3.1.2. | 2.8 |
| 22 | HttpClient 5's retries are accepted as they are (one retry of an idempotent request on an I/O error, one after a 429 or 503, no timeout retried; "2.7: the watch points"). | 1.7 |
| 23 | **Log lines keep Boot 2's date**, `2026-11-10 12:40:28.064`: `logging.pattern.dateformat` in `application.properties`, so a log reads the same before and after 4.0.0. Boot 3 wrote `2026-11-10T12:40:28.064+01:00`; the rest of the line did not change. | 1.8 |
| 24 | The trailing slash: `GET /config/` answering 404 since Spring 6 is accepted, no filter brings the old matching back. | 1.7 |
| 25 | **A new database comes out the same as an existing 3.1.2 database**, wherever the difference is more than an equivalent type: `promotion_group_item` keeps its primary key order, `uk_ho_down_change` stays a plain unique constraint (two rows with a NULL `store_id` still refused), no enum CHECK constraint (decision 4, open point 6). Done at 1.8 by taking each baseline from `release/3.1.2`'s own code, not from Hibernate 6.6: the same by construction, proved object by object ([step-1.8-schema](diffs/step-1.8-schema.txt)). | 1.8 |

## Steps

File counts are estimates from grep counts, not from a trial. Total: about 350 to 400 distinct files, well over half of
them one-line edits.

### Phase 1: to the Boot 3.5 compile checkpoint, never deployed

| # | Step | Boot | Files |
|---|---|---|---|
| 1.0 | Record the real baseline: suite per module, WAR jar lists, build command, Java and Maven versions. No code change. | 2.3.9 | 0 |
| 1.1 | Remove the unused list (decision 7, 8). Version 4.0.0 and its CHANGELOG section. Compare the WAR jars with the baseline. Pin the `Accept` header in the connector tests. | 2.3.9 | 26 |
| 1.2 | Safety nets: startup tests of both applications on `zsretail_migtest`, a status matrix on the real security chains, golden JSON of key DTOs and entities. | 2.3.9 | 17 new, 136 golden files |
| 1.3 | Edits the current API allows: `fromUriString`, `getStatusCode().value()`, company info fix, dead annotations. SQL Server version check: refuses below 2017. | 2.3.9 | 42 (17 are tests' one-line edits) |
| 1.4 | Boot 2.7.18: config-data tests, naming strategy, H2 2.1, logging keys, bean cycles, connection strings. POI upgrade. | 2.7.18 | 101 (54 tests and 3 new, 19 configs, 10 goldens, 5 main classes, 2 poms, docs) |
| 1.5 | Java 21: `release=21`, Lombok in the pom, processor paths, `proc=full`. | 2.7.18 | 7, and the root `CLAUDE.md` |
| 1.6 | `SecurityFilterChain` and lambda DSL on Security 5.8. ERROR and FORWARD dispatches permitted on the main chain (no such dispatch reaches `/ho/**`). | 2.7.18 | 12 (2 main classes, 5 tests and 1 new, the parent pom, 3 docs) |
| 1.7 | **Freeze.** Boot 3.5.16: jakarta rename, Hibernate 6.6, Security 6.5, HttpClient 5 with the erp-api helper and the timeouts, dialect keys, `WarLauncher`. | 3.5.16 | 281: 177 renamed (commit 1), 104 in the fixes (commit 2) |
| 1.8 | Behaviour the tests expose (HQL, casts, null parameters). Flyway: baseline per database type, catch-up `V4.0.0.x` scripts, `baseline-on-migrate`, `AppVersionGuard` out. `ddl-auto=validate`. Enum CHECK constraints confirmed. | 3.5.16 | 48 (4 scripts, 3 property files, 13 configs, 3 main classes, a pom, 11 tests and helpers, 4 records, 9 docs) |
| 1.9 | Remove every deprecated call, run the properties migrator once, tag the checkpoint. Merge `release/3.1.2`. | 3.5.16 | 10–20 |

Exception to "green at each step": 1.7 is two commits (mechanical rename, then fixes) and compiles only after both.

### Phase 2: to Boot 4

| # | Step | Boot | Files |
|---|---|---|---|
| 2.1 | Boot 4.0.x with the classic starters and `spring-boot-jackson2`, so Jackson stays on 2: Spring 7, Security 7, Hibernate 7.2, JUnit 6, Tomcat 11 in tests, post-processor move, explicit `starter-aspectj`, JPA 3.2 criteria types. | 4.0.x | 30–50 |
| 2.2 | Hibernate 7 behaviour. Catch-up SQL produced again, per database type, compared with the `V4.0.0.x` scripts. | 4.0.x | 5–20 |
| 2.3 | Jackson 3, main and tests: mappers through builders, the 4 deserializers, the exceptions. Guard test banning the old imports. `spring-boot-jackson2` removed. | 4.0.x | ~100 |
| 2.4 | Compare the golden JSON and decide which Jackson 2 defaults stay (decision 11). | 4.0.x | 1–5 |
| 2.5 | Classic starters out, explicit starters in, `spring-boot-starter-flyway` among them. | 4.0.x | ~15 |
| 2.6 | Compile on JDK 25 with `--release 21`, suite on JDK 25, Eclipse launches, devenv. Licence fingerprint charset test (decision 12). | 4.0.x | ~8 |
| 2.7 | Real proof, only when Zein asks: Tomcat 11, JDK 25, rehearsal databases, L2 scripts, NTLM GET and POST on ZEN's test NAV, RFID, licence, front. | 4.0.x | 0 |
| 2.8 | Upgrade path: old lines refused at startup, `security.jwt.secret` (decision 18), deploy guide (with the one line of decision 3), docs, changelogs, release notes. Merge `release/3.1.2`. | 4.0.x | ~15 |
| 2.9 | Bump to the latest 4.1.x and run the proof of 2.7 again on it: 4.1 is what gets deployed. | 4.1.x | 5–15 |

Exception to "green at each step": 2.3 is two commits (main, then tests) that pass one gate together, because
`@JsonDeserialize` under the new package is not read by a Jackson 2 test mapper.

## The safety nets (step 1.2)

Written from what 2.3.9 answers: they are the reference for every later step. All of it is test code; no main class changed.

**Startup tests.** `StoreStartupTest`, `RfidStoreStartupTest`, `HeadOfficeStartupTest`, and the two matrix classes below.
Each class really starts its application in the JVM of the tests, the way `ZsRetailApplication.run` does, on an embedded
Tomcat on a free port (`core.support.MigTestApplication`), on a `zsretail_migtest` it creates and drops
(`core.support.MigTestDatabase`, settings in `core/src/test/resources/migtest.properties`, no password in them). They live
in the store and head office projects because `network-tests` has both applications on its classpath and
`scanBasePackages` would scan both. They cover: the database reached, no connector, no link, no scheduler; the seeds; the
indexes of the startup T-SQL (`sys.indexes`: those classes only log a failure); a restart on its own database;
`HeadOfficeErpGuard` on the runner and on the jobs API. 184 of the 188 `@Query` are built by every start, and the 4 of the
RFID repositories by `RfidStoreStartupTest`.

**The other shapes (steps 1.2b, 1.3b).** `LinkedStoreStartupTest` (everything from the head office, the lines of
`customers/zen-store`), `ErpStoreStartupTest` (a store on an ERP, `parafendri`, the lines of `customers/parafendri-store`) and
`ErpHeadOfficeStartupTest` in its four forms (`emtop`, full ERP, the lines of `dev/headoffice-erp`; `zen`, `happyness` and
`parafendri`, catalogue only), each started and restarted. Every connector and link address is on
`MigTestApplication.CLOSED_PORT` (`http://127.0.0.1:1`), the scheduler is off, and `nothingCanLeave` refuses any other address,
from any source, before a bean exists.

`installation-beans.txt` now counts the ERP conditions too: 145 decided beans (112 and 33: zen 11, happyness 10, parafendri 8,
emtop 3, `NoOpErpConnector`). The 10 `@ConditionalOnErpAbility` classes were already counted through their other condition.
Each line of the file was rewritten, and compared bean by bean: none lost in any of the 31 lines. **Compared once on the real
contexts of these starts: all 145 are built.** The two catalogue classes of `parafendri` exist only on a head office.

**Status matrix.** `StoreSecurityMatrixTest`, `HeadOfficeSecurityMatrixTest`: real HTTP on the real chains. Reading the
tables: an anonymous call on the main chain gets **403, not 401** (no login form or HTTP Basic, so Spring Security's default
refusal); 401 is the `/ho/**` chain. A fresh database has no licence, so the licence row is made through the repository:
`getStatus()` reads the row and the machine, it does not check the signature. Roles are mostly not enforced by the API
(`CLAUDE.md`): `GET /admin/erp/jobs` is the row where a cashier and an admin differ.

**Golden JSON.** `core.support.GoldenJson`, files under `golden/` in each project's `src/test/resources`,
`-Dzsretail.golden.record=true` records them again, only when the difference is the intended one, and the diff goes in the
commit. Every field of a class is filled by `GoldenJson.sample`, so a new field shows as a difference to look at.

| Test | What it pins |
|---|---|
| `LinkDtoGoldenJsonTest` | The 36 link messages, Spring MVC's mapper, written and read back. |
| `LinkMappersGoldenJsonTest` | The same files through `HeadOfficeClient.WIRE_MAPPER` and the client's `RestTemplate`. `SalesPushService.HASH_MAPPER`'s text, **byte for byte**: a change would send every ticket again. |
| `HeadOfficeGoldenJsonTest` | The 13 copies through their services' `COPY_MAPPER`, as a tree, as they leave for the stores; the admin objects. |
| `CopiesDownGoldenJsonTest` | Those copies read by the store handlers' mappers, nothing lost (`network-tests`: it needs both applications). |
| `TillGoldenJsonTest` | What the till sends and reads, the four `@JsonFormat` patterns both ways, a null into a primitive, 1.5 refused on a whole quantity. |
| `CoreGoldenJsonTest` | 18 entities and 9 DTOs as served. `edges-*.txt`: 43 edge cases per mapper, the table to read at 2.3 and 2.4 (null into a primitive, empty string into a number or a date, decimal into an int, enum, unknown property). |
| The matrix classes | The error bodies through the real ERROR dispatch, `/config` and an entity as served. |

**Queries on the real database (step 1.4).** H2 2.1 in SQL Server mode refuses what real SQL Server accepts, so the five test
classes of the catalogue queries left H2 for `zsretail_migtest` with the SQL Server 2012 dialect and the production SQL:
`ItemCatalogueStoreQueryTest`, `ItemCatalogueStoreDetailsTest`, `ItemCatalogueQueryTest`, `ItemCatalogueFiltersQueryTest`,
`StockPointFilterQueryTest`. They test more than before, and skip with the startup tests. They connect through
`MigTestDatabase.connect`, a DataSource: given the address and the password as settings, Hibernate's own pool writes the
password in the build's log at INFO. The other H2 tests name `core.support.H2SqlServerModeDialect`.

**The schema (step 1.8).** `StoreSchemaStartupTest` and `HeadOfficeSchemaStartupTest`, on `core.support.FlywayStartups`: a new
database, a database at 3.1.2 (`Upgrade312`: the baseline run as plain SQL, stamped 3.1.2, one item), every refusal and a
newer database, each a real start. `SchemaCatalog` reads the schema as sorted lines (`schema/catalog.sql`, catalog views only)
and compares them with the type's record, `schema/store.txt` or `schema/headoffice.txt`: a new and an upgraded database must
both give it. `-Dzsretail.schema.record=true` writes it again, for a new V script only. `QuerySweep` runs every `@Query` of a
started application on SQL Server, with nulls and with samples, each call rolled back (the store, the RFID store, the head
office); `QueryResultTypes` pins the class of each native and HQL result (`schema/result-types.txt`).

The order of the properties is left out of the comparison, except for the hashed text. Jackson puts a property that comes
from a getter alone (`UserAccount.isEnabled`) in the order reflection gives, which **changed between two runs of the JVM**:
a byte-for-byte file was flaky. The code has one reader that depends on the order, the hash, and its mapper sorts.

## What each step must not forget

**1.8: how the schema became Flyway's** (done 2026-11-10)
- **The baseline is a 3.1.2 database** (decision 25). `release/3.1.2`'s own code, started for real on an empty
  `zsretail_migtest` (a scratch harness in a worktree of that branch: the 1.3b copies of `MigTestDatabase` and
  `MigTestApplication`, nothing committed there), made the reference database of each type. Hibernate 5.4's create script of
  the same code, run on an empty database, gave the same schema but for the unique indexes the startup T-SQL adds
  (`UserBadgeCodeIndex`, and `StoreErpCustomerIndex` on a head office). `db/migration/{store,headoffice}/V4.0.0__baseline.sql`
  is that script and those indexes, unedited: today's primary key order of `promotion_group_item`, the unique constraint
  `uk_ho_down_change`, no CHECK constraint, `datetime2(7)`, Hibernate 5's names of the unique keys (the two that can exceed
  1,700 bytes included, as every 3.1.2 database has them). Taking Hibernate 6.6's create script and editing it back would have
  been the other way: more edits, each one a chance to miss a difference.
- **Catch-up 1**, `db/migration/common/V4.0.0.1__indexes_of_the_old_scripts.sql`: the five indexes that only the scripts of
  `db/<version>/` made (`ix_stock_movement_item`, `ix_stock_movement_created`, `ix_sales_header_session_status`,
  `ix_ho_store_order_line_order`, `ix_pos_machine_reader`), each where its table is and it is missing. A database upgraded
  through the scripts has them; a database a version created from empty never had them, and nor would a new 4.0.0 one. Found
  by comparing the local upgraded databases with a fresh 3.1.2 one (read only). Not a no-op on a new database, on purpose
  (open point 3): a new database ends as an upgraded one.
- **Hibernate 6.6's own catch-up, reviewed** (decision 4, [step-1.8-catch-up](diffs/step-1.8-catch-up.txt)): on a 3.1.2
  database it would run 11 (store) and 9 (head office) `ALTER COLUMN`, each to the type the column already has (the columns
  whose entity spells its type), without `NOT NULL`: `app_release_notes.description` would become nullable, and update would
  run them again at every start. Nothing kept. No unique key comes back under 6.6's names, contrary to what 1.7 expected.
- **Taking over a database**: `spring.flyway.baseline-on-migrate`, `baseline-version=4.0.0`. An empty database gets the
  baseline and the catch-ups; a database with tables and no history gets a baseline marker, after
  `db/migration/common/beforeBaseline.sql` has checked that it is at 3.1.2: APP_VERSION and what `db/3.1.2/update.sql` checks
  before it stamps the version. Flyway runs that callback before it creates its history table (read in 11.7.2's `DbBaseline`),
  so a refused database is left exactly as it was: no table, no row, no Hibernate. This replaces open point 4's failing V
  script, which would have left the history table and its baseline row behind. Proved: an older version, an empty
  APP_VERSION, no APP_VERSION (not a ZS Retail database), and a 3.1.2 stamp without each of its four parts. Seen locally:
  `parafendri_ho` says 3.1.2 without `item.sold_in_bulk`, and would be told to run `db/3.1.2/bulk-sale.sql`.
- **A database newer than the WAR**: `spring.flyway.ignore-migration-patterns` emptied, so a script the WAR does not have
  stops the start at Flyway's check, as `AppVersionGuard` refused a newer database. Flyway's default (`*:future`) would start.
- `ddl-auto=validate` in `application.properties` and the 13 tracked outside files; `AppVersionGuard` deleted.
  `ZZDataInitializer` sets APP_VERSION to the WAR's version once the checks have passed: the callback reads it, and a 3.1.2 WAR
  put back on an upgraded database refuses it (its guard sees 4.0.0). Who else reads the row (open point 4): nobody;
  `DecimalColumnsCheck` names it in a comment. The release notes of a version (APP_RELEASE_NOTES) come with its scripts: the
  notes of 4.0.0 are a V script of step 2.8.
- Flyway on Boot 3.5: `flyway-core` and `flyway-sqlserver` 11.7.2 (Boot's BOM) in core; the starter comes at 2.5. Its floor on
  11.7.2 is SQL Server 2014 (12.0), not the 2017 read on its main branch (open point 1); `SqlServerVersionCheck` (2017) runs
  first anyway, on the DataSource.
- **The proofs**, real starts on `zsretail_migtest` ([step-1.8-schema](diffs/step-1.8-schema.txt)): a new database is the real
  3.1.2 one and catch-up 1's indexes; the real 3.1.2 database of each type, upgraded by this branch, ends with the very schema
  of a new one, and every table keeps its rows and their checksum but APP_VERSION and the head office's copies-down backfill
  (which a 3.1.2 restart writes the same). Permanent: `StoreSchemaStartupTest` and `HeadOfficeSchemaStartupTest`
  (`core.support.FlywayStartups`, `Upgrade312`, `SchemaCatalog`: the records `schema/store.txt` and `schema/headoffice.txt`,
  `-Dzsretail.schema.record=true`). Their 3.1.2 database is the baseline run as plain SQL, proved equal to the real one above.
- **Casts on the rows** ([step-1.8-result-types](diffs/step-1.8-result-types.txt)): the class of every native expression and
  HQL aggregate our queries use, on real SQL Server, Hibernate 5.4 against 6.6: only a native `bigint` changed, a `Long` instead
  of a `BigInteger`, and no class of main names `BigInteger`. The casts of the doc's count stay as they were. Pinned by the
  record `store/.../schema/result-types.txt`, to read again at 2.2.
- **`:param is null` and every other `@Query`** (`core.support.QuerySweep`): the 189 `@Query` (80 core, 41 store with 4 of
  RFID, 68 head office; 188 counted before, one is written `@org.springframework.data.jpa.repository.Query`) run on real
  SQL Server, once with null for every parameter that can be null, once with a sample of each type, each call rolled back:
  234 calls on a store, 242 with RFID, 296 on a head office, none fails. A native query broken on purpose was reported, alone.
- The `setId` sites: `_BaseController.create` with an id the table does not have answers 500 and writes nothing (Hibernate
  6.6's "Row was updated or deleted by another transaction"); `release/3.1.2` answered 201 and inserted a new row under a new
  id (both run). Without an id, or with `id: null` as the front sends, it creates. `GroupItemsDeserializer`'s items by id: the
  promotion is saved with its group (`PromotionService.save` loads them). `StoreSchemaStartupTest.clientIds`.
- Under `validate` the startup T-SQL still runs after the check, as before; on a baseline database its objects are there.
- `devenv/rehearsal.ps1` and `setup-stores.ps1` create empty databases: Flyway gives them the baseline, no `update` needed.

**2.2** (what 1.8 leaves for Hibernate 7)
- The golden `errors/store-api-refusal.json` holds Hibernate's own message: its wording may change, re-record after reading.
  (Hibernate 6.6 changed it once at 1.7: "value : com..." became "value: com...".)
- The safety nets of 1.8 are the reading of 2.2: the schema records (a new database and an upgraded 3.1.2 one must still give
  `schema/store.txt` and `schema/headoffice.txt`, now that Hibernate 7's `validate` reads them), the result types record, and
  `QuerySweep` on the three shapes. The update script produced again ([step-1.8-catch-up](diffs/step-1.8-catch-up.txt) is
  6.6's): Hibernate 7's `update` alters existing columns when a type or length differs (`StandardTableMigrator`,
  `SQLServerDialect.supportsAlterColumnType()` is true), with no `NULL` or `NOT NULL` clause; 6.6 already does it for the
  columns whose entity spells its type. Read every `ALTER COLUMN`.
- Hibernate 6.6's create script (step 1.7, [step-1.7-ddl](diffs/step-1.7-ddl.txt)) is no longer used: the baselines are
  3.1.2's. A new column of a later V script is written by hand in the form of the baseline (`datetime2`, not
  `datetime2(6)`; no CHECK constraint for an enum).
- Behaviour the suite exposed at 1.7, fixed there: two derived queries with a `String` for a `LocalDate` field
  (`SalesPriceRepository`, `SalesDiscountRepository`, unused, marked "legacy" in the code) stopped every start.

**1.9**
- `AntPathRequestMatcher` should be deprecated in Security 6.5 and gone in 7 (from memory, to read): `SecurityConfig.paths` and
  `HeadOfficeApiSecurityConfig.storeApiChain` move to `PathPatternRequestMatcher`, one class on an embedded and an external
  Tomcat. Do not use `requestMatchers(String...)` before 7: on 5.8 it follows Spring MVC's rules and builds another class on
  an external Tomcat (JSP servlet) than in the tests. The matrix rows `/config/`, `/CONFIG`, `/login/x`, `/hox` and `/ho`, written
  on the old chains, say whether the matching moved. (At 1.7 `/config/` went from 200 to 404 for a logged user: Spring 6 no
  longer takes a trailing slash, nothing to do with the chains; accepted, decision 24.) Confirmed at 1.7: `AntPathRequestMatcher`
  is deprecated for removal in 6.5.
- `ErpHttp` keeps `NTCredentials` and `NTLMSchemeFactory`, deprecated in HttpClient 5.5 but NTLM is what NAV and BC ask for
  (decision 5): not a deprecation to remove. Compile once with `-Xlint:deprecation` for the rest: the default build shows only
  what is deprecated for removal.

**1.9, 2.8: the merges of `release/3.1.2`**
- **The security fixes (`50a5cb8`, `2e05b3a`) were merged on 2026-11-10, before step 1.7**, on Zein's word, so that 1.7 renames
  them with the rest. 6 goldens re-recorded, not 5: `core/UserAccount`, `CashierSession`, `SalesHeader`, `ReturnHeader`,
  `Payment`, and the store's `Warranty`, which embeds a user. Read ignoring line ends, the diff is only 19 `"password"` and 19
  `"badgeCode"` lines going. The matrices hold the HTTP proof: as a cashier `GET /user-account` and `/user-account/2`,
  `PUT /user-account/999`, `POST /user-account/999/badge/generate` and `PUT /company-info` get 403, the admin gets 200 (400 for
  the user who does not exist: the permission let him through, and no row changes); `/cashiers` stays 200; the head office's
  rows with its keys. `StoreSecurityMatrixTest.badgeScan`: a real badge, made by the admin, scanned by a cashier: 200, a wrong
  code 400, and the admin's code in no answer to the cashier. The one conflict was the company information doc.
- At 1.9 and 2.8, what `release/3.1.2` gained since, if anything (the freeze of 1.7 holds it still). A merge leaves every pom
  at 4.0.0: the poms of `release/3.1.2` say 3.1.2.

**2.1**
- `EnvironmentPostProcessor` moves to `org.springframework.boot` (3 classes, `spring.factories`, tests). The tests read the
  configuration through `ConfigDataEnvironmentPostProcessor.applyTo` (`Starts`) and `EnvironmentPostProcessorApplicationListener`
  (`ConfigurationKeysProofTest`): check where those two go.
- `GoldenJson.bootMapper()` names `JacksonAutoConfiguration` and `MigTestApplication` its `SpringApplication` arguments: the
  test helpers follow the moved packages. `LinkMappersGoldenJsonTest` reads the `RestTemplate` converter by its class.
- `session.saveOrUpdate` 1 and `session.delete` 2 in tests are gone. `CashierSessionService`, `ReturnHeaderAPI`,
  `CustomerService`, `VendorService` use the criteria types that change in JPA 3.2.
- `starter-aspectj` explicitly: without it `HeadOfficeErpGuard` could silently stop applying. The startup test must prove it.
- `org.hibernate.dialect.SQLServer2012Dialect` is still in Hibernate 6.6 (deprecated, it warns) but may be gone from 7: a
  site's outside file that names it would then stop the start. The tracked lines left at 1.7 (Hibernate picks the dialect
  from the connection); check 7, and 2.8's guide removes the line on the sites.

**2.3**
- **With Jackson 2 still on the classpath (java-jwt keeps it), a `@JsonDeserialize` under its old import is silently
  ignored by Jackson 3**, which would disable the refusal of 1.5 on 9 whole-quantity fields. Hence the guard test.
- `new ObjectMapper()`: 22 in main, 68 in tests. They get Jackson 3's defaults whatever `use-jackson2-defaults` says.
- `WholeQuantityDeserializer` (`NumberDeserializers`, `JsonStreamContext`, extends `JsonMappingException`),
  `GroupItemsDeserializer`, and `withExactBigDecimals` in `NavPosInvoiceRow` and `ZenInvoiceRow`, which money depends on.
- A shared mapper factory cannot live in core for the connectors: a connector sees only erp-api.

**2.4**
- `FAIL_ON_NULL_FOR_PRIMITIVES` turns on: 173 primitive fields in `dto` packages, 115 `@RequestBody` parameters.
  `FAIL_ON_UNKNOWN_PROPERTIES` turns off for plain mappers. Checked, low risk: no field changes name under the new bean
  naming, no enum overrides `toString`.

**2.6**
- `MachineFingerprintService.java:171` reads `vol` with the default charset, which becomes UTF-8. A regex and a WMI
  fallback protect it, but a changed fingerprint would lock a site.
- Lombok 1.18.48 should compile on JDK 25 (from memory, to prove here). Eclipse has its own agent, `lombok.jar` 1.18.36, on its
  own JVM (23): it follows if Eclipse moves to JDK 25.

**2.7: the watch points of the real proof**
- **POI's libraries on the real WAR.** commons-codec and log4j-api stay at what Boot's BOM pins, below what POI 5.5.1 asks for
  (1.15 against 1.20.0, 2.17.2 against 2.24.3 at step 1.4; 1.18.0 and 2.24.3 in Boot 3.5.16's BOM; read Boot 4's). 22,024 calls
  of POI and its libraries were compared with the WAR and none is missing, but that says the methods exist, not that they
  behave as a newer version would, and only the Excel tests have run them. On the real WAR: a data import of a .xlsx
  (`DataImportService`), an inventory file (`InventoryFileReader`), the Journal export (`JournalExcel`), a damaged file (a clean
  error, not a `NoClassDefFoundError` or `NoSuchMethodError`), then read the log.
- **Encrypted connections, the local case.** On the real start under JDK 25 and Tomcat 11, with the final driver, read
  `encrypt_option` in `sys.dm_exec_connections`: TRUE. This proves SQL Server 2019 on this Windows 11 only: see 2.8.
- **NTLM on HttpClient 5, live** (decision 5): a GET and a POST (`commandesVente`) on ZEN's test NAV through `ErpHttp`, the
  user name without the domain. At 1.7 only a server of the test JVM proved that the client answers an NTLM challenge
  (`ErpHttpTest`): without NTLM in the preferred schemes, HttpClient 5.5 answered 401.
- **HttpClient 5's retries differ from 4's.** 4 retried an idempotent request 3 times on an I/O error; 5 retries once, and also
  once on a 429 or 503 answer, after the Retry-After delay (1 s without it). A timeout is retried by neither, so the
  scheduler-hang fix holds (`ErpHttpTest.readTimeout`). Watch a NAV that answers 503 under load. Accepted as is (decision 22).

**2.8**
- **An outside file's `spring.jpa.hibernate.ddl-auto=update` line** (the customer examples had it until 1.8, so sites have it)
  wins over `validate`: on 4.0.0 Hibernate would then run, at every start, the `ALTER COLUMN` of
  [step-1.8-catch-up](diffs/step-1.8-catch-up.txt), one of which makes `app_release_notes.description` nullable. Proposal:
  refused at startup with the other old lines (anything but `validate` or `none`), a developer's throwaway database keeping
  `update` by a key of its own (decision 4); at least the guide removes the line.
- The upgrade night, from 3.1.2: nothing to run by hand, the 4.0.0 WAR's start takes the database over (Flyway). From an older
  version: its `db/<version>/` scripts up to 3.1.2 first, as before; 4.0.0 refuses anything else and says which.
- The site check before the night (decision 21) can also run, read only, `beforeBaseline.sql`'s checks and the schema query
  (`core/src/test/resources/schema/catalog.sql`) against the type's record: a site at the wrong version, or one whose schema
  drifted, is then found the day before, not on the night.
- The notes of 4.0.0 (APP_RELEASE_NOTES, written from the CHANGELOG when the version closes) are a V script, no longer an
  `update.sql`.
- The deploy guide: every site's outside file gets `;encrypt=true;trustServerCertificate=true` on its `jdbc:sqlserver` line.
  The driver encrypts by default since 10.2, so without them a server with its own certificate refuses the connection and the
  start fails: seen on 2026-11-09, 8 of 8 local lines on driver 10.2.3. An outside file with `logging.file.*` lines moves them
  to `logging.logback.rollingpolicy.*`: none is tracked.
- The guide, from step 1.7: an outside file's `spring.jpa.properties.hibernate.dialect=...SQLServer2012Dialect` line goes
  (see 2.1), and `logging.level.org.hibernate.type.descriptor.sql.BasicBinder` becomes `logging.level.org.hibernate.orm.jdbc.bind`
  (the old name logs nothing on Hibernate 6). The WARs are Jakarta from 1.7: Tomcat 10.1 at the least, Tomcat 11 per decision 2.
- **Encrypted connections against a customer's server cannot be proved from here**: no installation and no test goes there.
  What can differ on a site: an older Windows Server may offer only TLS suites that JDK 25 refuses (from memory, JDK 24 disabled
  `TLS_RSA_*`: to read), SQL Server may force encryption or hold its own certificate. So the guide has a step **before** the
  night, on each site, with Zein's check of the SQL Server version (decision 2): the read-only connection test of decision 21,
  written here. A failed handshake is then found the day before. The options themselves go in on the night, not before.
- `trustServerCertificate=true` encrypts but does not check who answers. It is better than today (nothing encrypted after the
  login), and it is the choice of decision 2. A site with a real certificate can use `trustServerCertificate=false` and
  `hostNameInCertificate` instead: one line in the guide.
- `security.jwt.secret` (decision 18): `SecurityParams.SECRET` goes; `JWTAuthorizationFilter` and `JWTAuthenticationFilter`
  read the key. No line, or a blank one, stops the start with a message that says how to make one. Every tracked config
  has `CHANGE_ME`, never a real value; `ConfigurationKeysProofTest` records it as `(set)`. `MigTestApplication` gives a test
  value, and the matrix gains a row: a token signed with another secret gets what the bad token gets. The store keys of
  `/ho/**` do not use it. The deploy guide says how to generate one per site on the upgrade night.

## Open points

| # | Point | To settle |
|---|---|---|
| 1 | **Flyway's SQL Server floor.** Flyway's open-source `SQLServerDatabase` calls `ensureDatabaseNotOlderThanOtherwiseRecommendUpgradeToFlywayEdition("14.0", Tier.PREMIUM, ...)` (read on its main branch). 14.0 is SQL Server 2017. | Settled by decision 2: 2017 is the minimum, Zein verifies each site. Read on 11.7.2 at 1.8: its floor is 12.0 (2014), 11.0 refused outright; our 2017 check runs first, on the DataSource, with our message. Read again on Boot 4's Flyway. |
| 2 | **Where the baselines live.** Both WARs carry core, and Flyway refuses two scripts of one version in its locations. | Settled by decision 13: `db/migration/{common,store,headoffice}`. |
| 3 | **Deltas on a new database.** On an empty database Flyway runs the baseline, then the `V4.0.0.x` scripts too. | Settled at 1.8: each catch-up is guarded and re-runnable. Catch-up 1 is not a no-op on a new database, on purpose: the baseline is a 3.1.2 database made from empty, which never had the indexes of the old scripts, and catch-up 1 gives them to every database. |
| 4 | **`AppVersionGuard`.** Answer to decision 13: it can go, except for one check Flyway cannot make, that an existing database really is at 3.1.2 when `baseline-on-migrate` marks it 4.0.0. A database newer than the WAR is Flyway's if `spring.flyway.ignore-migration-patterns` is emptied: its default `*:future` ignores newer scripts. | Settled at 1.8: the guard is gone. The 3.1.2 check is `beforeBaseline.sql`, a callback Flyway runs before it writes anything (a failing V script, the first idea, would have left its history table and baseline row). The patterns are emptied. Nobody else reads APP_VERSION; `ZZDataInitializer` sets it to the WAR's version. The release notes become V scripts (2.8). |
| 5 | **Source of the catch-up SQL.** It reads the metadata of a database with the old schema, and decision 6 keeps startup tests off existing local databases. A script generated from one database may not fit a customer whose schema drifted: each delta has to tolerate that. | Settled at 1.8: the old schema is the one `release/3.1.2`'s code makes on `zsretail_migtest`. The drift of sites was read on the local upgraded databases, catalog views only, no start: no column type differs; the scripts' names of keys and five indexes do (catch-up 1). A customer's backup was not needed. 2.8 can read a site's schema the same way before its night. |
| 6 | **Enum CHECK constraints.** No setting found that disables them (Hibernate forum, one search). Under `validate` Hibernate creates nothing in production, so they can only arrive through the baseline and catch-up scripts, where review keeps them out. Seen at 1.7: Hibernate 6.6 writes 67 (store) and 56 (head office), one per enum column (`diffs/step-1.7-ddl.txt`). | Confirmed at 1.8, decision 25: none in the baselines (3.1.2's own, which has none) nor in the catch-up, and Hibernate 6.6's update script adds none to an existing table. A new enum value needs no script. |
| 7 | **`Accept`.** `ConnectorAcceptHeaderTest` pins what we send: it failed on the old classpath with `application/xml, text/xml, application/json, application/*+xml, application/*+json` on all four connectors, and passes on the new one. A mock cannot show what NAV answers: live GET at 2.7, or earlier when Zein opens the VPN. | 2.7 |
| 8 | **POI's jars.** 45 jars of the store WAR came with POI 5.0.0's compile dependencies (batik, pdfbox, xmlsec, bouncycastle...), and our code imports none of them. | Settled at 1.4 by POI 5.5.1: batik, bcprov, bcpkix, xmlsec, pdfbox, graphics2d, xmlgraphics, woodstox and stax2 are gone, commons-io is 2.21.0. Store WAR 130 to 76 jars, head office 128 to 75. Boot's BOM still pins commons-codec 1.15 and log4j-api 2.17.2 under what POI asks for: 22,024 calls of POI and its libraries compared with the WAR, none missing in them. commons-lang3 was missing 3 classes, hence 3.18.0. |
| 9 | **This machine for 2.6 and 2.7.** JDK 25 and Tomcat 11 are not installed (JDK 21 and 23; Tomcat 9). Eclipse is `jee-2024-12`, to be checked against JDK 25. Zein is told what to install at 2.6. | 2.6 |
| 10 | **Shapes the startup tests did not start.** | Settled: "The other shapes". All 145 recorded beans are built. |
| 11 | **The password hash reached the browser.** `UserAccount.password` was written in every answer embedding a user. Called on the running application as a cashier: `POST /cashier-session/open`, `GET /cashier-session/current` and `GET /cashier-session`. | Fixed on `release/3.1.2`, `50a5cb8` (2026-11-09). Merged here on 2026-11-10, before step 1.7: the goldens no longer hold the field. |
| 12 | **One JWT secret for all.** `SecurityParams.SECRET` is a constant in the source and `JWTAuthorizationFilter` checks only the signature, so a token made by any installation should open the main chain of any other, customers included, for 5 months. Read in the code, not tried. | Decision 18: `security.jwt.secret` at 2.8. |
| 13 | **The badge code travelled like the hash did, and `/user-account` checked nothing.** Called as a cashier: `GET /user-account` gave every badge code, `POST /badge/scan` a till right with one; `POST /user-account/1/badge/generate` the admin's new badge; `PUT /user-account/1` set the admin's password, and logging in with it worked. | Closed on `release/3.1.2`, `2e05b3a` and front `e708da8` (2026-11-09): the permissions of the Users pages on the API, `badgeCode` write-only in the entity, `PUT /company-info` behind the page's permission. Replayed live: all refused. Merged here on 2026-11-10, with the matrix rows that prove it. Not closed on `hotfix/1.12.1` (EMTOP): same hole, read only, not changed; EMTOP gets it by moving to 3.1.2 (decision 19). |
| 14 | **The rest of the API.** Most endpoints still ask only for a logged user, though the front shows them to some roles only: a cashier can call them directly. `/user-account` was the worst, because it gave the admin account. A pass over the other writes, endpoint by endpoint, is its own piece of work. | Zein, after the migration |
| 15 | **`/sse-endpoint/**`.** Public in `SecurityConfig` and let through by `LicenseFilter`, but no handler exists: no `SseEmitter` in the code. A dead entry, harmless today, that would make a future endpoint under that path public without a licence. | Closed 2026-11-10: no branch of the front names it, so the rule and the licence exemption are gone. `GET /sse-endpoint/x` answers 403 to an anonymous caller and 402 without a licence, 404 to everyone before. |
| 16 | **A user's own badge code in `/user-account/current`.** It answered a `UserAccountDTO`, which carries `badgeCode`, to any logged user about himself. No screen reads it there (the till reads a code only where it is typed, `TillAuthPopup`), and `/current` is called by no screen. A credential sent where nothing uses it. | Closed on `release/3.1.2`, `cbae53bb` (2026-11-10), merged here: `/current` answers `UserAccountDTO.ownAccount`, the account without its code. Not by a write-only field, as first asked: the Users page reads the code from the answers behind `read:admin-users` (its column, Revoke and Reactivate) and sends it back on a save (`badgeCode: userForm.badgeCode \|\| null`), so a hidden code would have removed the badge of every user an admin edits. `StoreSecurityMatrixTest.badgeScan`: the admin's own code not in his `/current`, still in his Users page. |

## Baseline (step 1.0)

Run on 2026-11-08 on `53c8996` (`feature/boot4-migration`, the same commit as `release/3.1.2`), in the working tree. Eclipse
was open with no application running. No instance was started.

**Result: BUILD SUCCESS, 1,884 tests, 0 failures, 0 errors, 3 skipped**, in 6 min 32 s.

| Project | Test classes | Tests | Failures | Errors | Skipped | Maven time |
|---|---|---|---|---|---|---|
| erp-api | 0 | 0 | | | | 8.8 s |
| connectors/emtop | 0 | 0 | | | | 10.4 s |
| connectors/happyness | 0 | 0 | | | | 9.1 s |
| connectors/parafendri | 14 | 91 | 0 | 0 | 1 | 14.0 s |
| connectors/zen | 8 | 81 | 0 | 0 | 0 | 14.1 s |
| core | 79 | 446 | 0 | 0 | 0 | 1 min 43 s |
| headoffice | 45 | 307 | 0 | 0 | 0 | 1 min 24 s |
| store | 88 | 603 | 0 | 0 | 1 | 1 min 16 s |
| network-tests | 60 | 356 | 0 | 0 | 1 | 1 min 10 s |
| **Total** | **294** | **1,884** | **0** | **0** | **3** | **6 min 32 s** |

The 3 skipped are whole classes, each counted as one test, switched on by a system property for a live or hardware run:
`ParafendriLiveConnectionTest` (`parafendri.live`), `HopelandRfidReaderManualTest` (`rfid.reader`),
`NavPosPagesLiveReadTest` (`navpospages.live`). No startup test exists yet: the rule on skipped startup tests starts at 1.2.

Counts come from the surefire XML reports. They replace the 1,475 of the root `CLAUDE.md` (2026-10-27).

**Build command** (Git Bash, from `Apps/ZSRetail-Back`):

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" mvn -gs "C:\Users\DELL\.m2\zsretail-settings.xml" -Dlombok.version=1.18.36 clean test --fail-at-end
```

The WARs, the same way with `-DskipTests clean package`. From step 1.2 a suite of a step adds
`-Dzsretail.migtest.required=true`, and the suite runs without `-T`: the startup tests share one database.

**Versions**

| | |
|---|---|
| Java | Oracle JDK 21.0.10 (build 21.0.10+8-LTS-217) |
| Maven | 3.9.0, with `-gs` replacing the machine's global settings |
| Lombok | 1.18.36 by the flag (the pom's managed 1.18.18 does not work on JDK 21) |
| OS | Windows 11, locale en_US, platform encoding UTF-8 |

**WARs**, jar lists in `baseline/`, sorted, one `WEB-INF/...` path per line, ready to compare at each step:

| WAR | Bytes | `WEB-INF/lib` | `WEB-INF/lib-provided` | List |
|---|---|---|---|---|
| `zsretail-store-3.1.2.war` | 172,611,746 | 263 | 4 | [store-war-jars.txt](baseline/store-war-jars.txt) |
| `zsretail-headoffice-3.1.2.war` | 170,322,174 | 262 | 4 | [headoffice-war-jars.txt](baseline/headoffice-war-jars.txt) |

The only difference between the lists is `rfid-reader-api-2.11-zs1.jar`, in the store WAR alone.

## Progress log

| Step | Date | Back commits | Result |
|---|---|---|---|
| 1.0 | 2026-11-08 | tested `53c8996`, no code change; recorded in `f15fb36` | 1,884 tests, 0 failures, 0 errors, 3 skipped. Both WARs built. |
| 1.1 | 2026-11-08 | `0387307` | 1,888 tests (the 1,884 and 4 new), 0 failures, 0 errors, the same 3 skipped. Store WAR 263 to 130 jars, 173 to 91 MB; head office WAR 262 to 128, 170 to 88 MB ([store diff](diffs/step-1.1-store-war-jars.diff), [head office diff](diffs/step-1.1-headoffice-war-jars.diff)). `commons-lang3` declared by the store. No instance started. |
| 1.2 | 2026-11-08 | `11c064a` | 2,069 tests (the 1,888 and 181 new), 0 failures, 0 errors, the same 3 skipped, twice in a row, 2 min 10 s, with `-Dzsretail.migtest.required=true`. **The startup tests ran**: `StoreStartupTest` 6, `RfidStoreStartupTest` 1, `HeadOfficeStartupTest` 8, `StoreSecurityMatrixTest` 5, `HeadOfficeSecurityMatrixTest` 5, none skipped, on `zsretail_migtest`, dropped afterwards. Per project: parafendri 91, zen 81, core 516, head office 341, store 667, network-tests 373. No main class changed. |
| 1.2b | 2026-11-09 | `8a7a668` | 2,078 tests (9 new), 0 failures, 0 errors, the same 3 skipped. `LinkedStoreStartupTest` 5 and `ErpHeadOfficeStartupTest` 2 ran, on the closed local port. All 112 recorded beans built, compared once on the real contexts. Password fix on `release/3.1.2` the same day, `50a5cb8` (1,889 tests there, 0 failures). |
| 1.3 | 2026-11-09 | `049b59a` | 2,089 tests (11 new), 0 failures, 0 errors, the same 3 skipped, with `-Dzsretail.migtest.required=true`. **The startup tests ran**: store 7, linked 5, RFID 1, refusal 1, head office 8, ERP 2, the two matrices 5 each, none skipped, on `zsretail_migtest`, dropped afterwards. No golden file changed. Per project: parafendri 91, zen 81, core 527, head office 343, store 674, network-tests 373. No instance started outside the tests. |
| 1.3b | 2026-11-09 | `1e90497` | 2,098 tests, 0 failures, 0 errors, the same 3 skipped. Every connector started on the closed local port (emtop, zen, happyness and parafendri on a head office, parafendri on a store). The record counts 145 beans (112 and 33), all built. The ERROR line of a badly written address. Security fixes on `release/3.1.2` the same day, `2e05b3a` and front `e708da8` (1,902 tests there, 0 failures). |
| 1.4 | 2026-11-09 | `79eb411` | 2,106 tests (8 new), 0 failures, 0 errors, the same 3 skipped, with `-Dzsretail.migtest.required=true`. **The startup tests ran**: store 8, linked 5, ERP store 1, RFID 1, refusal 1, head office 8, ERP head office 4, the two matrices 5 each, none skipped, on `zsretail_migtest`, dropped afterwards. No bean cycle in any start. Goldens: 10 lines of `"message":""` removed, nothing else. `configuration-keys.txt`: the 4 logging keys renamed in 21 lines and `spring.datasource.url` with the two options in 18, no other value changed. Store WAR 130 to 76 jars, 91 to 66 MB; head office 128 to 75, 88 to 63 MB ([store diff](diffs/step-1.4-store-war-jars.diff), [head office diff](diffs/step-1.4-headoffice-war-jars.diff), against `baseline/`). Per project: erp-api 3, parafendri 91, zen 81, core 534, head office 345, store 677, network-tests 375. No instance started outside the tests. This doc said `ConfigFileApplicationListener` was gone at 2.7: it still exists there, legacy, and the tests moved to the real post-processor anyway. |
| 1.5 | 2026-11-09 | `eb49d31` | 2,106 tests, 0 failures, 0 errors, the same 3 skipped, twice from clean, with `-Dzsretail.migtest.required=true`. **The startup tests ran**: store 8, linked 5, ERP store 1, RFID 1, refusal 1, head office 8, ERP head office 4, the two matrices 5 each, none skipped, on `zsretail_migtest`, dropped afterwards. javac runs with `--release 21 -proc:full -parameters`. Every class is at version 65, in every project and in the WAR, and nothing that reads class files complained (Spring 5.3's ASM, Byte Buddy 1.12.23, AspectJ 1.9.7, Mockito 4.5). Only Lombok and the configuration processor were on the compile path, and both are named: the compile gives the same files as before, the metadata files identical. The goldens and records did not change. WAR jars the same as step 1.4. The compiler plugin is 3.14.1, ahead of Boot's 3.10.1, because from 3.12 the processor paths take their versions from the BOM. `configs/local`: the two options added on its 8 lines (not in git), harmless on `release/3.1.2`'s driver 7.4.1: 8 of 8 connect encrypted, on JDK 21 and 23, same speed, and `InstallationSummary` still reads the database name. These runs took 12:55 and 12:29, against 4:13 and 5:27 in the morning. **Not this step**: side by side at the same time, the previous commit took 226 and 230 s, this one 223 and 224 s. The laptop was on battery, the CPU at 56% of its performance. No instance started outside the tests. |
| 1.6 | 2026-11-09 | `11ffcf8` | 2,108 tests (2 new), 0 failures, 0 errors, the same 3 skipped, with `-Dzsretail.migtest.required=true`, 6:45. **The startup tests ran**: store 9, linked 5, ERP store 1, RFID 1, refusal 1, head office 9, ERP head office 4, the two matrices 5 each, none skipped, on `zsretail_migtest`, dropped afterwards. Security 5.8.16: both chains are `SecurityFilterChain` beans on `authorizeHttpRequests`, and no `WebSecurityConfigurerAdapter` is left. New matrix rows on how a path matches ran first on the old chains, where one expectation of mine proved wrong (`/ho` with a store key is 401, the key's filter reads only `/ho/...`), then passed unchanged on the new ones. No golden and no record changed. `SecurityChains` checks on the real starts: two chains, `/ho/**` first, `AuthorizationFilter` on both. CORS untouched. The matchers are named `AntPathRequestMatcher`, not strings, so the matching is today's on an embedded and an external Tomcat. The ERROR rule is on the main chain only: that dispatch goes to `/error`, and the `/ho/**` rows and goldens pass without it. WARs: the 4 security jars change version, nothing else. No instance started outside the tests. |
| 1.7 | 2026-11-10 | `5108ebfb` (rename), `f8186063` (Boot 3.5.16 and the fixes) | 2,136 tests, 0 failures, 0 errors, 7 skipped (the same 3 live or manual classes: Surefire 3.5 counts each method), from clean, with `-Dzsretail.migtest.required=true`, 5 min 41 s. **The startup tests ran**: store 9, linked 5, ERP store 1, RFID 1, refusal 1, head office 9, ERP head office 4, the matrices 6 and 5, none skipped, on `zsretail_migtest`, dropped afterwards. Boot 3.5.16: Spring 6.2.19, Security 6.5.11, Hibernate 6.6.53, Tomcat 10.1.55, mssql-jdbc 12.10.2, HttpClient 5.5.2. The rename: 177 files, 853 lines, no `javax.validation` or `javax.transaction` left to rename. `ErpHttpTest` caught HttpClient 5.5 leaving NTLM out of its preferred schemes: every NTLM call answered 401 until it was named. Changed on purpose, each read first: matrices `GET /error` anonymous 500 to 403, `GET /config/` logged 200 to 404 (Spring 6, trailing slash); golden `store-api-refusal` (Hibernate's wording); `configuration-keys.txt` (the dialect key out of 21 configurations, the logger key renamed, nothing else). The ERROR rule's real test: without it, 20 error cells of the store matrix became 403. Checked with no change: URIs on Spring 6.2's parser (45 of 48 identical, 3 lower-case `HTTP://`), POI's 22,024 calls. DDL of a new database: [step-1.7-ddl](diffs/step-1.7-ddl.txt). WARs: store 66 to 83 MB, 76 to 81 jars; head office 63 to 80 MB, 75 to 80 ([store diff](diffs/step-1.7-store-war-jars.diff), [head office diff](diffs/step-1.7-headoffice-war-jars.diff)). Before the step, the same day: `release/3.1.2` merged (`3ed7e78`, matrices `25407e51`, 2,127 tests) and open point 15 closed (`70c2580d`, 2,127 tests). No instance started outside the tests. |
