package com.digithink.zsretail.core.communication.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.communication.enumeration.CommunicationEventStatus;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.3.0: the events of the communication module (comm_event). The queue reads come with the steps that use them. */
@Repository
public interface CommunicationEventRepository extends _BaseRepository<CommunicationEvent, Long> {

	Optional<CommunicationEvent> findByDedupKey(String dedupKey);

	/** Step 2: which of these sources already have an event of this source type (index ix_comm_event_source). */
	@Query("select e.sourceId from CommunicationEvent e where e.sourceType = :sourceType and e.sourceId in :sourceIds")
	List<Long> findSourceIds(@Param("sourceType") String sourceType, @Param("sourceIds") Collection<Long> sourceIds);

	/**
	 * Step 3: the events to handle after {@code afterId}, by id: PENDING, and ERROR with fewer than {@code maxAttempts}
	 * attempts (index ix_comm_event_queue).
	 */
	@Query("select e.id from CommunicationEvent e where (e.status = :pending or (e.status = :error and e.attempts < :maxAttempts))"
			+ " and e.id > :afterId order by e.id")
	List<Long> findToProcess(@Param("pending") CommunicationEventStatus pending,
			@Param("error") CommunicationEventStatus error, @Param("maxAttempts") int maxAttempts,
			@Param("afterId") long afterId, Pageable page);

	/**
	 * Step 6, a store whose loyalty is the head office's: the events still to send (in {@code statuses}) that happened
	 * before {@code before}, by id (they end quietly).
	 */
	@Query("select e from CommunicationEvent e where e.status in :statuses and e.occurredAt < :before order by e.id")
	List<CommunicationEvent> findOlderThan(@Param("statuses") java.util.Collection<CommunicationEventStatus> statuses,
			@Param("before") java.time.LocalDateTime before, Pageable page);
}
