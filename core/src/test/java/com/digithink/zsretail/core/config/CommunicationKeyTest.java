package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 3.3.0, communication module, step 1: communication.enabled. Absent, empty or false: off, and the start goes on as in
 * 3.2.0 (no refusal). True, trimmed and in any case: on. Any other value stops the start naming the key. A store and a
 * head office alike.
 */
class CommunicationKeyTest {

	private static MockEnvironment store() {
		return new MockEnvironment();
	}

	private static MockEnvironment headOffice() {
		return new MockEnvironment().withProperty("node.type", "HEAD_OFFICE");
	}

	@Test
	@DisplayName("No line, an empty line or false: off, and the start goes on (store and head office)")
	void offByDefault() {
		for (MockEnvironment env : new MockEnvironment[] { store(), headOffice(),
				store().withProperty(NodeOwnership.COMMUNICATION_KEY, ""),
				headOffice().withProperty(NodeOwnership.COMMUNICATION_KEY, "  "),
				store().withProperty(NodeOwnership.COMMUNICATION_KEY, " FALSE ") }) {
			NodeOwnership.resolve(env);
			assertFalse(NodeOwnership.isCommunicationSet(env));
			assertFalse(Installation.COMMUNICATION.isTrue(env));
		}
	}

	@Test
	@DisplayName("true, trimmed and in any case: on, on a store and on a head office")
	void on() {
		for (MockEnvironment env : new MockEnvironment[] { store().withProperty(NodeOwnership.COMMUNICATION_KEY, "true"),
				headOffice().withProperty(NodeOwnership.COMMUNICATION_KEY, " True ") }) {
			NodeOwnership.resolve(env);
			assertTrue(NodeOwnership.isCommunicationSet(env));
			assertTrue(Installation.COMMUNICATION.isTrue(env));
		}
	}

	@Test
	@DisplayName("Another value stops the start, the message naming the key")
	void refused() {
		MockEnvironment env = store().withProperty(NodeOwnership.COMMUNICATION_KEY, "yes");
		String message = assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(env)).getMessage();
		assertEquals("Invalid value 'yes' for property communication.enabled: true (the communication module is on) or"
				+ " false (off, also when the line is absent or empty)", message);
	}
}
