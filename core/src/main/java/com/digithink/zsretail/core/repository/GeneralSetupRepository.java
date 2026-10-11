package com.digithink.zsretail.core.repository;

import java.util.Optional;

import com.digithink.zsretail.core.model.GeneralSetup;

public interface GeneralSetupRepository extends _BaseRepository<GeneralSetup, Long> {

	Optional<GeneralSetup> findByCode(String code);
}

