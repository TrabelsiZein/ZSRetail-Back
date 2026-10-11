package com.digithink.zsretail.support;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.digithink.zsretail.core.config.MachineFileEnvironmentPostProcessor;
import com.digithink.zsretail.core.config.ZsRetailApplication;

/**
 * Code cleanup step 6.5: the environment of a start as the application of a type (store or headoffice) prepares it,
 * before the post-processors run: the system properties given (above everything, like -D), the application's default
 * properties (below everything: its type), and the active profiles as SpringApplication sets them (the application's
 * type, then those named by -D).
 */
public final class Starts {

	private Starts() {
	}

	public static StandardEnvironment environment(String type, Map<String, Object> systemProperties) {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource("test-system-properties", systemProperties));
		env.getPropertySources()
				.addLast(new MapPropertySource("defaultProperties", new LinkedHashMap<>(ZsRetailApplication.defaults(type))));
		Set<String> profiles = new LinkedHashSet<>(Collections.singleton(type));
		profiles.addAll(Arrays.asList(env.getActiveProfiles()));
		env.setActiveProfiles(profiles.toArray(new String[0]));
		return env;
	}

	/**
	 * The application a start runs: the type named by -Dspring.profiles.active, else by the outside file named by
	 * -Dzsretail.machine-file, when it is one of the two types; store otherwise.
	 */
	public static String applicationOf(Map<String, Object> systemProperties) {
		Object named = systemProperties.get("spring.profiles.active");
		Object file = systemProperties.get(MachineFileEnvironmentPostProcessor.MACHINE_FILE_KEY);
		if (named == null && file != null && Paths.get(file.toString()).toFile().isFile()) {
			named = Installations.file(Paths.get(file.toString())).getProperty("spring.profiles.active");
		}
		return named != null && "headoffice".equals(named.toString().trim()) ? "headoffice" : "store";
	}
}
