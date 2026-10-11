package com.digithink.zsretail.erp.api;

import java.util.Set;

/**
 * Code cleanup step 7: what a connector says about itself, so that erp-api and the application name no customer. Each
 * connector project declares one in its META-INF/spring.factories
 * ({@code com.digithink.zsretail.erp.api.ErpConnectorDescriptor=<its class>}); {@link ErpConnectors#descriptors()}
 * finds them before the beans exist (the conditions use them). Its name is a value of erp.connector, its abilities
 * answer {@link ErpAbilities}, and an old enabled line, when it has one, still selects it.
 */
public interface ErpConnectorDescriptor {

	/** Its value of erp.connector: the customer's name, lower case. */
	String name();

	/** What it can do. */
	Set<ErpAbility> abilities();

	/** The prefix of its keys in the configuration: erp.&lt;name&gt; unless it says otherwise (an older connector's keys). */
	default String keyPrefix() {
		return "erp." + name();
	}

	/**
	 * The old line that still selects it when erp.connector is absent ({@code <keyPrefix>.enabled=true}), null when it has
	 * none (it is then chosen by erp.connector only).
	 */
	default String enabledKey() {
		return null;
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
