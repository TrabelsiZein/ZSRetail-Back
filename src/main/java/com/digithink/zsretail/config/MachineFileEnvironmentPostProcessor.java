package com.digithink.zsretail.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Configuration step C1: the optional file outside the WAR of an installation (usually where it runs: database, port,
 * log, uploads, NAV connection, head office address and key; it may also name the type and change an owner). Loaded
 * before Spring reads its configuration files, so a {@code spring.profiles.active} it gives picks the type, and placed
 * above them: a key of the outside file wins over the type file and over application.properties (command-line
 * arguments and system properties still win over it).
 * <p>
 * Where it is found:
 * <ol>
 * <li>{@code zsretail.machine-file}: a system property (or command-line argument) naming the file. Used by the dev
 * scripts and the IDE, and to override the convention on a server. A file it names that does not exist stops the
 * startup;</li>
 * <li>otherwise, in Tomcat, when it exists: {@code ${catalina.base}/conf/zsretail/<context name>.properties}, the
 * context name of the WAR (given by {@code POSMainApp}: {@code zsretailws.war} gives {@code zsretailws}), so a head
 * office and a store can run on one Tomcat.</li>
 * </ol>
 * Neither: no outside file, the type files apply as they are (store by default). The type in effect is checked once
 * the configuration files are read ({@link InstallationTypeEnvironmentPostProcessor}). See docs/deployment-modes.md.
 */
public class MachineFileEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	public static final String MACHINE_FILE_KEY = "zsretail.machine-file";
	public static final String CONTEXT_NAME_KEY = "zsretail.context-name";
	/** The path of the outside file loaded, absent when there is none (startup summary, type refusal). */
	public static final String LOADED_KEY = MACHINE_FILE_KEY + ".loaded";
	static final String PROPERTY_SOURCE_NAME = "machineFile";

	/** Before ConfigFileApplicationListener (HIGHEST_PRECEDENCE + 10), which reads the active profiles. */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 5;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		Path file = locate(environment.getProperty(MACHINE_FILE_KEY), environment.getProperty(CONTEXT_NAME_KEY),
				environment.getProperty("catalina.base"));
		if (file == null) {
			return;
		}
		Properties properties = load(file);
		properties.setProperty(LOADED_KEY, file.toString());
		MutablePropertySources sources = environment.getPropertySources();
		PropertiesPropertySource machine = new PropertiesPropertySource(PROPERTY_SOURCE_NAME, properties);
		if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
			sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, machine);
		} else {
			sources.addLast(machine);
		}
	}

	/**
	 * The outside file: the one the system property names, else the Tomcat file when it exists; null when there is
	 * none.
	 */
	static Path locate(String explicit, String contextName, String catalinaBase) {
		if (explicit != null && !explicit.trim().isEmpty()) {
			return Paths.get(explicit.trim());
		}
		if (contextName != null && catalinaBase != null) {
			Path tomcat = Paths.get(catalinaBase, "conf", "zsretail", contextName + ".properties");
			return Files.isRegularFile(tomcat) ? tomcat : null;
		}
		return null;
	}

	static Properties load(Path file) {
		if (!Files.isRegularFile(file)) {
			throw new IllegalStateException("Outside file not found: " + file + " (named by -D" + MACHINE_FILE_KEY
					+ "). Create it from deploy/machine-model.properties, or correct the path (docs/deployment-modes.md).");
		}
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			properties.load(in);
		} catch (IOException e) {
			throw new IllegalStateException("Outside file " + file + " cannot be read: " + e.getMessage(), e);
		}
		return properties;
	}
}
