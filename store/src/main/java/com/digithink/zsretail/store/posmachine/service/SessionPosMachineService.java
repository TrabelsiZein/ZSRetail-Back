package com.digithink.zsretail.store.posmachine.service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Subquery;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.store.posmachine.DrawerOwner;
import com.digithink.zsretail.store.posmachine.DrawerRuleException;
import com.digithink.zsretail.store.posmachine.PosMachineException;
import com.digithink.zsretail.store.posmachine.dto.TillPosMachine;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.model.SessionPosMachine;
import com.digithink.zsretail.store.posmachine.repository.PosMachineRepository;
import com.digithink.zsretail.store.posmachine.repository.SessionPosMachineRepository;

/**
 * 3.2.0, POS machines step 2: the POS machine of a cashier session. A session is opened on the machine registered at the
 * address of its opening request (none when that PC is not registered); the session keeps the link
 * ({@link SessionPosMachine}), the sales and returns get their machine through their session. The lists ask the names
 * of a page of sessions in one query. docs/modules/pos-machines.md.
 */
@Service
public class SessionPosMachineService {

	private final PosMachineRepository machines;
	private final SessionPosMachineRepository sessionMachines;
	private final CashierSessionRepository sessions;
	/** Part B: who the drawer belongs to (CASHIER: the rules below refuse nothing). */
	private final CashDrawerSetting drawer;

	public SessionPosMachineService(PosMachineRepository machines, SessionPosMachineRepository sessionMachines,
			CashierSessionRepository sessions, CashDrawerSetting drawer) {
		this.machines = machines;
		this.sessionMachines = sessionMachines;
		this.sessions = sessions;
		this.drawer = drawer;
	}

	/** Who the drawer belongs to now (General setup CASH_DRAWER_OWNER). */
	public DrawerOwner drawerOwner() {
		return drawer.owner();
	}

	/**
	 * The machine registered at this request address (request.getRemoteAddr(), normalised), active or not; empty when
	 * none, or when the text is not an address.
	 */
	public Optional<PosMachine> machineAt(String remoteAddress) {
		String address = addressOrNull(remoteAddress);
		return address == null ? Optional.empty() : machines.findFirstByIpAddress(address);
	}

	/**
	 * The machine a session is opened on, from the opening request's address: the registered one, else none. Part B,
	 * when the drawer belongs to the POS machine: rule 1, only from a registered, active machine (the refusal says the
	 * address the server sees, POS_MACHINE_NOT_REGISTERED or POS_MACHINE_INACTIVE); rule 2, one open session per machine
	 * (POS_MACHINE_IN_USE names the cashier whose session holds it). Step 4, between the two: a machine for some users
	 * only ("Seulement") opens only for a listed, active user (POS_MACHINE_USER_NOT_ALLOWED names the machine); with
	 * nobody active left in its list, it opens for nobody. Checked at the opening only.
	 */
	public PosMachine machineForOpening(UserAccount user, String remoteAddress) {
		Optional<PosMachine> found = machineAt(remoteAddress);
		if (!drawer.toPosMachine()) {
			return found.orElse(null);
		}
		String address = addressOrNull(remoteAddress);
		String shown = address != null ? address : (remoteAddress == null ? "" : remoteAddress.trim());
		if (!found.isPresent()) {
			throw new DrawerRuleException("POS_MACHINE_NOT_REGISTERED", "This PC (address " + shown + ") is not a"
					+ " registered POS machine: the drawer belongs to the POS machine, so a session opens only on one."
					+ " Ask an administrator to add this address on the POS machines page.", "address", shown);
		}
		PosMachine machine = found.get();
		if (!Boolean.TRUE.equals(machine.getActive())) {
			throw new DrawerRuleException("POS_MACHINE_INACTIVE", "The POS machine '" + machine.getName() + "' (address "
					+ shown + ") is inactive: a session cannot be opened on it.", "name", machine.getName(), "address",
					shown);
		}
		if (machine.restrictsUsers() && !allowedOn(machine, user)) {
			throw new DrawerRuleException("POS_MACHINE_USER_NOT_ALLOWED", "You are not among the users of the POS machine '"
					+ machine.getName() + "': a session cannot be opened on it. Ask an administrator to add you to its"
					+ " users on the POS machines page.", "name", machine.getName());
		}
		openSessionOn(machine.getId()).ifPresent(open -> {
			String cashier = cashierName(open);
			throw new DrawerRuleException("POS_MACHINE_IN_USE", "The POS machine '" + machine.getName() + "' already has"
					+ " an open session, of " + cashier + ": it must be closed first.", "name", machine.getName(),
					"cashier", cashier);
		});
		return machine;
	}

	/** Step 4: the user is in the machine's list and active (a deleted user cannot log in). */
	static boolean allowedOn(PosMachine machine, UserAccount user) {
		return user != null && user.getId() != null && !Boolean.FALSE.equals(user.getActive())
				&& machine.getAllowedUserIds() != null && machine.getAllowedUserIds().contains(user.getId());
	}

	/** The OPENED session on this machine, if any (rule 2; a machine with one is not deactivated, rule 5). */
	public Optional<CashierSession> openSessionOn(Long posMachineId) {
		return posMachineId == null ? Optional.empty()
				: sessionMachines.findFirstByPosMachine_IdAndSession_Status(posMachineId, SessionStatus.OPENED)
						.map(SessionPosMachine::getSession);
	}

