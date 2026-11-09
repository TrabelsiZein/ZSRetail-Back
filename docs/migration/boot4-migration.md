# Spring Boot 4 migration

Branch `feature/boot4-migration`, from `release/3.1.2`. Product version 4.0.0. Plan approved by Zein on 2026-11-08.

**Status: step 1.4 done. Next: step 1.5.** Each step starts only on Zein's go.

| | Today | Target |
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
report of step 1.2.

**The schema rule, from 4.0.0: a schema change = one V script next to the Java change; never edit an applied script.**

| # | Decision | Step |
|---|---|---|
| 1 | Build on Boot 4.0.x as planned. A final step 2.9 bumps to the latest 4.1.x before any deployment. **The first deployment is on 4.1.** | 2.9 |
| 2 | Every customer gets JDK 25 and Tomcat 11 on its upgrade night, so the current JDK and Tomcat per site do not matter. Every tracked connection string and every customer config example gets `encrypt=true;trustServerCertificate=true`. **The minimum SQL Server for 4.0.0 is 2017: a startup check refuses to start below it, with a clear message** (this replaces the WARN below 2014). Zein verifies each site's version himself before its upgrade night. | 1.3, 1.4 |
| 3 | The head office and the stores of one customer upgrade together, the same night. No mixed-version tests; one line in the deploy guide. | 2.8 |
| 4 | `ddl-auto=validate` in every tracked config, store and head office. Schema changes ship only through V scripts (decision 13). Steps 1.8 and 2.2 produce, with `hibernate.hbm2ddl.scripts.action=update`, the catch-up SQL Hibernate would have run, reviewed, per database type: it becomes the `V4.0.0.x` scripts. Developer-local throwaway databases may keep `update`, documented. Enum CHECK constraints stay out through the review of the baseline and catch-up scripts, confirmed at 1.8. | 1.8, 2.2 |
| 5 | NTLM on HttpClient 5, registered explicitly in **one shared helper in erp-api**, used by all four connectors. Connect and read timeouts move to the connection manager with today's values: the scheduler-hang fix of 2026-10-22 must keep working. NTLM POST is proved live on ZEN's test NAV (`commandesVente`; orders were posted there on 2026-10-28), no fake server. | 1.7, 2.7 |
| 6 | Startup tests are allowed, against a throwaway local SQL Server database named `zsretail_migtest` **only**, created and dropped by the tests. Never Store A, never a customer database, never an existing local database. Its connection details are in a test-only properties file. When it is unreachable the startup tests are skipped with a clear message, not failed. | 1.2 |
| 7, 8 | Removed: springdoc, springfox, spring-security-oauth2, jasperreports (with iText, castor, jfreechart), firebase-admin, jsoup, modelmapper, commons-io 1.4, starter-webflux, reactor-core, starter-mail, starter-thymeleaf (and the `/thymeleaf` entries in `SecurityConfig` and `LicenseFilter`), jackson-dataformat-xml. The lone `@NotBlank` on `ErpSyncJob` is deleted; starter-validation is not added. Nothing is planned for Firebase or Jasper. | 1.1 |
| 9 | Base branch `feature/boot4-migration` from `release/3.1.2`. Product version 4.0.0. Customer fixes continue on `release/3.1.2` and are merged into this branch at the end of each phase. A freeze applies only during step 1.7. | all |
| 10 | The 2.7.18 internal stop is approved. Build on JDK 25 with `--release 21`. Lombok goes in the pom at step 1.5, so `-Dlombok.version` disappears. | 1.4, 1.5, 2.6 |
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
| 1.5 | Java 21: `release=21`, Lombok in the pom, processor paths, `proc=full`. | 2.7.18 | ~8 |
| 1.6 | `SecurityFilterChain` and lambda DSL on Security 5.8. ERROR and FORWARD dispatches permitted. | 2.7.18 | ~6 |
| 1.7 | **Freeze.** Boot 3.5.16: jakarta rename, Hibernate 6.6, Security 6.5, HttpClient 5 with the erp-api helper and the timeouts, dialect keys, `WarLauncher`. | 3.5.16 | ~210 |
| 1.8 | Behaviour the tests expose (HQL, casts, null parameters). Flyway: baseline per database type, catch-up `V4.0.0.x` scripts, `baseline-on-migrate`, `AppVersionGuard` out. `ddl-auto=validate`. Enum CHECK constraints confirmed. | 3.5.16 | 30–50 |
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

The order of the properties is left out of the comparison, except for the hashed text. Jackson puts a property that comes
from a getter alone (`UserAccount.isEnabled`) in the order reflection gives, which **changed between two runs of the JVM**:
a byte-for-byte file was flaky. The code has one reader that depends on the order, the hash, and its mapper sorts.

