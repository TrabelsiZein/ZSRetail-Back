package com.digithink.zsretail.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;
import com.digithink.zsretail.erp.api.ErpConnectors;

/**
 * Code cleanup, step 1: one line that chooses the ERP connector, {@code erp.connector=none | <a connector found>}
 * (trimmed, any case; the customers' names since step 5). Code cleanup step 8: it is the only way. Absent or none: no
 * connector. The conditions and the connectors read it ({@link #inEffect}); an unknown value stops the startup.
 * <p>
 * The old lines of the connectors are no longer read ({@link ErpConnectorDescriptor#oldKeyPrefix}): an old enabled line
 * ({@code <old prefix>.enabled}) or any other key of an old block, found in any property source, whatever its value,
 * stops the startup naming each line, where it is and what to write instead (erp.connector=&lt;name&gt;, the line removed,
 * or the key's new name under erp.&lt;name&gt;). See docs/deployment-modes.md.
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

	/** Every old line of the connectors found, in every source, refused together ({@link #oldLinesMessage}). */
	static void refuseOldLines(ConfigurableEnvironment environment) {
		String message = oldLinesMessage(environment);
		if (message != null) {
			throw new IllegalStateException(message);
		}
	}

	/**
	 * The refusal of the old lines of the connectors found, in every source, null when there is none: the enabled lines,
	 * each with what to write instead, then the other keys of the old blocks, each with its new name, source by source.
	 * Also added to the refusal of the type lines (InstallationTypeEnvironmentPostProcessor), so one message names every
	 * old line of a start.
	 */
	public static String oldLinesMessage(ConfigurableEnvironment environment) {
		List<String> lines = new ArrayList<>();
		Map<PropertySource<?>, List<String>> renamed = new LinkedHashMap<>();
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
			for (PropertySource<?> source : environment.getPropertySources()) {
				if (ConfigurationSources.isInstallation(source)) {
					List<String> found = renamedKeys(source, descriptor);
					if (!found.isEmpty()) {
						renamed.computeIfAbsent(source, s -> new ArrayList<>()).addAll(found);
					}
				}
			}
		}
		List<String> message = new ArrayList<>();
		if (!lines.isEmpty()) {
			message.add((lines.size() == 1 ? "Old line" : "Old lines") + " no longer read: " + String.join("; ", lines)
					+ ". The ERP connector is chosen by " + CONNECTOR_KEY + " only ("
					+ String.join(" | ", ErpConnectors.names()) + "; no line: none).");
		}
		for (Map.Entry<PropertySource<?>, List<String>> source : renamed.entrySet()) {
			message.add("Old keys no longer read, in " + ConfigurationSources.describe(environment, source.getKey()) + ": "
					+ String.join(", ", source.getValue()) + ".");
		}
		if (!renamed.isEmpty()) {
			message.add("Rename them: the keys of a connector are erp.<its name>.*.");
		}
		return message.isEmpty() ? null : String.join(" ", message);
	}

	/**
	 * The keys of a source under the connector's old prefix, its enabled line apart, each as "old is now new", sorted. An
	 * environment variable is named as written (ERP_&lt;OLD&gt;_X is now ERP_&lt;NEW&gt;_X). A source that cannot list its keys
	 * gives none (only its enabled line is found).
	 */
	static List<String> renamedKeys(PropertySource<?> source, ErpConnectorDescriptor descriptor) {
		if (!(source instanceof EnumerablePropertySource)) {
			return new ArrayList<>();
		}
		boolean variables = source instanceof SystemEnvironmentPropertySource;
		String old = variables ? variable(descriptor.oldKeyPrefix()) + "_" : descriptor.oldKeyPrefix() + ".";
		String now = variables ? variable(descriptor.keyPrefix()) + "_" : descriptor.keyPrefix() + ".";
		String enabled = old + (variables ? "ENABLED" : "enabled");
		List<String> found = new ArrayList<>();
		for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
			String key = variables ? name.toUpperCase(Locale.ROOT) : name;
			if (key.startsWith(old) && !key.equals(enabled)) {
				found.add(name + " is now " + now + name.substring(old.length()));
			}
		}
		found.sort(null);
		return found;
	}

	/** A key prefix as an environment variable names it: erp.x-y becomes ERP_XY. */
	private static String variable(String prefix) {
		return prefix.replace("-", "").replace('.', '_').toUpperCase(Locale.ROOT);
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
