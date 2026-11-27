package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 3.6.0, conventions step 1 (docs/roadmap/conventions.md, decision 1): convention.enabled. Absent, blank or false: off.
 * True, trimmed and in any case: on. Any other value: off with one WARN, never a refused start. A store and a head
 * office alike.
 */
class ConventionKeyTest {

	private static MockEnvironment store() {
		return new MockEnvironment();
	}

	private static MockEnvironment headOffice() {
		return new MockEnvironment().withProperty("node.type", "HEAD_OFFICE");
	}

	private static void assertOff(MockEnvironment env) {
		NodeOwnership.resolve(env); // the start goes on
		assertFalse(NodeOwnership.isConventionSet(env));
		assertFalse(Installation.CONVENTION.isTrue(env));
		assertFalse(mode(env).isConventionEnabled());
	}

	private static ApplicationModeService mode(MockEnvironment env) {
		try {
			ApplicationModeService mode = new ApplicationModeService();
			java.lang.reflect.Field field = ApplicationModeService.class.getDeclaredField("environment");
			field.setAccessible(true);
			field.set(mode, env);
			java.lang.reflect.Method init = ApplicationModeService.class.getDeclaredMethod("initOwnership");
			init.setAccessible(true);
			init.invoke(mode);
			return mode;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	@DisplayName("No line, a blank line or false: off, and the start goes on (store and head office)")
	void offByDefault() {
		for (MockEnvironment env : new MockEnvironment[] { store(), headOffice(),
				store().withProperty(NodeOwnership.CONVENTION_KEY, ""),
				headOffice().withProperty(NodeOwnership.CONVENTION_KEY, "  "),
				store().withProperty(NodeOwnership.CONVENTION_KEY, " FALSE ") }) {
			assertOff(env);
		}
	}

	@Test
	@DisplayName("true, trimmed and in any case: on, on a store and on a head office")
	void on() {
		for (MockEnvironment env : new MockEnvironment[] { store().withProperty(NodeOwnership.CONVENTION_KEY, "true"),
				headOffice().withProperty(NodeOwnership.CONVENTION_KEY, " True ") }) {
			NodeOwnership.resolve(env);
			assertTrue(NodeOwnership.isConventionSet(env));
			assertTrue(Installation.CONVENTION.isTrue(env));
			assertTrue(mode(env).isConventionEnabled());
		}
	}

	@Test
	@DisplayName("Another value: the module off with one WARN, never a refused start (store and head office)")
	void wrongValue() {
		for (MockEnvironment env : new MockEnvironment[] { store().withProperty(NodeOwnership.CONVENTION_KEY, "yes"),
				headOffice().withProperty(NodeOwnership.CONVENTION_KEY, "1"),
				store().withProperty(NodeOwnership.CONVENTION_KEY, "ture") }) {
			assertOff(env);
		}
	}

	@Test
	@DisplayName("The startup summary says \"conventions on\" only when on")
	void summary() {
		MockEnvironment on = store().withProperty(NodeOwnership.CONVENTION_KEY, "true");
		assertTrue(InstallationSummary.summary(on, NodeOwnership.resolve(on)).contains(", conventions on, "));
		MockEnvironment off = store();
		assertFalse(InstallationSummary.summary(off, NodeOwnership.resolve(off)).contains("conventions"));
	}
}
