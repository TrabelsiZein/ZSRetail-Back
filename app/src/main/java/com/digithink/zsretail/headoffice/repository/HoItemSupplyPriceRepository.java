package com.digithink.zsretail.headoffice.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.HoItemSupplyPrice;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoItemSupplyPriceRepository extends _BaseRepository<HoItemSupplyPrice, Long> {

	Optional<HoItemSupplyPrice> findByItemId(Long itemId);

	List<HoItemSupplyPrice> findByItemIdIn(Collection<Long> itemIds);

	/**
	 * [item, base supply price or null] of the products and packs (or no type), not the excluded code, by item code;
	 * search (lower case, with % around) on the code and the name, null for every item.
	 */
	@Query(value = "select i, s.price from Item i left join HoItemSupplyPrice s on s.itemId = i.id"
			+ " where (i.type is null or i.type in :types) and i.itemCode <> :excluded"
			+ " and (:search is null or lower(i.itemCode) like :search or lower(i.name) like :search) order by i.itemCode",
			countQuery = "select count(i) from Item i where (i.type is null or i.type in :types) and i.itemCode <> :excluded"
					+ " and (:search is null or lower(i.itemCode) like :search or lower(i.name) like :search)")
	Page<Object[]> findPage(@Param("types") Collection<ItemType> types, @Param("excluded") String excluded,
			@Param("search") String search, Pageable page);
}
