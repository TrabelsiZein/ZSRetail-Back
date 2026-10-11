package com.digithink.zsretail.store.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.rfid.reader.RfidReaderDevice;
import com.digithink.zsretail.store.rfid.reader.RfidTag;
import com.digithink.zsretail.store.rfid.reader.RfidTagCollector;

/**
 * Test support, RFID step 4: readers without hardware. Each address (ip:port) has the tag reports it gives during a
 * read, many per tag as a real reader gives them, collected like the real one ({@link RfidTagCollector}). An address
 * may be unreachable or answer with an error; a read may be held open ({@link #holdNextRead}) to test a busy reader.
 * Every call is recorded.
 */
public final class FakeRfidReader implements RfidReaderDevice {

	/** ip:port to its tag reports, each { EPC, signal }. */
	private final Map<String, List<Object[]>> reports = new LinkedHashMap<>();
	private final Set<String> unreachable = new HashSet<>();
	private final Set<String> failing = new HashSet<>();
	/** Every call: "connect ip:port" or "read ip:port power P for N ms". */
	public final List<String> calls = Collections.synchronizedList(new ArrayList<>());

	private volatile CountDownLatch holdEntered;
	private volatile CountDownLatch holdRelease;

	private static String key(RfidReader reader) {
		return reader.getIpAddress() + ":" + reader.getPort();
	}

	/** The reader at this address reports this tag once more, with this signal, during each read. */
	public FakeRfidReader report(String address, String epc, double rssi) {
		reports.computeIfAbsent(address, a -> new ArrayList<>()).add(new Object[] { epc, rssi });
		return this;
	}

	/** Nothing answers at this address. */
	public FakeRfidReader unreachable(String address) {
		unreachable.add(address);
		return this;
	}

	/** The reader at this address answers with an error. */
	public FakeRfidReader failing(String address) {
		failing.add(address);
		return this;
	}

	/**
	 * The next read waits, inside the reader, until the returned latch is released; {@link #awaitHeldRead} waits until
	 * it is there.
	 */
	public CountDownLatch holdNextRead() {
		holdEntered = new CountDownLatch(1);
		holdRelease = new CountDownLatch(1);
		return holdRelease;
	}

	/** Waits (at most 5 s) until the held read is inside the reader. */
	public void awaitHeldRead() throws InterruptedException {
		if (!holdEntered.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("the held read never started");
		}
	}

	@Override
	public String testConnection(RfidReader reader) {
		calls.add("connect " + key(reader));
		check(reader);
		return "FAKE reader at " + key(reader);
	}

	@Override
	public List<RfidTag> read(RfidReader reader, int millis) {
		calls.add("read " + key(reader) + " power " + reader.getPower() + " for " + millis + " ms");
		check(reader);
		CountDownLatch release = holdRelease;
		if (release != null) {
			holdRelease = null;
			holdEntered.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		RfidTagCollector collector = new RfidTagCollector();
		for (Object[] report : reports.getOrDefault(key(reader), Collections.emptyList())) {
			collector.seen((String) report[0], (Double) report[1]);
		}
		return collector.tags();
	}

	private void check(RfidReader reader) {
		if (unreachable.contains(key(reader))) {
			throw new RfidException(Reason.UNREACHABLE, "The RFID reader '" + reader.getName() + "' does not answer at "
					+ key(reader) + ".");
		}
		if (failing.contains(key(reader))) {
			throw new RfidException(Reason.READER_ERROR, "The RFID reader '" + reader.getName() + "' answered with an"
					+ " error.");
		}
	}
}
