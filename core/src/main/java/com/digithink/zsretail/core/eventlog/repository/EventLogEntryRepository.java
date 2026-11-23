package com.digithink.zsretail.core.eventlog.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.digithink.zsretail.core.eventlog.model.EventLogEntry;

/** 3.4.0, event log: inserts only (EventLogWriter); the reads of the Journal page come with it. */
public interface EventLogEntryRepository extends JpaRepository<EventLogEntry, Long> {
}
