package com.digithink.zsretail.store.holink.repository;

import java.util.Optional;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.holink.model.LinkRight;

public interface LinkRightRepository extends _BaseRepository<LinkRight, Long> {

	Optional<LinkRight> findByCode(String code);
}
