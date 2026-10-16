package com.digithink.zsretail.core.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;

@Repository
public interface ItemSectionRepository extends _BaseRepository<ItemSection, Long> {

	Optional<ItemSection> findByCode(String code);

	Optional<ItemSection> findByErpExternalId(String erpExternalId);

	List<ItemSection> findByCodeIn(Collection<String> codes);

	List<ItemSection> findBySubFamilyCode(String subFamilyCode);

	@Query("select s.code from ItemSection s")
	List<String> findAllCodes();

	/** Item variants (ZEN), step 11b: the table has a row, active or not (GET /config itemSections). */
	boolean existsByCodeIsNotNull();

	/** Item variants (ZEN), step 7: the origin of one row (the column is not updatable through a save), as the families. */
	@Modifying(flushAutomatically = true)
	@Query("update ItemSection x set x.origin = :origin where x.id = :id")
	int setOrigin(@Param("id") Long id, @Param("origin") RecordOrigin origin);
}
