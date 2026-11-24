package com.digithink.zsretail.store.eventlog;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.repository.EventLogEntryRepository;
import com.digithink.zsretail.core.eventlog.service.AccessEvents;
import com.digithink.zsretail.core.eventlog.service.EventLog;
import com.digithink.zsretail.core.eventlog.service.EventLogWriter;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.UserAccountRepository;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.repository.PosMachineRepository;
import com.digithink.zsretail.store.posmachine.service.SessionPosMachineService;

import lombok.Data;
import lombok.extern.log4j.Log4j2;

/**
 * 3.4.0, event log step 4: the till's events, received in groups (POST /event-log/batch; docs/modules/event-log.md,
 * "Step 4"). The till keeps them in its queue (localStorage) and sends them when it can; a group sent again is answered
 * the same way.
 * <ul>
 * <li>Only the codes of {@link EventCode#FROM_TILL}; any other (a money event above all) is refused, its UUID answered so
 * the till drops it. The importance and the subject type come from the catalogue, never from the till.</li>
 * <li>A UUID stored already, or twice in the group, is "duplicate": answered as stored.</li>
 * <li>The user, the session and the POS machine are the till's claims, kept. What the server cannot confirm is marked in
 * {@code doubt} (and said in the details), never a reason to refuse: a session that is another user's, a user, a
 * session or a machine unknown, a till time unreadable.</li>
 * <li>Who sent the group (the logged user), the request's address and the server's clock are the server's.</li>
 * <li>One own transaction: the numbers of the group reserved at once per importance, one batch insert. A UUID stored
 * meanwhile (the same group sent twice at once): tried again once without it.</li>
 * </ul>
 */
@Service
@Log4j2
public class TillEventBatch {

	/** The most events one group may hold (the till sends 50). */
	public static final int MAX_EVENTS = 100;

	static final String SESSION_OF_ANOTHER_USER = "SESSION_OF_ANOTHER_USER";
	static final String SESSION_UNKNOWN = "SESSION_UNKNOWN";
	static final String USER_UNKNOWN = "USER_UNKNOWN";
	static final String MACHINE_UNKNOWN = "MACHINE_UNKNOWN";
	static final String FRONT_TIME_INVALID = "FRONT_TIME_INVALID";

	static final String NO_UUID = "NO_UUID";
	static final String UNKNOWN_CODE = "UNKNOWN_CODE";
	static final String NOT_FROM_TILL = "NOT_FROM_TILL";
	/** Step 8: a Step while only Notable and Alert are stored (queued before the change): refused, the till drops it. */
	static final String BELOW_LEVEL = "BELOW_LEVEL";

	/** A group of the till: the browser that made the events, and the events. */
	@Data
	public static class Request {
		private String installId;
		private List<Event> events;
	}

	/** One event of the till, as it made it (its clock, its number, its claims). */
	@Data
	public static class Event {
		private String uuid;
		private String code;
		/** The till's clock, ISO 8601 (with its offset, as Date.toISOString gives it). */
		private String at;
		/** The install's own number of the event: 1, 2, 3... */
		private Long seq;
		private Long userId;
		/** The user's login, when the till has no id (its userData keeps the login of the login screen). */
		private String userLogin;
		private Long sessionId;
		private Long posMachineId;
		private String cartId;
		private String subjectId;
		private String subjectLabel;
		private String oldValue;
		private String newValue;
		private Double amount;
		private String details;
	}

	/** The answer: what the till may drop (stored, duplicates, refused) and why a refused one was. */
	@Data
	public static class Answer {
		private final List<String> stored = new ArrayList<>();
		private final List<String> duplicates = new ArrayList<>();
		private final List<Refused> refused = new ArrayList<>();
	}

	@Data
	public static class Refused {
		private final String uuid;
		private final String reason;
	}

	private final EventLog eventLog;
	private final EventLogWriter writer;
	private final Function<Collection<String>, List<String>> storedUuids;
	private final Function<Collection<Long>, List<UserAccount>> usersById;
	private final Function<String, Optional<UserAccount>> userByLogin;
	private final Function<Collection<Long>, List<CashierSession>> sessionsById;
	private final Function<Collection<Long>, List<PosMachine>> machinesById;
	private final Function<String, Optional<PosMachine>> machineAt;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public TillEventBatch(EventLog eventLog, EventLogWriter writer, EventLogEntryRepository entries,
			UserAccountRepository users, CashierSessionRepository sessions, PosMachineRepository machines,
			SessionPosMachineService sessionMachines) {
		this(eventLog, writer, entries::storedUuids, users::findAllById, users::findByUsername, sessions::findAllById,
				machines::findAllById, sessionMachines::machineAt, LocalDateTime::now);
	}

