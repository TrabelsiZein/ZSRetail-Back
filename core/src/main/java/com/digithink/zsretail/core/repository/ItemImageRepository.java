package com.digithink.zsretail.core.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.model.ItemImage;

/** 3.1.1: item_image, read only for now (docs/modules/store-orders.md). */
@Repository
public interface ItemImageRepository extends _BaseRepository<ItemImage, Long> {

	/** The order of an item's images: the main one first, then by sort order, then by id. */
	String IMAGE_ORDER = " order by case when m.main = true then 0 else 1 end, m.sortOrder, m.id";

	@Query("select m from ItemImage m where m.itemId = :itemId" + IMAGE_ORDER)
	List<ItemImage> findOfItem(@Param("itemId") Long itemId);

	/** The images of these items (at most 2,000 ids per call), each item's in {@link #IMAGE_ORDER}. */
	@Query("select m from ItemImage m where m.itemId in :itemIds" + IMAGE_ORDER)
	List<ItemImage> findOfItems(@Param("itemIds") Collection<Long> itemIds);
}
