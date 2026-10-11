package com.digithink.zsretail.repository;

import java.util.Optional;

import com.digithink.zsretail.model.GeneralSetup;

public interface GeneralSetupRepository extends _BaseRepository<GeneralSetup, Long> {

	Optional<GeneralSetup> findByCode(String code);
}

