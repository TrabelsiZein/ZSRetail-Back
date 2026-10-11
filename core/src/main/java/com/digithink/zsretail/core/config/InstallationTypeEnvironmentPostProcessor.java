package com.digithink.zsretail.core.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

import com.digithink.zsretail.core.model.enumeration.NodeType;

/**
 * Configuration step C1: the active profile in effect, once the configuration files are read, is exactly one
 * installation type, store or headoffice. Anything else (none, two, or an old profile name such as store-erp or
 * dynamics-prod left in the server's options) stops the startup naming the two types.
 * <p>
 * Code cleanup step 6.5: the application is the type ({@link ZsRetailApplication}). An outside file (or a server
 * option) may still name it with spring.profiles.active, and node.type: when it names the other type, the startup stops
 * naming the application and the file.
 */
public class InstallationTypeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	static final String ACTIVE_KEY = "spring.profiles.active";

	/** After ConfigFileApplicationListener (HIGHEST_PRECEDENCE + 10), which sets the active profiles. */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 20;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		String outsideFile = environment.getProperty(MachineFileEnvironmentPostProcessor.LOADED_KEY);
		String type = applicationType(environment);
		if (type == null) {
			check(environment.getActiveProfiles(), outsideFile);
			return;
		}
		List<String> named = new ArrayList<>(new LinkedHashSet<>(
				Arrays.asList(StringUtils.commaDelimitedListToStringArray(environment.getProperty(ACTIVE_KEY, "")))));
		named.replaceAll(String::trim);
		String[] active = environment.getActiveProfiles();
		if (named.size() == 1 && NodeOwnership.TYPES.contains(named.get(0)) && !named.get(0).equals(type)) {
			throw new IllegalStateException(wrongType(type, applicationName(application), ACTIVE_KEY + "=" + named.get(0),
					named.get(0), source(environment, ACTIVE_KEY, outsideFile)));
		}
		if (!(active.length == 1 && active[0].equals(type))) {
			// What was named is refused as before (none, two, an old profile name); else the profiles in effect are
			check(named.isEmpty() ? active : named.toArray(new String[0]), outsideFile);
			check(active, outsideFile);
		}
		NodeType expected = "headoffice".equals(type) ? NodeType.HEAD_OFFICE : NodeType.STORE;
		NodeType nodeType = NodeOwnership.nodeTypeOf(environment);
		if (nodeType != expected) {
			throw new IllegalStateException(wrongType(type, applicationName(application),
					NodeOwnership.NODE_TYPE_KEY + "=" + nodeType, nodeType == NodeType.HEAD_OFFICE ? "headoffice" : "store",
					source(environment, NodeOwnership.NODE_TYPE_KEY, outsideFile)));
		}
	}

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
		PropertySource<?> defaults = environment.getPropertySources().get("defaultProperties");
		Object type = defaults == null ? null : defaults.getProperty(ZsRetailApplication.TYPE_KEY);
		return type == null ? null : type.toString();
	}

	/** The main class of the application (the WAR's, the IDE's), null when there is none. */
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

	/** Where a key comes from: the outside file when it has it, else the IDE or the server's options. */
	static String source(ConfigurableEnvironment environment, String key, String outsideFile) {
		PropertySource<?> machine = environment.getPropertySources()
				.get(MachineFileEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
		if (machine != null && machine.containsProperty(key)) {
			return "the outside file " + outsideFile;
		}
		for (PropertySource<?> source : environment.getPropertySources()) {
			if (source.containsProperty(key) && !"defaultProperties".equals(source.getName())
					&& !source.getName().startsWith("applicationConfig")) {
				return "the IDE or the server's options (" + source.getName() + ")";
			}
		}
		return "the configuration";
	}

	/** The refusal of a start whose file (or option) names the other type: the application, the file, the line. */
	static String wrongType(String type, String application, String line, String otherType, String source) {
		return "This is the " + label(type) + " application" + (application == null ? "" : " (" + application + ")")
				+ ": it runs an installation of type " + type + ", but " + source + " names the type " + otherType + " ("
				+ line + "). The " + label(otherType) + " runs the " + label(otherType) + " application: deploy its WAR in"
				+ " this Tomcat (under zsretailws), or correct the file; an installation needs no " + ACTIVE_KEY
				+ " and no node.type line, the application is its type (docs/deployment-modes.md).";
	}

	static String label(String type) {
		return "headoffice".equals(type) ? "head office" : "store";
	}

}
