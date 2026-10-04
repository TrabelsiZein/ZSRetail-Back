package com.digithink.zsretail.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Head office plan, task 9.3: every installation has one machine file outside the WAR (database, port, log, uploads,
 * NAV settings, head office address and key, sales-push start date, and the preset it runs). Loaded before Spring reads
 * its configuration files, so the {@code spring.profiles.active} it gives picks the preset, and placed above them: a
 * key of the machine file wins over the preset and over application.properties (command-line arguments and system
 * properties still win over it).
 * <p>
 * Where it is found:
 * <ol>
 * <li>{@code zsretail.machine-file}: a system property (or command-line argument) naming the file. Used by the dev
 * scripts and the IDE, and to override the convention on a server;</li>
 * <li>otherwise, in Tomcat: {@code ${catalina.base}/conf/zsretail/<context name>.properties}, the context name of the
 * WAR (given by {@code POSMainApp}: {@code zsretailws.war} gives {@code zsretailws}), so a head office and a store can
 * run on one Tomcat.</li>
 * </ol>
 * There is no default: without a machine file, with a file that cannot be read, or with a file that names no preset
 * (or an unknown one), the application does not start and says what is missing. See docs/deployment-modes.md.
 */
public class MachineFileEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	public static final String MACHINE_FILE_KEY = "zsretail.machine-file";
	public static final String CONTEXT_NAME_KEY = "zsretail.context-name";
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
		Properties properties = load(file);
		checkPreset(file, properties.getProperty("spring.profiles.active"));
		MutablePropertySources sources = environment.getPropertySources();
		PropertiesPropertySource machine = new PropertiesPropertySource(PROPERTY_SOURCE_NAME, properties);
		if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
			sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, machine);
		} else {
			sources.addLast(machine);
		}
		properties.setProperty(MACHINE_FILE_KEY + ".loaded", file.toString());
	}

	/** The machine file: the system property, else the Tomcat convention; refuses to start without one. */
	static Path locate(String explicit, String contextName, String catalinaBase) {
		if (explicit != null && !explicit.trim().isEmpty()) {
			return Paths.get(explicit.trim());
		}
		if (contextName != null && catalinaBase != null) {
			return Paths.get(catalinaBase, "conf", "zsretail", contextName + ".properties");
		}
		throw new IllegalStateException("No machine file: start with -D" + MACHINE_FILE_KEY + "=<path of the file>"
				+ " (in Tomcat the file is found as ${catalina.base}/conf/zsretail/<context name>.properties). It gives"
				+ " the database, the port, the log and the preset (spring.profiles.active=" + presets() + "). Model:"
				+ " deploy/machine-model.properties of the backend repository; see docs/deployment-modes.md.");
	}

	static Properties load(Path file) {
		if (!Files.isRegularFile(file)) {
			throw new IllegalStateException("Machine file not found: " + file + ". Create it from"
					+ " deploy/machine-model.properties, or point -D" + MACHINE_FILE_KEY + " to it (docs/deployment-modes.md).");
		}
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			properties.load(in);
		} catch (IOException e) {
			throw new IllegalStateException("Machine file " + file + " cannot be read: " + e.getMessage(), e);
		}
		return properties;
	}

	/** The machine file names exactly one preset. */
	static void checkPreset(Path file, String active) {
		List<String> profiles = active == null ? List.of()
				: Arrays.stream(active.split(",")).map(String::trim).filter(p -> !p.isEmpty()).collect(Collectors.toList());
		if (profiles.size() != 1 || !NodeOwnership.PRESETS.contains(profiles.get(0))) {
			throw new IllegalStateException("The machine file " + file + " must name one preset: spring.profiles.active="
					+ presets() + (active == null ? " (the key is missing)." : " (found '" + active + "')."));
		}
	}

	private static String presets() {
		return String.join(" | ", NodeOwnership.PRESETS);
	}
}
