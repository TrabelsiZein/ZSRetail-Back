package com.digithink.zsretail.store.rfid.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.rfidread.RFIDReader;

/**
 * RFID step 5: the Hopeland reader without a reader. A port where nothing listens is UNREACHABLE at once, the SDK never
 * called (no connection in its map, none left open); an address that never answers is UNREACHABLE after the 2 s TCP
 * check at most, not the SDK's 5 s. The real reader: HopelandRfidReaderManualTest.
 */
class HopelandRfidReaderTest {

	private final HopelandRfidReader hopeland = new HopelandRfidReader();

	private static RfidReader reader(String ip, int port) {
		RfidReader reader = new RfidReader();
		reader.setName("Caisse 1");
		reader.setIpAddress(ip);
		reader.setPort(port);
		return reader;
	}

	/** A local port where nothing listens. */
	private static int closedPort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private void assertUnreachable(String connection, Executable call, long maxMillis) {
		long start = System.nanoTime();
		RfidException e = assertThrows(RfidException.class, call);
		long took = (System.nanoTime() - start) / 1_000_000;
		assertEquals(RfidException.Reason.UNREACHABLE, e.getReason());
		assertEquals("The RFID reader 'Caisse 1' does not answer at " + connection + " (no connection within 2 s): is it"
				+ " on, and on this network?", e.getMessage());
		assertTrue(took < maxMillis, "answered in " + took + " ms");
		assertFalse(RFIDReader.HP_CONNECT.containsKey(connection), "the SDK was never asked to connect");
		assertTrue(hopeland.openConnections().isEmpty());
	}

	@Test
	@DisplayName("Nothing listens at the port: UNREACHABLE at once, for a read and a test connection, without the SDK")
	void nothingListens() throws Exception {
		int port = closedPort();
		RfidReader reader = reader("127.0.0.1", port);
		assertUnreachable("127.0.0.1:" + port, () -> hopeland.read(reader, 1500), 1500);
		assertUnreachable("127.0.0.1:" + port, () -> hopeland.testConnection(reader), 1500);
	}

	@Test
	@DisplayName("An address that never answers (TEST-NET 192.0.2.1): UNREACHABLE within the 2 s TCP check")
	void neverAnswers() {
		assertUnreachable("192.0.2.1:9090", () -> hopeland.read(reader("192.0.2.1", 9090), 1500), 3500);
	}

	@Test
	@DisplayName("Shutdown with no connection open: nothing to close")
	void closeAllWithNothingOpen() {
		hopeland.closeAll();
		assertTrue(hopeland.openConnections().isEmpty());
	}
}
