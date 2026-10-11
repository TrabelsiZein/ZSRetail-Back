package com.digithink.zsretail.erp.api;

import java.util.Collections;
import java.util.Set;

/**
 * Code cleanup step 7: what a connector says about itself, so that erp-api and the application name no customer. Each
 * connector project declares one in its META-INF/spring.factories
 * ({@code com.digithink.zsretail.erp.api.ErpConnectorDescriptor=<its class>}); {@link ErpConnectors#descriptors()}
 * finds them before the beans exist (the conditions use them). Its name is a value of erp.connector, the only line
 * that chooses it (code cleanup step 8), and its abilities answer {@link ErpAbilities}.
 */
public interface ErpConnectorDescriptor {

	/** Its value of erp.connector: the customer's name, lower case. */
	String name();

	/** What it can do. */
	Set<ErpAbility> abilities();

	/** The prefix of its keys in the configuration: erp.&lt;name&gt; for every connector (code cleanup step 8). */
	default String keyPrefix() {
		return "erp." + name();
	}

	/**
	 * Code cleanup step 8: the prefix of its keys up to 2.2.2, null when it had none. A key under it refuses the startup,
	 * whatever its value: its old enabled line ({@code <old prefix>.enabled}, the connector is chosen by erp.connector only)
	 * and every other key, named with its new name (the same key under {@link #keyPrefix()}).
	 */
	default String oldKeyPrefix() {
		return null;
	}

	/**
	 * Code cleanup step 8: the keys of its block that nothing reads any more, without the prefix (e.g. location-code).
	 * Under its prefix or its old one, any of them refuses the startup with "remove the line", never a new name.
	 */
	default Set<String> deadKeys() {
		return Collections.emptySet();
	}

	/**
	 * What the startup summary shows after its name, as "label value": the label (e.g. NAV) and the key of the value (e.g.
	 * its address); null label for nothing.
	 */
	default String summaryLabel() {
		return null;
	}

	/** The key of the value the startup summary shows after {@link #summaryLabel()}. */
	default String summaryKey() {
		return null;
	}
}
