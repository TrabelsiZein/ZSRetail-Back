package com.digithink.zsretail.holink.repository;

import java.util.Optional;

import com.digithink.zsretail.holink.model.LinkRight;
import com.digithink.zsretail.repository._BaseRepository;

public interface LinkRightRepository extends _BaseRepository<LinkRight, Long> {

	Optional<LinkRight> findByCode(String code);
}
