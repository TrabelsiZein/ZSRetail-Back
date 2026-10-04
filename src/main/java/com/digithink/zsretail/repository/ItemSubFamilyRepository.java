package com.digithink.zsretail.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;

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
}
