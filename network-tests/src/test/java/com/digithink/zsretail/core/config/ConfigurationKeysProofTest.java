package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.digithink.zsretail.core.support.Repo;
import com.digithink.zsretail.support.Installations;

/**
 * Code cleanup step 6, proof: every key of every configuration resolves as it did before the store and the head office
 * became two applications. Each file of configs/ (as committed), and a start without an outside file of
 * each type, is started the way Spring Boot starts (every EnvironmentPostProcessor of spring.factories, in order, with
 * the configuration files of the classpath); the value of every key that a configuration file names is compared with
 * the record made on the code before the change (src/test/resources/config/configuration-keys.txt). A password or a
 * key is recorded as "(set)", never its value. -Dconfiguration.proof.write=true writes the record again (only on the
 * code before the change).
 */
class ConfigurationKeysProofTest {

	static final Path RECORD = Paths.get("src", "test", "resources", "config", "configuration-keys.txt");

	@TempDir
	Path folder;

	/** A configuration: its outside file (null: none) and the type it names (store when it names none). */
	static final class Configuration {
		final Path file;
		final String type;

		Configuration(Path file, String type) {
			this.file = file;
			this.type = type;
		}
	}

	/** The type a file names: its spring.profiles.active, else store (the default of the single application). */
	static String typeOf(Properties file) {
		String type = file.getProperty("spring.profiles.active");
		return type == null ? "store" : type.trim();
	}

	Map<String, Configuration> configurations() throws IOException {
		Map<String, Configuration> configurations = new LinkedHashMap<>();
		for (String file : Installations.machineFiles()) {
			Path path = Installations.MACHINES.resolve(file);
			configurations.put("configs/" + file, new Configuration(path, typeOf(Installations.file(path))));
		}
		for (String file : Installations.configFiles()) {
			Path path = committed(file);
			configurations.put("configs/" + file, new Configuration(path, typeOf(Installations.file(path))));
		}
		configurations.put("no outside file, store", new Configuration(null, "store"));
		configurations.put("no outside file, headoffice", new Configuration(null, "headoffice"));
		return configurations;
	}

