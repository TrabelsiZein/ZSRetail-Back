package com.digithink.zsretail.headoffice.repository;

import com.digithink.zsretail.core.repository.ItemCatalogueQuery;

/**
 * Fiches items (3.1.0): what the head office adds to the item catalogue's list ({@link ItemCatalogueQuery}, in core
 * since 3.1.1 for the store's Articles page): the point de stock filter, written as an item condition, the count of
 * the points de stock (a flag of the page) and their choices. A store has no ho_stock_point table: these statements run
 * on a head office only.
 */
public final class HoItemCatalogueQuery {

	/** The number of points de stock (active or not): [stock_point_count]. */
	public static final String STOCK_POINT_COUNT = "SELECT COUNT(*) AS stock_point_count FROM ho_stock_point p";

	/** The points de stock in list order: [id, code, name]. */
	public static final String STOCK_POINTS = "SELECT p.id AS id, p.code AS code, p.name AS name FROM ho_stock_point p"
			+ " ORDER BY CASE WHEN p.sort_order IS NULL THEN 1 ELSE 0 END, p.sort_order, p.code";

	/** An item with an active row in the point de stock ({a}: the item row's alias). */
	static final String STOCK_POINT_CONDITION = "{a}.id IN (SELECT r.item_id FROM ho_stock_point_item r"
			+ " WHERE r.stock_point_id = :stockPointId AND (r.active IS NULL OR r.active = 1))";

	private HoItemCatalogueQuery() {
	}

	/** The point de stock filter on the criteria (nothing for null): a line shows when one of its items has a row. */
	public static ItemCatalogueQuery.Criteria stockPoint(ItemCatalogueQuery.Criteria criteria, Long stockPointId) {
		if (stockPointId != null) {
			criteria.getItemConditions().add(STOCK_POINT_CONDITION);
			criteria.getItemParameters().put("stockPointId", stockPointId);
		}
		return criteria;
	}
}
