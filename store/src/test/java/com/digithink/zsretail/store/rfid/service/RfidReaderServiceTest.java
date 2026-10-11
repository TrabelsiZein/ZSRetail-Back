package com.digithink.zsretail.store.rfid.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.support.InMemoryRfid;

/** RFID step 3: the rules of a reader (RfidReaderService) over the in-memory tables. */
class RfidReaderServiceTest {

	private final InMemoryRfid rfid = new InMemoryRfid();

	private static RfidReader reader(String name, String ip, Integer port) {
		RfidReader reader = new RfidReader();
		reader.setName(name);
		reader.setIpAddress(ip);
		reader.setPort(port);
		return reader;
	}

	private static void assertRefused(String message, Executable save) {
		RfidException e = assertThrows(RfidException.class, save);
		assertEquals(RfidException.Reason.INVALID, e.getReason());
		assertEquals(message, e.getMessage());
	}

	@Test
	@DisplayName("Saved with its texts trimmed and the defaults: power 30, 1500 ms, active, the logged user")
	void savedWithDefaults() throws Exception {
		RfidReader in = reader("  Caisse 1 ", " 192.168.100.200 ", 9090);
		in.setPower(null);
		in.setReadTimeMs(null);
		in.setActive(null);
		RfidReader saved = rfid.readerService.save(in);
		assertEquals("Caisse 1", saved.getName());
		assertEquals("192.168.100.200", saved.getIpAddress());
		assertEquals(30, saved.getPower());
		assertEquals(1500, saved.getReadTimeMs());
		assertTrue(saved.getActive());
		assertEquals("tester", saved.getCreatedBy());
		assertEquals(1, rfid.readers.size());
		// A new reader object already has the defaults
		assertEquals(30, new RfidReader().getPower());
		assertEquals(1500, new RfidReader().getReadTimeMs());
	}

	@Test
	@DisplayName("A name is required, at most 100 characters, unique in any case; the reader itself may keep its name")
	void name() throws Exception {
		assertRefused("The reader needs a name.", () -> rfid.readerService.save(reader(null, "10.0.0.1", 9090)));
		assertRefused("The reader needs a name.", () -> rfid.readerService.save(reader("   ", "10.0.0.1", 9090)));
		assertRefused("The reader's name has at most 100 characters.",
				() -> rfid.readerService.save(reader(repeat('x', 101), "10.0.0.1", 9090)));
		assertDoesNotThrow(() -> rfid.readerService.save(reader(repeat('x', 100), "10.0.0.9", 9090)));

		RfidReader first = rfid.reader("Caisse 1", "10.0.0.1", 9090);
		assertRefused("A reader is already named 'Caisse 1'.",
				() -> rfid.readerService.save(reader("CAISSE 1", "10.0.0.2", 9090)));
		first.setPower(20);
		assertEquals(20, rfid.readerService.save(first).getPower(), "the same reader saved again");
	}

	@Test
	@DisplayName("The address is an IPv4 address; the port 1 to 65535; the address and port pair unique")
	void addressAndPort() {
		for (String bad : new String[] { "reader-1", "::1", "192.168.1", "" }) {
			assertRefused("The reader's address must be an IPv4 address such as 192.168.1.50, not '" + bad + "'.",
					() -> rfid.readerService.save(reader("R", bad, 9090)));
		}
		assertRefused("The reader's address must be an IPv4 address such as 192.168.1.50, not ''.",
				() -> rfid.readerService.save(reader("R", null, 9090)));
		assertRefused("The reader's port must be a number from 1 to 65535.",
				() -> rfid.readerService.save(reader("R", "10.0.0.1", null)));
		assertRefused("The reader's port must be a number from 1 to 65535, not 0.",
				() -> rfid.readerService.save(reader("R", "10.0.0.1", 0)));
		assertRefused("The reader's port must be a number from 1 to 65535, not 65536.",
				() -> rfid.readerService.save(reader("R", "10.0.0.1", 65536)));

		rfid.reader("Caisse 1", "10.0.0.1", 9090);
		assertRefused("The reader 'Caisse 1' already has the address 10.0.0.1:9090.",
				() -> rfid.readerService.save(reader("Caisse 2", "10.0.0.1", 9090)));
		assertDoesNotThrow(() -> rfid.readerService.save(reader("Caisse 2", "10.0.0.1", 9091)), "another port");
		assertDoesNotThrow(() -> rfid.readerService.save(reader("Caisse 3", "10.0.0.2", 65535)), "another address");
	}

	@Test
	@DisplayName("The power 0 to 30 and the reading time 100 to 10000 ms, the limits included")
	void powerAndTime() {
		RfidReader low = reader("Low", "10.0.0.1", 9090);
		low.setPower(0);
		low.setReadTimeMs(100);
		assertDoesNotThrow(() -> rfid.readerService.save(low));
		RfidReader high = reader("High", "10.0.0.2", 9090);
		high.setPower(30);
		high.setReadTimeMs(10000);
		assertDoesNotThrow(() -> rfid.readerService.save(high));

		for (int power : new int[] { -1, 31 }) {
			RfidReader r = reader("R", "10.0.0.3", 9090);
			r.setPower(power);
			assertRefused("The reading power must be from 0 to 30, not " + power + ".", () -> rfid.readerService.save(r));
		}
		for (int time : new int[] { 99, 10001 }) {
			RfidReader r = reader("R", "10.0.0.3", 9090);
			r.setReadTimeMs(time);
			assertRefused("The reading time must be from 100 to 10000 ms, not " + time + ".",
					() -> rfid.readerService.save(r));
		}
	}

	@Test
	@DisplayName("A reader linked to POS machines is not deleted (the message names them); once unlinked it is")
	void deletion() throws Exception {
		RfidReader used = rfid.reader("Caisse 1", "10.0.0.1", 9090);
		RfidReader free = rfid.reader("Spare", "10.0.0.2", 9090);
		rfid.machine("PC caisse 2", "192.168.1.12", used);
		rfid.machine("PC caisse 1", "192.168.1.11", used);
		rfid.machine("PC bureau", "192.168.1.20", null);

		assertRefused("This reader is used by the POS machines PC caisse 1, PC caisse 2: give them another reader, or"
				+ " none, first.", () -> rfid.readerService.deleteById(used.getId()));
		assertTrue(rfid.readers.containsKey(used.getId()));

		rfid.machineService.deleteById(rfid.machineService.findByRequestAddress("192.168.1.12").get().getId());
		assertRefused("This reader is used by the POS machine PC caisse 1: give them another reader, or none, first.",
				() -> rfid.readerService.deleteById(used.getId()));

		rfid.readerService.deleteById(free.getId());
		assertFalse(rfid.readers.containsKey(free.getId()));
	}

	@Test
	@DisplayName("get: the reader, or NOT_FOUND naming the number")
	void get() {
		RfidReader saved = rfid.reader("Caisse 1", "10.0.0.1", 9090);
		assertEquals(saved, rfid.readerService.get(saved.getId()));
		RfidException e = assertThrows(RfidException.class, () -> rfid.readerService.get(99L));
		assertEquals(RfidException.Reason.NOT_FOUND, e.getReason());
		assertEquals("No RFID reader number 99.", e.getMessage());
	}

	private static String repeat(char c, int n) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < n; i++) {
			text.append(c);
		}
		return text.toString();
	}
}
