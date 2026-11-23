package com.digithink.zsretail.core.support;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.repository.EventLogEntryRepository;
import com.digithink.zsretail.core.eventlog.repository.EventLogSequenceRepository;
import com.digithink.zsretail.core.eventlog.service.EventLog;
import com.digithink.zsretail.core.eventlog.service.EventLogContext;
import com.digithink.zsretail.core.eventlog.service.EventLogWriter;
import com.digithink.zsretail.core.model.UserAccount;

/**
 * 3.4.0, event log: the module on, its two tables in memory (the rows written, the numbers per importance), the real
 * EventLog and EventLogWriter over them, for the tests of the gestures that write events. No Spring context, no
 * transaction (an event is written at once).
 */
public final class InMemoryEventLog {

	public final List<EventLogEntry> rows = new ArrayList<>();
	public final Map<Integer, Long> numbers = new HashMap<>();
	public final Map<String, UserAccount> users = new HashMap<>();
	public final List<EventLogContext> contexts = new ArrayList<>();
	public boolean on = true;
	public String loggedLogin;
	public String address;
	public LocalDateTime now = LocalDateTime.of(2026, 11, 23, 10, 0);

	/** The logged user (the login the security context gives, the user its lookup finds). */
	public InMemoryEventLog logged(UserAccount user) {
		loggedLogin = user == null ? null : user.getUsername();
		if (user != null) {
			users.put(user.getUsername(), user);
		}
		return this;
	}

	public EventLog eventLog() {
		return new EventLog(() -> on, writer(), () -> loggedLogin, users::get, () -> address, contexts, () -> now);
	}

	public EventLogWriter writer() {
		return new EventLogWriter(entries(), sequences(), TransactionOperations.withoutTransaction());
	}

	public List<String> codes() {
		return rows.stream().map(EventLogEntry::getCode).collect(Collectors.toList());
	}

	/** The one row of this code; fails when there is none or more than one. */
	public EventLogEntry only(String code) {
		List<EventLogEntry> found = rows.stream().filter(row -> code.equals(row.getCode())).collect(Collectors.toList());
		if (found.size() != 1) {
			throw new AssertionError("One " + code + " expected, found " + found.size() + " in " + codes());
		}
		return found.get(0);
	}

	private EventLogEntryRepository entries() {
		return stub(EventLogEntryRepository.class, (method, args) -> {
			if ("save".equals(method)) {
				EventLogEntry entry = (EventLogEntry) args[0];
				entry.setId((long) rows.size() + 1);
				rows.add(entry);
				return entry;
			}
			throw new UnsupportedOperationException(method);
		});
	}

	private EventLogSequenceRepository sequences() {
		return stub(EventLogSequenceRepository.class, (method, args) -> {
			switch (method) {
				case "increment": {
					Integer importance = (Integer) args[0];
					if (!numbers.containsKey(importance)) {
						return 0;
					}
					numbers.put(importance, numbers.get(importance) + 1);
					return 1;
				}
				case "lastValue":
					return Collections.singletonList(numbers.get((Integer) args[0]));
				case "saveAndFlush": {
					com.digithink.zsretail.core.eventlog.model.EventLogSequence row = (com.digithink.zsretail.core.eventlog.model.EventLogSequence) args[0];
					numbers.put(row.getImportance(), row.getLastValue());
					return row;
				}
				default:
					throw new UnsupportedOperationException(method);
			}
		});
	}

	interface Answer {
		Object answer(String method, Object[] args);
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, Answer answer) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			if (method.getDeclaringClass() == Object.class) {
				switch (method.getName()) {
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					default:
						return type.getSimpleName();
				}
			}
			return answer.answer(method.getName(), args);
		});
	}
}
