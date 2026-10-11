#!/usr/bin/env bash
# RFID step 1: makes the cleaned copy of the Hopeland SDK (original/RFIDReaderAPI.jar) and puts it into the file
# repository libs/maven as com.hopeland:rfid-reader-api:2.11-zs1, the one the store pom takes. Run from Git Bash, from
# anywhere: libs/hopeland/clean-jar.sh. Needs JDK 17+ (jar --date) and Maven. Same input, same output: the entries keep
# one fixed date, so the cleaned jar's hash is the one in README.md.
#
# Removed from the vendor's fat jar: its own copy of Apache Commons Lang 3.8 (org/apache/commons/** with its
# META-INF/LICENSE.txt and NOTICE.txt; the store WAR has commons-lang3-3.10 and two copies in WEB-INF/lib load in no fixed
# order), the obfuscated Swing code a/** (nothing of com.rfidread uses it), the junit resources (junit/**) and a javadoc
# stylesheet.css. Kept: com.rfidread and the USB and serial libraries (javax.usb, org.usb4java, jSerialComm and their
# native files), which com.rfidread.RFIDReader refers to; a TCP reader never loads them.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)/maven"
ORIGINAL="$HERE/original/RFIDReaderAPI.jar"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir "$WORK/x"
(cd "$WORK/x" && jar xf "$ORIGINAL")
rm -rf "$WORK/x/org/apache/commons" "$WORK/x/a" "$WORK/x/junit" "$WORK/x/stylesheet.css" \
	"$WORK/x/META-INF/LICENSE.txt" "$WORK/x/META-INF/NOTICE.txt"
rmdir "$WORK/x/org/apache" 2>/dev/null || true
mv "$WORK/x/META-INF/MANIFEST.MF" "$WORK/MANIFEST.MF"
jar --create --file "$WORK/rfid-reader-api-2.11-zs1.jar" --manifest "$WORK/MANIFEST.MF" \
	--date 2021-11-10T10:53:00Z -C "$WORK/x" .

mvn -q install:install-file -Dfile="$WORK/rfid-reader-api-2.11-zs1.jar" -DgroupId=com.hopeland \
	-DartifactId=rfid-reader-api -Dversion=2.11-zs1 -Dpackaging=jar -DgeneratePom=true -DcreateChecksum=true \
	-DlocalRepositoryPath="$REPO"
# install-file also writes the metadata of a local repository; a file repository read by Maven needs none of it
find "$REPO/com/hopeland" -name 'maven-metadata-local.xml*' -delete
find "$REPO/com/hopeland" -name '_remote.repositories' -delete

sha256sum "$ORIGINAL" "$REPO/com/hopeland/rfid-reader-api/2.11-zs1/rfid-reader-api-2.11-zs1.jar"
