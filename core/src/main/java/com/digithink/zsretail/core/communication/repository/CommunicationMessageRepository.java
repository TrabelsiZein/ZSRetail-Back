package com.digithink.zsretail.core.communication.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.3.0: the messages of the communication module (comm_message). The queue reads come with the steps that use them. */
@Repository
public interface CommunicationMessageRepository extends _BaseRepository<CommunicationMessage, Long> {

	Optional<CommunicationMessage> findByDedupKey(String dedupKey);
}
