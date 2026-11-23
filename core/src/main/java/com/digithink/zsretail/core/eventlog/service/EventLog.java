package com.digithink.zsretail.core.eventlog.service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.security.CurrentUserProvider;

import lombok.extern.log4j.Log4j2;

/**
 * 3.4.0, the event log's one entry point: {@code eventLog.write(NewEvent.of(EventCode.X)...)} where the gesture
 * happens. It never breaks nor blocks the gesture: off (eventlog.enabled not true) it does nothing; a failure is one WARN
 * and the sale, the return or the save goes on. docs/modules/event-log.md.
 * <ul>
 * <li>{@link #write}: the gesture's event. Called inside a transaction, it is written once that transaction has
 * committed, in its own: a sale that rolls back leaves no "sale validated".</li>
 * <li>{@link #writeNow}: written at once, in its own transaction, whatever becomes of the caller's: a refusal (the
 * gesture throws and rolls back, its event stays).</li>
 * </ul>
 */
@Component
@Log4j2
public class EventLog {

	private final BooleanSupplier on;
	private final EventLogWriter writer;
	private final Supplier<UserAccount> loggedUser;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public EventLog(ApplicationModeService mode, EventLogWriter writer, CurrentUserProvider users) {
		this(mode::isEventLogEnabled, writer, () -> loggedUser(users), LocalDateTime::now);
	}

	/** With the switch, the logged user and the clock given: used by the tests. */
	public EventLog(BooleanSupplier on, EventLogWriter writer, Supplier<UserAccount> loggedUser,
			Supplier<LocalDateTime> clock) {
		this.on = on;
		this.writer = writer;
		this.loggedUser = loggedUser;
		this.clock = clock;
	}

	/** eventlog.enabled=true on this store. */
	public boolean isOn() {
		return on.getAsBoolean();
	}

	/** The gesture's event: after the caller's transaction has committed (none if it rolls back), else at once. */
	public void write(NewEvent event) {
		if (!isOn()) {
			return;
		}
		try {
			EventLogEntry entry = entryOf(event);
			if (TransactionSynchronizationManager.isSynchronizationActive()
					&& TransactionSynchronizationManager.isActualTransactionActive()) {
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
					@Override
					public void afterCommit() {
						store(entry);
					}
				});
			} else {
				store(entry);
			}
		} catch (RuntimeException e) {
			log.warn("Event log: {} not written ({})", event.getCode(), e.toString());
		}
	}

	/** An event written at once, in its own transaction, even when the caller's rolls back (a refusal). */
	public void writeNow(NewEvent event) {
		if (!isOn()) {
			return;
		}
		try {
			store(entryOf(event));
		} catch (RuntimeException e) {
			log.warn("Event log: {} not written ({})", event.getCode(), e.toString());
		}
	}

	private void store(EventLogEntry entry) {
		try {
			writer.insert(entry);
		} catch (RuntimeException e) {
			log.warn("Event log: {} not written ({})", entry.getCode(), e.toString());
		}
	}

	/** The row: the catalogue's importance and subject, the server's clock and UUID, the texts cut to their columns. */
	EventLogEntry entryOf(NewEvent event) {
		EventLogEntry entry = new EventLogEntry();
		entry.setCode(event.getCode().name());
		entry.setImportance(event.getCode().getImportance());
		entry.setSubjectType(event.getCode().getSubject().name());
		entry.setEventUuid(event.getFrontUuid() != null ? cut(event.getFrontUuid(), EventLogEntry.UUID_LENGTH)
				: UUID.randomUUID().toString());
		entry.setEventAt(clock.get());
		entry.setFrontAt(event.getFrontAt());
		entry.setSubjectId(cut(event.getSubjectId(), EventLogEntry.SUBJECT_ID_LENGTH));
		entry.setSubjectLabel(cut(event.getSubjectLabel(), EventLogEntry.LABEL_LENGTH));
		entry.setPosMachineId(event.getPosMachineId());
		entry.setPosMachineName(cut(event.getPosMachineName(), EventLogEntry.MACHINE_NAME_LENGTH));
		entry.setSessionId(event.getSessionId());
		entry.setSessionNumber(cut(event.getSessionNumber(), EventLogEntry.SESSION_NUMBER_LENGTH));
		entry.setCartId(cut(event.getCartId(), EventLogEntry.UUID_LENGTH));
		if (event.isUserGiven()) {
			entry.setUserId(event.getUserId());
			entry.setUserLogin(cut(event.getUserLogin(), EventLogEntry.LOGIN_LENGTH));
			entry.setUserName(cut(event.getUserName(), EventLogEntry.NAME_LENGTH));
			entry.setUserRole(cut(event.getUserRole(), EventLogEntry.LOGIN_LENGTH));
		} else {
			withLoggedUser(entry);
		}
		entry.setAuthorisedById(event.getAuthorisedById());
		entry.setAuthorisedByLogin(cut(event.getAuthorisedByLogin(), EventLogEntry.LOGIN_LENGTH));
		entry.setAuthorisedByName(cut(event.getAuthorisedByName(), EventLogEntry.NAME_LENGTH));
		entry.setSentById(event.getSentById());
		entry.setSentByLogin(cut(event.getSentByLogin(), EventLogEntry.LOGIN_LENGTH));
		entry.setClientAddress(cut(event.getClientAddress(), EventLogEntry.ADDRESS_LENGTH));
		entry.setMachineSequenceNo(event.getMachineSequenceNo());
		entry.setOldValue(cut(event.getOldValue(), EventLogEntry.VALUE_LENGTH));
		entry.setNewValue(cut(event.getNewValue(), EventLogEntry.VALUE_LENGTH));
		entry.setAmount(event.getAmount());
		entry.setDetails(cut(event.getDetails(), EventLogEntry.DETAILS_LENGTH));
		return entry;
	}

	/** The logged user's id, login, name and role; nothing when nobody is logged (a start, a job). */
	private void withLoggedUser(EventLogEntry entry) {
		UserAccount user;
		try {
			user = loggedUser.get();
		} catch (RuntimeException e) {
			user = null;
		}
		if (user == null) {
			return;
		}
		entry.setUserId(user.getId());
		entry.setUserLogin(cut(user.getUsername(), EventLogEntry.LOGIN_LENGTH));
		entry.setUserName(cut(user.getFullName(), EventLogEntry.NAME_LENGTH));
		try {
			entry.setUserRole(user.getAppRole() == null ? null : cut(user.getAppRole().getName(), EventLogEntry.LOGIN_LENGTH));
		} catch (RuntimeException e) {
			entry.setUserRole(null); // a role that cannot be read never stops the event
		}
	}

	/** The logged user, or null when there is none (no request, an anonymous one, a login that matches no user). */
	private static UserAccount loggedUser(CurrentUserProvider users) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			return null;
		}
		return users.getCurrentUser();
	}

	static String cut(String text, int length) {
		return text == null || text.length() <= length ? text : text.substring(0, length);
	}
}
