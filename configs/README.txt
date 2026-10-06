ZS Retail - one file per installation, outside the WAR
======================================================

Inside the WAR (src/main/resources) there are only three files, and they hold NO database, port or folder:
  application.properties             common lines
  application-store.properties       what a store is
  application-headoffice.properties  what a head office is

The only two profiles are: store, headoffice. "store1" is not a profile.

Every installation has its own file. It names the type (spring.profiles.active=store | headoffice) and says where
it runs: database, port, log file, upload folder, NAV connection, head office address and key. Its keys win over
the three files above. Without it the application has no database and does not start.

local\   this PC, the Happyness test: happyness_ho, happyness_store1, happyness_store2.
         Given to Eclipse by the VM argument of a debug configuration:
           -Dzsretail.machine-file="${project_loc:zsretail}/configs/local/happyness_store1.properties"

On a server: copy one of these files into the Tomcat as conf\zsretail\zsretailws.properties (the name of the WAR)
and change its values (database, folders, head office address and key). The same WAR goes in every Tomcat and is
never edited.

How to check: the log line "Installation: type ..., database ..., outside file ..." at startup.