## What each step must not forget

**1.5**
- JDK 23 and later skip annotation processors unless told. `-Dlombok.version` also leaves `devenv/build.ps1:13` and both
  `CLAUDE.md`. 15 `Stream.toList()` calls become legal; they compile today only because there is no `--release`.

**1.6**
- `SecurityConfig.java:48`, `HeadOfficeApiSecurityConfig.java:43`. Authorization applies to every dispatch type from
  Security 6: without ERROR and FORWARD, a real error is expected to come back as 401 or 403. The main chain has permitted
  the path `/error` since 1.4, because Boot 2.6 blanks its body otherwise; the dispatches are still to permit. The matrix rows
  `/no-such-path`, `/location/not-a-number`, bad JSON, `/admin/erp/jobs` and `/error` are there for this.
- CORS stays the hand-written headers of `JWTAuthorizationFilter`.

**1.7**
- `core.support.H2SqlServerModeDialect` goes, and the 38 test files that name it take Hibernate 6's `H2Dialect`, which should
  write OFFSET/FETCH itself (from memory, to read).
- The BOM changes the libraries under POI again. `ExcelLibrariesTest` covers the three classes known today. Keep
  `commons-lang3.version` unless Boot 3.5's BOM reaches 3.18.0. Compare at class and method level, as step 1.4 did, what POI,
  xmlbeans and commons-compress call with what the WAR holds: a green suite does not see it.
- Rename only `javax.persistence`, `servlet`, `annotation`, `validation`: 176 files plus 57 inline lines. Never
  `javax.imageio`, `javax.net.ssl` or `libs/hopeland` (`javax.usb`).
- The helper: `DynamicsNavConfig`, `NavPosPagesConfig`, `ParafendriConfig`, `ZenConfig`, and `ZenProductsClient` (no auth).
  HttpClient 5's default registry has Basic, Digest and Bearer only; NTLM has been deprecated since 5.3. erp-api takes
  httpclient5 and spring-web: check `ProjectSortingGuardTest` and `ConnectorDependenciesGuardTest`.
- ZEN's test NAV answered GET with the username alone (`domain\user` gave 401, 2026-10-27): keep that working.
- `HeadOfficeClient.java:194` has its own factory, and `:600` classifies a read timeout by `SocketTimeoutException`.
  `NavPosPagesReadRetryTest` and `RetryingCatalogueSource` depend on the exception classes: retest.
- `SQLServer2012Dialect`: 14 config files and `QueryParameterBindingTest`. `BasicBinder` logger: 5 files.
  `devenv/start.ps1:26` names the old `WarLauncher` package. `getOne`: 3 in `SalesCopyReceiver`.
- Internal Hibernate API in `QueryParameterBindingTest` and `ItemJsonProxyTest`.
- `getRawStatusCode` still has 3 uses, `HeadOfficeClient.java:493,575,576`: they catch `RestClientResponseException`, which
  has no `getStatusCode()` before Spring 6 (step 1.3 changed the 12 on `HttpStatusCodeException`). Change them here.
- `UriComponentsBuilder.fromUriString` should move to a stricter RFC parser on Spring 6.2 (from memory, to read). Rerun on
  it the comparison of step 1.3: the 11 addresses of `configs/`, with spaces, accents and quotes in the path.

**1.8, 2.2**
- The `setId` sites, read at 1.3: all take the id of a row just read, or null, and the DTO ones are not entities, so
  Hibernate 6.6's refusal of a new row with an id does not reach them. Two to run there: `_BaseController.create` saves
  whatever id a client sends (an unknown id: inserted today, refused then; the front sends `id: null`), and
  `GroupItemsDeserializer` builds an `Item` with only an id as a reference for `Promotion.groupItems`.
- Flyway on Boot 3.5 is `flyway-core` plus `flyway-sqlserver` (11.7.2 in the 3.5.14 BOM): `spring-boot-starter-flyway` exists
  only from Boot 4, so the starter comes at 2.5. The startup tests go through Flyway from here: an empty
  `zsretail_migtest`, baseline, `validate`. They prove the new-install path. Check the floor of open point 1 on 11.7.2.
- Hibernate warns at the first start that two generated unique keys (`UK3vcep39q1j2ixbg2gqgylr1pk`,
  `UKqrre1fi800ou4vagktwr92a6q`) can exceed SQL Server's 1,700 bytes: they enter the baseline with the review.
- The golden `errors/store-api-refusal.json` holds Hibernate's own message: its wording may change, re-record after reading.
- Casts that may break at run time: `(Long)` 19, `(Double)` 11, `(Integer)` 8, `(BigDecimal)` 10 on `Object[]` rows.
  `(Number)` 106 is safe. `:param is null`: 49 in 17 files, to run on real SQL Server.
