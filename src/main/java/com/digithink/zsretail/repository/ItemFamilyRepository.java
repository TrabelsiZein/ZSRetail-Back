package com.digithink.zsretail.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.model.ItemFamily;

@Repository
public interface ItemFamilyRepository extends _BaseRepository<ItemFamily, Long> {

	Optional<ItemFamily> findByCode(String code);

	Optional<ItemFamily> findByErpExternalId(String erpExternalId);
}


