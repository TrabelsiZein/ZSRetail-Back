package com.digithink.zsretail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.config.OldLines;
import com.digithink.zsretail.core.support.Repo;
import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;
import com.digithink.zsretail.erp.api.ErpConnectors;

/**
 * Code cleanup step 8: the old configuration keys are gone. No file that the applications or an installation read names
 * one: every project's src/main/java and src/main/resources, configs/, eclipse/ and devenv/. The old names are each
 * connector's old key prefix (erp.dynamicsnav, erp.navpospages), declared by its descriptor only, where the refusals read
 * them: the descriptors are the only files allowed to name them. Not scanned: the released SQL scripts
 * (src/main/resources/db/, never edited) and configs/local (Zein's own files, upgraded by hand as docs/deploy-note.md
 * says). The dead keys (a connector's, declared by its descriptor; the application's, by core's OldLines) are named only
 * there too. The type lines (spring.profiles.active, node.type) are refused in the files of an installation, its launches
 * and its scripts (configs/, eclipse/, devenv/).
 */
class OldKeysGuardTest {

	/** The folders scanned, relative to the repository: each project's main code and resources, and these three. */
	static List<String> folders() {
		List<String> folders = new ArrayList<>();
		for (String project : Repo.projects()) {
			folders.add(project + "/src/main/java");
			folders.add(project + "/src/main/resources");
		}
		folders.addAll(Arrays.asList("configs", "eclipse", "devenv"));
		return folders;
	}

	/** False for the released SQL scripts and configs/local. */
	static boolean scanned(String relativePath) {
		return !relativePath.contains("/src/main/resources/db/") && !relativePath.startsWith("configs/local/");
	}

