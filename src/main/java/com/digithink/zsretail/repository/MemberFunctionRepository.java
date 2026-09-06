package com.digithink.zsretail.repository;

import java.util.Optional;

import com.digithink.zsretail.model.MemberFunction;

public interface MemberFunctionRepository extends _BaseRepository<MemberFunction, Long> {

	Optional<MemberFunction> findByCode(String code);

}
