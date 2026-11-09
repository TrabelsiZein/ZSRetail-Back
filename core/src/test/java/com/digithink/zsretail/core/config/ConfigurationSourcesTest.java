package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Boot 4 migration, step 1.4: the words a refusal uses for where a line is. Since Boot 2.4 a configuration file is named
 * differently; the sentence stays readable.
 */
class ConfigurationSourcesTest {

	private static String describe(String sourceName) {
		return ConfigurationSources.describe(new StandardEnvironment(),
				new MapPropertySource(sourceName, Collections.singletonMap("a", "b")));
	}

	@Test
	@DisplayName("A configuration file of the WAR: its file name")
	void classpathFile() {
		assertEquals("the configuration file application-store.properties",
				describe("Config resource 'class path resource [application-store.properties]' via location 'optional:classpath:/'"));
	}

	@Test
	@DisplayName("A configuration file on the disk: its path")
	void fileOnDisk() {
		assertEquals("the configuration file config\\application.properties",
				describe("Config resource 'file [config\\application.properties]' via location 'optional:file:./config/'"));
	}

	@Test
	@DisplayName("The others keep their words")
	void others() {
		assertEquals("a system property (-D, the IDE or the server's options)", describe("systemProperties"));
		assertEquals("an environment variable", describe("systemEnvironment"));
		assertEquals("a command-line argument", describe("commandLineArgs"));
		assertEquals("the property source anything", describe("anything"));
	}
}
