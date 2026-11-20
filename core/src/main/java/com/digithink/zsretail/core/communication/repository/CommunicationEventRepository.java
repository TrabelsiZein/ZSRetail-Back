package com.digithink.zsretail.core.communication.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.3.0: the events of the communication module (comm_event). The queue reads come with the steps that use them. */
@Repository
public interface CommunicationEventRepository extends _BaseRepository<CommunicationEvent, Long> {

	Optional<CommunicationEvent> findByDedupKey(String dedupKey);

	/** Step 2: which of these sources already have an event of this source type (index ix_comm_event_source). */
	@Query("select e.sourceId from CommunicationEvent e where e.sourceType = :sourceType and e.sourceId in :sourceIds")
	List<Long> findSourceIds(@Param("sourceType") String sourceType, @Param("sourceIds") Collection<Long> sourceIds);
}