- Hibernate 7's `update` alters existing columns when a type or length differs (`StandardTableMigrator`,
  `SQLServerDialect.supportsAlterColumnType()` is true), with no `NULL` or `NOT NULL` clause. Read every `ALTER COLUMN` of
  the catch-up SQL. Hibernate 5 never touched existing columns, so drift has had years to build up.
- Under `validate`, the startup T-SQL runs after the check: `TillSchemaUpgrade`, `UserBadgeCodeIndex`,
  `StoreErpCustomerIndex`, `StoreOrderDraftIndex`, `JdbcCatalogueState`.
- `devenv/rehearsal.ps1` and `setup-stores.ps1` create databases: they keep `update` by `-Set`. Documented with `configs/local`.

**1.9, 2.8: the merges of `release/3.1.2`**
- The first brings the security fixes (`50a5cb8`, `2e05b3a`; front `e708da8`). Re-record the 5 goldens that hold the fields
  (`core/UserAccount`, `CashierSession`, `SalesHeader`, `ReturnHeader`, `Payment`) and read the diff: only `password` and
  `badgeCode` lines go. Then the matrices gain their rows, and only then does the HTTP proof live in the suite (the fixes were
  replayed by hand on a real start on 2026-11-09): as a cashier `GET /user-account`, `GET /user-account/2`,
  `POST /user-account/1/badge/generate`, `PUT /user-account/1` and `PUT /company-info` get 403; as an admin 200; `/cashiers`,
  `/current` and `POST /badge/scan` with a real badge stay 200; anonymous `PUT /company-info` gets 403. The head office's too,
  with its keys. `UserAccountApiAccessTest` and `CompanyInformationApiAccessTest` come with the merge.
- `CompanyInformationAPI` and `UserAccountAPI` merge with this branch's company row (`findFirstByOrderByIdAsc`).
- A merge leaves every pom at 4.0.0: the poms of `release/3.1.2` say 3.1.2.

**2.1**
- `EnvironmentPostProcessor` moves to `org.springframework.boot` (3 classes, `spring.factories`, tests). The tests read the
  configuration through `ConfigDataEnvironmentPostProcessor.applyTo` (`Starts`) and `EnvironmentPostProcessorApplicationListener`
  (`ConfigurationKeysProofTest`): check where those two go.
- `GoldenJson.bootMapper()` names `JacksonAutoConfiguration` and `MigTestApplication` its `SpringApplication` arguments: the
  test helpers follow the moved packages. `LinkMappersGoldenJsonTest` reads the `RestTemplate` converter by its class.
- `session.saveOrUpdate` 1 and `session.delete` 2 in tests are gone. `CashierSessionService`, `ReturnHeaderAPI`,
  `CustomerService`, `VendorService` use the criteria types that change in JPA 3.2.
- `starter-aspectj` explicitly: without it `HeadOfficeErpGuard` could silently stop applying. The startup test must prove it.

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

**2.8**
- The deploy guide: every site's outside file gets `;encrypt=true;trustServerCertificate=true` on its `jdbc:sqlserver` line.
  The driver encrypts by default since 10.2, so without them a server with its own certificate refuses the connection and the
  start fails. `configs/local` is Zein's, by hand. An outside file with `logging.file.*` lines moves them to
  `logging.logback.rollingpolicy.*`: none is tracked.
- `security.jwt.secret` (decision 18): `SecurityParams.SECRET` goes; `JWTAuthorizationFilter` and `JWTAuthenticationFilter`
  read the key. No line, or a blank one, stops the start with a message that says how to make one. Every tracked config
  has `CHANGE_ME`, never a real value; `ConfigurationKeysProofTest` records it as `(set)`. `MigTestApplication` gives a test
  value, and the matrix gains a row: a token signed with another secret gets what the bad token gets. The store keys of
  `/ho/**` do not use it. The deploy guide says how to generate one per site on the upgrade night.

## Open points

