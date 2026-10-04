package com.digithink.zsretail.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.model.ItemFamily;

@Repository
public interface ItemFamilyRepository extends _BaseRepository<ItemFamily, Long> {

	Optional<ItemFamily> findByCode(String code);

	Optional<ItemFamily> findByErpExternalId(String erpExternalId);

	/** Head office plan, step 6: the families of these codes. */
	List<ItemFamily> findByCodeIn(Collection<String> codes);

	/** Step 6: every family code (the startup backfill of the catalogue copies). */
	@Query("select f.code from ItemFamily f")
	List<String> findAllCodes();
}
