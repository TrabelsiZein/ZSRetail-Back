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

	/** Without an ERP: every owner LOCAL (preset store without its keys). */
	public static ApplicationModeService standalone() {
		return of(new MockEnvironment());
	}

	/** With an ERP: catalogue, customers and supply the ERP's, sales to the ERP (preset store-erp). */
	public static ApplicationModeService erp() {
		return of(erpOwners(new MockEnvironment()));
	}

	/**
	 * Task 9.3: the keys application.standalone=false used to imply, each set only when absent: catalogue, customers and
	 * supply ERP, and on a store the sales to the ERP. Returns the same environment.
	 */
	public static MockEnvironment erpOwners(MockEnvironment env) {
		for (String key : new String[] { "ownership.catalogue", "ownership.customers", "ownership.supply" }) {
			if (!env.containsProperty(key)) {
				env.setProperty(key, "ERP");
			}
		}
		boolean headOffice = "HEAD_OFFICE".equalsIgnoreCase(env.getProperty("node.type", "").trim());
		if (!headOffice && !env.containsProperty("sales.upstream")) {
			env.setProperty("sales.upstream", "ERP");
		}
		return env;
	}

	/** standalone() or erp(). */
	public static ApplicationModeService of(boolean standalone) {
		return standalone ? standalone() : erp();
	}

	/** From the given properties, resolved like the service at startup. */
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

	private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
		Field field = ApplicationModeService.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
