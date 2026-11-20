package com.digithink.zsretail.core.communication.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.3.0: the events of the communication module (comm_event). The queue reads come with the steps that use them. */
@Repository
public interface CommunicationEventRepository extends _BaseRepository<CommunicationEvent, Long> {

	Optional<CommunicationEvent> findByDedupKey(String dedupKey);
}
