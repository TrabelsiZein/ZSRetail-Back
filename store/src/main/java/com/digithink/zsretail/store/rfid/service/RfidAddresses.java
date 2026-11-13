package com.digithink.zsretail.store.rfid.service;

import com.digithink.zsretail.store.posmachine.PosMachineException;
import com.digithink.zsretail.store.posmachine.service.PosMachineAddresses;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;

/**
 * RFID step 3: the addresses of the RFID pages, without any name lookup (a name is refused, never resolved). Since
 * 3.2.0 the rules live in {@link PosMachineAddresses} (a POS machine exists on every store); here the RFID code gets
 * them with its own refusal, {@link RfidException} INVALID with the same message.
 * <ul>
 * <li>A reader: an IPv4 address ({@link #isIpv4}).</li>
 * <li>A POS machine: the address of its requests, in one form ({@link #normalise}, {@link PosMachineAddresses#normalise}).</li>
 * </ul>
 */
public final class RfidAddresses {

	/** The one form of every loopback address: the browser runs on the PC of the back end. */
	public static final String LOOPBACK = PosMachineAddresses.LOOPBACK;

	private RfidAddresses() {
	}

	/** True for a dotted IPv4 address, four numbers 0 to 255 without leading zeros (192.168.100.200). */
	public static boolean isIpv4(String address) {
		return PosMachineAddresses.isIpv4(address);
	}

	/** {@link PosMachineAddresses#normalise}; a refusal is an {@link RfidException} INVALID with the same message. */
	public static String normalise(String address) {
		try {
			return PosMachineAddresses.normalise(address);
		} catch (PosMachineException e) {
			throw new RfidException(Reason.INVALID, e.getMessage());
		}
	}
}
