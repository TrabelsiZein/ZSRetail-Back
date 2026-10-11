package com.digithink.zsretail.store.rfid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;
import com.digithink.zsretail.store.rfid.dto.RfidConnectionResult;
import com.digithink.zsretail.store.rfid.dto.RfidReadResult;
import com.digithink.zsretail.store.rfid.model.PosMachine;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.rfid.reader.RfidTag;
import com.digithink.zsretail.store.support.FakeRfidReader;
import com.digithink.zsretail.store.support.InMemoryRfid;

/**
 * RFID step 4: RfidReadService over the in-memory tables and the fake reader: the test connection, the test read, the
 * read for a POS machine found by its request address, the refusals with their messages, one request at a time per
 * reader.
 */
class RfidReadServiceTest {

	private final InMemoryRfid rfid = new InMemoryRfid();
	private final FakeRfidReader device = new FakeRfidReader();
	private final RfidReadService service = new RfidReadService(rfid.readerService, rfid.machineService, device);

	private final RfidReader desk = rfid.reader("Caisse 1", "192.168.100.200", 9090);
	private final RfidReader door = rfid.reader("Entrée", "192.168.100.201", 9090);

	private static List<String> epcs(RfidReadResult result) {
		return result.getTags().stream().map(RfidTag::getEpc).collect(Collectors.toList());
	}

	private static void assertRefused(Reason reason, String message, Executable call) {
		RfidException e = assertThrows(RfidException.class, call);
		assertEquals(reason, e.getReason());
		assertEquals(message, e.getMessage());
	}

	@Test
	@DisplayName("Test read: each tag once with its best signal, the reader's power and reading time, no POS machine")
	void testRead() {
		device.report("192.168.100.200:9090", "E2001", -61).report("192.168.100.200:9090", "E2002", -50)
				.report("192.168.100.200:9090", "E2001", -45).report("192.168.100.201:9090", "E9999", -40);
		desk.setPower(25);
		desk.setReadTimeMs(800);
		RfidReadResult result = service.testRead(desk.getId());
		assertNull(result.getPosMachineName());
		assertEquals("Caisse 1", result.getReaderName());
		assertEquals(800, result.getReadTimeMs());
		assertEquals(Arrays.asList("E2001", "E2002"), epcs(result));
		assertEquals(-45, result.getTags().get(0).getBestRssi());
		assertEquals(2, result.getTags().get(0).getSeen());
		assertEquals(Arrays.asList("read 192.168.100.200:9090 power 25 for 800 ms"), device.calls);
	}

	@Test
	@DisplayName("RFID weak tags, Test read: each tag weak below the reader's minimum signal; the minimum, the lowest and"
			+ " the highest signal of the read, to tune it; none without a tag")
	void testReadWeak() {
		device.report("192.168.100.200:9090", "E2001", -23).report("192.168.100.200:9090", "E2002", -44)
				.report("192.168.100.200:9090", "E2003", -61).report("192.168.100.200:9090", "E2003", -58);
		RfidReadResult result = service.testRead(desk.getId());
		assertEquals(-50, result.getMinSignal());
		assertEquals(-58.0, result.getLowestRssi(), "the best signal of each tag, the lowest of them");
		assertEquals(-23.0, result.getHighestRssi());
		assertEquals(Arrays.asList(false, false, true),
				result.getTags().stream().map(RfidTag::isWeak).collect(Collectors.toList()));

		desk.setMinSignal(-30);
		assertEquals(Arrays.asList(false, true, true),
				service.testRead(desk.getId()).getTags().stream().map(RfidTag::isWeak).collect(Collectors.toList()));

		RfidReadResult none = service.testRead(door.getId());
		assertNull(none.getLowestRssi());
		assertNull(none.getHighestRssi());
		assertEquals(-50, none.getMinSignal());
		assertTrue(RfidReadService.isWeak(-50.5, -50));
		assertFalse(RfidReadService.isWeak(-50, -50));
	}

	@Test
	@DisplayName("Test connection: the reader's information and address, also on an inactive reader")
	void testConnection() {
		desk.setActive(false);
		RfidConnectionResult result = service.testConnection(desk.getId());
		assertEquals("Caisse 1", result.getReaderName());
		assertEquals("192.168.100.200:9090", result.getAddress());
		assertEquals("FAKE reader at 192.168.100.200:9090", result.getInformation());
		assertEquals(Arrays.asList("connect 192.168.100.200:9090"), device.calls);
	}

	@Test
	@DisplayName("Refused before the reader: no reader of that id; an inactive reader is never read")
	void refusedReader() {
		assertRefused(Reason.NOT_FOUND, "No RFID reader number 99.", () -> service.testRead(99L));
		assertRefused(Reason.NOT_FOUND, "No RFID reader number 99.", () -> service.testConnection(99L));
		desk.setActive(false);
		assertRefused(Reason.NOT_READY, "The RFID reader 'Caisse 1' is inactive.", () -> service.testRead(desk.getId()));
		assertTrue(device.calls.isEmpty(), "the reader was not called: " + device.calls);
	}