	/** With the lookups given: used by the tests. */
	public TillEventBatch(EventLog eventLog, EventLogWriter writer, Function<Collection<String>, List<String>> storedUuids,
			Function<Collection<Long>, List<UserAccount>> usersById, Function<String, Optional<UserAccount>> userByLogin,
			Function<Collection<Long>, List<CashierSession>> sessionsById,
			Function<Collection<Long>, List<PosMachine>> machinesById, Function<String, Optional<PosMachine>> machineAt,
			Supplier<LocalDateTime> clock) {
		this.eventLog = eventLog;
		this.writer = writer;
		this.storedUuids = storedUuids;
		this.usersById = usersById;
		this.userByLogin = userByLogin;
		this.sessionsById = sessionsById;
		this.machinesById = machinesById;
		this.machineAt = machineAt;
		this.clock = clock;
	}

	public boolean isOn() {
		return eventLog.isOn();
	}

	/**
	 * Stores a group. {@code sender} is the logged user who sent it (the till's queue is sent by whoever is logged),
	 * {@code address} the request's. Throws when the database refuses the group twice: the till keeps it and sends it
	 * again later.
	 */
	public Answer receive(Request request, UserAccount sender, String address) {
		Answer answer = new Answer();
		List<Event> events = request.getEvents() != null ? request.getEvents() : new ArrayList<>();
		String installId = cut(request.getInstallId(), EventLogEntry.UUID_LENGTH);

		// The codes and the UUIDs: what is refused, what is twice in the group
		List<Event> accepted = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Event event : events) {
			if (event == null || blank(event.getUuid())) {
				answer.getRefused().add(new Refused(event != null ? event.getUuid() : null, NO_UUID));
				continue;
			}
			String uuid = event.getUuid().trim();
			EventCode code = codeOf(event.getCode());
			if (code == null || !EventCode.FROM_TILL.contains(code)) {
				String reason = code == null ? UNKNOWN_CODE : NOT_FROM_TILL;
				answer.getRefused().add(new Refused(uuid, reason));
				log.warn("Event log batch: {} refused ({}) from {} sent by {}", cut(event.getCode(), 60), reason, address,
						sender != null ? sender.getUsername() : "nobody");
				continue;
			}
			if (!eventLog.stores(code)) {
				answer.getRefused().add(new Refused(uuid, BELOW_LEVEL));
				continue;
			}
			if (uuid.length() > EventLogEntry.UUID_LENGTH || !seen.add(uuid)) {
				if (uuid.length() > EventLogEntry.UUID_LENGTH) {
					answer.getRefused().add(new Refused(uuid, NO_UUID));
				} else {
					answer.getDuplicates().add(uuid);
				}
				continue;
			}
			event.setUuid(uuid);
			accepted.add(event);
		}
		if (accepted.isEmpty()) {
			return answer;
		}

		// The claims, read once for the group
		Map<Long, UserAccount> users = byId(usersById, accepted.stream().map(Event::getUserId), UserAccount::getId);
		Map<String, UserAccount> usersByLogin = new HashMap<>();
		for (Event event : accepted) {
			String login = event.getUserId() == null ? trimmed(event.getUserLogin()) : null;
			if (login != null && !usersByLogin.containsKey(login.toLowerCase())) {
				usersByLogin.put(login.toLowerCase(), safely(() -> userByLogin.apply(login).orElse(null)));
			}
		}
		Map<Long, CashierSession> sessions = byId(sessionsById, accepted.stream().map(Event::getSessionId),
				CashierSession::getId);
		Map<Long, PosMachine> machines = byId(machinesById, accepted.stream().map(Event::getPosMachineId), PosMachine::getId);
		PosMachine machineOfAddress = accepted.stream().anyMatch(event -> event.getPosMachineId() == null)
				? safely(() -> machineAt.apply(address).orElse(null))
				: null;

