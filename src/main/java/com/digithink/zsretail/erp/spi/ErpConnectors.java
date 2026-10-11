package com.digithink.zsretail.erp.spi;

import java.util.List;

import org.springframework.core.env.PropertyResolver;
import org.springframework.core.io.support.SpringFactoriesLoader;

/**
 * Code cleanup step 5: the names of the ERP connectors, the values of erp.connector: one per customer (EMTOP's
 * Dynamics NAV, Happyness's POS pages), even when two ERPs look alike. The connector in effect is read through the
 * application's {@link ErpConnectorResolver}.
 */
public final class ErpConnectors {

	public static final String NONE = "none";
	/** EMTOP's Dynamics NAV / Business Central (its keys: the dynamicsnav connector package). */
	public static final String EMTOP = "emtop";
	/** Happyness's "POS pages" (its keys: the navpospages connector package). */
	public static final String HAPPYNESS = "happyness";

	private static volatile ErpConnectorResolver resolver;

	private ErpConnectors() {
	}

	/** The connector in effect; none when the application gives no resolver. */
	public static String inEffect(PropertyResolver env) {
		return resolver().inEffect(env);
	}

	private static ErpConnectorResolver resolver() {
		ErpConnectorResolver found = resolver;
		if (found == null) {
			List<ErpConnectorResolver> resolvers = SpringFactoriesLoader.loadFactories(ErpConnectorResolver.class,
					ErpConnectors.class.getClassLoader());
			found = resolvers.isEmpty() ? env -> NONE : resolvers.get(0);
			resolver = found;
		}
		return found;
	}
}
