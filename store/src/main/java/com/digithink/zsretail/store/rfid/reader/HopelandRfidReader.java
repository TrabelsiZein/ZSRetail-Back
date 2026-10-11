package com.digithink.zsretail.store.rfid.reader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.rfidread.RFIDReader;
import com.rfidread.Enumeration.eReadType;
import com.rfidread.Interface.IAsynchronousMessage;
import com.rfidread.Models.GPI_Model;
import com.rfidread.Models.Tag_Model;

/**
 * RFID step 5: the Hopeland readers (HZ100, TCP/IP) through the vendor's SDK (libs/hopeland/README.md). The only class
 * that uses the SDK (RfidSdkConfinementGuardTest).
 * <ul>
 * <li>Before the SDK, a plain TCP connection to ip:port, {@value #TCP_CHECK_MS} ms at most: a reader that does not answer
 * is UNREACHABLE at once, without the SDK's own 5 s wait and the stack trace it prints.</li>
 * <li>A connection for each request, closed in a finally. The SDK keeps its connections in one static map that is not
 * thread-safe: every connect and close of this class holds one lock for the whole SDK ({@link #SDK}); the reads
 * themselves run in parallel, one per reader (RfidReadService).</li>
 * <li>A read: stop whatever the reader was doing, set the power of antenna 1 only when it differs (a change may be
 * written to the reader's memory), read for the time, stop, wait {@value #LAST_TAGS_MS} ms for the last reports,
 * close. The SDK reports each tag many times from its own thread: {@link RfidTagCollector} keeps it once.</li>
 * <li>At shutdown, every connection still open is closed.</li>
 * </ul>
 * Measured on the dev desk reader (RFID step 0): connect about 10 ms, a read of 1500 ms about 1850 ms in all.
 */
@Component
@ConditionalOnInstallation(Installation.RFID)
public class HopelandRfidReader implements RfidReaderDevice {

	private static final Logger log = LoggerFactory.getLogger(HopelandRfidReader.class);

	/** The plain TCP check before the SDK. */
	static final int TCP_CHECK_MS = 2000;
	/** After the stop, the last reports of the reader still arrive. */
	static final int LAST_TAGS_MS = 300;
	/** The SDK's antenna mask of {@link RfidReaderDevice#ANTENNA}. */
	private static final int ANTENNA_MASK = 1;

	/** One lock for every connect and close of the SDK (its static map of connections). */
	private static final Object SDK = new Object();

	/** The connections open now (ip:port), closed at shutdown. */
	private final Set<String> open = ConcurrentHashMap.newKeySet();

	@Override
	public String testConnection(RfidReader reader) {
		String connection = reachable(reader);
		connect(reader, connection, new Reports(null));
		try {
			RFIDReader._Config.Stop(connection);
			String information = RFIDReader._Config.GetReaderInformation(connection);
			return information == null || information.trim().isEmpty() ? "(no information)" : information.trim();
		} finally {
			close(connection);
		}
	}

	@Override
	public List<RfidTag> read(RfidReader reader, int millis) {
		String connection = reachable(reader);
		RfidTagCollector collector = new RfidTagCollector();
		long start = System.nanoTime();
		connect(reader, connection, new Reports(collector));
		try {
			RFIDReader._Config.Stop(connection);
			applyPower(reader, connection);
			int started = RFIDReader._Tag6C.GetEPC(connection, ANTENNA_MASK, eReadType.Inventory);
			if (started != 0) {
				throw new RfidException(Reason.READER_ERROR, "The RFID reader '" + reader.getName() + "' did not start"
						+ " reading (code " + started + ").");
			}
			pause(reader, millis);
			RFIDReader._Config.Stop(connection);
			pause(reader, LAST_TAGS_MS);
		} finally {
			close(connection);
		}
		List<RfidTag> tags = collector.tags();
		log.info("RFID read: reader '{}' ({}), {} ms, {} tags in {} ms", reader.getName(), connection, millis, tags.size(),
				(System.nanoTime() - start) / 1_000_000);
		return tags;
	}

