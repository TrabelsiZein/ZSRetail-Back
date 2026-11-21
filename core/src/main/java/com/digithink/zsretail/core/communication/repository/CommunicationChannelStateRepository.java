package com.digithink.zsretail.core.communication.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.communication.model.CommunicationChannelState;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.3.0, step 8a part 2: each channel's pause and measured speed (comm_channel_state). */
@Repository
public interface CommunicationChannelStateRepository extends _BaseRepository<CommunicationChannelState, Long> {

	Optional<CommunicationChannelState> findByChannel(String channel);
}
