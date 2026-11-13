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
import com.digithink.zsretail.store.posmachine.PosMachineException;
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

	public SessionPosMachineService(PosMachineRepository machines, SessionPosMachineRepository sessionMachines) {
		this.machines = machines;
		this.sessionMachines = sessionMachines;
	}

	/**
	 * The machine registered at this request address (request.getRemoteAddr(), normalised), active or not; empty when
	 * none, or when the text is not an address.
	 */
	public Optional<PosMachine> machineAt(String remoteAddress) {
		String address = addressOrNull(remoteAddress);
		return address == null ? Optional.empty() : machines.findFirstByIpAddress(address);
	}

	/** The machine a session is opened on, from the opening request's address: the registered one, else none. */
	public PosMachine machineForOpening(String remoteAddress) {
		return machineAt(remoteAddress).orElse(null);
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
