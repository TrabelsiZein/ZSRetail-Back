package com.digithink.zsretail.core.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;

@Repository
public interface ItemCategoryRepository extends _BaseRepository<ItemCategory, Long> {

	Optional<ItemCategory> findByCode(String code);

	Optional<ItemCategory> findByErpExternalId(String erpExternalId);

	List<ItemCategory> findByCodeIn(Collection<String> codes);

	@Query("select c.code from ItemCategory c")
	List<String> findAllCodes();

	/** Item variants (ZEN), step 7: the origin of one row (the column is not updatable through a save), as the families. */
	@Modifying(flushAutomatically = true)
	@Query("update ItemCategory x set x.origin = :origin where x.id = :id")
	int setOrigin(@Param("id") Long id, @Param("origin") RecordOrigin origin);
}
