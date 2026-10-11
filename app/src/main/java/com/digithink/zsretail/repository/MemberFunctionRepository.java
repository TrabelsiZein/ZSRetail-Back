package com.digithink.zsretail.repository;

import java.util.Optional;

import com.digithink.zsretail.model.MemberFunction;

public interface MemberFunctionRepository extends _BaseRepository<MemberFunction, Long> {

	Optional<MemberFunction> findByCode(String code);

	/** 2.2.2: the active functions (active not false, as the screens list them). */
	@org.springframework.data.jpa.repository.Query("select count(f) from MemberFunction f where f.active is null or f.active = true")
	long countActive();

}
