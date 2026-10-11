package com.digithink.zsretail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.support.Repo;

/**
 * RFID step 7, guard: the vendor's SDK (com.rfidread, libs/hopeland/README.md) is used by one class only,
 * HopelandRfidReader, and taken by one project only, the store. Any other main class that names the SDK, by an
 * import or a full name, and any other pom that takes it, fail here.
 */
class RfidSdkConfinementGuardTest {

	static final String SDK_PACKAGE = "com.rfidread";
	static final String THE_ONE_CLASS = "com/digithink/zsretail/store/rfid/reader/HopelandRfidReader.java";

	@Test
	@DisplayName("Only HopelandRfidReader names com.rfidread, in every project's main code")
	void oneClassUsesTheSdk() throws IOException {
		List<String> users = new ArrayList<>();
		for (Map.Entry<String, Path> file : Repo.mainJavaFiles().entrySet()) {
			String text = new String(Files.readAllBytes(file.getValue()), StandardCharsets.UTF_8);
			if (text.contains(SDK_PACKAGE + ".")) {
				users.add(file.getKey());
			}
		}
		Collections.sort(users);
		assertEquals(Collections.singletonList(THE_ONE_CLASS), users);
	}

	@Test
	@DisplayName("Only the store's pom takes the SDK (rfid-reader-api)")
	void oneProjectTakesTheSdk() throws IOException {
		List<String> poms = new ArrayList<>();
		for (String project : Repo.projects()) {
			Path pom = Repo.ROOT.resolve(project).resolve("pom.xml");
			if (new String(Files.readAllBytes(pom), StandardCharsets.UTF_8).contains("<artifactId>rfid-reader-api</artifactId>")) {
				poms.add(project);
			}
		}
		if (new String(Files.readAllBytes(Repo.ROOT.resolve("pom.xml")), StandardCharsets.UTF_8)
				.contains("rfid-reader-api")) {
			poms.add("(the parent)");
		}
		assertEquals(Collections.singletonList("store"), poms);
	}
}