	/**
	 * Part B, rule 3: the machine of the user's open session when the drawer belongs to the POS machine and the request
	 * comes from another address (its machine's current address: an address corrected on the POS machines page counts).
	 * Empty, never refused: the drawer of the cashier, no open session, a session without a machine, the right address.
	 */
	public Optional<PosMachine> otherMachine(UserAccount user, String remoteAddress) {
		if (user == null || !drawer.toPosMachine()) {
			return Optional.empty();
		}
		Optional<PosMachine> machine = sessions.findByCashierAndStatus(user, SessionStatus.OPENED)
				.flatMap(session -> machineOf(session.getId()));
		if (!machine.isPresent()) {
			return Optional.empty();
		}
		String address = addressOrNull(remoteAddress);
		return address != null && address.equals(machine.get().getIpAddress()) ? Optional.empty() : machine;
	}

	/**
	 * What the till asks when its screens open (GET /cashier-session/current/pos-machine): who the drawer belongs to, the
	 * address seen, the machine of the user's open session (null without one) and whether this PC may sell for it.
	 */
	public TillPosMachine tillPosMachine(UserAccount user, String remoteAddress) {
		String address = addressOrNull(remoteAddress);
		Optional<CashierSession> open = user == null ? Optional.empty()
				: sessions.findByCashierAndStatus(user, SessionStatus.OPENED);
		String sessionMachine = open.map(CashierSession::getId).map(this::nameOf).orElse(null);
		boolean blocked = otherMachine(user, remoteAddress).isPresent();
		return new TillPosMachine(drawer.owner().name(), address, sessionMachine, blocked);
	}

	private static String cashierName(CashierSession session) {
		UserAccount cashier = session.getCashier();
		if (cashier == null) {
			return "?";
		}
		String fullName = cashier.getFullName();
		return fullName != null && !fullName.trim().isEmpty() ? fullName.trim() : cashier.getUsername();
	}

	/** Records the machine a session was opened on (nothing without a machine). */
	public void record(CashierSession session, PosMachine machine) {
		if (session != null && machine != null) {
			sessionMachines.save(new SessionPosMachine(session, machine));
		}
	}

	/** The machine a session was opened on; empty without one (a PC not registered, a session before 3.2.0). */
	public Optional<PosMachine> machineOf(Long sessionId) {
		return sessionId == null ? Optional.empty()
				: sessionMachines.findById(sessionId).map(SessionPosMachine::getPosMachine);
	}

	/** The machine's name of a session; null without one. */
	public String nameOf(Long sessionId) {
		return machineOf(sessionId).map(PosMachine::getName).orElse(null);
	}

	/** The machines' names of these sessions (one query), by session id; a session without a machine is not in it. */
	public Map<Long, String> namesBySession(Collection<Long> sessionIds) {
		if (sessionIds == null || sessionIds.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<Long, String> names = new HashMap<>();
		for (SessionPosMachine row : sessionMachines.findBySessionIdIn(sessionIds)) {
			names.put(row.getSessionId(), row.getPosMachine().getName());
		}
		return names;
	}

	/**
	 * Step 3: the machines of these sessions (one query), by session id; a session without a machine is not in it.
	 */
	public Map<Long, PosMachine> machinesBySession(Collection<Long> sessionIds) {
		if (sessionIds == null || sessionIds.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<Long, PosMachine> found = new HashMap<>();
		for (SessionPosMachine row : sessionMachines.findBySessionIdIn(sessionIds)) {
			found.put(row.getSessionId(), row.getPosMachine());
		}
		return found;
	}

	/** Step 3: a POS machine by its id; empty when none. */
	public Optional<PosMachine> machine(Long posMachineId) {
		return posMachineId == null ? Optional.empty() : machines.findById(posMachineId);
	}

	/**
	 * A filter of a list: the session id is one of the sessions opened on this machine (a subquery on the link table's
	 * pos_machine_id; the sales then come through ix_sales_header_session_status).
	 */
	public static Predicate openedOn(Expression<Long> sessionId, Long posMachineId, CriteriaQuery<?> query,
			CriteriaBuilder cb) {
		Subquery<Long> sessions = query.subquery(Long.class);
		Root<SessionPosMachine> link = sessions.from(SessionPosMachine.class);
		sessions.select(link.get("sessionId")).where(cb.equal(link.get("posMachine").get("id"), posMachineId));
		return sessionId.in(sessions);
	}

	/**
	 * A filter of the sales or returns: those of a session opened on this machine (machineId blank or unreadable: no
	 * filter). Their cashierSession's id through {@link #openedOn}.
	 */
	public static <T> Specification<T> ofSessionOn(String posMachineId) {
		Long id = parseId(posMachineId);
		return (root, query, cb) -> id == null ? null
				: openedOn(root.get("cashierSession").<Long>get("id"), id, query, cb);
	}

	/** A request's id, null when blank or not a number. */
	static Long parseId(String text) {
		if (text == null || text.trim().isEmpty()) {
			return null;
		}
		try {
			return Long.valueOf(text.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** The request's address in one form (PosMachineAddresses.normalise); null when it is not an address. */
	public static String addressOrNull(String remoteAddress) {
		try {
			return PosMachineAddresses.normalise(remoteAddress);
		} catch (PosMachineException e) {
			return null;
		}
	}
}
