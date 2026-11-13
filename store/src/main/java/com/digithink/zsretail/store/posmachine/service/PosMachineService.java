package com.digithink.zsretail.store.posmachine.service;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.repository.UserAccountRepository;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.posmachine.PosMachineException;
import com.digithink.zsretail.store.posmachine.PosMachineException.Reason;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.repository.PosMachineRepository;
import com.digithink.zsretail.store.posmachine.repository.SessionPosMachineRepository;
import com.digithink.zsretail.store.rfid.repository.RfidReaderRepository;

/**
 * The store's POS machines and their rules (RFID step 3; every store since 3.2.0, POS machines step 1:
 * docs/modules/pos-machines.md). A machine is saved only with a name (at most 100 characters, unique in any case) and an
 * IP address, kept normalised ({@link PosMachineAddresses#normalise}) and unique. Every refusal is a
 * {@link PosMachineException} INVALID with its message.
 * <p>
 * The reader is RFID's: the RFID reader repository exists only with rfid.enabled=true. With it, a machine's reader, when
 * it has one, must exist (as in 3.1.0). Without it, the reader sent is ignored: a new machine has none, a machine saved
 * again keeps the one it has (a store that turned RFID off loses no link by renaming a machine).
 * <p>
 * Step 2: a machine a session was opened on cannot be deleted (it is deactivated instead); every refusal has its code
 * (serverErrors.&lt;code&gt; of the front) and the values of its message.
 */
@Service
public class PosMachineService extends _BaseService<PosMachine, Long> {

	/** The longest name of a POS machine. */
	public static final int MAX_NAME_LENGTH = 100;

	private final PosMachineRepository machines;
	private final SessionPosMachineRepository sessionMachines;
	/** Present only with rfid.enabled=true. */
	private final Optional<RfidReaderRepository> readers;

	/** Part B: who the drawer belongs to (rule 5: a machine with an open session is not deactivated). */
	private final CashDrawerSetting drawer;

	/** Step 4: the users of a "Seulement" list (core's), read to keep only those that exist and are active. */
	private final UserAccountRepository users;

	public PosMachineService(PosMachineRepository machines, SessionPosMachineRepository sessionMachines,
			Optional<RfidReaderRepository> readers, CashDrawerSetting drawer, UserAccountRepository users) {
		this.machines = machines;
		this.sessionMachines = sessionMachines;
		this.readers = readers;
		this.drawer = drawer;
		this.users = users;
	}

	@Override
	protected _BaseRepository<PosMachine, Long> getRepository() {
		return machines;
	}

	/** The machine of this id; NOT_FOUND when there is none. */
	public PosMachine get(Long id) {
		return machines.findById(id)
				.orElseThrow(() -> new PosMachineException(Reason.NOT_FOUND, "POS_MACHINE_NOT_FOUND",
						"No POS machine number " + id + ".", "id", id));
	}

	/** NOT_FOUND when there is none; INVALID when a session was opened on it (step 2: deactivate it instead). */
	@Override
	@Transactional
	public void deleteById(Long id) {
		PosMachine machine = get(id);
		if (sessionMachines.existsByPosMachine_Id(id)) {
			throw new PosMachineException(Reason.INVALID, "POS_MACHINE_HAS_SESSIONS", "The POS machine '"
					+ machine.getName() + "' has sessions: it cannot be deleted. Deactivate it instead.", "name",
					machine.getName());
		}
		super.deleteById(id);
	}

	/** The machine whose requests come from this address (as Tomcat gives it); empty when none is recorded. */
	public Optional<PosMachine> findByRequestAddress(String remoteAddress) {
		return machines.findFirstByIpAddress(PosMachineAddresses.normalise(remoteAddress));
	}

	@Override
	@Transactional
	public PosMachine save(PosMachine machine) throws Exception {
		check(machine);
		return super.save(machine);
	}

