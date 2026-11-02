package com.digithink.zsretail.headoffice.repository;

import com.digithink.zsretail.core.repository.ItemCatalogueQuery;

/**
 * Fiches items (3.1.0): what the head office adds to the item catalogue's list ({@link ItemCatalogueQuery}, in core
 * since 3.1.1 for the store's Articles page): the point de stock filter, written as an item condition, the count of
 * the points de stock (a flag of the page) and their choices. A store has no ho_stock_point table: these statements run
 * on a head office only.
 * <p>
 * 3.1.1, step 1 of retiring "Items by point de stock": with a point chosen, the row status at that point (ACTIVE, the
 * default, INACTIVE or ALL) and the price range on the point's row (its selling price with the item's VAT) instead of
 * the item's own price. Without a point, nothing changes.
 */
public final class HoItemCatalogueQuery {

	/** The number of points de stock (active or not): [stock_point_count]. */
	public static final String STOCK_POINT_COUNT = "SELECT COUNT(*) AS stock_point_count FROM ho_stock_point p";

	/** The points de stock in list order: [id, code, name]. */
	public static final String STOCK_POINTS = "SELECT p.id AS id, p.code AS code, p.name AS name FROM ho_stock_point p"
			+ " ORDER BY CASE WHEN p.sort_order IS NULL THEN 1 ELSE 0 END, p.sort_order, p.code";

	/** An item with a row in the point de stock ({a}: the item row's alias); the row conditions follow. */
	static final String STOCK_POINT_ROWS = "{a}.id IN (SELECT r.item_id FROM ho_stock_point_item r"
			+ " WHERE r.stock_point_id = :stockPointId";

	/** Row status ACTIVE: an active row (a null active counts as active, as everywhere); {r}: the row's alias. */
	static final String ROW_ACTIVE = " AND ({r}.active IS NULL OR {r}.active = 1)";

	/** Row status INACTIVE: an inactive row. */
	static final String ROW_INACTIVE = " AND {r}.active = 0";

	/**
	 * The row's selling price with the item's VAT: the clearance price when it applies (StockPointPrice: clearance true
	 * and a clearance price above 0), else the row's price; both before VAT, as the item's own price of the list. {r}: the
	 * row's alias, {a}: the item's.
	 */
	static final String ROW_PRICE = "((CASE WHEN {r}.clearance = 1 AND {r}.clearance_price > 0 THEN {r}.clearance_price"
			+ " ELSE {r}.unit_price END) * (1 + COALESCE({a}.defaultvat, 0) / 100.0))";

	/**
	 * 3.1.1, step 2: the row of the line's item at the point, of the row status (alias pr), joined to the lines so that
	 * their price_min / price_max and the price sort read its selling price ({@link #LINE_PRICE}); one row per item and
	 * point (uk_ho_stock_point_item), so no line counts an item twice.
	 */
	static final String LINE_PRICE_JOIN = " LEFT JOIN ho_stock_point_item pr ON pr.item_id = i.id"
			+ " AND pr.stock_point_id = :stockPointId";

	/** The line's price of one item row (alias i): its row's selling price at the point; null without a row. */
	static final String LINE_PRICE = rowPrice("pr", "i");

	private HoItemCatalogueQuery() {
	}

	/** The point de stock filter with the row status ACTIVE (see below). */
	public static ItemCatalogueQuery.Criteria stockPoint(ItemCatalogueQuery.Criteria criteria, Long stockPointId) {
		return stockPoint(criteria, stockPointId, null);
	}

	/**
	 * The point de stock filter on the criteria (nothing for null): a line shows when one of its items has a row of the
	 * row status there. rowStatus: ACTIVE (absent, blank or anything else), INACTIVE or ALL, in any case. With a point,
	 * the criteria's price range moves onto the row's selling price with VAT (the item's own price no longer filters).
	 */
	public static ItemCatalogueQuery.Criteria stockPoint(ItemCatalogueQuery.Criteria criteria, Long stockPointId,
			String rowStatus) {
		if (stockPointId == null) {
			return criteria;
		}
		String price = rowPrice("r", "{a}");
		StringBuilder condition = new StringBuilder(STOCK_POINT_ROWS).append(rowCondition(rowStatus, "r"));
		if (criteria.getPriceMin() != null) {
			condition.append(" AND ").append(price).append(" >= :pointPriceMin");
			criteria.getItemParameters().put("pointPriceMin", criteria.getPriceMin());
			criteria.setPriceMin(null);
		}
		if (criteria.getPriceMax() != null) {
			condition.append(" AND ").append(price).append(" <= :pointPriceMax");
			criteria.getItemParameters().put("pointPriceMax", criteria.getPriceMax());
			criteria.setPriceMax(null);
		}
		criteria.getItemConditions().add(condition.append(")").toString());
		criteria.getItemParameters().put("stockPointId", stockPointId);
		// Step 2: the lines' price is the point's (the rows of the same row status), shown and sorted
		criteria.setLinePriceJoin(LINE_PRICE_JOIN + rowCondition(rowStatus, "pr"));
		criteria.setLinePrice(LINE_PRICE);
		return criteria;
	}

	/** The row status's condition on the row alias: INACTIVE an inactive row, ALL any row, else an active row. */
	static String rowCondition(String rowStatus, String alias) {
		String status = rowStatus == null ? "" : rowStatus.trim();
		if ("INACTIVE".equalsIgnoreCase(status)) {
			return ROW_INACTIVE.replace("{r}", alias);
		}
		return "ALL".equalsIgnoreCase(status) ? "" : ROW_ACTIVE.replace("{r}", alias);
	}

	/** The row status's rule in Java (as {@link #rowCondition}): a row of this active flag is of the row status. */
	public static boolean rowOfStatus(Boolean active, String rowStatus) {
		String status = rowStatus == null ? "" : rowStatus.trim();
		if ("INACTIVE".equalsIgnoreCase(status)) {
			return Boolean.FALSE.equals(active);
		}
		return "ALL".equalsIgnoreCase(status) || !Boolean.FALSE.equals(active);
	}

	/** {@link #ROW_PRICE} on these aliases of the row and the item. */
	static String rowPrice(String row, String item) {
		return ROW_PRICE.replace("{r}", row).replace("{a}", item);
	}
}
