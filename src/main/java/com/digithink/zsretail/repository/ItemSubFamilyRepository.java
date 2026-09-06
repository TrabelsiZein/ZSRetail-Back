package com.digithink.zsretail.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;

@Repository
public interface ItemSubFamilyRepository extends _BaseRepository<ItemSubFamily, Long> {

	Optional<ItemSubFamily> findByCode(String code);

	List<ItemSubFamily> findByItemFamily(ItemFamily itemFamily);

	Optional<ItemSubFamily> findByErpExternalId(String erpExternalId);
}


