package com.digithink.zsretail.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Code cleanup step 6: the name a class had before the moves of step 6 (config/class-renames.txt, one line per class
 * moved: old name, new name), so a proof recorded before the moves reads the classes by their old names.
 */
public final class ClassRenames {

	private static final Map<String, String> OLD_BY_NEW = load();

	private ClassRenames() {
	}

	private static Map<String, String> load() {
		Map<String, String> oldByNew = new HashMap<>();
		try (InputStream in = ClassRenames.class.getResourceAsStream("/config/class-renames.txt");
				BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			for (String line; (line = reader.readLine()) != null;) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] names = line.split("\\s+");
				if (oldByNew.put(names[1], names[0]) != null) {
					throw new IllegalStateException("class-renames.txt names " + names[1] + " twice");
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return Collections.unmodifiableMap(oldByNew);
	}

	/** The old name of a class (or of "class#method"); the name itself when the class did not move. */
	public static String oldName(String name) {
		int hash = name.indexOf('#');
		String type = hash < 0 ? name : name.substring(0, hash);
		return OLD_BY_NEW.getOrDefault(type, type) + (hash < 0 ? "" : name.substring(hash));
	}
}
