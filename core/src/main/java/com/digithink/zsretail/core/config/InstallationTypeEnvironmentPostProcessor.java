package com.digithink.zsretail.core.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

/**
 * Configuration step C1: the active profile in effect, once the configuration files are read, is exactly one
 * installation type, store or headoffice.
 * <p>
 * Code cleanup step 6.5: the application is the type ({@link ZsRetailApplication}). Code cleanup step 8 (decision of
 * 2026-10-11): an installation no longer names it. spring.profiles.active or node.type from any source but the
 * application's own default properties (the outside file, a configuration file, a -D, an environment variable such as
 * SPRING_PROFILES_ACTIVE), whatever its value, stops the startup naming each line and where it is, and saying to remove
 * it; a line that names the other type also says which application that installation needs. The old lines of the
 * connectors found at the same start are added to the message
 * ({@link ErpConnectorEnvironmentPostProcessor#oldLinesMessage}), so one message names every old line. Anything else
 * that changes the profiles (spring.profiles.include) stops the startup too.
 */
public class InstallationTypeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	static final String ACTIVE_KEY = "spring.profiles.active";
	/** The lines that named the type before the application was the type. */
	static final List<String> TYPE_KEYS = Arrays.asList(ACTIVE_KEY, NodeOwnership.NODE_TYPE_KEY);

	/** After ConfigFileApplicationListener (HIGHEST_PRECEDENCE + 10), which sets the active profiles. */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 20;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		String type = applicationType(environment);
		if (type == null) {
			check(environment.getActiveProfiles(), environment.getProperty(MachineFileEnvironmentPostProcessor.LOADED_KEY));
			return;
		}
		String refusal = typeLinesMessage(environment, type, applicationName(application));
		if (refusal != null) {
			String connectors = ErpConnectorEnvironmentPostProcessor.oldLinesMessage(environment);
			throw new IllegalStateException(connectors == null ? refusal : refusal + " " + connectors);
		}
		String[] active = environment.getActiveProfiles();
		if (!(active.length == 1 && active[0].equals(type))) {
			throw new IllegalStateException("The active profiles are " + Arrays.toString(active) + ": the " + label(type)
					+ " application runs with its profile " + type + " only. Remove what adds a profile"
					+ " (spring.profiles.include in the outside file, the IDE or the server's options).");
		}
	}

	/**
	 * The refusal of the lines that name the type (spring.profiles.active, node.type), every source but the application's
	 * defaults, null when there is none.
	 */
	static String typeLinesMessage(ConfigurableEnvironment environment, String type, String application) {
		List<String> lines = new ArrayList<>();
		boolean otherType = false;
		for (String key : TYPE_KEYS) {
			for (PropertySource<?> source : ConfigurationSources.holding(environment, key)) {
				String value = String.valueOf(source.getProperty(key)).trim();
				lines.add(key + "=" + value + " in " + ConfigurationSources.describe(environment, source));
				otherType |= namesOtherType(key, value, type);
			}
		}
		if (lines.isEmpty()) {
			return null;
		}
		String other = "headoffice".equals(type) ? "store" : "headoffice";
		return (lines.size() == 1 ? "Old line" : "Old lines") + " no longer read: " + String.join("; ", lines)
				+ ". An installation no longer names its type: the application is its type (this is the " + label(type)
				+ " application" + (application == null ? "" : ", " + application) + "). Remove "
				+ (lines.size() == 1 ? "the line" : "the lines") + "."
				+ (otherType ? " It names a " + label(other) + ": a " + label(other) + " runs the " + label(other)
						+ " application, deploy its WAR in this Tomcat (under zsretailws)." : "");
	}

	/** True when a type line names the other type than the application's. */
	static boolean namesOtherType(String key, String value, String type) {
		String other = "headoffice".equals(type) ? "store" : "headoffice";
		if (ACTIVE_KEY.equals(key)) {
			return Arrays.stream(StringUtils.commaDelimitedListToStringArray(value)).map(String::trim)
					.anyMatch(other::equals);
		}
		return value.replace("_", "").equalsIgnoreCase(other);
	}

	/** Without the application (no type of its own): the active profile must be exactly one installation type. */
	static void check(String[] activeProfiles, String outsideFile) {
		if (activeProfiles.length == 1 && NodeOwnership.TYPES.contains(activeProfiles[0])) {
			return;
		}
		throw new IllegalStateException("The active profile is " + Arrays.toString(activeProfiles) + ": it must be one"
				+ " installation type, spring.profiles.active=" + String.join(" | ", NodeOwnership.TYPES) + " (store by"
				+ " default). It is named by the outside file" + (outsideFile == null ? "" : " (" + outsideFile + ")")
				+ ", the IDE or the server's options (system property, SPRING_PROFILES_ACTIVE); an old profile name"
				+ " becomes a type and its ownership.* lines (docs/deployment-modes.md).");
	}

	/** The type the application gave as a default property ({@link ZsRetailApplication#TYPE_KEY}); null for none. */
	static String applicationType(ConfigurableEnvironment environment) {
		PropertySource<?> defaults = environment.getPropertySources().get(ConfigurationSources.DEFAULTS);
		Object type = defaults == null ? null : defaults.getProperty(ZsRetailApplication.TYPE_KEY);
		return type == null ? null : type.toString();
	}

	/** The main class of the application (the WAR's, the IDE's), null when there is none. */
	static String applicationName(SpringApplication application) {
		if (application != null) {
			for (Object source : application.getAllSources()) {
				if (source instanceof Class) {
					return ((Class<?>) source).getSimpleName();
				}
			}
		}
		return null;
	}

	static String label(String type) {
		return "headoffice".equals(type) ? "head office" : "store";
	}
}
