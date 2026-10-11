# Hopeland RFID reader SDK

The store application talks to the Hopeland RFID readers (HZ100, TCP/IP) with the vendor's Java SDK. This folder keeps it
in the repo, so the build needs nothing outside it (RFID step 1, release 3.1.0).

| File | What |
|---|---|
| `original/RFIDReaderAPI.jar` | the vendor's jar, unchanged: `PCReaderJava_V2.11_211110/Libs/RFIDReaderAPI.jar` of the Hopeland Java SDK 2.11 (build 2021-11-10), 2,258,272 bytes. **Not used by the build** |
| `clean-jar.sh` | makes the cleaned copy from the original and puts it into `../maven` |
| `../maven/com/hopeland/rfid-reader-api/2.11-zs1/` | the cleaned copy, `com.hopeland:rfid-reader-api:2.11-zs1`, the one the store pom takes (its repository `zsretail-libs`, `file:${project.basedir}/../libs/maven`) |

SHA-256:
- original: `8060f99483a509d536641dff57a7dfad18862b48a5a26135ac0810b1f8b8a5b6`
- cleaned copy: `612cfb12d15f686174abda6707d32e021fd8defec724f8e1f8c4c91200093f15`

## Why a cleaned copy

The vendor's jar is a fat jar. Besides the SDK (`com.rfidread`) it carries its own Apache Commons Lang 3.8
(`org.apache.commons.lang3`), while the store WAR has `commons-lang3-3.10.jar`: the same classes twice in `WEB-INF/lib`,
in an order Tomcat does not fix, and a library built for 3.10 can fail on the old copy. The SDK's own classes never use
Commons Lang (`jdeps`: only `org.usb4java` does, on the USB path).

Removed: `org/apache/commons/**` with its `META-INF/LICENSE.txt` and `NOTICE.txt`, the obfuscated Swing code `a/**`
(nothing of `com.rfidread` uses it), the junit resources `junit/**` (images and a properties file, no class), a javadoc
`stylesheet.css`.

Kept: `com.rfidread`, the top-level class `a`, and the USB and serial libraries (`javax.usb`, `org.usb4java`,
`com.fazecast.jSerialComm` and their native files): `com.rfidread.RFIDReader` refers to them; a TCP reader never loads
them. None of them is in the store WAR otherwise.

The classes are Java 8 (class version 52) and use no JDK internal API (`jdeps --jdk-internals`): they run on Java 11 to
25.

## Making it again

From Git Bash, with JDK 17 or later and Maven: `libs/hopeland/clean-jar.sh`. The entries keep one fixed date, so the
cleaned copy has the hash above. A new SDK version: replace `original/`, change the version in the script (a new
`-zsN` for a new cleaning of the same SDK) and in `store/pom.xml`, update the two hashes here.

Checked on the reader of the dev desk (192.168.100.200:9090) with the proof program of RFID step 0: the cleaned copy
reads the same 15 tags as the original.
