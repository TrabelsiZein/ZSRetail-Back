package com.digithink.zsretail.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySource;

/**
 * Code cleanup, step 1: one line that chooses the ERP connector, {@code erp.connector=none | dynamicsnav | navpospages}
 * (trimmed, any case). It is only a translation into the two old lines, done before Spring reads its conditions:
 * dynamicsnav gives erp.dynamicsnav.enabled=true and erp.navpospages.enabled=false, navpospages the inverse, none both
 * false. Every class that reads the old lines works unchanged.
 * <p>
 * Absent: nothing is translated, the two old lines act as before. The translated values sit just above the property
 * source that gives erp.connector, so they win over an old line of a lower source (the type files say
 * erp.dynamicsnav.enabled=false) and an old line of the same or a higher source that says the opposite stops the
 * startup. An unknown value stops it too. See docs/deployment-modes.md.
 */
public class ErpConnectorEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	public static final String CONNECTOR_KEY = "erp.connector";
	static final String DYNAMICS_NAV_ENABLED_KEY = "erp.dynamicsnav.enabled";
	static final String NAVPOSPAGES_ENABLED_KEY = NodeOwnership.NAVPOSPAGES_ENABLED_KEY;
	public static final String NONE = "none";
	public static final String DYNAMICS_NAV = "dynamicsnav";
	public static final String NAVPOSPAGES = "navpospages";
	static final String PROPERTY_SOURCE_NAME = "erpConnector";
	/** Spring Boot's view of all the sources, attached first: never the source of a key. */
	private static final String ATTACHED_SOURCE_NAME = "configurationProperties";

	/** After ConfigFileApplicationListener (HIGHEST_PRECEDENCE + 10), which reads the configuration files. */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 30;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		PropertySource<?> connectorSource = sourceOf(environment, CONNECTOR_KEY);
		if (connectorSource == null) {
			return;
		}
		String connector = String.valueOf(connectorSource.getProperty(CONNECTOR_KEY));
		Map<String, Object> translated = translate(connector);
		for (Map.Entry<String, Object> line : translated.entrySet()) {
			PropertySource<?> oldSource = sourceOf(environment, line.getKey());
			if (oldSource != null && precedence(environment, oldSource) <= precedence(environment, connectorSource)
					&& isTrue(oldSource.getProperty(line.getKey())) != "true".equals(line.getValue())) {
				throw new IllegalStateException("Invalid combination: " + CONNECTOR_KEY + "=" + connector.trim() + " with "
						+ line.getKey() + "=" + String.valueOf(oldSource.getProperty(line.getKey())).trim() + " ("
						+ oldSource.getName() + "). " + CONNECTOR_KEY + "=" + normalized(connector) + " means "
						+ line.getKey() + "=" + line.getValue() + ": remove the old line, or make it agree.");
			}
		}
		environment.getPropertySources().addBefore(connectorSource.getName(),
				new MapPropertySource(PROPERTY_SOURCE_NAME, translated));
	}

	/** The two old lines a value of erp.connector stands for; an unknown value stops the startup naming the key. */
	static Map<String, Object> translate(String connector) {
		String value = normalized(connector);
		if (!NONE.equals(value) && !DYNAMICS_NAV.equals(value) && !NAVPOSPAGES.equals(value)) {
			throw new IllegalStateException("Invalid value '" + connector + "' for property " + CONNECTOR_KEY + ": "
					+ NONE + " | " + DYNAMICS_NAV + " | " + NAVPOSPAGES + " (or leave it out and use the old lines "
					+ DYNAMICS_NAV_ENABLED_KEY + " and " + NAVPOSPAGES_ENABLED_KEY + ").");
		}
		Map<String, Object> lines = new LinkedHashMap<>();
		lines.put(DYNAMICS_NAV_ENABLED_KEY, String.valueOf(DYNAMICS_NAV.equals(value)));
		lines.put(NAVPOSPAGES_ENABLED_KEY, String.valueOf(NAVPOSPAGES.equals(value)));
		return Collections.unmodifiableMap(lines);
	}

	/**
	 * The connector in effect, from the two old lines whichever way they were set (startup summary). Both true is refused
	 * by NavPosPagesStartupCheck.
	 */
	public static String inEffect(PropertyResolver env) {
		if (isTrue(env.getProperty(DYNAMICS_NAV_ENABLED_KEY))) {
			return DYNAMICS_NAV;
		}
		return isTrue(env.getProperty(NAVPOSPAGES_ENABLED_KEY)) ? NAVPOSPAGES : NONE;
	}

	private static String normalized(String connector) {
		return connector == null ? "" : connector.trim().toLowerCase(Locale.ROOT);
	}

	private static boolean isTrue(Object value) {
		return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
	}

	/** The first property source (the one in effect) that holds the key, null when none does. */
	private static PropertySource<?> sourceOf(ConfigurableEnvironment environment, String key) {
		for (PropertySource<?> source : environment.getPropertySources()) {
			if (!ATTACHED_SOURCE_NAME.equals(source.getName()) && source.containsProperty(key)) {
				return source;
			}
		}
		return null;
	}

	/** Position among the property sources: lower wins. */
	private static int precedence(ConfigurableEnvironment environment, PropertySource<?> source) {
		return environment.getPropertySources().precedenceOf(source);
	}
}
