package com.digithink.zsretail.store.rfid.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;
import com.digithink.zsretail.store.rfid.dto.RfidConnectionResult;
import com.digithink.zsretail.store.rfid.dto.RfidMachineStatus;
import com.digithink.zsretail.store.rfid.dto.RfidReadResult;
import com.digithink.zsretail.store.rfid.model.PosMachine;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.rfid.reader.RfidReaderDevice;
import com.digithink.zsretail.store.rfid.reader.RfidTag;

/**
 * RFID step 4: the reads, one request at a time per reader. A second request for a reader that is busy answers at once
 * BUSY (never waits); two readers read at the same time.
 * <ul>
 * <li>{@link #testConnection}: the reader answers (allowed on an inactive reader, to check it before turning it on).</li>
 * <li>{@link #testRead}: a read of an active reader, for its reading time (the RFID readers page).</li>
 * <li>{@link #readForRequest}: a read with the reader of the POS machine the request comes from, found by the request's
 * address; refused with the address seen when no machine has it, and when the machine or its reader cannot read.</li>
 * <li>{@link #machineStatus}: what the back end sees of the request's machine, without reading (RFID step 6).</li>
 * </ul>
 */
@Service
@ConditionalOnInstallation(Installation.RFID)
public class RfidReadService {

	private final RfidReaderService readers;
	private final PosMachineService machines;
	private final RfidReaderDevice device;

	/** One lock per reader id: held during a request to that reader. */
	private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

	public RfidReadService(RfidReaderService readers, PosMachineService machines, RfidReaderDevice device) {
		this.readers = readers;
		this.machines = machines;
		this.device = device;
	}

	/** Test connection: the reader of this id answers with its information. */
	public RfidConnectionResult testConnection(Long readerId) {
		RfidReader reader = readers.get(readerId);
		long start = System.nanoTime();
		String information = alone(reader, () -> device.testConnection(reader));
		return new RfidConnectionResult(reader.getName(), reader.getIpAddress() + ":" + reader.getPort(), information,
				millisSince(start));
	}

	/** Test read: the tags the reader of this id sees now, for its reading time. */
	public RfidReadResult testRead(Long readerId) {
		RfidReader reader = readers.get(readerId);
		ready(reader, null);
		return read(reader, null);
	}

	/** A read for the POS machine whose request comes from this address (request.getRemoteAddr()). */
	public RfidReadResult readForRequest(String remoteAddress) {
		PosMachine machine = readyMachine(RfidAddresses.normalise(remoteAddress));
		return read(machine.getReader(), machine);
	}

	/**
	 * RFID step 6: the machine a request comes from, as the back end sees it (the address I see for you): its address,
	 * the POS machine recorded there and its reader, and whether it can read now (else the refusal a read would give).
	 * Never calls the reader.
	 */
	public RfidMachineStatus machineStatus(String remoteAddress) {
		String address = RfidAddresses.normalise(remoteAddress);
		PosMachine machine = machines.findByRequestAddress(address).orElse(null);
		String refusal = null;
		try {
			readyMachine(address);
		} catch (RfidException e) {
			refusal = e.getMessage();
		}
		return new RfidMachineStatus(address, machine == null ? null : machine.getId(),
				machine == null ? null : machine.getName(),
				machine == null || machine.getReader() == null ? null : machine.getReader().getName(), refusal == null,
				refusal);
	}

	/**
	 * The POS machine at this address (normalised), active, with an active reader. NOT_FOUND naming the address when no
	 * machine has it, NOT_READY when the machine or its reader cannot read.
	 */
	private PosMachine readyMachine(String address) {
		PosMachine machine = machines.findByRequestAddress(address).orElseThrow(() -> new RfidException(Reason.NOT_FOUND,
				"No POS machine has the address " + address + ": add this machine on the RFID POS machines page"
						+ " (it needs a fixed address)."));
		if (!Boolean.TRUE.equals(machine.getActive())) {
			throw new RfidException(Reason.NOT_READY, "The POS machine '" + machine.getName() + "' is inactive.");
		}
		RfidReader reader = machine.getReader();
		if (reader == null) {
			throw new RfidException(Reason.NOT_READY, "The POS machine '" + machine.getName() + "' has no RFID reader.");
		}
		ready(reader, machine);
		return machine;
	}

	/** An inactive reader is never read. */
	private static void ready(RfidReader reader, PosMachine machine) {
		if (!Boolean.TRUE.equals(reader.getActive())) {
			throw new RfidException(Reason.NOT_READY, "The RFID reader '" + reader.getName() + "'"
					+ (machine == null ? "" : " of the POS machine '" + machine.getName() + "'") + " is inactive.");
		}
	}

	private RfidReadResult read(RfidReader reader, PosMachine machine) {
		long start = System.nanoTime();
		int millis = reader.getReadTimeMs();
		List<RfidTag> tags = alone(reader, () -> device.read(reader, millis));
		return new RfidReadResult(machine == null ? null : machine.getName(), reader.getName(), millis,
				millisSince(start), tags);
	}

	/** The work, holding the reader's lock; BUSY at once when another request holds it. */
	private <T> T alone(RfidReader reader, Supplier<T> work) {
		ReentrantLock lock = locks.computeIfAbsent(reader.getId(), id -> new ReentrantLock());
		if (!lock.tryLock()) {
			throw new RfidException(Reason.BUSY, "The RFID reader '" + reader.getName() + "' is busy with another"
					+ " read: try again in a moment.");
		}
		try {
			return work.get();
		} finally {
			lock.unlock();
		}
	}

	private static long millisSince(long start) {
		return (System.nanoTime() - start) / 1_000_000;
	}
}
