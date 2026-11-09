package com.digithink.zsretail.core.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.ByteArrayInputStream;

import org.apache.commons.compress.compressors.deflate64.Deflate64CompressorInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Boot 4 migration, step 1.4: commons-compress, which POI reads a .xlsx with, calls classes of commons-lang3 that the version
 * pinned by Boot's BOM does not have. An ordinary .xlsx never goes there, so every Excel test stays green; a rare file (one
 * zipped in Deflate64, as Windows Explorer does for large files) gets a NoClassDefFoundError, an Error that no
 * catch (Exception) of an import stops. The parent pom sets commons-lang3 to what commons-compress asks for.
 */
class ExcelLibrariesTest {

	@ParameterizedTest
	@ValueSource(strings = { "org.apache.commons.lang3.ArrayFill", "org.apache.commons.lang3.SystemProperties",
			"org.apache.commons.lang3.function.Suppliers" })
	@DisplayName("The classes of commons-lang3 that commons-compress calls are there")
	void classesCommonsCompressCalls(String name) {
		assertDoesNotThrow(() -> Class.forName(name));
	}

	@Test
	@DisplayName("A Deflate64 entry can be opened")
	void deflate64() {
		assertDoesNotThrow(() -> new Deflate64CompressorInputStream(new ByteArrayInputStream(new byte[0])).close());
	}
}
