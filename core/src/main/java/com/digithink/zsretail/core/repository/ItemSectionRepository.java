package com.digithink.zsretail.core.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.model.ItemSection;

@Repository
public interface ItemSectionRepository extends _BaseRepository<ItemSection, Long> {

	Optional<ItemSection> findByCode(String code);

	Optional<ItemSection> findByErpExternalId(String erpExternalId);

	List<ItemSection> findByCodeIn(Collection<String> codes);

	List<ItemSection> findBySubFamilyCode(String subFamilyCode);

	@Query("select s.code from ItemSection s")
	List<String> findAllCodes();
}
