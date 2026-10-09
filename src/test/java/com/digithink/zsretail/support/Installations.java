package com.digithink.zsretail.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.mock.env.MockEnvironment;

/**
 * Test support (task 9.3, configuration step C1): an installation's configuration merged the way the application does,
 * without Spring: application.properties, under the type file (src/main/resources/application-&lt;type&gt;.properties,
 * the type named by spring.profiles.active, store by default), under the outside file (deploy/... of this repository)
 * when there is one.
 * <p>
 * The six presets of task 9.3 are gone (step C1); each one is kept here as a variant, its type file with the mode lines
 * it gave (the lines its machine files of deploy/ now state), so the frozen rows of the truth tables load as before.
 */
public final class Installations {

	/** The repository's deploy folder (the tests run from the project directory). */
	public static final Path DEPLOY = Paths.get("deploy");

	/** The repository's configs folder: one file per installation (ERP catalogue, step 1: checked like deploy/). */
	public static final Path CONFIGS = Paths.get("configs");

	/** Old preset name to its type and the mode lines it gave (task 9.3 files, removed at step C1). */
	private static final Map<String, String[]> VARIANTS = new LinkedHashMap<>();

	static {
		variant("store", "store", "");
		variant("store-erp", "store", "ownership.catalogue=ERP|ownership.customers=ERP|ownership.supply=ERP"
				+ "|sales.upstream=ERP|erp.dynamicsnav.enabled=true");
		variant("headoffice", "headoffice", "");
		variant("headoffice-erp", "headoffice", "ownership.catalogue=ERP|ownership.customers=ERP|ownership.supply=ERP"
				+ "|erp.dynamicsnav.enabled=true");
		variant("network-store", "store", "ownership.catalogue=HEAD_OFFICE|ownership.supply=HEAD_OFFICE"
				+ "|sales.upstream=HEAD_OFFICE");
		variant("network-store-erp", "store", "ownership.catalogue=ERP|ownership.customers=ERP"
				+ "|ownership.promotions=HEAD_OFFICE|ownership.loyalty=HEAD_OFFICE|ownership.supply=ERP"
				+ "|sales.upstream=ERP,HEAD_OFFICE|erp.dynamicsnav.enabled=true");
	}

	private Installations() {
	}

	private static void variant(String name, String type, String modeLines) {
		VARIANTS.put(name, new String[] { type, modeLines });
	}

	/** The six shapes of task 9.3 (store, store-erp, headoffice, headoffice-erp, network-store, network-store-erp). */
	public static List<String> variants() {
		return new ArrayList<>(VARIANTS.keySet());
	}

	/** An outside file of deploy/ (e.g. "dev/store-b.properties") over its type file and application.properties. */
	public static MockEnvironment machine(String relativePath) {
		return outside(DEPLOY.resolve(relativePath));
	}

	/** An installation file of configs/ (e.g. "local/happyness_ho.properties") over its type file and application.properties. */
	public static MockEnvironment config(String relativePath) {
		return outside(CONFIGS.resolve(relativePath));
	}

	/**
	 * Every installation file of configs/ that git tracks, as paths relative to configs/: a local file not committed (a
	 * rehearsal copy) does not change the tests. Every file when git cannot answer.
	 */
	public static List<String> configFiles() {
		List<String> files = propertiesFiles(CONFIGS);
		List<String> tracked = trackedUnder(CONFIGS);
		return tracked == null ? files : files.stream().filter(tracked::contains).collect(Collectors.toList());
	}

	/** The files git tracks under the folder, relative to it; null when git cannot answer. */
	private static List<String> trackedUnder(Path folder) {
		try {
			Process git = new ProcessBuilder("git", "ls-files", "--", folder.toString()).redirectErrorStream(true).start();
			List<String> lines;
			try (java.io.BufferedReader out = new java.io.BufferedReader(
					new java.io.InputStreamReader(git.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
				lines = out.lines().collect(Collectors.toList());
			}
			if (git.waitFor() != 0) {
				return null;
			}
			String prefix = folder.toString().replace('\\', '/') + "/";
			return lines.stream().filter(line -> line.startsWith(prefix)).map(line -> line.substring(prefix.length()))
					.collect(Collectors.toList());
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private static MockEnvironment outside(Path path) {
		Properties machine = file(path);
		String type = machine.getProperty("spring.profiles.active", resource("/application.properties")
				.getProperty("spring.profiles.active"));
		MockEnvironment env = type(type.trim());
		machine.stringPropertyNames().forEach(key -> env.setProperty(key, machine.getProperty(key)));
		return env;
	}

	/** A type (store, headoffice) over application.properties, without an outside file. */
	public static MockEnvironment type(String type) {
		MockEnvironment env = new MockEnvironment();
		Properties base = resource("/application.properties");
		base.stringPropertyNames().forEach(key -> env.setProperty(key, base.getProperty(key)));
		Properties keys = resource("/application-" + type + ".properties");
		keys.stringPropertyNames().forEach(key -> env.setProperty(key, keys.getProperty(key)));
		return env;
	}

	/** One of the six shapes of task 9.3: its type file with the mode lines the preset gave. */
	public static MockEnvironment preset(String name) {
		String[] variant = VARIANTS.get(name);
		if (variant == null) {
			throw new IllegalArgumentException("no variant " + name);
		}
		MockEnvironment env = type(variant[0]);
		if (!variant[1].isEmpty()) {
			for (String pair : variant[1].split("\\|")) {
				int eq = pair.indexOf('=');
				env.setProperty(pair.substring(0, eq), pair.substring(eq + 1));
			}
		}
		return env;
	}

	/** Every outside file of deploy/ (the model excluded), as paths relative to deploy/. */
	public static List<String> machineFiles() {
		return propertiesFiles(DEPLOY);
	}

	private static List<String> propertiesFiles(Path folder) {
		try (Stream<Path> files = Files.walk(folder)) {
			return files.filter(p -> p.toString().endsWith(".properties"))
					.filter(p -> !p.getFileName().toString().equals("machine-model.properties"))
					.map(p -> folder.relativize(p).toString().replace('\\', '/')).sorted().collect(Collectors.toList());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static Properties file(Path path) {
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(path)) {
			properties.load(in);
		} catch (IOException e) {
			throw new UncheckedIOException("cannot read " + path, e);
		}
		return properties;
	}

	public static Properties resource(String name) {
		Properties properties = new Properties();
		try (InputStream in = Installations.class.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalArgumentException("no resource " + name);
			}
			properties.load(in);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return properties;
	}
}
