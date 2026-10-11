package com.digithink.zsretail.store.rfid.reader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RFID step 4: the tags of one read. A reader reports each tag many times, from its own thread (the SDK's callback):
 * {@link #seen} keeps each EPC once with its best signal and its count, safely from any thread. Trimmed, upper case; an
 * empty EPC is ignored.
 */
public final class RfidTagCollector {

	/** EPC to { times seen, best signal }. */
	private final Map<String, double[]> tags = new ConcurrentHashMap<>();

	/** One report of a tag. */
	public void seen(String epc, double rssi) {
		if (epc == null || epc.trim().isEmpty()) {
			return;
		}
		tags.merge(epc.trim().toUpperCase(), new double[] { 1, rssi },
				(a, b) -> new double[] { a[0] + 1, Math.max(a[1], b[1]) });
	}

	/** Every tag once, by EPC. */
	public List<RfidTag> tags() {
		List<RfidTag> list = new ArrayList<>();
		tags.forEach((epc, value) -> list.add(new RfidTag(epc, value[1], (int) value[0])));
		list.sort(Comparator.comparing(RfidTag::getEpc));
		return list;
	}
}