	/**
	 * A file of configs/ as committed (configs/local is edited on this machine), copied to a temporary folder (the file on
	 * disk when git cannot answer). Code cleanup step 8: upgraded (Installations.upgraded), as Zein will upgrade it.
	 */
	Path committed(String file) throws IOException {
		Path copy = folder.resolve("configs").resolve(file);
		Files.createDirectories(copy.getParent());
		byte[] content = null;
		try {
			Process git = new ProcessBuilder("git", "show", "HEAD:configs/" + file).directory(Repo.ROOT.toFile()).start();
			try (InputStream in = git.getInputStream()) {
				content = in.readAllBytes();
			}
			if (git.waitFor() != 0 || content.length == 0) {
				content = null;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		Files.write(copy, content != null ? content : Files.readAllBytes(Installations.CONFIGS.resolve(file)));
		Properties lines = Installations.file(copy);
		Properties upgraded = Installations.upgraded(lines);
		if (!upgraded.equals(lines)) {
			try (OutputStream out = Files.newOutputStream(copy)) {
				upgraded.store(out, null);
			}
		}
		return copy;
	}

	/**
	 * A start of this configuration, as Spring Boot starts: the outside file named as by -Dzsretail.machine-file, then
	 * every EnvironmentPostProcessor (ConfigFileApplicationListener among them). Before the change (the record): the
	 * single application, its type named by the file (or, without a file, by -Dspring.profiles.active=headoffice).
	 * Since code cleanup step 6.5: the application of the configuration's type, as it prepares a start (its default
	 * properties and its profile, see support/Starts).
	 */
	static StandardEnvironment start(Configuration configuration) {
		Map<String, Object> systemProperties = new HashMap<>();
		if (configuration.file != null) {
			systemProperties.put(MachineFileEnvironmentPostProcessor.MACHINE_FILE_KEY, configuration.file.toString());
		}
		StandardEnvironment env = new StandardEnvironment();
		// Only what the test gives: the machine's own variables and properties must not change the record
		env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
				new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>()));
		env.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
				new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, new HashMap<>()));
		env.getPropertySources().addFirst(new MapPropertySource("test-system-properties", systemProperties));
		env.getPropertySources().addLast(
				new MapPropertySource("defaultProperties", new HashMap<>(ZsRetailApplication.defaults(configuration.type))));
		Set<String> profiles = new LinkedHashSet<>(Collections.singleton(configuration.type));
		profiles.addAll(Arrays.asList(env.getActiveProfiles()));
		env.setActiveProfiles(profiles.toArray(new String[0]));
		SpringApplication application = new SpringApplication();
		new ConfigFileApplicationListener()
				.onApplicationEvent(new ApplicationEnvironmentPreparedEvent(application, new String[0], env));
		return env;
	}

	/** Every key a configuration file names: the application*.properties of the classpath and every outside file. */
	static TreeSet<String> keys(Map<String, Configuration> configurations) throws IOException {
		TreeSet<String> keys = new TreeSet<>();
		for (Resource resource : new PathMatchingResourcePatternResolver()
				.getResources("classpath*:application*.properties")) {
			Properties properties = new Properties();
			try (InputStream in = resource.getInputStream()) {
				properties.load(in);
			}
			keys.addAll(properties.stringPropertyNames());
		}
		for (Configuration configuration : configurations.values()) {
			if (configuration.file != null) {
				keys.addAll(Installations.file(configuration.file).stringPropertyNames());
			}
		}
		keys.add(MachineFileEnvironmentPostProcessor.LOADED_KEY);
		return keys;
	}

	static boolean secret(String key) {
		String lower = key.toLowerCase();
		return lower.contains("password") || lower.contains("secret") || lower.contains("api-key")
				|| lower.contains("token");
	}

	/** The value of a key as the application reads it; a secret as "(set)"; a path of this machine made relative. */
	String value(StandardEnvironment env, String key) {
		if (!env.containsProperty(key)) {
			return "(none)";
		}
		if (secret(key)) {
			return "(set)";
		}
		String value;
		try {
			value = env.getProperty(key);
		} catch (IllegalArgumentException unresolved) {
			value = "(unresolved: " + env.getPropertySources().stream()
					.filter(source -> source.containsProperty(key)).findFirst().map(source -> source.getProperty(key))
					.orElse(null) + ")";
		}
		return value == null ? "(null)" : value.replace(folder.toString(), "<copy>").replace(Repo.ROOT.toString(), "<repo>")
				.replace('\\', '/');
	}

	List<String> record() throws IOException {
		Map<String, Configuration> configurations = configurations();
		TreeSet<String> keys = keys(configurations);
		List<String> lines = new ArrayList<>();
		lines.add("keys " + keys.size());
		for (Map.Entry<String, Configuration> configuration : configurations.entrySet()) {
			lines.add("== " + configuration.getKey());
			StandardEnvironment env;
			try {
				env = start(configuration.getValue());
			} catch (RuntimeException refused) {
				lines.add("refused: " + refused.getMessage());
				continue;
			}
			lines.add("profiles=" + String.join(",", env.getActiveProfiles()));
			for (String key : keys) {
				lines.add(key + "=" + value(env, key));
			}
		}
		return lines;
	}

	@Test
	@DisplayName("Every key of every configuration resolves as in the record made before the two applications")
	void sameKeysAsRecorded() throws Exception {
		List<String> now = record();
		if (Boolean.getBoolean("configuration.proof.write")) {
			Files.createDirectories(RECORD.getParent());
			Files.write(RECORD, now, StandardCharsets.UTF_8);
		}
		List<String> recorded = Files.readAllLines(RECORD, StandardCharsets.UTF_8);
		assertTrue(recorded.size() > 1000, "lines recorded: " + recorded.size());
		for (int i = 0; i < Math.max(now.size(), recorded.size()); i++) {
			assertEquals(i < recorded.size() ? recorded.get(i) : "(none)", i < now.size() ? now.get(i) : "(none)",
					"line " + (i + 1));
		}
	}
}
