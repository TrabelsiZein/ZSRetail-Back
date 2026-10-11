package com.digithink.zsretail.erp.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.core.env.PropertyResolver;
import org.springframework.core.io.support.SpringFactoriesLoader;

/**
 * Code cleanup step 5: the values of erp.connector, one per customer, even when two ERPs look alike. Code cleanup step
 * 7: erp-api names none of them: the connectors found declare themselves ({@link ErpConnectorDescriptor}, in their
 * jar's META-INF/spring.factories), and {@link #NONE} is the value for no connector. The connector in effect is read
 * through the application's {@link ErpConnectorResolver}.
 */
public final class ErpConnectors {

	public static final String NONE = "none";

	private static volatile ErpConnectorResolver resolver;

	private ErpConnectors() {
	}

	/**
	 * The connectors found, sorted by name: the descriptors of the jars of the class loader in use (the thread's when it
	 * sees erp-api, else erp-api's own). Two with one name stop the startup.
	 */
	public static List<ErpConnectorDescriptor> descriptors() {
		List<ErpConnectorDescriptor> found = new ArrayList<>(
				SpringFactoriesLoader.loadFactories(ErpConnectorDescriptor.class, loader()));
		found.sort(Comparator.comparing(ErpConnectorDescriptor::name));
		for (int i = 1; i < found.size(); i++) {
			if (found.get(i).name().equals(found.get(i - 1).name())) {
				throw new IllegalStateException("Two ERP connectors are named " + found.get(i).name() + ": "
						+ found.get(i - 1).getClass().getName() + " and " + found.get(i).getClass().getName());
			}
		}
		return found;
	}

	/** The connector of that name, null for none or an unknown name. */
	public static ErpConnectorDescriptor descriptor(String name) {
		return descriptors().stream().filter(descriptor -> descriptor.name().equals(name)).findFirst().orElse(null);
	}

	/** The accepted values of erp.connector: none, then the connectors found, by name. */
	public static List<String> names() {
		List<String> names = new ArrayList<>();
		names.add(NONE);
		names.addAll(descriptors().stream().map(ErpConnectorDescriptor::name).collect(Collectors.toList()));
		return names;
	}

	/** The connector in effect; none when the application gives no resolver. */
	public static String inEffect(PropertyResolver env) {
		return resolver().inEffect(env);
	}

	/** A head office whose catalogue only comes from the ERP ({@link ErpConnectorResolver#isErpCatalogueOnlyHeadOffice}). */
	public static boolean isErpCatalogueOnlyHeadOffice(PropertyResolver env) {
		return resolver().isErpCatalogueOnlyHeadOffice(env);
	}

	/** The thread's class loader when it sees erp-api (a webapp, a test that adds a connector), else erp-api's. */
	private static ClassLoader loader() {
		ClassLoader own = ErpConnectors.class.getClassLoader();
		ClassLoader thread = Thread.currentThread().getContextClassLoader();
		if (thread != null && thread != own) {
			try {
				if (Class.forName(ErpConnectors.class.getName(), false, thread) == ErpConnectors.class) {
					return thread;
				}
			} catch (ClassNotFoundException e) {
				// a loader that does not see erp-api: erp-api's own
			}
		}
		return own;
	}

	private static ErpConnectorResolver resolver() {
		ErpConnectorResolver found = resolver;
		if (found == null) {
			List<ErpConnectorResolver> resolvers = SpringFactoriesLoader.loadFactories(ErpConnectorResolver.class,
					ErpConnectors.class.getClassLoader());
			found = resolvers.isEmpty() ? new Nothing() : resolvers.get(0);
			resolver = found;
		}
		return found;
	}

	/** Without the application: no connector, not a head office. */
	private static final class Nothing implements ErpConnectorResolver {

		@Override
		public String inEffect(PropertyResolver env) {
			return NONE;
		}

		@Override
		public boolean isErpCatalogueOnlyHeadOffice(PropertyResolver env) {
			return false;
		}
	}
}
