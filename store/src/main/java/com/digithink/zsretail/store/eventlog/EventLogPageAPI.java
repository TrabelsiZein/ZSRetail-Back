package com.digithink.zsretail.store.eventlog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.eventlog.EventSubject;
import com.digithink.zsretail.core.eventlog.service.EventLog;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.repository.AppRoleRepository;
import com.digithink.zsretail.core.repository.UserAccountRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.repository.PosMachineRepository;
import com.digithink.zsretail.store.service.JournalExcel;

import lombok.extern.log4j.Log4j2;

/**
 * 3.4.0, event log step 6: the Journal page ("Journal des événements"), read only (EventLogReader). Looking at it or
 * exporting it is not an event.
 * <ul>
 * <li>GET /event-log?from&amp;to&amp;importances&amp;userId&amp;authorisedById&amp;badgeOwnerId&amp;posMachineId&amp;
 * sessionId&amp;sessionIds&amp;newValues&amp;sessionNumber&amp;subjectType&amp;subjectId&amp;cartId&amp;codes&amp;text&amp;doubtOnly&amp;groupKey&amp;page&amp;size:
 * one page of the lines (EventLogPage); from and to (YYYY-MM-DD) required; importances and codes separated by commas;
 * groupKey: the events of one run of refused logins.</li>
 * <li>GET /event-log/excel, the same filters: the file of the events (EventLogExcel).</li>
 * <li>GET /event-log/catalogue: what the filters offer (the codes with their importance and French label, the subjects,
 * every user, the disabled ones included, the POS machines and, step 7, the roles' names).</li>
 * <li>GET /event-log/missing: the numbers missing per importance (a row deleted in the database).</li>
 * </ul>
 * 404 when eventlog.enabled is off; 403 without {@link EventLog#PAGE_PERMISSION}; 400 for filters that do not parse.
 */
@RestController
@RequestMapping("event-log")
@Log4j2
public class EventLogPageAPI {

	public static final String READ_PERMISSION = EventLog.PAGE_PERMISSION;

	@Autowired
	private EventLog eventLog;

	@Autowired
	private EventLogReader reader;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@Autowired
	private UserAccountRepository users;

	@Autowired
	private PosMachineRepository machines;

