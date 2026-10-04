package com.digithink.zsretail.config;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Configuration step C1: the active profile in effect, once the configuration files are read (application.properties
 * gives store by default; the outside file, the IDE or the server's options may name another), is exactly one
 * installation type, store or headoffice. Anything else (none, two, or an old profile name such as store-erp or
 * dynamics-prod left in the server's options) stops the startup naming the two types.
 */
public class InstallationTypeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	/** After ConfigFileApplicationListener (HIGHEST_PRECEDENCE + 10), which sets the active profiles. */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 20;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		check(environment.getActiveProfiles(), environment.getProperty(MachineFileEnvironmentPostProcessor.LOADED_KEY));
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
}
