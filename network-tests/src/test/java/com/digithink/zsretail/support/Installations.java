package com.digithink.zsretail.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ZsRetailApplication;
import com.digithink.zsretail.core.support.Repo;
import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;
import com.digithink.zsretail.erp.api.ErpConnectors;

/**
 * Test support (task 9.3, configuration step C1): an installation's configuration merged the way the application does,
 * without Spring: application.properties, under the type file (src/main/resources/application-&lt;type&gt;.properties,
 * the type named by spring.profiles.active, store by default), under the outside file (configs/... of this repository)
 * when there is one.
 * <p>
 * The six presets of task 9.3 are gone (step C1); each one is kept here as a variant, its type file with the mode lines
 * it gave (the lines its machine files now state), so the frozen rows of the truth tables load as before.
 */
public final class Installations {

	/**
	 * The repository's configs folder, every outside file of the repository (code cleanup step 7: deploy/ joined it; found
	 * from any project folder, {@link Repo#ROOT}): the machine files (dev/, rehearsal/, customers/, ...) and configs/local.
	 */
	public static final Path CONFIGS = Repo.ROOT.resolve("configs");

	/** The machine files: configs/ apart from configs/local (paths relative to configs/, as when they were in deploy/). */
	public static final Path MACHINES = CONFIGS;

	/** configs/local: the files of this PC's installations, checked as committed (ERP catalogue, step 1). */
	static final String LOCAL = "local/";

	/** Old preset name to its type and the mode lines it gave (task 9.3 files, removed at step C1). */
	private static final Map<String, String[]> VARIANTS = new LinkedHashMap<>();

	static {
		variant("store", "store", "");
		variant("store-erp", "store", "ownership.catalogue=ERP|ownership.customers=ERP|ownership.supply=ERP"
				+ "|sales.upstream=ERP|erp.connector=emtop");
		variant("headoffice", "headoffice", "");
		variant("headoffice-erp", "headoffice", "ownership.catalogue=ERP|ownership.customers=ERP|ownership.supply=ERP"
				+ "|erp.connector=emtop");
		variant("network-store", "store", "ownership.catalogue=HEAD_OFFICE|ownership.supply=HEAD_OFFICE"
				+ "|sales.upstream=HEAD_OFFICE");
		variant("network-store-erp", "store", "ownership.catalogue=ERP|ownership.customers=ERP"
				+ "|ownership.promotions=HEAD_OFFICE|ownership.loyalty=HEAD_OFFICE|ownership.supply=ERP"
				+ "|sales.upstream=ERP,HEAD_OFFICE|erp.connector=emtop");
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

	/** A machine file of configs/ (e.g. "dev/store-b.properties") over its type file and application.properties. */
	public static MockEnvironment machine(String relativePath) {
		return outside(MACHINES.resolve(relativePath));
	}

	/**
	 * An installation file of configs/local (e.g. "local/happyness_ho.properties"), {@link #upgraded}, over its type file and
	 * application.properties.
	 */
	public static MockEnvironment config(String relativePath) {
		return over(upgraded(file(CONFIGS.resolve(relativePath))));
	}

	/**
	 * Code cleanup step 8: an outside file of 2.2.2 upgraded as docs/deploy-note.md says ("Upgrade from 2.2.2: the outside
	 * file"): a connector's old enabled line becomes erp.connector=&lt;its name&gt; when true and goes when false; the
	 * other keys of its old block take its prefix erp.&lt;name&gt;. configs/local is Zein's (this repository does not edit
	 * it): the tests read its files as they will be once upgraded, and a start of the file as it is refuses
	 * (ErpConnectorTest). A file without old lines comes back as it is.
	 */
	public static Properties upgraded(Properties file) {
		Properties upgraded = new Properties();
		upgraded.putAll(file);
		for (ErpConnectorDescriptor descriptor : ErpConnectors.descriptors()) {
			if (descriptor.oldKeyPrefix() == null) {
				continue;
			}
			Object enabled = upgraded.remove(descriptor.oldKeyPrefix() + ".enabled");
			if (enabled != null && "true".equalsIgnoreCase(enabled.toString().trim())) {
				upgraded.setProperty("erp.connector", descriptor.name());
			}
			String old = descriptor.oldKeyPrefix() + ".";
			for (String key : file.stringPropertyNames()) {
				if (key.startsWith(old) && upgraded.containsKey(key)) {
					upgraded.setProperty(descriptor.keyPrefix() + "." + key.substring(old.length()),
							(String) upgraded.remove(key));
				}
			}
		}
		return upgraded;
	}

	/**
	 * Every installation file of configs/local that git tracks, as paths relative to configs/: a local file not committed (a
	 * rehearsal copy) does not change the tests. Every file when git cannot answer.
	 */
	public static List<String> configFiles() {
		List<String> files = propertiesFiles(CONFIGS).stream().filter(file -> file.startsWith(LOCAL)).collect(Collectors.toList());
		List<String> tracked = trackedUnder(CONFIGS);
		return tracked == null ? files : files.stream().filter(tracked::contains).collect(Collectors.toList());
	}

	/** The files git tracks under the folder, relative to it; null when git cannot answer. */
	private static List<String> trackedUnder(Path folder) {
		try {
			String relative = Repo.ROOT.relativize(folder).toString().replace('\\', '/');
			Process git = new ProcessBuilder("git", "ls-files", "--", relative).directory(Repo.ROOT.toFile())
					.redirectErrorStream(true).start();
			List<String> lines;
			try (java.io.BufferedReader out = new java.io.BufferedReader(
					new java.io.InputStreamReader(git.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
				lines = out.lines().collect(Collectors.toList());
			}
			if (git.waitFor() != 0) {
				return null;
			}
			String prefix = relative + "/";
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
		return over(file(path));
	}

	/** An outside file's lines over its type file and application.properties. */
	public static MockEnvironment over(Properties machine) {
		String type = machine.getProperty("spring.profiles.active", DEFAULT_TYPE);
		MockEnvironment env = type(type.trim());
		machine.stringPropertyNames().forEach(key -> env.setProperty(key, machine.getProperty(key)));
		return env;
	}

	/**
	 * The type of a file that names none: every file of configs/ that is a head office names headoffice (they
	 * were written for the single application, whose default was store).
	 */
	public static final String DEFAULT_TYPE = "store";

	/**
	 * A type (store, headoffice) over application.properties, without an outside file: the application of the type's
	 * default properties (code cleanup step 6.5), application.properties, the type file.
	 */
	public static MockEnvironment type(String type) {
		MockEnvironment env = new MockEnvironment();
		ZsRetailApplication.defaults(type).forEach((key, value) -> env.setProperty(key, value.toString()));
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

	/** Every machine file (the model and configs/local excluded), as paths relative to configs/. */
	public static List<String> machineFiles() {
		return propertiesFiles(MACHINES).stream().filter(file -> !file.startsWith(LOCAL)).collect(Collectors.toList());
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
