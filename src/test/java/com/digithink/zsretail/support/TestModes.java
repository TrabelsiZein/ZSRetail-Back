package com.digithink.zsretail.support;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.config.ApplicationModeService;

/**
 * Step 9: an ApplicationModeService built like Spring builds it (the @Value flags read from the properties, then the
 * owners resolved), so a test asks the step 9 questions (isCatalogueFromErp...) and gets the answers of a real
 * installation. A service with only its standalone field set has no owners.
 */
public final class TestModes {

	private TestModes() {
	}

	/** Without an ERP (application.standalone=true), every owner derived. */
	public static ApplicationModeService standalone() {
		return of(new MockEnvironment().withProperty("application.standalone", "true"));
	}

	/** With an ERP (application.standalone=false), every owner derived. */
	public static ApplicationModeService erp() {
		return of(new MockEnvironment().withProperty("application.standalone", "false"));
	}

	/** standalone() or erp(). */
	public static ApplicationModeService of(boolean standalone) {
		return standalone ? standalone() : erp();
	}

	/** From the given properties, read like the @Value fields of the service (false when absent). */
	public static ApplicationModeService of(MockEnvironment env) {
		try {
			ApplicationModeService mode = new ApplicationModeService();
			set(mode, "environment", env);
			Method init = ApplicationModeService.class.getDeclaredMethod("initOwnership");
			init.setAccessible(true);
			init.invoke(mode);
			return mode;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean flag(MockEnvironment env, String key) {
		return Boolean.TRUE.equals(env.getProperty(key, Boolean.class, Boolean.FALSE));
	}

	private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
		Field field = ApplicationModeService.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
