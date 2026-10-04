package com.digithink.zsretail.holink.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.holink.model.StockCopy;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.repository._BaseRepository;

public interface StockCopyRepository extends _BaseRepository<StockCopy, Long> {

	/**
	 * [id, itemCode, name, stockQuantity, origin] of the items whose stock is to send: a product or a pack (or no type),
	 * not the excluded code, and no row with the same quantity (null stock counts as 0); by item id.
	 */
	@Query("select i.id, i.itemCode, i.name, i.stockQuantity, i.origin from Item i where (i.type is null or i.type in :types)"
			+ " and i.itemCode <> :excluded and not exists (select s.id from StockCopy s where s.itemId = i.id"
			+ " and s.quantitySent = coalesce(i.stockQuantity, 0)) order by i.id")
	List<Object[]> findToSend(@Param("types") Collection<ItemType> types, @Param("excluded") String excluded,
			Pageable page);

	/** How many items have a stock to send (same rule as {@link #findToSend}). */
	@Query("select count(i) from Item i where (i.type is null or i.type in :types) and i.itemCode <> :excluded"
			+ " and not exists (select s.id from StockCopy s where s.itemId = i.id"
			+ " and s.quantitySent = coalesce(i.stockQuantity, 0))")
	long countToSend(@Param("types") Collection<ItemType> types, @Param("excluded") String excluded);

	/** The rows whose item no longer exists here: sent as removed. */
	@Query("select s from StockCopy s where not exists (select i.id from Item i where i.id = s.itemId) order by s.id")
	List<StockCopy> findRemoved(Pageable page);

	List<StockCopy> findByItemIdIn(Collection<Long> itemIds);

	/** The last push accepted; empty list when none. */
	@Query("select max(s.sentAt) from StockCopy s")
	List<LocalDateTime> lastSentAt();
}
