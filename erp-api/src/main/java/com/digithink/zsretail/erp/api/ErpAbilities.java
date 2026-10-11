package com.digithink.zsretail.erp.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.env.PropertyResolver;


/**
 * Code cleanup, step 2: for each connector name of erp.connector, what it can do. The application never names a
 * connector: it asks whether the connector in effect has an ability. Read from the environment (the connector in effect
 * is worked out from the values in effect, whichever way they were set), so the conditions use it before the beans
 * exist. See docs/modules/erp-sync.md, "Abilities".
 */
public final class ErpAbilities {

	private static final Map<String, Set<ErpAbility>> BY_CONNECTOR;

	static {
		Map<String, Set<ErpAbility>> map = new LinkedHashMap<>();
		map.put(ErpConnectors.NONE, Collections.unmodifiableSet(EnumSet.noneOf(ErpAbility.class)));
		map.put(ErpConnectors.EMTOP,
				Collections.unmodifiableSet(EnumSet.noneOf(ErpAbility.class)));
		map.put(ErpConnectors.HAPPYNESS, Collections.unmodifiableSet(
				EnumSet.of(ErpAbility.HEAD_OFFICE_STOCK_POINTS, ErpAbility.SUPPLY_INVOICES)));
		BY_CONNECTOR = Collections.unmodifiableMap(map);
	}

	private ErpAbilities() {
	}

	/** The connector in effect: none, emtop or happyness ({@link ErpConnectors}). */
	public static String connectorInEffect(PropertyResolver env) {
		return ErpConnectors.inEffect(env);
	}

	/** True when there is a connector in effect (not none). */
	public static boolean hasConnector(PropertyResolver env) {
		return !ErpConnectors.NONE.equals(connectorInEffect(env));
	}

	/** What the connector of that name can do; an unknown name throws. */
	public static Set<ErpAbility> of(String connector) {
		Set<ErpAbility> abilities = BY_CONNECTOR.get(connector);
		if (abilities == null) {
			throw new IllegalArgumentException("Unknown ERP connector '" + connector + "': " + BY_CONNECTOR.keySet());
		}
		return abilities;
	}

	/** True when the connector in effect has the ability. */
	public static boolean has(PropertyResolver env, ErpAbility ability) {
		return of(connectorInEffect(env)).contains(ability);
	}

	/** The connector names that have the ability, in the order none, emtop, happyness (refusal messages). */
	public static List<String> connectorsWith(ErpAbility ability) {
		return BY_CONNECTOR.entrySet().stream().filter(entry -> entry.getValue().contains(ability))
				.map(Map.Entry::getKey).collect(Collectors.toList());
	}
}
