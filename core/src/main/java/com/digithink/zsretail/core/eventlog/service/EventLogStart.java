package com.digithink.zsretail.core.eventlog.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.eventlog.EventCode;

/**
 * 3.4.0, the event log logs itself: one EVENTLOG_STARTED at each start with the module on. A long time between two of
 * them, with no event in between, shows the module was off (eventlog.enabled is a line of a file, its change leaves no
 * other trace). Nothing with the module off; a failure never stops the start (EventLog).
 */
@Component
public class EventLogStart {

	private final EventLog eventLog;
	private final String appVersion;

	public EventLogStart(EventLog eventLog, @Value("${app.version:unknown}") String appVersion) {
		this.eventLog = eventLog;
		this.appVersion = appVersion;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void started() {
		eventLog.writeNow(NewEvent.of(EventCode.EVENTLOG_STARTED).subject("EVENT_LOG", "Journal des événements")
				.details("Event log on, version " + appVersion));
	}
}
