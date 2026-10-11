package com.digithink.zsretail.core.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Code cleanup step 6: the folder of the repository (the parent POM, configs/) and the sources of every Maven
 * project in it, whichever project folder a test runs from (Maven runs each project's tests in its own folder).
 */
public final class Repo {

	/** The folder holding the parent POM and configs/. */
	public static final Path ROOT = root();

	private Repo() {
	}

	private static Path root() {
		Path start = Paths.get("").toAbsolutePath();
		for (Path dir = start; dir != null; dir = dir.getParent()) {
			if (Files.isDirectory(dir.resolve("configs")) && Files.isRegularFile(dir.resolve("pom.xml"))) {
				return dir;
			}
		}
		throw new IllegalStateException("No repository folder (configs/ and pom.xml) at or above " + start);
	}

	/** The src/main/java folder of every project, by project folder name. */
	public static List<Path> mainSources() {
		return projects().stream().map(project -> ROOT.resolve(project).resolve("src").resolve("main").resolve("java"))
				.filter(Files::isDirectory).collect(Collectors.toList());
	}

	/**
	 * Every Maven project of the repository, as its folder relative to the root ("core", "connectors/emtop"): a folder
	 * with a pom.xml, at the root or one level below (code cleanup step 7: the connectors are in connectors/), sorted.
	 */
	public static List<String> projects() {
		List<String> found = new ArrayList<>();
		try (Stream<Path> children = Files.list(ROOT)) {
			for (Path child : children.filter(Files::isDirectory).sorted().collect(Collectors.toList())) {
				if (Files.isRegularFile(child.resolve("pom.xml"))) {
					found.add(child.getFileName().toString());
				}
				try (Stream<Path> grandChildren = Files.list(child)) {
					grandChildren.filter(dir -> Files.isRegularFile(dir.resolve("pom.xml"))).sorted()
							.forEach(dir -> found.add(child.getFileName() + "/" + dir.getFileName()));
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return found;
	}

	/** The connector projects (connectors/&lt;name&gt;), by name: their package root is com.digithink.zsretail.connector.&lt;name&gt;. */
	public static List<String> connectorNames() {
		return projects().stream().filter(project -> project.startsWith("connectors/"))
				.map(project -> project.substring("connectors/".length())).collect(Collectors.toList());
	}

	/**
	 * Every .java file of every project's src/main/java, by its path under that folder ("com/digithink/zsretail/..."). The
	 * same path in two projects fails: a class lives in one project.
	 */
	public static Map<String, Path> mainJavaFiles() {
		Map<String, Path> files = new TreeMap<>();
		List<String> twice = new ArrayList<>();
		for (Path sources : mainSources()) {
			try (Stream<Path> walk = Files.walk(sources)) {
				for (Path file : walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
					String relativePath = sources.relativize(file).toString().replace('\\', '/');
					Path before = files.put(relativePath, file);
					if (before != null) {
						twice.add(relativePath + ": " + before + " and " + file);
					}
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		if (!twice.isEmpty()) {
			throw new IllegalStateException("A class in two projects: " + twice);
		}
		return files;
	}
}
