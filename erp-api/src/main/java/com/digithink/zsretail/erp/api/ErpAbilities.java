package com.digithink.zsretail.erp.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.env.PropertyResolver;

/**
 * Code cleanup, step 2: for each connector name of erp.connector, what it can do. The application never names a
 * connector: it asks whether the connector in effect has an ability. Read from the environment (the connector in effect
 * is worked out from the values in effect, whichever way they were set), so the conditions use it before the beans
 * exist. Code cleanup step 7: the abilities are those each connector declares ({@link ErpConnectorDescriptor}); none
 * has none. See docs/modules/erp-sync.md, "Abilities".
 */
public final class ErpAbilities {

	private ErpAbilities() {
	}

	/** The connector in effect: none or the name of a connector found ({@link ErpConnectors}). */
	public static String connectorInEffect(PropertyResolver env) {
		return ErpConnectors.inEffect(env);
	}

	/** True when there is a connector in effect (not none). */
	public static boolean hasConnector(PropertyResolver env) {
		return !ErpConnectors.NONE.equals(connectorInEffect(env));
	}

	/** What the connector of that name can do; an unknown name throws. */
	public static Set<ErpAbility> of(String connector) {
		if (ErpConnectors.NONE.equals(connector)) {
			return Collections.unmodifiableSet(EnumSet.noneOf(ErpAbility.class));
		}
		ErpConnectorDescriptor descriptor = ErpConnectors.descriptor(connector);
		if (descriptor == null) {
			throw new IllegalArgumentException("Unknown ERP connector '" + connector + "': " + ErpConnectors.names());
		}
		return Collections.unmodifiableSet(descriptor.abilities().isEmpty() ? EnumSet.noneOf(ErpAbility.class)
				: EnumSet.copyOf(descriptor.abilities()));
	}

	/** True when the connector in effect has the ability. */
	public static boolean has(PropertyResolver env, ErpAbility ability) {
		return of(connectorInEffect(env)).contains(ability);
	}

	/** The connector names that have the ability, in the order of {@link ErpConnectors#names()} (refusal messages). */
	public static List<String> connectorsWith(ErpAbility ability) {
		return ErpConnectors.names().stream().filter(name -> of(name).contains(ability)).collect(Collectors.toList());
	}
}
