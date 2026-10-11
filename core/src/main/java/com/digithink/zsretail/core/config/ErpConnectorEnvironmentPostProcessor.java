package com.digithink.zsretail.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySource;

import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;
import com.digithink.zsretail.erp.api.ErpConnectors;

/**
 * Code cleanup, step 1: one line that chooses the ERP connector, {@code erp.connector=none | <a connector found>}
 * (trimmed, any case; the customers' names since step 5). Code cleanup step 8: it is the only way. Absent or none: no
 * connector. The conditions and the connectors read it ({@link #inEffect}); an unknown value stops the startup.
 * <p>
 * The old enabled lines of the connectors (their {@code <old prefix>.enabled}, {@link ErpConnectorDescriptor#oldKeyPrefix})
 * are no longer read: one found in any property source, whatever its value, stops the startup naming the line, where it
 * is and what to write instead. See docs/deployment-modes.md.
 * <p>
 * Code cleanup, step 2: the application asks {@link com.digithink.zsretail.erp.api.ErpAbilities} what the connector in
 * effect can do. Code cleanup step 7: this class names no connector either: the accepted values, the old lines and the
 * summary come from the connectors found ({@link ErpConnectorDescriptor}).
 */
public class ErpConnectorEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	public static final String CONNECTOR_KEY = "erp.connector";

	/** After ConfigFileApplicationListener (HIGHEST_PRECEDENCE + 10), which reads the configuration files. */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 30;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		refuseOldLines(environment);
		inEffect(environment);
	}

	/** Every old line of the connectors found, in every source: refused together, each with what to write instead. */
	static void refuseOldLines(ConfigurableEnvironment environment) {
		List<String> lines = new ArrayList<>();
		for (ErpConnectorDescriptor descriptor : ErpConnectors.descriptors()) {
			if (descriptor.oldKeyPrefix() == null) {
				continue;
			}
			String key = descriptor.oldKeyPrefix() + ".enabled";
			for (PropertySource<?> source : ConfigurationSources.holding(environment, key)) {
				Object value = source.getProperty(key);
				lines.add(key + "=" + (value == null ? "" : String.valueOf(value).trim()) + " in "
						+ ConfigurationSources.describe(environment, source) + ": "
						+ (isTrue(value) ? "write " + CONNECTOR_KEY + "=" + descriptor.name() + " instead" : "remove the line"));
			}
		}
		if (!lines.isEmpty()) {
			throw new IllegalStateException((lines.size() == 1 ? "Old line" : "Old lines") + " no longer read: "
					+ String.join("; ", lines) + ". The ERP connector is chosen by " + CONNECTOR_KEY + " only ("
					+ String.join(" | ", ErpConnectors.names()) + "; no line: none).");
		}
	}

	/**
	 * The connector in effect (the conditions, the startup summary, the resolver): the value of erp.connector, trimmed and
	 * lower case; none when the line is absent. An unknown value (blank included) throws naming the key and the values.
	 */
	public static String inEffect(PropertyResolver env) {
		String connector = env.getProperty(CONNECTOR_KEY);
		if (connector == null) {
			return ErpConnectors.NONE;
		}
		String value = connector.trim().toLowerCase(Locale.ROOT);
		List<String> names = ErpConnectors.names();
		if (!names.contains(value)) {
			throw new IllegalStateException("Invalid value '" + connector + "' for property " + CONNECTOR_KEY + ": "
					+ String.join(" | ", names) + ".");
		}
		return value;
	}

	/**
	 * Code cleanup, step 2: how to choose one of these connectors, for a refusal message (the application never names a
	 * connector key itself): {@code erp.connector=<name>}, several joined with "or".
	 */
	public static String howToChoose(List<String> connectors) {
		StringJoiner choices = new StringJoiner(" or ");
		for (String connector : connectors) {
			choices.add(CONNECTOR_KEY + "=" + connector);
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

	private static boolean isTrue(Object value) {
		return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
	}
}
