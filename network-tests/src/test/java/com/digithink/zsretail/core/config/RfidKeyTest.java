package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.support.Installations;

/**
 * RFID step 2: rfid.enabled, false in the store type file, strictly true or false; true on a store gives the question
 * Installation.RFID, /config rfidEnabled and "RFID on" in the startup summary; true on a head office stops the startup.
 * Every file of configs/ (configs/local apart, edited on this machine) and both types without an outside file keep RFID
 * off: EMTOP and Happyness see no difference. Plain environments (no Spring context).
 */
class RfidKeyTest {

	private static final String KEY = "rfid.enabled";

	private static MockEnvironment store() {
		return Installations.type("store");
	}

	private static MockEnvironment headOffice() {
		return Installations.type("headoffice");
	}

	/** Off: no question, /config false, no summary word, the configuration starts. */
	private static void assertOff(MockEnvironment env, String label) throws Exception {
		NodeOwnership resolved = NodeOwnership.resolve(env);
		assertFalse(NodeOwnership.isRfidSet(env), label);
		assertFalse(Installation.RFID.isTrue(env), label);
		assertFalse(TestModes.of(env).isRfidEnabled(), label);
		assertFalse(HeadOfficeStockKeyTest.config(env).isRfidEnabled(), label);
		assertFalse(InstallationSummary.summary(env, resolved).contains("RFID"), label);
	}

	@Test
	@DisplayName("The store type file says false; the head office type file has no line: RFID off in both")
	void typeFiles() throws Exception {
		assertEquals("false", store().getProperty(KEY));
		assertOff(store(), "store");
		assertFalse(headOffice().containsProperty(KEY));
		assertOff(headOffice(), "head office");
		assertOff(new MockEnvironment(), "no line at all");
	}

	@Test
	@DisplayName("Every file of configs/ (EMTOP, Happyness, dev, rehearsal, customers): RFID off, no line in any file")
	void everyFileOff() throws Exception {
		for (String file : Installations.machineFiles()) {
			assertFalse(Installations.file(Installations.MACHINES.resolve(file)).containsKey(KEY), file);
			assertOff(Installations.machine(file), file);
		}
		for (String variant : Installations.variants()) {
			if (!variant.startsWith("network")) { // a network shape needs its head office link, given by the files above
				assertOff(Installations.preset(variant), "shape " + variant);
			}
		}
	}

	@Test
	@DisplayName("true on a store (any case and spacing): the question, /config true, the summary says RFID on")
	void storeOn() throws Exception {
		for (String value : Arrays.asList("true", " TRUE ", "True")) {
			MockEnvironment env = store().withProperty(KEY, value);
			NodeOwnership resolved = NodeOwnership.resolve(env);
			assertTrue(NodeOwnership.isRfidSet(env), value);
			assertTrue(Installation.RFID.isTrue(env), value);
			assertTrue(TestModes.of(env).isRfidEnabled(), value);
			assertTrue(HeadOfficeStockKeyTest.config(env).isRfidEnabled(), value);
			assertTrue(InstallationSummary.summary(env, resolved).contains(", RFID on, ERP connector "), value);
		}
		// With an ERP or linked to a head office as well
		assertTrue(NodeOwnership.isRfidSet(Installations.machine("dev/store-b.properties").withProperty(KEY, "true")));
		assertTrue(NodeOwnership.isRfidSet(Installations.machine("customers/erp-prod.properties").withProperty(KEY, "true")));
	}

	@Test
	@DisplayName("false on a store, any case: off")
	void storeOff() throws Exception {
		for (String value : Arrays.asList("false", " FALSE ", "False")) {
			assertOff(store().withProperty(KEY, value), value);
		}
	}

	@Test
	@DisplayName("Strict: any other value stops the startup with a message naming the key, on a store and on a head office")
	void strict() {
		for (String value : Arrays.asList("flase", "", " ", "yes", "1", "on")) {
			for (MockEnvironment env : Arrays.asList(store().withProperty(KEY, value), headOffice().withProperty(KEY, value))) {
				IllegalStateException e = assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(env), value);
				assertEquals("Invalid value '" + value + "' for property rfid.enabled: true (this store reads RFID tags) or"
						+ " false (no RFID)", e.getMessage());
				assertThrows(IllegalStateException.class, () -> Installation.RFID.isTrue(env), value);
			}
		}
	}

	@Test
	@DisplayName("true on a head office stops the startup; false there is accepted")
	void headOfficeTrueRefused() throws Exception {
		for (String value : Arrays.asList("true", " TRUE ")) {
			MockEnvironment env = headOffice().withProperty(KEY, value);
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(env), value);
			assertEquals("Invalid value '" + value + "' for property rfid.enabled: a head office reads no RFID tags (the"
					+ " readers belong to a store). Remove the line.", e.getMessage());
			assertThrows(IllegalStateException.class, () -> TestModes.of(env), value);
		}
		assertOff(headOffice().withProperty(KEY, "false"), "head office, false");
	}
}
