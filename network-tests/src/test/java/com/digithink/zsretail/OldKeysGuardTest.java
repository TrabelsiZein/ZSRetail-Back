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

import com.digithink.zsretail.core.support.Repo;
import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;
import com.digithink.zsretail.erp.api.ErpConnectors;

/**
 * Code cleanup step 8: the old configuration keys are gone. No file that the applications or an installation read names
 * one: every project's src/main/java and src/main/resources, configs/, eclipse/ and devenv/. The old names are each
 * connector's old key prefix (erp.dynamicsnav, erp.navpospages), declared by its descriptor only, where the refusals read
 * them: the descriptors are the only files allowed to name them. Not scanned: the released SQL scripts
 * (src/main/resources/db/, never edited) and configs/local (Zein's own files, upgraded by hand as docs/deploy-note.md
 * says).
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

	/** The old key prefixes, as the connectors declare them. */
	static List<String> oldNames() {
		return ErpConnectors.descriptors().stream().map(ErpConnectorDescriptor::oldKeyPrefix).filter(name -> name != null)
				.collect(Collectors.toList());
	}

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
	@DisplayName("No old key name in main code, resources, configs/, eclipse/ or devenv/, except in the descriptors")
	void noOldKeys() throws IOException {
		List<String> names = oldNames();
		List<String> allowed = declaring();
		List<String> offences = new ArrayList<>();
		List<String> files = files();
		for (String file : files) {
			if (!allowed.contains(file)) {
				offences.addAll(offences(file,
						Arrays.asList(new String(Files.readAllBytes(Repo.ROOT.resolve(file)), StandardCharsets.ISO_8859_1)
								.split("\r?\n")),
						names));
			}
		}
		assertTrue(files.size() > 500, "files scanned: " + files.size());
		assertEquals(new ArrayList<String>(), offences);
	}

	@Test
	@DisplayName("The guard itself: the old names from the descriptors, only they may name them, what is not scanned")
	void guardItself() {
		assertEquals(Arrays.asList("erp.dynamicsnav", "erp.navpospages"), oldNames());
		assertEquals(Arrays.asList(
				"connectors/emtop/src/main/java/com/digithink/zsretail/connector/emtop/EmtopConnectorDescriptor.java",
				"connectors/happyness/src/main/java/com/digithink/zsretail/connector/happyness/HappynessConnectorDescriptor.java"),
				declaring());
		assertEquals(2, offences("x.properties", Arrays.asList("erp.dynamicsnav.base-url=x", "# the erp.navpospages.* keys",
				"erp.emtop.base-url=x", "erp.connector=happyness"), oldNames()).size());
		assertFalse(scanned("core/src/main/resources/db/2.2.0/update.sql"), "a released script is never edited");
		assertFalse(scanned("configs/local/happyness_ho.properties"), "Zein's own files");
		assertTrue(scanned("configs/dev/store-b.properties"));
		assertTrue(files().contains("configs/machine-model.properties"));
		assertTrue(files().contains("eclipse/dev-store-b.launch"));
		assertTrue(files().contains("devenv/common.ps1"));
	}
}