| # | Point | To settle |
|---|---|---|
| 1 | **Flyway's SQL Server floor.** Flyway's open-source `SQLServerDatabase` calls `ensureDatabaseNotOlderThanOtherwiseRecommendUpgradeToFlywayEdition("14.0", Tier.PREMIUM, ...)` (read on its main branch). 14.0 is SQL Server 2017. | Settled by decision 2: 2017 is the minimum, Zein verifies each site. Still to read on Flyway 11.7.2 at 1.8. |
| 2 | **Where the baselines live.** Both WARs carry core, and Flyway refuses two scripts of one version in its locations. | Settled by decision 13: `db/migration/{common,store,headoffice}`. |
| 3 | **Deltas on a new database.** On an empty database Flyway runs the baseline, then the `V4.0.0.x` scripts too, and the baseline already has their result. Each delta must be a guarded no-op there. | 1.8 |
| 4 | **`AppVersionGuard`.** Answer to decision 13: it can go, except for one check Flyway cannot make, that an existing database really is at 3.1.2 when `baseline-on-migrate` marks it 4.0.0. A `V4.0.0.x` script that fails when `APP_VERSION` is not 3.1.2 does that in SQL, with no Java guard. A database newer than the WAR is Flyway's if `spring.flyway.ignore-migration-patterns` is emptied: its default `*:future` ignores newer scripts. To check at 1.8: who else reads the `APP_VERSION` row, and who writes the release notes of `update.sql`. **Until 1.8 the guard stays**, so this branch stops on any 3.1.2 database and names a `db/4.0.0/update.sql` that does not exist. | 1.8 |
| 5 | **Source of the catch-up SQL.** It reads the metadata of a database with the old schema, and decision 6 keeps startup tests off existing local databases. A restored copy of a customer backup Zein provides is acceptable, read-only. A script generated from one database may not fit a customer whose schema drifted: each delta has to tolerate that. Proposal due at 1.8. | 1.8 |
| 6 | **Enum CHECK constraints.** No setting found that disables them (Hibernate forum, one search). Under `validate` Hibernate creates nothing in production, so they can only arrive through the baseline and catch-up scripts, where review keeps them out. Confirmed at 1.8. | 1.8 |
| 7 | **`Accept`.** `ConnectorAcceptHeaderTest` pins what we send: it failed on the old classpath with `application/xml, text/xml, application/json, application/*+xml, application/*+json` on all four connectors, and passes on the new one. A mock cannot show what NAV answers: live GET at 2.7, or earlier when Zein opens the VPN. | 2.7 |
| 8 | **POI's jars.** 45 jars of the store WAR came with POI 5.0.0's compile dependencies (batik, pdfbox, xmlsec, bouncycastle...), and our code imports none of them. | Settled at 1.4 by POI 5.5.1: batik, bcprov, bcpkix, xmlsec, pdfbox, graphics2d, xmlgraphics, woodstox and stax2 are gone, commons-io is 2.21.0. Store WAR 130 to 76 jars, head office 128 to 75. Boot's BOM still pins commons-codec 1.15 and log4j-api 2.17.2 under what POI asks for: 22,024 calls of POI and its libraries compared with the WAR, none missing in them. commons-lang3 was missing 3 classes, hence 3.18.0. |
| 9 | **This machine for 2.6 and 2.7.** JDK 25 and Tomcat 11 are not installed (JDK 21 and 23; Tomcat 9). Eclipse is `jee-2024-12`, to be checked against JDK 25. Zein is told what to install at 2.6. | 2.6 |
| 10 | **Shapes the startup tests did not start.** | Settled: "The other shapes". All 145 recorded beans are built. |
| 11 | **The password hash reached the browser.** `UserAccount.password` was written in every answer embedding a user. Called on the running application as a cashier: `POST /cashier-session/open`, `GET /cashier-session/current` and `GET /cashier-session`. | Fixed on `release/3.1.2`, `50a5cb8` (2026-11-09). On this branch only after the merge of 1.9: until then the goldens hold the field. |
| 12 | **One JWT secret for all.** `SecurityParams.SECRET` is a constant in the source and `JWTAuthorizationFilter` checks only the signature, so a token made by any installation should open the main chain of any other, customers included, for 5 months. Read in the code, not tried. | Decision 18: `security.jwt.secret` at 2.8. |
| 13 | **The badge code travelled like the hash did, and `/user-account` checked nothing.** Called as a cashier: `GET /user-account` gave every badge code, `POST /badge/scan` a till right with one; `POST /user-account/1/badge/generate` the admin's new badge; `PUT /user-account/1` set the admin's password, and logging in with it worked. | Closed on `release/3.1.2`, `2e05b3a` and front `e708da8` (2026-11-09): the permissions of the Users pages on the API, `badgeCode` write-only in the entity, `PUT /company-info` behind the page's permission. Replayed live: all refused. On this branch after the merge of 1.9. Not closed on `hotfix/1.12.1` (EMTOP): same hole, read only, not changed; EMTOP gets it by moving to 3.1.2 (decision 19). |
| 14 | **The rest of the API.** Most endpoints still ask only for a logged user, though the front shows them to some roles only: a cashier can call them directly. `/user-account` was the worst, because it gave the admin account. A pass over the other writes, endpoint by endpoint, is its own piece of work. | Zein, after the migration |

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
