package com.digithink.zsretail.store.eventlog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.service.EventLogContext;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.UserAccountRepository;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.service.SessionPosMachineService;

/**
 * 3.4.0, event log step 2: what a store's event's caller did not say, filled by itself; a value the caller gave is never
 * replaced.
 * <ul>
 * <li>At the gesture (memory only): the badge's owner, from the grants the request used (TillRequestGrants); more than one
 * owner: the first, the others named in the details.</li>
 * <li>In the event's own transaction: the session (the user's open session), its number, the POS machine registered at
 * the request's address and its name. Step 3: a request from a PC that is no registered POS machine (the back office)
 * leaves the machine empty, its address kept (no more the session's till).</li>
 * </ul>
 * A refusal (TILL_RIGHT_REFUSED) is never "authorised by": the grants the request used before it are not its own.
 */
@Component
public class StoreEventContext implements EventLogContext {

	private final Function<Long, Optional<CashierSession>> sessionById;
	private final Function<Long, Optional<CashierSession>> openSessionOfUser;
	private final Function<String, Optional<PosMachine>> machineAt;

	@Autowired
	public StoreEventContext(CashierSessionRepository sessions, UserAccountRepository users,
			SessionPosMachineService sessionMachines) {
		this(sessions::findById,
				userId -> users.findById(userId)
						.flatMap(user -> sessions.findByCashierAndStatus(user, SessionStatus.OPENED)),
				sessionMachines::machineAt);
	}

	/** With the lookups given: used by the tests. */
	public StoreEventContext(Function<Long, Optional<CashierSession>> sessionById,
			Function<Long, Optional<CashierSession>> openSessionOfUser, Function<String, Optional<PosMachine>> machineAt) {
		this.sessionById = sessionById;
		this.openSessionOfUser = openSessionOfUser;
		this.machineAt = machineAt;
	}

	@Override
	public void atCall(EventLogEntry entry) {
		if (entry.getAuthorisedById() != null || entry.getAuthorisedByLogin() != null
				|| EventCode.TILL_RIGHT_REFUSED.name().equals(entry.getCode())) {
			return;
		}
		List<TillRequestGrants.Owner> used = TillRequestGrants.used();
		if (used.isEmpty()) {
			return;
		}
		TillRequestGrants.Owner first = used.get(0);
		entry.setAuthorisedById(first.id);
		entry.setAuthorisedByLogin(first.login);
		entry.setAuthorisedByName(first.name);
		List<String> others = new ArrayList<>();
		for (TillRequestGrants.Owner owner : used) {
			String name = owner.name != null ? owner.name : owner.login;
			if (!sameOwner(owner, first) && name != null && !others.contains(name)) {
				others.add(name);
			}
		}
		if (!others.isEmpty()) {
			String also = "Autorisé aussi par : " + String.join(", ", others) + ".";
			entry.setDetails(entry.getDetails() == null ? also : entry.getDetails() + " " + also);
		}
	}

	@Override
	public void complete(EventLogEntry entry) {
		if (entry.getSessionId() == null && entry.getUserId() != null) {
			openSessionOfUser.apply(entry.getUserId()).ifPresent(session -> {
				entry.setSessionId(session.getId());
				entry.setSessionNumber(session.getSessionNumber());
			});
		}
		if (entry.getSessionId() != null && entry.getSessionNumber() == null) {
			sessionById.apply(entry.getSessionId()).ifPresent(session -> entry.setSessionNumber(session.getSessionNumber()));
		}
		if (entry.getPosMachineId() == null && entry.getPosMachineName() == null && entry.getClientAddress() != null) {
			machineAt.apply(entry.getClientAddress()).ifPresent(found -> {
				entry.setPosMachineId(found.getId());
				entry.setPosMachineName(found.getName());
			});
		}
	}

	private static boolean sameOwner(TillRequestGrants.Owner owner, TillRequestGrants.Owner first) {
		return owner.id != null ? owner.id.equals(first.id) : owner.login != null && owner.login.equals(first.login);
	}
}