	/** Every file scanned, relative to the repository, sorted. */
	static List<String> files() {
		TreeSet<String> files = new TreeSet<>();
		for (String folder : folders()) {
			Path start = Repo.ROOT.resolve(folder);
			if (!Files.isDirectory(start)) {
				continue;
			}
			try (Stream<Path> walk = Files.walk(start)) {
				walk.filter(Files::isRegularFile)
						.map(file -> Repo.ROOT.relativize(file).toString().replace('\\', '/')).filter(OldKeysGuardTest::scanned)
						.forEach(files::add);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return new ArrayList<>(files);
	}

	/**
	 * The type lines, refused since code cleanup step 8 (the application is the type): never in the files of an
	 * installation, its launches or its scripts. The application's own code still names them, to state its type and to
	 * refuse them (ZsRetailApplication, InstallationTypeEnvironmentPostProcessor; node.type is read inside).
	 */
	static final List<String> TYPE_LINES = Arrays.asList("spring.profiles.active", "node.type", "SPRING_PROFILES_ACTIVE");

	/** A file of an installation (configs/), a launch (eclipse/) or a dev script (devenv/). */
	static boolean installationFile(String relativePath) {
		return relativePath.startsWith("configs/") || relativePath.startsWith("eclipse/")
				|| relativePath.startsWith("devenv/");
	}

	/** The old key prefixes, as the connectors declare them. */
	static List<String> oldNames() {
		return ErpConnectors.descriptors().stream().map(ErpConnectorDescriptor::oldKeyPrefix).filter(name -> name != null)
				.collect(Collectors.toList());
	}

	/**
	 * Code cleanup step 8: the dead keys, the application's and the connectors' under their new prefix (under the old one
	 * they are covered by the old prefix).
	 */
	static List<String> deadNames() {
		return OldLines.deadKeys().stream().filter(key -> oldNames().stream().noneMatch(key::startsWith))
				.collect(Collectors.toList());
	}

	/** Where the application declares its dead keys, the only file of core allowed to name them. */
	static final String CORE_DECLARATION = "core/src/main/java/com/digithink/zsretail/core/config/OldLines.java";

	/** The source files of the descriptors that declare an old prefix, relative to the repository. */
	static List<String> declaring() {
		Map<String, Path> sources = Repo.mainJavaFiles();
		return ErpConnectors.descriptors().stream().filter(descriptor -> descriptor.oldKeyPrefix() != null)
				.map(descriptor -> sources.get(descriptor.getClass().getName().replace('.', '/') + ".java"))
				.map(file -> Repo.ROOT.relativize(file).toString().replace('\\', '/')).collect(Collectors.toList());
	}

	/** The lines of the file that name an old key, as "path:line: text". */
	static List<String> offences(String relativePath, List<String> lines, List<String> names) {
		List<String> found = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			for (String name : names) {
				if (lines.get(i).contains(name)) {
					found.add(relativePath + ":" + (i + 1) + ": " + lines.get(i).trim());
					break;
				}
			}
		}
		return found;
	}

	@Test
	@DisplayName("No old key name in main code, resources, configs/, eclipse/ or devenv/ (except in the descriptors); no type line in configs/, eclipse/ or devenv/")
	void noOldKeys() throws IOException {
		List<String> names = new ArrayList<>(oldNames());
		names.addAll(deadNames());
		List<String> withTypeLines = new ArrayList<>(names);
		withTypeLines.addAll(TYPE_LINES);
		List<String> allowed = new ArrayList<>(declaring());
		allowed.add(CORE_DECLARATION);
		List<String> offences = new ArrayList<>();
		List<String> files = files();
		for (String file : files) {
			if (!allowed.contains(file)) {
				offences.addAll(offences(file,
						Arrays.asList(new String(Files.readAllBytes(Repo.ROOT.resolve(file)), StandardCharsets.ISO_8859_1)
								.split("\r?\n")),
						installationFile(file) ? withTypeLines : names));
			}
		}
		assertTrue(files.size() > 500, "files scanned: " + files.size());
		assertEquals(new ArrayList<String>(), offences);
	}

	@Test
	@DisplayName("The guard itself: the old names from the descriptors, only they may name them, what is not scanned")
	void guardItself() {
		assertEquals(Arrays.asList("erp.dynamicsnav", "erp.navpospages"), oldNames());
		assertEquals(Arrays.asList("franchise.admin", "franchise.customer", "franchise.customer.allow-local-items",
				"franchise.api-key", "franchise.remote.url", "franchise.sync.sales.cron", "erp.happyness.location-code",
				"erp.happyness.invoices.years", "erp.happyness.invoices.start-number",
				"erp.happyness.deactivate-guard-percent"), deadNames());
		assertTrue(files().contains(CORE_DECLARATION));
		assertEquals(Arrays.asList(
				"connectors/emtop/src/main/java/com/digithink/zsretail/connector/emtop/EmtopConnectorDescriptor.java",
				"connectors/happyness/src/main/java/com/digithink/zsretail/connector/happyness/HappynessConnectorDescriptor.java"),
				declaring());
		assertEquals(2, offences("x.properties", Arrays.asList("erp.dynamicsnav.base-url=x", "# the erp.navpospages.* keys",
				"erp.emtop.base-url=x", "erp.connector=happyness"), oldNames()).size());
		assertFalse(scanned("core/src/main/resources/db/2.2.0/update.sql"), "a released script is never edited");
		assertFalse(scanned("configs/local/test_happyness_ho.properties"), "Zein's own files");
		assertTrue(scanned("configs/dev/store-b.properties"));
		assertTrue(files().contains("configs/machine-model.properties"));
		// The shared Eclipse launches left the repository (2026-10-11): each developer's own, still scanned when there
		assertTrue(folders().contains("eclipse"));
		assertTrue(files().contains("devenv/common.ps1"));
		assertEquals(2, offences("x.launch", Arrays.asList("-Dspring.profiles.active=store", "node.type=STORE", "-Dx=y"),
				TYPE_LINES).size());
		assertTrue(installationFile("configs/dev/store-b.properties") && installationFile("eclipse/Happyness-store.launch")
				&& !installationFile("core/src/main/java/X.java"), "the type lines: in installation files only");
	}
}
