package com.digithink.zsretail.headoffice.repository;

import java.util.Optional;

import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.repository._BaseRepository;

public interface StoreRepository extends _BaseRepository<Store, Long> {

	Optional<Store> findByCodeIgnoreCase(String code);
}
