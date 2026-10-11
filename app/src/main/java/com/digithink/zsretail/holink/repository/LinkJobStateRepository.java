package com.digithink.zsretail.holink.repository;

import java.util.Optional;

import com.digithink.zsretail.holink.model.LinkJobState;
import com.digithink.zsretail.repository._BaseRepository;

public interface LinkJobStateRepository extends _BaseRepository<LinkJobState, Long> {

	Optional<LinkJobState> findByCode(String code);
}
