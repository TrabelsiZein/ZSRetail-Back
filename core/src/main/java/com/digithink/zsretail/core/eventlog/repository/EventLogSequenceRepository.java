package com.digithink.zsretail.core.eventlog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.eventlog.model.EventLogSequence;

public interface EventLogSequenceRepository extends JpaRepository<EventLogSequence, Long> {

	/** Adds 1 to an importance's number; the row stays locked until the transaction ends. 0 when it has no row yet. */
	@Modifying(flushAutomatically = true)
	@Query("update EventLogSequence s set s.lastValue = s.lastValue + 1 where s.importance = :importance")
	int increment(@Param("importance") int importance);

	/** The importance's last number (as written by this transaction); empty when it has no row. */
	@Query("select s.lastValue from EventLogSequence s where s.importance = :importance")
	List<Long> lastValue(@Param("importance") int importance);
}
