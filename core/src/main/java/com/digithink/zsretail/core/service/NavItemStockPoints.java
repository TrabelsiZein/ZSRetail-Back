package com.digithink.zsretail.core.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.digithink.zsretail.core.dto.NavItemVariantsDTO;

/**
 * Item variants (ZEN), step 11a: the point de stock part of the "NAV items" page, read from the head office's rows
 * (ho_stock_point_item). Implemented by the head office application only: on a store there is no such bean and the page
 * has no point de stock column.
 */
public interface NavItemStockPoints {

	/**
	 * NAV item number to the number of active points de stock where at least one of its variants has an active row. A
	 * number without such a point is absent.
	 */
	Map<String, Long> activePointCounts(Collection<String> navItemNos);

	/** Item id to its rows, point de stock id to the row (no entry: no row in that point). */
	Map<Long, Map<Long, NavItemVariantsDTO.PointPrice>> prices(Collection<Long> itemIds);

	/** The points de stock of these ids, in list order (sort order, code). */
	List<NavItemVariantsDTO.Point> points(Collection<Long> pointIds);
}
