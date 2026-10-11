package com.digithink.zsretail.store.rfid.reader;

/**
 * RFID step 4: one tag seen during a read, once: its EPC, the best signal it was seen with (RSSI in dB, the higher the
 * nearer) and how many times the reader reported it.
 */
public final class RfidTag {

	private final String epc;
	private final double bestRssi;
	private final int seen;

	public RfidTag(String epc, double bestRssi, int seen) {
		this.epc = epc;
		this.bestRssi = bestRssi;
		this.seen = seen;
	}

	public String getEpc() {
		return epc;
	}

	public double getBestRssi() {
		return bestRssi;
	}

	public int getSeen() {
		return seen;
	}

	@Override
	public String toString() {
		return epc + " (" + bestRssi + " dB, " + seen + "x)";
	}
}