	/** Closes every connection still open (the application stops). */
	@PreDestroy
	public void closeAll() {
		for (String connection : new ArrayList<>(open)) {
			close(connection);
		}
	}

	/** The connections open now (for the tests). */
	Set<String> openConnections() {
		return open;
	}

	/**
	 * The plain TCP check: ip:port answers within {@value #TCP_CHECK_MS} ms, else UNREACHABLE. The SDK's connection name
	 * (ip:port).
	 */
	static String reachable(RfidReader reader) {
		String connection = reader.getIpAddress() + ":" + reader.getPort();
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(reader.getIpAddress(), reader.getPort()), TCP_CHECK_MS);
		} catch (IOException | IllegalArgumentException e) {
			throw new RfidException(Reason.UNREACHABLE, "The RFID reader '" + reader.getName() + "' does not answer at "
					+ connection + " (no connection within " + TCP_CHECK_MS / 1000 + " s): is it on, and on this"
					+ " network?");
		}
		return connection;
	}

	private void connect(RfidReader reader, String connection, Reports reports) {
		boolean connected;
		synchronized (SDK) {
			connected = RFIDReader.CreateTcpConn(connection, reports);
			if (connected) {
				open.add(connection);
			}
		}
		if (!connected) {
			throw new RfidException(Reason.UNREACHABLE, "The RFID reader '" + reader.getName() + "' answers at "
					+ connection + " but refuses the connection: another program (the vendor's tool?) may be connected"
					+ " to it.");
		}
	}

	private void close(String connection) {
		synchronized (SDK) {
			try {
				RFIDReader.CloseConn(connection);
			} catch (RuntimeException e) {
				log.warn("RFID: closing the connection to {} failed: {}", connection, e.toString());
			} finally {
				open.remove(connection);
			}
		}
	}

	/** Antenna 1's power set only when it differs from the reader's power (or cannot be read). */
	private static void applyPower(RfidReader reader, String connection) {
		HashMap<Integer, Integer> current = RFIDReader._Config.GetANTPowerParam(connection);
		Integer now = current == null ? null : current.get(ANTENNA);
		if (reader.getPower().equals(now)) {
			return;
		}
		HashMap<Integer, Integer> power = new HashMap<>();
		power.put(ANTENNA, reader.getPower());
		int result = RFIDReader._Config.SetANTPowerParam(connection, power);
		if (result != 0) {
			throw new RfidException(Reason.READER_ERROR, "The RFID reader '" + reader.getName() + "' refused the power "
					+ reader.getPower() + " (code " + result + ").");
		}
		log.info("RFID: reader '{}' power {} (was {})", reader.getName(), reader.getPower(), now);
	}

	private static void pause(RfidReader reader, int millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RfidException(Reason.READER_ERROR, "The read of the RFID reader '" + reader.getName()
					+ "' was interrupted.", e);
		}
	}

	/** The SDK's reports of one connection: the tags go to the collector (none for a test connection). */
	private static final class Reports implements IAsynchronousMessage {

		private final RfidTagCollector collector;

		Reports(RfidTagCollector collector) {
			this.collector = collector;
		}

		@Override
		public void OutPutTags(Tag_Model tag) {
			if (collector != null && tag != null) {
				collector.seen(tag._EPC, tag._RSSI_dB);
			}
		}

		@Override
		public void WriteLog(String message) {
			log.debug("RFID SDK: {}", message);
		}

		@Override
		public void WriteDebugMsg(String message) {
			log.trace("RFID SDK: {}", message);
		}

		@Override
		public void OutPutTagsOver() {
		}

		@Override
		public void PortConnecting(String connection) {
		}

		@Override
		public void PortClosing(String connection) {
		}

		@Override
		public void GPIControlMsg(GPI_Model gpi) {
		}

		@Override
		public void OutPutScanData(byte[] data) {
		}
	}
}
