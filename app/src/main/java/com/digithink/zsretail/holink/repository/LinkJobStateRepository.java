package com.digithink.zsretail.holink.repository;

import java.util.Optional;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.holink.model.LinkJobState;

public interface LinkJobStateRepository extends _BaseRepository<LinkJobState, Long> {

	Optional<LinkJobState> findByCode(String code);
}
