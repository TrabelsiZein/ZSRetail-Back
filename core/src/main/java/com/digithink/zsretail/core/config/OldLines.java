package com.digithink.zsretail.core.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;
import com.digithink.zsretail.erp.api.ErpConnectors;

/**
 * Code cleanup step 8 (decision of 2026-10-11): no old line is accepted or ignored silently. Every line of a start that
 * nothing reads any more is refused in one message: the type lines ({@link InstallationTypeEnvironmentPostProcessor}),
 * the connectors' old enabled lines and renamed keys ({@link ErpConnectorEnvironmentPostProcessor}), then the dead keys,
 * whatever their value: the application's ({@link #APPLICATION_DEAD_KEYS}) and each connector's
 * ({@link ErpConnectorDescriptor#deadKeys()}, under its prefix or its old one). A dead key is named, never its value (a
 * key may be secret), with "remove the line".
 */
public final class OldLines {

	/**
	 * The application's keys that nothing reads any more: the franchise profiles' keys (head office plan step 9, task
	 * 9.4a).
	 */
	public static final List<String> APPLICATION_DEAD_KEYS = Collections.unmodifiableList(Arrays.asList(
			"franchise.admin", "franchise.customer", "franchise.customer.allow-local-items", "franchise.api-key",
			"franchise.remote.url", "franchise.sync.sales.cron"));

	private OldLines() {
	}

	/**
	 * The refusal of every old line of the start, null when there is none: the type lines given (null for none), the
	 * connectors' lines, the dead keys, in that order.
	 */
	static String message(ConfigurableEnvironment environment, String typeLines) {
		List<String> parts = new ArrayList<>();
		if (typeLines != null) {
			parts.add(typeLines);
		}
		String connectors = ErpConnectorEnvironmentPostProcessor.connectorLinesMessage(environment);
		if (connectors != null) {
			parts.add(connectors);
		}
		String dead = deadKeysMessage(environment);
		if (dead != null) {
			parts.add(dead);
		}
		return parts.isEmpty() ? null : String.join(" ", parts);
	}

	/** Every dead key, the application's and the connectors', by its full name (both prefixes for a connector). */
	public static List<String> deadKeys() {
		List<String> keys = new ArrayList<>(APPLICATION_DEAD_KEYS);
		for (ErpConnectorDescriptor descriptor : ErpConnectors.descriptors()) {
			for (String key : descriptor.deadKeys()) {
				keys.add(descriptor.keyPrefix() + "." + key);
				if (descriptor.oldKeyPrefix() != null) {
					keys.add(descriptor.oldKeyPrefix() + "." + key);
				}
			}
		}
		return keys;
	}

	/** The dead keys of every source, source by source, null when there is none. */
	static String deadKeysMessage(ConfigurableEnvironment environment) {
		List<String> keys = deadKeys();
		List<String> sources = new ArrayList<>();
		int count = 0;
		for (PropertySource<?> source : environment.getPropertySources()) {
			if (!ConfigurationSources.isInstallation(source)) {
				continue;
			}
			List<String> found = found(source, keys);
			if (!found.isEmpty()) {
				count += found.size();
				sources.add("in " + ConfigurationSources.describe(environment, source) + ", " + String.join(", ", found));
			}
		}
		if (sources.isEmpty()) {
			return null;
		}
		return (count == 1 ? "Line" : "Lines") + " no longer read: " + String.join("; ", sources) + "."
				+ (count == 1 ? " Remove the line: nothing reads it any more."
						: " Remove the lines: nothing reads them any more.");
	}

	/** The dead keys a source holds, sorted; an environment variable as written. */
	private static List<String> found(PropertySource<?> source, List<String> keys) {
		List<String> found = new ArrayList<>();
		if (source instanceof SystemEnvironmentPropertySource) {
			for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
				String upper = name.toUpperCase(Locale.ROOT);
				if (keys.stream().anyMatch(key -> ErpConnectorEnvironmentPostProcessor.variable(key).equals(upper))) {
					found.add(name);
				}
			}
		} else {
			for (String key : keys) {
				if (source.containsProperty(key)) {
					found.add(key);
				}
			}
		}
		found.sort(null);
		return found;
	}
}
