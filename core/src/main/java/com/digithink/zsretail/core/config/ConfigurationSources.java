package com.digithink.zsretail.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Code cleanup step 8: the property sources of a start that an installation gives (its outside file, the configuration
 * files, the server's options), and where each one is, for the refusals of the lines that are no longer read: the line,
 * and the file or option it is in.
 */
final class ConfigurationSources {

	/** The application's own properties (its type): never a line an installation wrote. */
	static final String DEFAULTS = "defaultProperties";

	/**
	 * The name Spring Boot gives a configuration file since 2.4: Config resource 'class path resource
	 * [application-store.properties]' via location 'optional:classpath:/'. The group: the file.
	 */
	private static final Pattern CONFIG_FILE = Pattern.compile("Config resource '(?:.*\\[)?([^\\]']+)\\]?' via location '.*'");

	private ConfigurationSources() {
	}

	/** The sources that hold the key, in the order of precedence (the application's defaults and Spring's view left out). */
	static List<PropertySource<?>> holding(ConfigurableEnvironment environment, String key) {
		List<PropertySource<?>> found = new ArrayList<>();
		for (PropertySource<?> source : environment.getPropertySources()) {
			if (isInstallation(source) && source.containsProperty(key)) {
				found.add(source);
			}
		}
		return found;
	}

	/** False for the application's defaults and Spring Boot's view of all the sources (it holds every key). */
	static boolean isInstallation(PropertySource<?> source) {
		return !DEFAULTS.equals(source.getName())
				&& !ConfigurationPropertySources.isAttachedConfigurationPropertySource(source);
	}

	/** Where a source is, in words: the outside file and its path, a configuration file, the server's options... */
	static String describe(ConfigurableEnvironment environment, PropertySource<?> source) {
		String name = source.getName();
		if (MachineFileEnvironmentPostProcessor.PROPERTY_SOURCE_NAME.equals(name)) {
			return "the outside file " + environment.getProperty(MachineFileEnvironmentPostProcessor.LOADED_KEY);
		}
		Matcher configFile = CONFIG_FILE.matcher(name);
		if (configFile.matches()) {
			return "the configuration file " + configFile.group(1);
		}
		switch (name) {
		case StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME:
			return "a system property (-D, the IDE or the server's options)";
		case StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME:
			return "an environment variable";
		case "commandLineArgs":
			return "a command-line argument";
		case "servletContextInitParams":
		case "servletConfigInitParams":
			return "the servlet context (Tomcat's context.xml or web.xml)";
		default:
			return "the property source " + name;
		}
	}
}
