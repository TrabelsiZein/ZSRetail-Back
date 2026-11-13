package com.digithink.zsretail.store.posmachine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.store.posmachine.PosMachineException;

/**
 * 3.2.0: the one form of a POS machine's address, without RFID (RfidAddressesTest covers every form through RFID's
 * wrapper, which gives the same answers with RFID's refusal).
 */
class PosMachineAddressesTest {

	@Test
	@DisplayName("Loopback, IPv4 as IPv6, IPv6 with a zone: one form each")
	void forms() {
		assertEquals("127.0.0.1", PosMachineAddresses.normalise("0:0:0:0:0:0:0:1"));
		assertEquals("192.168.1.5", PosMachineAddresses.normalise(" ::ffff:192.168.1.5 "));
		assertEquals("fe80:0:0:0:0:0:0:1", PosMachineAddresses.normalise("fe80::1%12"));
	}

	@Test
	@DisplayName("Anything else: PosMachineException INVALID naming it, a name included (never looked up)")
	void refused() {
		for (String bad : Arrays.asList(null, "localhost", "192.168.1.256", "192.168.1.5:9090")) {
			PosMachineException e = assertThrows(PosMachineException.class, () -> PosMachineAddresses.normalise(bad));
			assertEquals(PosMachineException.Reason.INVALID, e.getReason());
			assertEquals("'" + (bad == null ? "" : bad) + "' is not an IP address: write an IPv4 address such as"
					+ " 192.168.1.20 (or an IPv6 address).", e.getMessage());
		}
	}
}
