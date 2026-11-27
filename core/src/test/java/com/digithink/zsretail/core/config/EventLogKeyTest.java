package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 3.4.0, event log: eventlog.enabled, as warranty.enabled. Absent, blank (3.5.0) or false: off, the start goes on. True,
 * trimmed and in any case: on, on a store. Any other value stops the start naming the key; so does true on a head office
 * (version 1: the events stay in the store).
 */
class EventLogKeyTest {

	private static MockEnvironment store() {
		return new MockEnvironment();
	}

	private static MockEnvironment headOffice() {
		return new MockEnvironment().withProperty("node.type", "HEAD_OFFICE");
	}

	@Test
	@DisplayName("No line, a blank one (3.5.0) or false: off, and the start goes on (store and head office)")
	void offByDefault() {
		for (MockEnvironment env : new MockEnvironment[] { store(), headOffice(),
				store().withProperty(NodeOwnership.EVENT_LOG_KEY, ""), store().withProperty(NodeOwnership.EVENT_LOG_KEY, "  "),
				headOffice().withProperty(NodeOwnership.EVENT_LOG_KEY, ""),
				store().withProperty(NodeOwnership.EVENT_LOG_KEY, " FALSE "),
				headOffice().withProperty(NodeOwnership.EVENT_LOG_KEY, "false") }) {
			NodeOwnership.resolve(env);
			assertFalse(NodeOwnership.isEventLogSet(env));
		}
	}

	@Test
	@DisplayName("true, trimmed and in any case: on, on a store")
	void onOnAStore() {
		MockEnvironment env = store().withProperty(NodeOwnership.EVENT_LOG_KEY, " True ");
		NodeOwnership.resolve(env);
		assertTrue(NodeOwnership.isEventLogSet(env));
	}

	@Test
	@DisplayName("Another value stops the start naming the key")
	void otherValueRefused() {
		for (String value : new String[] { "yes", "1", "on" }) {
			MockEnvironment env = store().withProperty(NodeOwnership.EVENT_LOG_KEY, value);
			IllegalStateException refused = assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(env));
			assertTrue(refused.getMessage().contains(NodeOwnership.EVENT_LOG_KEY), refused.getMessage());
		}
	}

	@Test
	@DisplayName("true on a head office stops the start: the events stay in the store")
	void refusedOnAHeadOffice() {
		MockEnvironment env = headOffice().withProperty(NodeOwnership.EVENT_LOG_KEY, "true");
		IllegalStateException refused = assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(env));
		assertTrue(refused.getMessage().contains("head office"), refused.getMessage());
		assertThrows(IllegalStateException.class, () -> NodeOwnership.isEventLogSet(env));
	}
}
