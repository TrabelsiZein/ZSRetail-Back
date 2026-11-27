package com.digithink.zsretail.core.convention.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.convention.model.Convention;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.6.0, conventions step 1: the conventions. The service of part B3 adds its queries here. */
@Repository
public interface ConventionRepository extends _BaseRepository<Convention, Long> {

	/** The code as stored (the service upper-cases it first). */
	Optional<Convention> findByCode(String code);
}
