package com.digithink.zsretail.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.enumeration.RecordOrigin;

@Repository
public interface ItemSubFamilyRepository extends _BaseRepository<ItemSubFamily, Long> {

	Optional<ItemSubFamily> findByCode(String code);

	List<ItemSubFamily> findByItemFamily(ItemFamily itemFamily);

	Optional<ItemSubFamily> findByErpExternalId(String erpExternalId);

	/** Head office plan, step 6: the sub-families of these codes. */
	List<ItemSubFamily> findByCodeIn(Collection<String> codes);

	/** Step 6: every sub-family code (the startup backfill of the catalogue copies). */
	@Query("select s.code from ItemSubFamily s")
	List<String> findAllCodes();

	/**
	 * Head office plan, step 6: the origin of one row (the column is not updatable through a save). Pending changes are
	 * flushed first. Returns the number of rows updated.
	 */
	@Modifying(flushAutomatically = true)
	@Query("update ItemSubFamily x set x.origin = :origin where x.id = :id")
	int setOrigin(@Param("id") Long id, @Param("origin") RecordOrigin origin);
}
