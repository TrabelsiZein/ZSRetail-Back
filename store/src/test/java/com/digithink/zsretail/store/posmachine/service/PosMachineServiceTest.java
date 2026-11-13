package com.digithink.zsretail.store.posmachine.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.digithink.zsretail.store.posmachine.PosMachineException;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.support.InMemoryRfid;

/**
 * The rules of a POS machine (PosMachineService, RFID step 3; every store since 3.2.0) over the in-memory tables: with
 * RFID (the reader taken, checked) and without (the reader ignored, the one saved kept).
 */
class PosMachineServiceTest {

	private final InMemoryRfid rfid = new InMemoryRfid();

	private static PosMachine machine(String name, String ip, RfidReader reader) {
		PosMachine machine = new PosMachine();
		machine.setName(name);
		machine.setIpAddress(ip);
		machine.setReader(reader);
		return machine;
	}

	private static void assertRefused(String message, Executable save) {
		PosMachineException e = assertThrows(PosMachineException.class, save);
		assertEquals(PosMachineException.Reason.INVALID, e.getReason());
		assertEquals(message, e.getMessage());
	}

	@Test
	@DisplayName("Saved with its name trimmed, its address normalised, its reader loaded by id, active")
	void saved() throws Exception {
		RfidReader reader = rfid.reader("Caisse 1", "192.168.100.200", 9090);
		RfidReader byId = new RfidReader();
		byId.setId(reader.getId());
		PosMachine in = machine(" PC caisse 1 ", "::ffff:192.168.1.11", byId);
		in.setActive(null);
		PosMachine saved = rfid.machineService.save(in);
		assertEquals("PC caisse 1", saved.getName());
		assertEquals("192.168.1.11", saved.getIpAddress());
		assertSame(reader, saved.getReader(), "the reader of the table, not the one sent");
		assertTrue(saved.getActive());
		assertEquals("tester", saved.getCreatedBy());
	}

	@Test
	@DisplayName("Without a reader: saved, it cannot read yet; a reader id that does not exist is refused")
	void reader() throws Exception {
		assertNull(rfid.machine("PC bureau", "192.168.1.20", null).getReader());
		RfidReader missing = new RfidReader();
		missing.setId(42L);
		assertRefused("No RFID reader number 42.",
				() -> rfid.machineService.save(machine("PC caisse 1", "192.168.1.11", missing)));
		assertNull(rfid.machineService.save(machine("PC caisse 2", "192.168.1.12", new RfidReader())).getReader(),
				"a reader without an id: none");
	}

	@Test
	@DisplayName("A name is required, at most 100 characters, unique in any case")
	void name() {
		assertRefused("The POS machine needs a name.", () -> rfid.machineService.save(machine(" ", "192.168.1.11", null)));
		assertRefused("The POS machine's name has at most 100 characters.", () -> rfid.machineService
				.save(machine(new String(new char[101]).replace('\0', 'x'), "192.168.1.11", null)));
		rfid.machine("PC caisse 1", "192.168.1.11", null);
		assertRefused("A POS machine is already named 'PC caisse 1'.",
				() -> rfid.machineService.save(machine("pc CAISSE 1", "192.168.1.12", null)));
	}

	@Test
	@DisplayName("The address: an IP address, never a name; unique once normalised (::1 and 127.0.0.1 are one machine)")
	void address() throws Exception {
		assertRefused("'caisse-1' is not an IP address: write an IPv4 address such as 192.168.1.20 (or an IPv6 address).",
				() -> rfid.machineService.save(machine("PC caisse 1", "caisse-1", null)));
		assertRefused("'' is not an IP address: write an IPv4 address such as 192.168.1.20 (or an IPv6 address).",
				() -> rfid.machineService.save(machine("PC caisse 1", null, null)));

		PosMachine server = rfid.machine("PC du serveur", "0:0:0:0:0:0:0:1", null);
		assertEquals("127.0.0.1", server.getIpAddress());
		assertRefused("The POS machine 'PC du serveur' already has the address 127.0.0.1.",
				() -> rfid.machineService.save(machine("Autre", "127.0.0.1", null)));
		server.setName("Server PC");
		assertDoesNotThrow(() -> rfid.machineService.save(server), "the same machine saved again");
	}

	@Test
	@DisplayName("findByRequestAddress: the machine of a request's address, in any of its forms; empty for another")
	void byRequestAddress() {
		PosMachine local = rfid.machine("PC du serveur", "127.0.0.1", null);
		PosMachine lan = rfid.machine("PC caisse 1", "192.168.1.11", null);
		assertSame(local, rfid.machineService.findByRequestAddress("0:0:0:0:0:0:0:1").get());
		assertSame(local, rfid.machineService.findByRequestAddress("::1").get());
		assertSame(lan, rfid.machineService.findByRequestAddress("::ffff:192.168.1.11").get());
		assertFalse(rfid.machineService.findByRequestAddress("192.168.1.99").isPresent());
		assertSame(lan, rfid.machineServiceWithoutRfid.findByRequestAddress("192.168.1.11").get(), "without RFID too");
	}

	@Test
	@DisplayName("Without RFID: the same rules; a reader sent is ignored, a new machine has none, a machine saved again keeps"
			+ " the one it has (a store that turned RFID off)")
	void withoutRfid() throws Exception {
		PosMachineService service = rfid.machineServiceWithoutRfid;
		RfidReader any = new RfidReader();
		any.setId(42L);
		PosMachine created = service.save(machine(" PC caisse 1 ", "::ffff:192.168.1.11", any));
		assertEquals("PC caisse 1", created.getName());
		assertEquals("192.168.1.11", created.getIpAddress());
		assertNull(created.getReader(), "never checked, never taken");
		assertTrue(created.getActive());

		// A machine linked while RFID was on, saved again with RFID off (the page sends no reader)
		RfidReader desk = rfid.reader("Caisse 2", "192.168.100.201", 9090);
		PosMachine linked = rfid.machine("PC caisse 2", "192.168.1.12", desk);
		PosMachine renamed = machine("PC caisse 2 bis", "192.168.1.12", null);
		renamed.setId(linked.getId());
		assertSame(desk, service.save(renamed).getReader(), "its reader kept");

		assertRefused("A POS machine is already named 'PC caisse 1'.",
				() -> service.save(machine("pc caisse 1", "192.168.1.13", null)));
		assertRefused("'caisse-3' is not an IP address: write an IPv4 address such as 192.168.1.20 (or an IPv6 address).",
				() -> service.save(machine("PC caisse 3", "caisse-3", null)));
	}
}
