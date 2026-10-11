ZS Retail - one file per installation, outside the WAR
======================================================

Two applications, each its WAR (docs/deploy-note.md): the store (StoreApplication) and the head office
(HeadOfficeApplication). The application is the installation type. Inside the WARs there are only three configuration
files, and they hold NO database, port or folder:
  application.properties             common lines (in core, in both WARs)
  application-store.properties       what a store is (the store WAR)
  application-headoffice.properties  what a head office is (the head office WAR)

Every installation has its own file. It says where it runs: database, port, log file, upload folder, NAV connection,
head office address and key; it may change an owner. It names no type (the application is the type); an old file
that names it (spring.profiles.active, node.type) keeps working when it names the application's type. Its keys win
over the three files above.

This folder holds every such file of the repository (code cleanup step 7: deploy/ joined it):
  machine-model.properties   the model: copy it for a new installation
  dev\                       this PC's dev instances (store A, store B, store C, the dev head office, with NAV)
  rehearsal\                 the rehearsal pair (devenv\rehearsal.ps1)
  customers\                 customers' values without their passwords
  happyness-from-scratch\    the Happyness franchise as first set up on this PC on 2026-10-04: a head office and two
                             stores without the ERP catalogue (ports 889, 557, 558)
  local\                     this PC, the Happyness test with its ERP catalogue: happyness_ho, happyness_store1,
                             happyness_store2 (committed with CHANGE_ME; filled on the PC only)

Given to the IDE by the VM argument of a launch (eclipse\*.launch), e.g.
  -Dzsretail.machine-file="${project_loc:zsretail-parent}/configs/dev/store-b.properties"

On a server: copy one of these files into the Tomcat as conf\zsretail\zsretailws.properties (the WAR is deployed as
zsretailws) and change its values (database, folders, head office address and key). The WAR of the installation's
type goes in its Tomcat and is never edited.

How to check: the log line "Installation: type ..., database ..., outside file ..." at startup.

ERP connector "POS pages" (erp.navpospages.*, ERP catalogue step 5): a head office whose catalogue only comes from a NAV /
Business Central with the pages ItemCategory, PointStockPOS and ItemBarCodePOS. Its lines go in that head office's own
file here (local\happyness_ho.properties has them), never in the WAR: enabled, base-url, company, domain, username,
password (CHANGE_ME in the repository, filled on the PC only), location-code, default-vat, price-includes-vat.
GET only: it never writes to the ERP. List of keys and startup refusals: docs/deployment-modes.md.
