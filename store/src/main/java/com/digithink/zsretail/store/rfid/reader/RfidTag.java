package com.digithink.zsretail.store.rfid.reader;

/**
 * RFID step 4: one tag seen during a read, once: its EPC, the best signal it was seen with (RSSI in dB, the higher the
 * nearer) and how many times the reader reported it. RFID weak tags: weak, when its best signal is below the reader's
 * minimum signal (set by RfidReadService, {@link #withWeak}).
 */
public final class RfidTag {

	private final String epc;
	private final double bestRssi;
	private final int seen;
	private final boolean weak;

	public RfidTag(String epc, double bestRssi, int seen) {
		this(epc, bestRssi, seen, false);
	}

	public RfidTag(String epc, double bestRssi, int seen, boolean weak) {
		this.epc = epc;
		this.bestRssi = bestRssi;
		this.seen = seen;
		this.weak = weak;
	}

	/** The same tag, weak or not. */
	public RfidTag withWeak(boolean weak) {
		return new RfidTag(epc, bestRssi, seen, weak);
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

	public boolean isWeak() {
		return weak;
	}

	@Override
	public String toString() {
		return epc + " (" + bestRssi + " dB, " + seen + "x" + (weak ? ", weak" : "") + ")";
	}
}
