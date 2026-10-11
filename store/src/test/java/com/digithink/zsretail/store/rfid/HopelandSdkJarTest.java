package com.digithink.zsretail.store.rfid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rfidread.RFIDReader;

/**
 * RFID step 1: the store takes the cleaned copy of the Hopeland SDK (libs/hopeland/README.md). Its entry class loads and
 * initialises without a reader, and the jar carries none of what was removed, so Commons Lang comes from the store's own
 * commons-lang3 only.
 */
class HopelandSdkJarTest {

	@Test
	@DisplayName("The SDK's entry class loads and initialises without a reader")
	void sdkLoads() {
		assertNotNull(RFIDReader._Tag6C);
		assertNotNull(RFIDReader._Config);
	}

	@Test
	@DisplayName("The SDK jar is the cleaned copy: no Commons Lang, no Swing code a/, no junit")
	void sdkJarIsCleaned() throws Exception {
		File jar = new File(RFIDReader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		assertEquals("rfid-reader-api-2.11-zs1.jar", jar.getName());
		try (JarFile file = new JarFile(jar)) {
			List<String> removed = file.stream().map(e -> e.getName())
					.filter(n -> n.startsWith("org/apache/") || n.startsWith("a/") || n.startsWith("junit/"))
					.collect(Collectors.toList());
			assertTrue(removed.isEmpty(), "entries that the cleaned copy must not have: " + removed);
		}
	}

	@Test
	@DisplayName("Commons Lang comes from commons-lang3, not from the SDK")
	void commonsLangIsTheStores() throws Exception {
		File jar = new File(StringUtils.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		assertTrue(jar.getName().startsWith("commons-lang3-"), "StringUtils loaded from " + jar.getName());
	}
}
