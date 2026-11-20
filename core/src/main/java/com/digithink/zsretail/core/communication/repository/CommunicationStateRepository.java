package com.digithink.zsretail.core.communication.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.communication.model.CommunicationState;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.3.0: the communication module's state (comm_state): its activation instant and the finders' cursors. */
@Repository
public interface CommunicationStateRepository extends _BaseRepository<CommunicationState, Long> {

	Optional<CommunicationState> findByCode(String code);
}
