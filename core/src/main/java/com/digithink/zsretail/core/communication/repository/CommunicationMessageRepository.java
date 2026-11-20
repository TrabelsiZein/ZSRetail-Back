package com.digithink.zsretail.core.communication.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.3.0: the messages of the communication module (comm_message). */
@Repository
public interface CommunicationMessageRepository extends _BaseRepository<CommunicationMessage, Long> {

	Optional<CommunicationMessage> findByDedupKey(String dedupKey);

	/**
	 * Step 3: the messages due after {@code afterId}, by id: PENDING, with no next attempt or one not after {@code now}
	 * (index ix_comm_message_queue).
	 */
	@Query("select m from CommunicationMessage m where m.status = :pending"
			+ " and (m.nextAttemptAt is null or m.nextAttemptAt <= :now) and m.id > :afterId order by m.id")
	List<CommunicationMessage> findDue(@Param("pending") CommunicationMessageStatus pending,
			@Param("now") LocalDateTime now, @Param("afterId") long afterId, Pageable page);
}
