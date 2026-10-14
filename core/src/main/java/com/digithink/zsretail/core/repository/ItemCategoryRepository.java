package com.digithink.zsretail.core.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.model.ItemCategory;

@Repository
public interface ItemCategoryRepository extends _BaseRepository<ItemCategory, Long> {

	Optional<ItemCategory> findByCode(String code);

	Optional<ItemCategory> findByErpExternalId(String erpExternalId);

	List<ItemCategory> findByCodeIn(Collection<String> codes);

	@Query("select c.code from ItemCategory c")
	List<String> findAllCodes();
}
