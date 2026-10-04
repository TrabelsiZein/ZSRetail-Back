package com.digithink.zsretail.headoffice.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.HoPriceListLine;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoPriceListLineRepository extends _BaseRepository<HoPriceListLine, Long> {

	List<HoPriceListLine> findByPriceListId(Long priceListId);

	List<HoPriceListLine> findByPriceListIdAndItemIdIn(Long priceListId, Collection<Long> itemIds);

	Optional<HoPriceListLine> findByPriceListIdAndItemId(Long priceListId, Long itemId);

	List<HoPriceListLine> findByItemId(Long itemId);

	long countByPriceListId(Long priceListId);

	/** The item codes of a list's lines (a store's list change sends them again). */
	@Query("select i.itemCode from HoPriceListLine l, Item i where l.itemId = i.id and l.priceListId = :listId")
	List<String> findItemCodes(@Param("listId") Long listId);

	/**
	 * [line, item] of a list, by item code; search (lower case, with % around) on the item code and name, null for
	 * every line.
	 */
	@Query(value = "select l, i from HoPriceListLine l, Item i where l.itemId = i.id and l.priceListId = :listId"
			+ " and (:search is null or lower(i.itemCode) like :search or lower(i.name) like :search) order by i.itemCode",
			countQuery = "select count(l) from HoPriceListLine l, Item i where l.itemId = i.id and l.priceListId = :listId"
					+ " and (:search is null or lower(i.itemCode) like :search or lower(i.name) like :search)")
	Page<Object[]> findLines(@Param("listId") Long listId, @Param("search") String search, Pageable page);
}
