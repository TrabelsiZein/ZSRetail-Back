# Deploy note: two applications, two WARs

**Since code cleanup steps 6.5 (head office) and 6.6 (store)** the store and the head office are two applications, each
its WAR. The application is the installation type: a store runs the store WAR, a head office the head office WAR. Each WAR is deployed **alone in its
Tomcat, under the context name `zsretailws`**, as the single WAR was.

| Application | Main class | Built as | Delivered as |
|---|---|---|---|
| store | `StoreApplication` (was `POSMainApp`) | `store/target/zsretail-store-<version>.war` (since step 6.6; `app/target/zsretailws.war` in step 6.5) | `store/zsretailws.war` |
| head office | `HeadOfficeApplication` | `headoffice/target/zsretail-headoffice-<version>.war` | `headoffice/zsretailws.war` |

## Release

`mvn -DskipTests clean package` at the root builds both. The delivery puts each WAR **in its own folder, named by its
application, under the deploy name `zsretailws.war`**: `store/zsretailws.war` and `headoffice/zsretailws.war`. Never two
`zsretailws.war` side by side in one folder: the name says where it is deployed, the folder which application it is.
On this PC `devenv\build.ps1` does the same for the dev instances (`C:\zsretail-dev\artifacts\store\` and
`artifacts\headoffice\`, `zsretailws-<time>-<commit>.war`).

## Installation

- **A store**: `store/zsretailws.war` into its Tomcat's `webapps\`; its outside file stays
  `<Tomcat>\conf\zsretail\zsretailws.properties`.
- **A head office**: `headoffice/zsretailws.war` into its own Tomcat's `webapps\`; the same outside file convention.
- A store and a head office cannot share a Tomcat (both are `zsretailws`); each has its own, as on every site today.
- **The outside file is unchanged**: every key resolves as before (`ConfigurationKeysProofTest`). A new installation needs
  no `spring.profiles.active` and no `node.type` line. An old file that has them keeps working when they name the
  application's type; when they name the other type the start stops with a message naming the application, the file and
  the line, e.g. `This is the head office application (HeadOfficeApplication): it runs an installation of type
  headoffice, but the outside file ...\zsretailws.properties names the type store (spring.profiles.active=store). The
  store runs the store application: deploy its WAR in this Tomcat (under zsretailws), or correct the file ...`.
- **Upgrade of an existing installation**: deploy the WAR of its type (a store: the store WAR; a head office: the head
  office WAR), after the database scripts of the version as before.

## Database

Each application creates and checks only its own tables: a new store database has no head office table (`ho_*`), a new
head office database no store-only table (`hol_*`, inventory counts, payment changes...); an existing database keeps the
tables it has. The start-up column check (`DecimalColumnsCheck`) reads core's columns and its application's only. The
update scripts of the released versions (`core/src/main/resources/db/`) are unchanged: they already skip a table a
database does not have.