	@Autowired
	private AppRoleRepository roles;

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> page(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String importances, @RequestParam(required = false) Long userId,
			@RequestParam(required = false) Long authorisedById, @RequestParam(required = false) Long badgeOwnerId,
			@RequestParam(required = false) Long posMachineId, @RequestParam(required = false) Long sessionId,
			@RequestParam(required = false) String sessionNumber, @RequestParam(required = false) String subjectType,
			@RequestParam(required = false) String subjectId, @RequestParam(required = false) String cartId,
			@RequestParam(required = false) String codes, @RequestParam(required = false) String text,
			@RequestParam(defaultValue = "false") boolean doubtOnly, @RequestParam(required = false) String groupKey,
			@RequestParam(required = false) String sessionIds, @RequestParam(required = false) String newValues,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + EventLogReader.PAGE_SIZE) int size) {
		ResponseEntity<?> refused = refused();
		if (refused != null) {
			return refused;
		}
		try {
			EventLogFilter filter = EventLogFilter.of(from, to, importances, userId, authorisedById, badgeOwnerId,
					posMachineId, sessionId, sessionNumber, subjectType, subjectId, cartId, codes, text, doubtOnly, groupKey,
					sessionIds, newValues);
			return ResponseEntity.ok(reader.page(filter, page, size));
		} catch (EventLogFilter.Refused e) {
			return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(error(e.getMessage()));
		}
	}

	@GetMapping("/excel")
	public ResponseEntity<?> excel(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String importances, @RequestParam(required = false) Long userId,
			@RequestParam(required = false) Long authorisedById, @RequestParam(required = false) Long badgeOwnerId,
			@RequestParam(required = false) Long posMachineId, @RequestParam(required = false) Long sessionId,
			@RequestParam(required = false) String sessionNumber, @RequestParam(required = false) String subjectType,
			@RequestParam(required = false) String subjectId, @RequestParam(required = false) String cartId,
			@RequestParam(required = false) String codes, @RequestParam(required = false) String text,
			@RequestParam(defaultValue = "false") boolean doubtOnly, @RequestParam(required = false) String groupKey,
			@RequestParam(required = false) String sessionIds, @RequestParam(required = false) String newValues) {
		ResponseEntity<?> refused = refused();
		if (refused != null) {
			return refused;
		}
		try {
			EventLogFilter filter = EventLogFilter.of(from, to, importances, userId, authorisedById, badgeOwnerId,
					posMachineId, sessionId, sessionNumber, subjectType, subjectId, cartId, codes, text, doubtOnly, groupKey,
					sessionIds, newValues);
			List<EventLogRow> rows = reader.all(filter, EventLogReader.EXCEL_LIMIT);
			long total = rows.size() < EventLogReader.EXCEL_LIMIT ? rows.size() : reader.countAll(filter);
			byte[] file = EventLogExcel.of(filter, rows, total);
			return ResponseEntity.ok()
					.header(HttpHeaders.CONTENT_DISPOSITION,
							"attachment; filename=\"" + EventLogExcel.fileName(filter.getFrom(), filter.getTo()) + "\"")
					.contentType(MediaType.parseMediaType(JournalExcel.CONTENT_TYPE)).body(file);
		} catch (EventLogFilter.Refused e) {
			return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(error(e.getMessage()));
		} catch (Exception e) {
			log.error("EventLogPageAPI::excel:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body(error(e.getMessage()));
		}
	}

	@GetMapping(value = "/catalogue", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> catalogue() {
		ResponseEntity<?> refused = refused();
		if (refused != null) {
			return refused;
		}
		Map<String, Object> answer = new LinkedHashMap<>();
		List<Map<String, Object>> codes = new ArrayList<>();
		for (EventCode code : EventCode.values()) {
			Map<String, Object> c = new LinkedHashMap<>();
			c.put("code", code.name());
			c.put("subjectType", code.getSubject().name());
			c.put("importance", code.getImportance());
			c.put("label", code.getLabel());
			codes.add(c);
		}
		answer.put("codes", codes);
		List<String> subjects = new ArrayList<>();
		for (EventSubject subject : EventSubject.values()) {
			subjects.add(subject.name());
		}
		answer.put("subjects", subjects);
		List<Map<String, Object>> people = new ArrayList<>();
		List<UserAccount> all = new ArrayList<>(users.findAll());
		all.sort(Comparator.comparing(u -> String.valueOf(u.getFullName() != null ? u.getFullName() : u.getUsername())
				.toLowerCase()));
		for (UserAccount u : all) {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("id", u.getId());
			p.put("login", u.getUsername());
			p.put("name", u.getFullName());
			p.put("active", !Boolean.FALSE.equals(u.getActive()));
			people.add(p);
		}
		answer.put("users", people);
		List<Map<String, Object>> posMachines = new ArrayList<>();
		for (PosMachine m : machines.findAll()) {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("id", m.getId());
			p.put("name", m.getName());
			posMachines.add(p);
		}
		posMachines.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
		answer.put("posMachines", posMachines);
		// step 7: the roles' names (user_role holds the role's code, POS_USER; the window shows "Caissier")
		List<Map<String, Object>> roleNames = new ArrayList<>();
		for (AppRole role : roles.findAll()) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("name", role.getName());
			r.put("label", role.getLabel());
			roleNames.add(r);
		}
		answer.put("roles", roleNames);
		return ResponseEntity.ok(answer);
	}

	@GetMapping(value = "/missing", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> missing() {
		ResponseEntity<?> refused = refused();
		if (refused != null) {
			return refused;
		}
		return ResponseEntity.ok(reader.missing());
	}

	/**
	 * 404 when the module is off (the page is hidden), 403 without the page's right; null when allowed. JSON, the Excel
	 * call's too (it produces a file otherwise).
	 */
	private ResponseEntity<?> refused() {
		if (!eventLog.isOn()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
					.body(error("The event log is off on this store (eventlog.enabled)."));
		}
		if (!currentUserProvider.currentUserHasPermission(READ_PERMISSION)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(error("Permission required: " + READ_PERMISSION));
		}
		return null;
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}
}
