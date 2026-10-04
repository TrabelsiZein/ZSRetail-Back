package com.digithink.zsretail.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.mock.env.MockEnvironment;

/**
 * Test support (task 9.3): an installation's configuration merged the way the application does, without Spring:
 * application.properties, under the preset (src/main/resources/application-&lt;preset&gt;.properties), under the machine
 * file (deploy/... of this repository, which names the preset with spring.profiles.active).
 */
public final class Installations {

	/** The repository's deploy folder (the tests run from the project directory). */
	public static final Path DEPLOY = Paths.get("deploy");

	private Installations() {
	}

	/** A machine file of deploy/ (e.g. "dev/store-b.properties") over its preset and application.properties. */
	public static MockEnvironment machine(String relativePath) {
		Properties machine = file(DEPLOY.resolve(relativePath));
		String preset = machine.getProperty("spring.profiles.active");
		MockEnvironment env = preset(preset == null ? "" : preset.trim());
		machine.stringPropertyNames().forEach(key -> env.setProperty(key, machine.getProperty(key)));
		return env;
	}

	/** A preset over application.properties, without a machine file. */
	public static MockEnvironment preset(String preset) {
		MockEnvironment env = new MockEnvironment();
		Properties base = resource("/application.properties");
		base.stringPropertyNames().forEach(key -> env.setProperty(key, base.getProperty(key)));
		Properties keys = resource("/application-" + preset + ".properties");
		keys.stringPropertyNames().forEach(key -> env.setProperty(key, keys.getProperty(key)));
		return env;
	}

	/** Every machine file of deploy/ (the model excluded), as paths relative to deploy/. */
	public static List<String> machineFiles() {
		try (Stream<Path> files = Files.walk(DEPLOY)) {
			return files.filter(p -> p.toString().endsWith(".properties"))
					.filter(p -> !p.getFileName().toString().equals("machine-model.properties"))
					.map(p -> DEPLOY.relativize(p).toString().replace('\\', '/')).sorted().collect(Collectors.toList());
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