	@Test
	@DisplayName("Read for a request: the machine of its address, in any form; the result names the machine and the reader")
	void readForRequest() {
		device.report("192.168.100.200:9090", "E2001", -50);
		rfid.machine("PC caisse 1", "192.168.1.11", desk);
		rfid.machine("PC du serveur", "127.0.0.1", door);

		RfidReadResult lan = service.readForRequest("192.168.1.11");
		assertEquals("PC caisse 1", lan.getPosMachineName());
		assertEquals("Caisse 1", lan.getReaderName());
		assertEquals(1500, lan.getReadTimeMs());
		assertEquals(Arrays.asList("E2001"), epcs(lan));

		for (String local : Arrays.asList("0:0:0:0:0:0:0:1", "::1", "127.0.0.1")) {
			RfidReadResult server = service.readForRequest(local);
			assertEquals("PC du serveur", server.getPosMachineName(), local);
			assertEquals("Entrée", server.getReaderName(), local);
			assertTrue(server.getTags().isEmpty());
		}
	}

	@Test
	@DisplayName("Refused for a request: no machine at the address (named, as normalised), machine inactive, no reader,"
			+ " reader inactive; the reader is never called")
	void refusedRequest() {
		assertRefused(Reason.NOT_FOUND, "No POS machine has the address 192.168.1.99: add this machine on the RFID POS"
				+ " machines page (it needs a fixed address).", () -> service.readForRequest("192.168.1.99"));
		assertRefused(Reason.NOT_FOUND, "No POS machine has the address 127.0.0.1: add this machine on the RFID POS"
				+ " machines page (it needs a fixed address).", () -> service.readForRequest("0:0:0:0:0:0:0:1"));

		PosMachine noReader = rfid.machine("PC bureau", "192.168.1.20", null);
		assertRefused(Reason.NOT_READY, "The POS machine 'PC bureau' has no RFID reader.",
				() -> service.readForRequest("192.168.1.20"));
		noReader.setReader(desk);
		noReader.setActive(false);
		assertRefused(Reason.NOT_READY, "The POS machine 'PC bureau' is inactive.",
				() -> service.readForRequest("192.168.1.20"));
		noReader.setActive(true);
		desk.setActive(false);
		assertRefused(Reason.NOT_READY, "The RFID reader 'Caisse 1' of the POS machine 'PC bureau' is inactive.",
				() -> service.readForRequest("192.168.1.20"));
		assertTrue(device.calls.isEmpty(), "the reader was not called: " + device.calls);
	}

	@Test
	@DisplayName("One request at a time per reader: a second one is BUSY at once; another reader reads meanwhile; then free")
	void busy() throws Exception {
		device.report("192.168.100.200:9090", "E2001", -50);
		rfid.machine("PC caisse 1", "192.168.1.11", desk);
		rfid.machine("PC caisse 2", "192.168.1.12", desk);
		rfid.machine("PC entrée", "192.168.1.13", door);

		CountDownLatch release = device.holdNextRead();
		CompletableFuture<RfidReadResult> first = CompletableFuture.supplyAsync(() -> service.readForRequest("192.168.1.11"));
		device.awaitHeldRead();

		long before = System.nanoTime();
		assertRefused(Reason.BUSY, "The RFID reader 'Caisse 1' is busy with another read: try again in a moment.",
				() -> service.readForRequest("192.168.1.12"));
		assertRefused(Reason.BUSY, "The RFID reader 'Caisse 1' is busy with another read: try again in a moment.",
				() -> service.testConnection(desk.getId()));
		assertTrue((System.nanoTime() - before) / 1_000_000 < 1000, "answered at once, without waiting");
		assertEquals("Entrée", service.readForRequest("192.168.1.13").getReaderName(), "another reader reads meanwhile");

		release.countDown();
		assertEquals("PC caisse 1", first.get(5, TimeUnit.SECONDS).getPosMachineName());
		assertEquals("PC caisse 2", service.readForRequest("192.168.1.12").getPosMachineName(), "free again");
	}

	@Test
	@DisplayName("The reader's own failures pass through (UNREACHABLE, READER_ERROR) and free the reader")
	void readerFailures() {
		device.unreachable("192.168.100.200:9090");
		assertRefused(Reason.UNREACHABLE, "The RFID reader 'Caisse 1' does not answer at 192.168.100.200:9090.",
				() -> service.testRead(desk.getId()));
		assertRefused(Reason.UNREACHABLE, "The RFID reader 'Caisse 1' does not answer at 192.168.100.200:9090.",
				() -> service.testConnection(desk.getId()));
		device.failing("192.168.100.201:9090");
		assertRefused(Reason.READER_ERROR, "The RFID reader 'Entrée' answered with an error.",
				() -> service.testRead(door.getId()));

		// The lock was released: the next request reaches the reader again (not BUSY)
		assertRefused(Reason.UNREACHABLE, "The RFID reader 'Caisse 1' does not answer at 192.168.100.200:9090.",
				() -> service.testRead(desk.getId()));
		assertEquals(4, device.calls.size());
	}
}