		accepted.sort(Comparator.comparing(Event::getSeq, Comparator.nullsLast(Comparator.naturalOrder())));
		List<EventLogEntry> rows = new ArrayList<>();
		LocalDateTime now = clock.get();
		for (Event event : accepted) {
			rows.add(entryOf(event, installId, sender, address, now, users, usersByLogin, sessions, machines,
					machineOfAddress));
		}
		store(rows, answer);
		return answer;
	}

	/** Without the UUIDs stored already; tried again once when the database refuses the group (a UUID stored meanwhile). */
	private void store(List<EventLogEntry> rows, Answer answer) {
		List<EventLogEntry> fresh = withoutStored(rows, answer);
		try {
			writer.insertAll(fresh);
		} catch (DataIntegrityViolationException first) {
			log.info("Event log batch: tried again ({})", first.getMostSpecificCause().getMessage());
			fresh.forEach(row -> row.setSequenceNo(null));
			fresh = withoutStored(fresh, answer);
			writer.insertAll(fresh);
		}
		fresh.forEach(row -> answer.getStored().add(row.getEventUuid()));
	}

	private List<EventLogEntry> withoutStored(List<EventLogEntry> rows, Answer answer) {
		if (rows.isEmpty()) {
			return rows;
		}
		Set<String> stored = new HashSet<>(
				storedUuids.apply(rows.stream().map(EventLogEntry::getEventUuid).collect(Collectors.toList())));
		List<EventLogEntry> fresh = new ArrayList<>();
		for (EventLogEntry row : rows) {
			if (stored.contains(row.getEventUuid())) {
				answer.getDuplicates().add(row.getEventUuid());
			} else {
				fresh.add(row);
			}
		}
		return fresh;
	}

	private EventLogEntry entryOf(Event event, String installId, UserAccount sender, String address, LocalDateTime now,
			Map<Long, UserAccount> users, Map<String, UserAccount> usersByLogin, Map<Long, CashierSession> sessions,
			Map<Long, PosMachine> machines, PosMachine machineOfAddress) {
		EventCode code = EventCode.valueOf(event.getCode().trim());
		EventLogEntry entry = new EventLogEntry();
		Set<String> doubts = new LinkedHashSet<>();
		List<String> notes = new ArrayList<>();
		entry.setEventUuid(event.getUuid());
		entry.setCode(code.name());
		entry.setImportance(code.getImportance());
		entry.setSubjectType(code.getSubject().name());
		entry.setSubjectId(cut(event.getSubjectId(), EventLogEntry.SUBJECT_ID_LENGTH));
		entry.setSubjectLabel(cut(event.getSubjectLabel(), EventLogEntry.LABEL_LENGTH));
		entry.setEventAt(now);
		entry.setFrontAt(frontTime(event.getAt(), doubts));
		entry.setInstallId(installId);
		entry.setMachineSequenceNo(event.getSeq());
		entry.setCartId(cut(event.getCartId(), EventLogEntry.UUID_LENGTH));
		entry.setOldValue(cut(event.getOldValue(), EventLogEntry.VALUE_LENGTH));
		entry.setNewValue(cut(event.getNewValue(), EventLogEntry.VALUE_LENGTH));
		entry.setAmount(event.getAmount());

		// The user: the till's claim (its id, else its login), else the sender
		String login = trimmed(event.getUserLogin());
		UserAccount user = event.getUserId() != null ? users.get(event.getUserId())
				: login != null ? usersByLogin.get(login.toLowerCase()) : sender;
		if (user != null) {
			withUser(entry, user);
		} else {
			entry.setUserId(event.getUserId());
			entry.setUserLogin(cut(login, EventLogEntry.LOGIN_LENGTH));
			doubts.add(USER_UNKNOWN);
			notes.add(event.getUserId() != null ? "utilisateur n° " + event.getUserId() + " inconnu"
					: "utilisateur « " + login + " » inconnu");
		}
		if (sender != null) {
			entry.setSentById(sender.getId());
			entry.setSentByLogin(cut(sender.getUsername(), EventLogEntry.LOGIN_LENGTH));
		}
		entry.setClientAddress(cut(address, EventLogEntry.ADDRESS_LENGTH));

		// The session: the till's claim, checked against the user
		if (event.getSessionId() != null) {
			entry.setSessionId(event.getSessionId());
			CashierSession session = sessions.get(event.getSessionId());
			if (session == null) {
				doubts.add(SESSION_UNKNOWN);
				notes.add("session n° " + event.getSessionId() + " inconnue");
			} else {
				entry.setSessionNumber(cut(session.getSessionNumber(), EventLogEntry.SESSION_NUMBER_LENGTH));
				UserAccount owner = session.getCashier();
				if (owner != null && entry.getUserId() != null && !owner.getId().equals(entry.getUserId())) {
					doubts.add(SESSION_OF_ANOTHER_USER);
					notes.add("la session " + session.getSessionNumber() + " est celle de " + AccessEvents.who(owner)
							+ ", pas de " + (entry.getUserName() != null ? entry.getUserName() : entry.getUserLogin()));
				}
			}
		}

		// The POS machine: the till's claim, else the one registered at the request's address
		if (event.getPosMachineId() != null) {
			entry.setPosMachineId(event.getPosMachineId());
			PosMachine machine = machines.get(event.getPosMachineId());
			if (machine == null) {
				doubts.add(MACHINE_UNKNOWN);
				notes.add("poste n° " + event.getPosMachineId() + " inconnu");
			} else {
				entry.setPosMachineName(cut(machine.getName(), EventLogEntry.MACHINE_NAME_LENGTH));
			}
		} else if (machineOfAddress != null) {
			entry.setPosMachineId(machineOfAddress.getId());
			entry.setPosMachineName(cut(machineOfAddress.getName(), EventLogEntry.MACHINE_NAME_LENGTH));
		}
		if (doubts.contains(FRONT_TIME_INVALID)) {
			notes.add("heure de la caisse illisible");
		}

		String details = event.getDetails() != null ? event.getDetails().trim() : "";
		if (!notes.isEmpty()) {
			details = (details.isEmpty() ? "" : details + " ")
					+ AccessEvents.sentence("À vérifier : " + String.join(" ; ", notes));
		}
		entry.setDetails(cut(details.isEmpty() ? null : details, EventLogEntry.DETAILS_LENGTH));
		entry.setDoubt(doubts.isEmpty() ? null : cut(String.join(",", doubts), EventLogEntry.DOUBT_LENGTH));
		return entry;
	}

	private static void withUser(EventLogEntry entry, UserAccount user) {
		entry.setUserId(user.getId());
		entry.setUserLogin(cut(user.getUsername(), EventLogEntry.LOGIN_LENGTH));
		entry.setUserName(cut(user.getFullName(), EventLogEntry.NAME_LENGTH));
		entry.setUserRole(user.getAppRole() != null ? cut(user.getAppRole().getName(), EventLogEntry.LOGIN_LENGTH) : null);
	}

	/** The till's clock in the server's zone; null (and the doubt) when it cannot be read. */
	static LocalDateTime frontTime(String at, Set<String> doubts) {
		if (blank(at)) {
			return null;
		}
		try {
			return OffsetDateTime.parse(at.trim()).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
		} catch (DateTimeParseException withoutOffset) {
			try {
				return LocalDateTime.parse(at.trim());
			} catch (DateTimeParseException unreadable) {
				doubts.add(FRONT_TIME_INVALID);
				return null;
			}
		}
	}

	private static EventCode codeOf(String code) {
		if (blank(code)) {
			return null;
		}
		try {
			return EventCode.valueOf(code.trim());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static <T> Map<Long, T> byId(Function<Collection<Long>, List<T>> lookup,
			java.util.stream.Stream<Long> ids, Function<T, Long> idOf) {
		Set<Long> wanted = ids.filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		Map<Long, T> found = new HashMap<>();
		if (wanted.isEmpty()) {
			return found;
		}
		List<T> rows = safely(() -> lookup.apply(wanted));
		if (rows != null) {
			for (T row : rows) {
				found.put(idOf.apply(row), row);
			}
		}
		return found;
	}

	/** A lookup that fails leaves the claim unconfirmed (its doubt), never the group lost. */
	private static <T> T safely(Supplier<T> read) {
		try {
			return read.get();
		} catch (RuntimeException e) {
			log.debug("Event log batch: a lookup failed ({})", e.toString());
			return null;
		}
	}

	private static String cut(String text, int length) {
		return EventLog.cut(text, length);
	}

	private static String trimmed(String text) {
		return blank(text) ? null : text.trim();
	}

	private static boolean blank(String text) {
		return text == null || text.trim().isEmpty();
	}
}