	/** The rules of a machine about to be saved; trims its name, normalises its address, sets its reader. */
	void check(PosMachine machine) {
		String name = requiredName(machine.getName());
		machine.setName(name);
		machines.findFirstByNameIgnoreCase(name).filter(other -> !other.getId().equals(machine.getId()))
				.ifPresent(other -> {
					throw new PosMachineException(Reason.INVALID, "POS_MACHINE_NAME_TAKEN",
							"A POS machine is already named '" + other.getName() + "'.", "name", other.getName());
				});

		String address = PosMachineAddresses.normalise(machine.getIpAddress());
		machine.setIpAddress(address);
		machines.findFirstByIpAddress(address).filter(other -> !other.getId().equals(machine.getId()))
				.ifPresent(other -> {
					throw new PosMachineException(Reason.INVALID, "POS_MACHINE_ADDRESS_TAKEN", "The POS machine '"
							+ other.getName() + "' already has the address " + address + ".", "name", other.getName(),
							"address", address);
				});

		if (readers.isPresent()) {
			if (machine.getReader() != null) {
				Long readerId = machine.getReader().getId();
				machine.setReader(readerId == null ? null : readers.get().findById(readerId).orElseThrow(
						() -> new PosMachineException(Reason.INVALID, "POS_MACHINE_READER_NOT_FOUND",
								"No RFID reader number " + readerId + ".", "id", readerId)));
			}
		} else {
			// No RFID: the reader is not this page's; the machine keeps the one it has (none when new)
			machine.setReader(machine.getId() == null ? null
					: machines.findById(machine.getId()).map(PosMachine::getReader).orElse(null));
		}
		if (machine.getActive() == null) {
			machine.setActive(true);
		}
		checkUsers(machine);
		// Part B, rule 5: the drawer of the POS machine, a machine with an open session stays active (its address can be
		// corrected); a delete is refused anyway (it has a session)
		if (!machine.getActive() && machine.getId() != null && drawer.toPosMachine()) {
			sessionMachines.findFirstByPosMachine_IdAndSession_Status(machine.getId(), SessionStatus.OPENED)
					.ifPresent(open -> {
						String cashier = open.getSession().getCashier() == null ? "?"
								: (open.getSession().getCashier().getFullName() != null
										? open.getSession().getCashier().getFullName()
										: open.getSession().getCashier().getUsername());
						throw new PosMachineException(Reason.INVALID, "POS_MACHINE_OPEN_SESSION", "The POS machine '"
								+ machine.getName() + "' has an open session, of " + cashier + ": it cannot be deactivated"
								+ " before that session is closed.", "name", machine.getName(), "cashier", cashier);
					});
		}
	}

	/**
	 * Step 4, the users of the machine: "Tous les utilisateurs" (usersRestricted false) or "Seulement" (true) with its
	 * list. A field sent null (an older screen) keeps what the machine has, so no save turns "Seulement" into "Tous" by
	 * itself. A list sent keeps only the users that exist and are active (a user deleted or deactivated leaves it).
	 * "Seulement" with nobody left is refused (POS_MACHINE_USERS_REQUIRED); a list kept as it is (not sent) is not
	 * checked again: a machine whose listed users all left keeps its restriction (nobody opens a session on it). "Tous"
	 * keeps no list.
	 */
	private void checkUsers(PosMachine machine) {
		Optional<PosMachine> stored = machine.getId() == null ? Optional.empty() : machines.findById(machine.getId());
		boolean listSent = machine.getAllowedUserIds() != null;
		if (machine.getUsersRestricted() == null) {
			machine.setUsersRestricted(stored.map(PosMachine::getUsersRestricted).orElse(false));
		}
		if (!listSent) {
			machine.setAllowedUserIds(new HashSet<>(
					stored.map(PosMachine::getAllowedUserIds).orElse(java.util.Collections.emptySet())));
		}
		if (!machine.restrictsUsers()) {
			machine.setUsersRestricted(false);
			machine.setAllowedUserIds(new HashSet<>());
			return;
		}
		if (listSent) {
			Set<Long> active = new HashSet<>();
			Set<Long> sent = new HashSet<>(machine.getAllowedUserIds());
			sent.remove(null);
			for (UserAccount user : users.findAllById(sent)) {
				if (!Boolean.FALSE.equals(user.getActive())) {
					active.add(user.getId());
				}
			}
			machine.setAllowedUserIds(active);
		}
		if (machine.getAllowedUserIds().isEmpty()) {
			throw new PosMachineException(Reason.INVALID, "POS_MACHINE_USERS_REQUIRED", "The POS machine '"
					+ machine.getName() + "' is for some users only: choose at least one active user, or every user.",
					"name", machine.getName());
		}
	}

	/** A name, trimmed: required, at most 100 characters. */
	private static String requiredName(String raw) {
		String name = raw == null ? "" : raw.trim();
		if (name.isEmpty()) {
			throw new PosMachineException(Reason.INVALID, "POS_MACHINE_NAME_REQUIRED", "The POS machine needs a name.");
		}
		if (name.length() > MAX_NAME_LENGTH) {
			throw new PosMachineException(Reason.INVALID, "POS_MACHINE_NAME_TOO_LONG",
					"The POS machine's name has at most " + MAX_NAME_LENGTH + " characters.", "max", MAX_NAME_LENGTH);
		}
		return name;
	}
}
