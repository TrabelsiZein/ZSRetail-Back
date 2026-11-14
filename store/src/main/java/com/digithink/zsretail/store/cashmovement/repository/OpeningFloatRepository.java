package com.digithink.zsretail.store.cashmovement.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.digithink.zsretail.store.cashmovement.model.OpeningFloat;

/** 3.2.0, the opening amount: the floats proposed at the openings. */
public interface OpeningFloatRepository extends JpaRepository<OpeningFloat, Long> {

	List<OpeningFloat> findBySessionIdIn(Collection<Long> sessionIds);

	/** The floats of the sessions opened on a day (the Journal's day). */
	List<OpeningFloat> findBySession_OpenedAtGreaterThanEqualAndSession_OpenedAtLessThanOrderBySession_OpenedAtAsc(
			LocalDateTime from, LocalDateTime to);
}
