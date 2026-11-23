package com.digithink.zsretail.core.eventlog.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.eventlog.model.EventLogEntry;

/** 3.4.0, event log: inserts only (EventLogWriter); the reads of the Journal page come with it. */
public interface EventLogEntryRepository extends JpaRepository<EventLogEntry, Long> {

	/** Step 4: which of these UUIDs are stored already (a till's group sent again); uk_event_log_uuid answers it. */
	@Query("select e.eventUuid from EventLogEntry e where e.eventUuid in :uuids")
	List<String> storedUuids(@Param("uuids") Collection<String> uuids);
}
