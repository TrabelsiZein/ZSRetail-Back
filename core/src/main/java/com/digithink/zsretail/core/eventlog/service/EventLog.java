package com.digithink.zsretail.core.eventlog.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.repository.UserAccountRepository;

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
 * Step 2: at the gesture only memory is read (the logged login, the request's address, the contexts' atCall: no query
 * in the gesture's transaction); the user's id, name and role and the contexts' completion (machine, session, names) are
 * read in the event's own transaction.
 */
@Component
@Log4j2
public class EventLog {

	private final BooleanSupplier on;
	private final EventLogWriter writer;
	private final Supplier<String> loggedLogin;
	private final Function<String, UserAccount> userByLogin;
	private final Supplier<String> requestAddress;
	private final List<EventLogContext> contexts;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public EventLog(ApplicationModeService mode, EventLogWriter writer, UserAccountRepository users,
			ObjectProvider<EventLogContext> contexts) {
		this(mode::isEventLogEnabled, writer, EventLog::loggedLogin, login -> users.findByUsername(login).orElse(null),
				EventLog::requestAddress, new ArrayList<>(), LocalDateTime::now);
		contexts.orderedStream().forEach(this.contexts::add);
	}

	/** Everything given: used by the tests. */
	public EventLog(BooleanSupplier on, EventLogWriter writer, Supplier<String> loggedLogin,
			Function<String, UserAccount> userByLogin, Supplier<String> requestAddress, List<EventLogContext> contexts,
			Supplier<LocalDateTime> clock) {
		this.on = on;
		this.writer = writer;
		this.loggedLogin = loggedLogin;
		this.userByLogin = userByLogin;
		this.requestAddress = requestAddress;
		this.contexts = contexts;
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
			writer.insert(entry, this::complete);
		} catch (RuntimeException e) {
			log.warn("Event log: {} not written ({})", entry.getCode(), e.toString());
		}
	}

	/** In the event's own transaction: the user's id, name and role from the login, then the contexts. */
	void complete(EventLogEntry entry) {
		if (entry.getUserId() == null && entry.getUserLogin() != null) {
			try {
				withUser(entry, userByLogin.apply(entry.getUserLogin()));
			} catch (RuntimeException e) {
				log.debug("Event log: the user {} not read ({})", entry.getUserLogin(), e.toString());
			}
		}
		for (EventLogContext context : contexts) {
			try {
				context.complete(entry);
			} catch (RuntimeException e) {
				log.debug("Event log: a context of {} not read ({})", entry.getCode(), e.toString());
			}
		}
	}

	/** The row at the gesture: the catalogue's importance and subject, the server's clock and UUID, the texts cut. */
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
			entry.setUserLogin(cut(safely(loggedLogin), EventLogEntry.LOGIN_LENGTH));
		}
		entry.setAuthorisedById(event.getAuthorisedById());
		entry.setAuthorisedByLogin(cut(event.getAuthorisedByLogin(), EventLogEntry.LOGIN_LENGTH));
		entry.setAuthorisedByName(cut(event.getAuthorisedByName(), EventLogEntry.NAME_LENGTH));
		entry.setSentById(event.getSentById());
		entry.setSentByLogin(cut(event.getSentByLogin(), EventLogEntry.LOGIN_LENGTH));
		entry.setClientAddress(cut(event.getClientAddress() != null ? event.getClientAddress() : safely(requestAddress),
				EventLogEntry.ADDRESS_LENGTH));
		entry.setMachineSequenceNo(event.getMachineSequenceNo());
		entry.setOldValue(cut(event.getOldValue(), EventLogEntry.VALUE_LENGTH));
		entry.setNewValue(cut(event.getNewValue(), EventLogEntry.VALUE_LENGTH));
		entry.setAmount(event.getAmount());
		entry.setDetails(cut(event.getDetails(), EventLogEntry.DETAILS_LENGTH));
		for (EventLogContext context : contexts) {
			try {
				context.atCall(entry);
			} catch (RuntimeException e) {
				log.debug("Event log: a context of {} not read at the gesture ({})", entry.getCode(), e.toString());
			}
		}
		entry.setDetails(cut(entry.getDetails(), EventLogEntry.DETAILS_LENGTH));
		return entry;
	}

	/** Fills the user's id, login, name and role; nothing when there is none. */
	static void withUser(EventLogEntry entry, UserAccount user) {
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

	private static String safely(Supplier<String> value) {
		try {
			return value.get();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The logged login, from memory (no query); null when nobody is logged (a start, a job, an anonymous request). */
	private static String loggedLogin() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			return null;
		}
		return authentication.getName();
	}

	/** The address the request came from; null outside a request. */
	private static String requestAddress() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		return attributes instanceof ServletRequestAttributes
				? ((ServletRequestAttributes) attributes).getRequest().getRemoteAddr()
				: null;
	}

	public static String cut(String text, int length) {
		return text == null || text.length() <= length ? text : text.substring(0, length);
	}
}
