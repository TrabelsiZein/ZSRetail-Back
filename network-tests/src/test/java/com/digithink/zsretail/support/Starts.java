package com.digithink.zsretail.support;

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
	 * The application a start runs: code cleanup step 8, nothing names the type any more; the application of the outside
	 * file named by -Dzsretail.machine-file, by its name ({@link Installations#typeOf}); store without one.
	 */
	public static String applicationOf(Map<String, Object> systemProperties) {
		Object file = systemProperties.get(MachineFileEnvironmentPostProcessor.MACHINE_FILE_KEY);
		return file == null ? "store" : Installations.typeOf(file.toString());
	}
}
