package com.digithink.zsretail.store.rfid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.store.rfid.RfidException;

/** RFID step 3: one form for the address of a POS machine, an IPv4 address for a reader, never a name lookup. */
class RfidAddressesTest {

	@Test
	@DisplayName("IPv4 of a reader: four numbers 0 to 255, no leading zero, nothing else")
	void ipv4() {
		for (String ok : Arrays.asList("192.168.100.200", "0.0.0.0", "255.255.255.255", "10.0.0.1")) {
			assertTrue(RfidAddresses.isIpv4(ok), ok);
		}
		for (String bad : Arrays.asList(null, "", "192.168.1", "192.168.1.256", "01.2.3.4", "1.2.3.4.5", " 1.2.3.4",
				"localhost", "::1", "192.168.1.5:9090")) {
			assertFalse(RfidAddresses.isIpv4(bad), String.valueOf(bad));
		}
	}

	@Test
	@DisplayName("A plain IPv4 address stays as it is, trimmed")
	void plainIpv4() {
		assertEquals("192.168.1.5", RfidAddresses.normalise("192.168.1.5"));
		assertEquals("192.168.1.5", RfidAddresses.normalise("  192.168.1.5 "));
	}

	@Test
	@DisplayName("Every loopback form is 127.0.0.1: IPv4, ::1, Tomcat's 0:0:0:0:0:0:0:1, in brackets, with a zone")
	void loopback() {
		for (String local : Arrays.asList("127.0.0.1", "127.1.2.3", "::1", "0:0:0:0:0:0:0:1", "[::1]", "::1%1")) {
			assertEquals("127.0.0.1", RfidAddresses.normalise(local), local);
		}
	}

	@Test
	@DisplayName("IPv4 written as IPv6 is the IPv4 address; IPv6 in Java's full form, lower case, without its zone")
	void ipv6() {
		assertEquals("192.168.1.5", RfidAddresses.normalise("::ffff:192.168.1.5"));
		assertEquals("fe80:0:0:0:0:0:0:1", RfidAddresses.normalise("fe80::1%12"));
		assertEquals("fe80:0:0:0:0:0:0:1", RfidAddresses.normalise("FE80::1"));
		assertEquals("fe80:0:0:0:0:0:0:1", RfidAddresses.normalise("fe80:0:0:0:0:0:0:1%eth0"));
		assertEquals("2001:db8:0:0:0:0:0:1", RfidAddresses.normalise("2001:db8::1"));
	}

	@Test
	@DisplayName("Anything else is refused with a message naming it, a name included (never looked up)")
	void refused() {
		for (String bad : Arrays.asList(null, "", "  ", "localhost", "pos-1", "192.168.1", "192.168.1.256", "01.2.3.4",
				"1::2::3", "abc:def", "192.168.1.5:9090")) {
			RfidException e = assertThrows(RfidException.class, () -> RfidAddresses.normalise(bad), String.valueOf(bad));
			assertEquals(RfidException.Reason.INVALID, e.getReason());
			assertEquals("'" + (bad == null ? "" : bad.trim()) + "' is not an IP address: write an IPv4 address such as"
					+ " 192.168.1.20 (or an IPv6 address).", e.getMessage());
		}
	}
}
