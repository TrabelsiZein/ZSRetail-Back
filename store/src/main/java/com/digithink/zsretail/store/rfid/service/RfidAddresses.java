package com.digithink.zsretail.store.rfid.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;

/**
 * RFID step 3: the addresses of the RFID pages, without any name lookup (a name is refused, never resolved).
 * <ul>
 * <li>A reader: an IPv4 address ({@link #isIpv4}).</li>
 * <li>A POS machine: the address of its requests as Tomcat gives it (request.getRemoteAddr()), in one form
 * ({@link #normalise}), so the same machine always gives the same text: every loopback (127.x.x.x, ::1, Tomcat's
 * 0:0:0:0:0:0:0:1) is 127.0.0.1, an IPv4 address written as IPv6 (::ffff:192.168.1.5) is the IPv4 address, the zone of an
 * IPv6 address (fe80::1%12) is dropped and IPv6 is written in Java's full form (fe80:0:0:0:0:0:0:1, as Tomcat writes it).</li>
 * </ul>
 */
public final class RfidAddresses {

	/** The one form of every loopback address: the browser runs on the PC of the back end. */
	public static final String LOOPBACK = "127.0.0.1";

	private static final Pattern IPV4 = Pattern
			.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]\\d|\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]\\d|\\d)){3}");

	private RfidAddresses() {
	}

	/** True for a dotted IPv4 address, four numbers 0 to 255 without leading zeros (192.168.100.200). */
	public static boolean isIpv4(String address) {
		return address != null && IPV4.matcher(address).matches();
	}

	/**
	 * The one form of a POS machine's address (see the class comment). Throws {@link RfidException} INVALID on anything
	 * but an IPv4 or IPv6 address, a name included.
	 */
	public static String normalise(String address) {
		String text = address == null ? "" : address.trim();
		if (text.startsWith("[") && text.endsWith("]")) {
			text = text.substring(1, text.length() - 1);
		}
		int zone = text.indexOf('%');
		if (zone >= 0 && text.contains(":")) {
			text = text.substring(0, zone);
		}
		if (!isIpv4(text) && !text.contains(":")) {
			throw invalid(address);
		}
		InetAddress parsed;
		try {
			// A literal: an IPv4 one checked above, a text with ':' is parsed as IPv6, never looked up
			parsed = InetAddress.getByName(text);
		} catch (UnknownHostException | SecurityException e) {
			throw invalid(address);
		}
		return parsed.isLoopbackAddress() ? LOOPBACK : parsed.getHostAddress();
	}

	private static RfidException invalid(String address) {
		return new RfidException(Reason.INVALID, "'" + (address == null ? "" : address.trim())
				+ "' is not an IP address: write an IPv4 address such as 192.168.1.20 (or an IPv6 address).");
	}
}
