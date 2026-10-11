package com.digithink.zsretail.core.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySource;

import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;
import com.digithink.zsretail.erp.api.ErpConnectors;

/**
 * Code cleanup, step 1: one line that chooses the ERP connector, {@code erp.connector=none | <a connector found>}
 * (trimmed, any case; the customers' names since step 5). It is only a translation into the old enabled lines of the
 * connectors that have one, done before Spring reads its conditions: the chosen connector's line true, the others
 * false, none all false. Every class that reads the old lines works unchanged.
 * <p>
 * Absent: nothing is translated, the old lines act as before. The translated values sit just above the property
 * source that gives erp.connector, so they win over an old line of a lower source (the type files say it false) and an old line of the same or a higher source that says the opposite stops the
 * startup. An unknown value stops it too. See docs/deployment-modes.md.
 * <p>
 * Code cleanup, step 2: the application asks {@link com.digithink.zsretail.erp.api.ErpAbilities} what the connector in
 * effect can do. Code cleanup step 7: this class names no connector either: the accepted values, the old lines and
 * the summary come from the connectors found ({@link ErpConnectorDescriptor}); a connector with no old line is chosen by erp.connector only.
 */
public class ErpConnectorEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	public static final String CONNECTOR_KEY = "erp.connector";
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

	/** The old lines a value of erp.connector stands for; an unknown value stops the startup naming the key. */
	static Map<String, Object> translate(String connector) {
		String value = normalized(connector);
		List<String> names = ErpConnectors.names();
		List<ErpConnectorDescriptor> withOldLine = ErpConnectors.descriptors().stream()
				.filter(descriptor -> descriptor.enabledKey() != null).collect(Collectors.toList());
		if (!names.contains(value)) {
			throw new IllegalStateException("Invalid value '" + connector + "' for property " + CONNECTOR_KEY + ": "
					+ String.join(" | ", names) + (withOldLine.isEmpty() ? "" : " (or leave it out and use the old lines "
							+ withOldLine.stream().map(ErpConnectorDescriptor::enabledKey).collect(Collectors.joining(" and ")) + ")")
					+ ".");
		}
		Map<String, Object> lines = new LinkedHashMap<>();
		for (ErpConnectorDescriptor descriptor : withOldLine) {
			lines.put(descriptor.enabledKey(), String.valueOf(descriptor.name().equals(value)));
		}
		return Collections.unmodifiableMap(lines);
	}

	/**
	 * The connector in effect, whichever way it was chosen (startup summary, the resolver): the first connector, by name,
	 * whose old line is true; else a connector with no old line named by erp.connector; else none. Two old lines true
	 * are refused by the connector itself (its startup check).
	 */
	public static String inEffect(PropertyResolver env) {
		List<ErpConnectorDescriptor> descriptors = ErpConnectors.descriptors();
		for (ErpConnectorDescriptor descriptor : descriptors) {
			if (descriptor.enabledKey() != null && isTrue(env.getProperty(descriptor.enabledKey()))) {
				return descriptor.name();
			}
		}
		String named = normalized(env.getProperty(CONNECTOR_KEY));
		for (ErpConnectorDescriptor descriptor : descriptors) {
			if (descriptor.enabledKey() == null && descriptor.name().equals(named)) {
				return descriptor.name();
			}
		}
		return ErpConnectors.NONE;
	}

	/**
	 * Code cleanup, step 2: how to choose one of these connectors, for a refusal message (the application never names a
	 * connector key itself): {@code erp.connector=<name> (or the old line <its enabled key>=true, still accepted)},
	 * several joined with "or".
	 */
	public static String howToChoose(List<String> connectors) {
		StringJoiner choices = new StringJoiner(" or ");
		for (String connector : connectors) {
			ErpConnectorDescriptor descriptor = ErpConnectors.descriptor(connector);
			String oldLine = descriptor == null ? null : descriptor.enabledKey();
			choices.add(CONNECTOR_KEY + "=" + connector + (oldLine == null ? ""
					: " (or the old line " + oldLine + "=true, still accepted)"));
		}
		return choices.toString();
	}

	/**
	 * Code cleanup, step 2: what the startup summary adds after the connector in effect, null when nothing: what its
	 * descriptor gives ({@code <label> <value of its key>}, e.g. a NAV address).
	 */
	public static String summaryAddress(PropertyResolver env) {
		ErpConnectorDescriptor descriptor = ErpConnectors.descriptor(inEffect(env));
		if (descriptor == null || descriptor.summaryLabel() == null) {
			return null;
		}
		return descriptor.summaryLabel() + " " + env.getProperty(descriptor.summaryKey(), "(no address)");
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
