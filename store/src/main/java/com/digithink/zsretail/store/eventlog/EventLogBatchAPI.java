package com.digithink.zsretail.store.eventlog;

import java.util.Collections;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.security.CurrentUserProvider;

import lombok.extern.log4j.Log4j2;

/**
 * 3.4.0, event log step 4: POST /event-log/batch, the till's queue sent in groups (TillEventBatch). 404 {error} when the
 * module is off (the till then keeps its queue and stops sending); 400 above {@link TillEventBatch#MAX_EVENTS} events;
 * 200 {stored, duplicates, refused} otherwise, the till dropping all three; 503 when the database refuses the group (the
 * till sends it again later). A logged user is needed; the licence is not (LicenseFilter lets it pass, as /login).
 */
@RestController
@Log4j2
public class EventLogBatchAPI {

	@Autowired
	private TillEventBatch batch;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@PostMapping(value = "/event-log/batch", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> receive(@RequestBody TillEventBatch.Request request, HttpServletRequest http) {
		if (!batch.isOn()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(Collections.singletonMap("error", "The event log is off on this store (eventlog.enabled)."));
		}
		if (request.getEvents() != null && request.getEvents().size() > TillEventBatch.MAX_EVENTS) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("error",
					"At most " + TillEventBatch.MAX_EVENTS + " events per group, " + request.getEvents().size() + " sent."));
		}
		try {
			return ResponseEntity.ok(batch.receive(request, currentUserProvider.getCurrentUser(), http.getRemoteAddr()));
		} catch (RuntimeException e) {
			log.warn("EventLogBatchAPI::receive: group not stored ({})", e.toString());
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.body(Collections.singletonMap("error", "The events were not stored; send them again later."));
		}
	}
}
