package com.digithink.zsretail.store.rfid.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;
import com.digithink.zsretail.store.rfid.model.PosMachine;
import com.digithink.zsretail.store.rfid.repository.PosMachineRepository;
import com.digithink.zsretail.store.rfid.repository.RfidReaderRepository;

/**
 * RFID step 3: the store's POS machines and their rules (docs/modules/rfid.md). A machine is saved only with a name (at
 * most 100 characters, unique in any case) and an IP address, kept normalised ({@link RfidAddresses#normalise}) and
 * unique; its reader, when it has one, must exist. Every refusal is an {@link RfidException} INVALID with its message.
 */
@Service
@ConditionalOnInstallation(Installation.RFID)
public class PosMachineService extends _BaseService<PosMachine, Long> {

	private final PosMachineRepository machines;
	private final RfidReaderRepository readers;

	public PosMachineService(PosMachineRepository machines, RfidReaderRepository readers) {
		this.machines = machines;
		this.readers = readers;
	}

	@Override
	protected _BaseRepository<PosMachine, Long> getRepository() {
		return machines;
	}

	/** The machine whose requests come from this address (as Tomcat gives it); empty when none is recorded. */
	public Optional<PosMachine> findByRequestAddress(String remoteAddress) {
		return machines.findFirstByIpAddress(RfidAddresses.normalise(remoteAddress));
	}

	@Override
	@Transactional
	public PosMachine save(PosMachine machine) throws Exception {
		check(machine);
		return super.save(machine);
	}

	/** The rules of a machine about to be saved; trims its name, normalises its address, loads its reader. */
	void check(PosMachine machine) {
		String name = requiredName(machine.getName(), "POS machine");
		machine.setName(name);
		machines.findFirstByNameIgnoreCase(name).filter(other -> !other.getId().equals(machine.getId()))
				.ifPresent(other -> {
					throw new RfidException(Reason.INVALID, "A POS machine is already named '" + other.getName() + "'.");
				});

		String address = RfidAddresses.normalise(machine.getIpAddress());
		machine.setIpAddress(address);
		machines.findFirstByIpAddress(address).filter(other -> !other.getId().equals(machine.getId()))
				.ifPresent(other -> {
					throw new RfidException(Reason.INVALID, "The POS machine '" + other.getName() + "' already has the"
							+ " address " + address + ".");
				});

		if (machine.getReader() != null) {
			Long readerId = machine.getReader().getId();
			machine.setReader(readerId == null ? null : readers.findById(readerId).orElseThrow(
					() -> new RfidException(Reason.INVALID, "No RFID reader number " + readerId + ".")));
		}
		if (machine.getActive() == null) {
			machine.setActive(true);
		}
	}

	/** A name, trimmed: required, at most 100 characters. */
	static String requiredName(String raw, String what) {
		String name = raw == null ? "" : raw.trim();
		if (name.isEmpty()) {
			throw new RfidException(Reason.INVALID, "The " + what + " needs a name.");
		}
		if (name.length() > RfidReaderService.MAX_NAME_LENGTH) {
			throw new RfidException(Reason.INVALID, "The " + what + "'s name has at most "
					+ RfidReaderService.MAX_NAME_LENGTH + " characters.");
		}
		return name;
	}
}
