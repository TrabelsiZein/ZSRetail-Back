package com.digithink.zsretail.store.holink.repository;

import java.util.Optional;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.holink.model.LinkJobState;

public interface LinkJobStateRepository extends _BaseRepository<LinkJobState, Long> {

	Optional<LinkJobState> findByCode(String code);
}
