package com.digithink.zsretail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.support.Repo;

/**
 * Code cleanup step 6: a full class name written in a string of the main code (a JPQL "select new", an enum constant in
 * JPQL, an AOP pointcut, a scanned package) is only checked when the application runs it. Every one must name a class
 * (or a constant of one) that exists, so a class moved to another package cannot leave a string behind.
 */
class ClassNamesInStringsGuardTest {

	static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
	static final Pattern NAME = Pattern.compile("com\\.digithink\\.zsretail(?:\\.\\w+)+");

	/** True when the name, or the name without its last parts (a constant, a method), is a class. */
	static boolean exists(String name) {
		for (String candidate = name; candidate.contains("."); candidate = candidate.substring(0,
				candidate.lastIndexOf('.'))) {
			try {
				Class.forName(candidate, false, ClassNamesInStringsGuardTest.class.getClassLoader());
				return true;
			} catch (ClassNotFoundException e) {
				// shorter
			}
		}
		return false;
	}

	@Test
	@DisplayName("Every full class name in a string of the main code names a class that exists")
	void namesExist() throws IOException {
		List<String> missing = new ArrayList<>();
		int found = 0;
		for (Map.Entry<String, Path> file : Repo.mainJavaFiles().entrySet()) {
			List<String> lines = Files.readAllLines(file.getValue(), StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				Matcher string = STRING.matcher(lines.get(i));
				while (string.find()) {
					Matcher name = NAME.matcher(string.group(1));
					while (name.find()) {
						found++;
						if (!exists(name.group())) {
							missing.add(file.getKey() + ":" + (i + 1) + ": " + name.group());
						}
					}
				}
			}
		}
		assertTrue(found >= 14, "names found in strings: " + found);
		assertEquals(new ArrayList<String>(), missing);
	}

	@Test
	@DisplayName("The guard itself: a class, a nested name or constant pass; a name that is no class fails")
	void guardCatches() {
		assertTrue(exists("com.digithink.zsretail.core.support.Repo"));
		assertTrue(exists("com.digithink.zsretail.core.support.Repo.ROOT"));
		assertTrue(!exists("com.digithink.zsretail.model.NoSuchClass"));
	}
}
