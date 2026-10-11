package com.digithink.zsretail.store.rfid.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;
import com.digithink.zsretail.store.rfid.model.PosMachine;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.rfid.repository.PosMachineRepository;
import com.digithink.zsretail.store.rfid.repository.RfidReaderRepository;

/**
 * RFID step 3: the store's RFID readers and their rules (docs/modules/rfid.md). A reader is saved only with a name (at
 * most 100 characters, unique in any case), an IPv4 address and a port 1 to 65535 (the pair unique), a power
 * {@value RfidReader#MIN_POWER} to {@value RfidReader#MAX_POWER} (default {@value RfidReader#DEFAULT_POWER}) and a
 * reading time {@value RfidReader#MIN_READ_TIME_MS} to {@value RfidReader#MAX_READ_TIME_MS} ms (default
 * {@value RfidReader#DEFAULT_READ_TIME_MS}), a minimum signal {@value RfidReader#MIN_MIN_SIGNAL} to
 * {@value RfidReader#MAX_MIN_SIGNAL} dB (default {@value RfidReader#DEFAULT_MIN_SIGNAL}); a reader still linked to a POS machine is not deleted. Every refusal is an
 * {@link RfidException} INVALID with its message.
 */
@Service
@ConditionalOnInstallation(Installation.RFID)
public class RfidReaderService extends _BaseService<RfidReader, Long> {

	static final int MAX_NAME_LENGTH = 100;

	private final RfidReaderRepository readers;
	private final PosMachineRepository machines;

	public RfidReaderService(RfidReaderRepository readers, PosMachineRepository machines) {
		this.readers = readers;
		this.machines = machines;
	}

	@Override
	protected _BaseRepository<RfidReader, Long> getRepository() {
		return readers;
	}

	/** The reader of this id; NOT_FOUND when there is none. */
	public RfidReader get(Long id) {
		return readers.findById(id)
				.orElseThrow(() -> new RfidException(Reason.NOT_FOUND, "No RFID reader number " + id + "."));
	}

	@Override
	@Transactional
	public RfidReader save(RfidReader reader) throws Exception {
		check(reader);
		return super.save(reader);
	}

	/** The rules of a reader about to be saved; trims its texts and gives the defaults of what is empty. */
	void check(RfidReader reader) {
		String name = PosMachineService.requiredName(reader.getName(), "reader");
		reader.setName(name);
		readers.findFirstByNameIgnoreCase(name).filter(other -> !other.getId().equals(reader.getId()))
				.ifPresent(other -> {
					throw new RfidException(Reason.INVALID, "A reader is already named '" + other.getName() + "'.");
				});

		String address = reader.getIpAddress() == null ? "" : reader.getIpAddress().trim();
		if (!RfidAddresses.isIpv4(address)) {
			throw new RfidException(Reason.INVALID, "The reader's address must be an IPv4 address such as 192.168.1.50,"
					+ " not '" + address + "'.");
		}
		reader.setIpAddress(address);
		Integer port = reader.getPort();
		if (port == null || port < 1 || port > 65535) {
			throw new RfidException(Reason.INVALID, "The reader's port must be a number from 1 to 65535"
					+ (port == null ? "." : ", not " + port + "."));
		}
		readers.findFirstByIpAddressAndPort(address, port).filter(other -> !other.getId().equals(reader.getId()))
				.ifPresent(other -> {
					throw new RfidException(Reason.INVALID, "The reader '" + other.getName() + "' already has the address "
							+ address + ":" + port + ".");
				});

		if (reader.getPower() == null) {
			reader.setPower(RfidReader.DEFAULT_POWER);
		}
		if (reader.getPower() < RfidReader.MIN_POWER || reader.getPower() > RfidReader.MAX_POWER) {
			throw new RfidException(Reason.INVALID, "The reading power must be from " + RfidReader.MIN_POWER + " to "
					+ RfidReader.MAX_POWER + ", not " + reader.getPower() + ".");
		}
		if (reader.getReadTimeMs() == null) {
			reader.setReadTimeMs(RfidReader.DEFAULT_READ_TIME_MS);
		}
		if (reader.getReadTimeMs() < RfidReader.MIN_READ_TIME_MS || reader.getReadTimeMs() > RfidReader.MAX_READ_TIME_MS) {
			throw new RfidException(Reason.INVALID, "The reading time must be from " + RfidReader.MIN_READ_TIME_MS
					+ " to " + RfidReader.MAX_READ_TIME_MS + " ms, not " + reader.getReadTimeMs() + ".");
		}
		if (reader.getMinSignal() == null) {
			reader.setMinSignal(RfidReader.DEFAULT_MIN_SIGNAL);
		}
		if (reader.getMinSignal() < RfidReader.MIN_MIN_SIGNAL || reader.getMinSignal() > RfidReader.MAX_MIN_SIGNAL) {
			throw new RfidException(Reason.INVALID, "The minimum signal must be from " + RfidReader.MIN_MIN_SIGNAL + " to "
					+ RfidReader.MAX_MIN_SIGNAL + " dB, not " + reader.getMinSignal() + ".");
		}
		if (reader.getActive() == null) {
			reader.setActive(true);
		}
	}

	/** NOT_FOUND when there is none; refused while a POS machine reads with this reader: the message names the machines. */
	@Override
	@Transactional
	public void deleteById(Long id) {
		get(id);
		List<PosMachine> linked = machines.findByReader_Id(id);
		if (!linked.isEmpty()) {
			throw new RfidException(Reason.INVALID, "This reader is used by the POS machine"
					+ (linked.size() > 1 ? "s " : " ")
					+ linked.stream().map(PosMachine::getName).sorted().collect(Collectors.joining(", "))
					+ ": give them another reader, or none, first.");
		}
		super.deleteById(id);
	}
}
