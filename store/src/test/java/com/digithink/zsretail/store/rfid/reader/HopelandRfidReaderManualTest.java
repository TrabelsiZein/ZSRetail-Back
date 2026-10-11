package com.digithink.zsretail.store.rfid.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.model.RfidReader;

/**
 * RFID step 5, manual: the Hopeland class against a real reader, with tags in front of it. Out of the normal suite: it
 * runs only when the reader's address is given, with the reader's current power (30 by default, so nothing is written
 * to the reader):
 *
 * <pre>
 * mvn test -pl store -am -Dtest=HopelandRfidReaderManualTest -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Drfid.reader=192.168.100.200:9090 [-Drfid.reader.power=30] [-Drfid.reader.absent=192.168.100.201:9090]
 * </pre>
 *
 * It prints what it finds (the reader's information, the tags, the times) in the test output.
 */
@EnabledIfSystemProperty(named = "rfid.reader", matches = ".+:\\d+")
class HopelandRfidReaderManualTest {

	private final HopelandRfidReader hopeland = new HopelandRfidReader();

	private static RfidReader reader(String address) {
		RfidReader reader = new RfidReader();
		reader.setName("manual test");
		reader.setIpAddress(address.substring(0, address.lastIndexOf(':')));
		reader.setPort(Integer.parseInt(address.substring(address.lastIndexOf(':') + 1)));
		reader.setPower(Integer.getInteger("rfid.reader.power", RfidReader.DEFAULT_POWER));
		return reader;
	}

	@Test
	@DisplayName("The real reader: test connection, two reads of 1500 ms with tags, nothing left open")
	void realReader() {
		RfidReader reader = reader(System.getProperty("rfid.reader"));

		long start = System.nanoTime();
		String information = hopeland.testConnection(reader);
		System.out.println("RFID manual: test connection " + ms(start) + " ms: " + information);

		for (int i = 1; i <= 2; i++) {
			start = System.nanoTime();
			List<RfidTag> tags = hopeland.read(reader, RfidReader.DEFAULT_READ_TIME_MS);
			long took = ms(start);
			System.out.println("RFID manual: read " + i + ", " + tags.size() + " tags in " + took + " ms");
			tags.forEach(tag -> System.out.println("RFID manual:   " + tag));
			assertFalse(tags.isEmpty(), "tags in front of the reader");
			assertTrue(took >= RfidReader.DEFAULT_READ_TIME_MS, "read for its time: " + took);
		}
		assertTrue(hopeland.openConnections().isEmpty(), "every connection closed");
	}

	@Test
	@EnabledIfSystemProperty(named = "rfid.reader.absent", matches = ".+:\\d+")
	@DisplayName("An address with no reader: UNREACHABLE within the 2 s TCP check")
	void absentReader() {
		long start = System.nanoTime();
		RfidException e = assertThrows(RfidException.class,
				() -> hopeland.read(reader(System.getProperty("rfid.reader.absent")), 1500));
		long took = ms(start);
		System.out.println("RFID manual: absent reader " + took + " ms: " + e.getMessage());
		assertEquals(RfidException.Reason.UNREACHABLE, e.getReason());
		assertTrue(took < 3000, "within the TCP check: " + took);
	}

	private static long ms(long start) {
		return (System.nanoTime() - start) / 1_000_000;
	}
}
