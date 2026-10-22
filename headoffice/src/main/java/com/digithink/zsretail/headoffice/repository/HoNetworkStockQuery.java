package com.digithink.zsretail.headoffice.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.model.enumeration.CatalogueKind;

import lombok.Data;

/**
 * Network stock (3.1.0), step 1: the head office items of the page "Network stock" (HoNetworkStockService.page), native
 * SQL built with only the filters set, as Item cards' {@link HoItemCatalogueQuery}. The items: products and packs (or no
 * type), not the tax stamp, by code. The stock filter looks at "the columns shown": the head office's own stock (where
 * it keeps one) and the stores shown (one store, or every active store), ho_store_stock read through its item_code index
 * (an EXISTS per item); a store that never sent an item counts as 0. See docs/modules/head-office.md, "Stock of the
 * stores".
 */
@Repository
public class HoNetworkStockQuery {

	/** The columns of a row, in order. */
	public static final int COL_ITEM_CODE = 0;
	public static final int COL_ITEM_NAME = 1;
	public static final int COL_STOCK_QUANTITY = 2;

	/** The alias of the count. */
	public static final String COUNT_COLUMN = "line_count";

	/** The stock filter's values; null or another value: ALL. */
	public static final String IN_STOCK = "IN_STOCK";
	public static final String ZERO = "ZERO";
	public static final String BELOW_ZERO = "BELOW_ZERO";

	static final String SELECT = "SELECT i.item_code AS item_code, i.name AS item_name, i.stock_quantity AS stock_quantity"
			+ " FROM item i";

	static final String ITEMS = " WHERE (i.type IS NULL OR i.type IN ('PRODUCT', 'PACKAGE')) AND i.item_code <> '"
			+ CatalogueKind.TAX_STAMP_CODE + "'";

	static final String SEARCH = "CAST(:search AS VARCHAR(255))";

	@PersistenceContext
	private EntityManager entityManager;

	/** What the page asks for; null (or blank) means no filter. */
	@Data
	public static class Criteria {

		/** Lower case with % around it, or null. */
		private String like;

		/** One store; 0: every active store. */
		private long storeId;

		/** The head office's own stock is a column (headoffice.stock.enabled not false). */
		private boolean headOfficeColumn = true;

		/** ALL (or null), IN_STOCK, ZERO, BELOW_ZERO. */
		private String stock;

		private Long familyId;

		private Long subFamilyId;

		private String divisionCode;

		private String categoryCode;

		private String sectionCode;
	}

	/** The two statements of a criteria and their parameters. */
	@Data
	public static class Built {
		private final String lines;
		private final String count;
		private final Map<String, Object> parameters;
	}

	/** One page of rows [item_code, item_name, stock_quantity] and the number of rows. */
	@SuppressWarnings("unchecked")
	public Page<Object[]> lines(Criteria criteria, Pageable page) {
		Built built = build(criteria);
		Query lines = entityManager.createNativeQuery(built.getLines());
		Query count = entityManager.createNativeQuery(built.getCount());
		built.getParameters().forEach((name, value) -> {
			lines.setParameter(name, value);
			count.setParameter(name, value);
		});
		lines.setFirstResult((int) page.getOffset());
		lines.setMaxResults(page.getPageSize());
		List<Object[]> rows = lines.getResultList();
		long total = ((Number) count.getSingleResult()).longValue();
		return new PageImpl<>(rows, page, total);
	}

	/** The statements of a criteria (public for the query test). */
	public static Built build(Criteria c) {
		Map<String, Object> parameters = new LinkedHashMap<>();
		List<String> conditions = new ArrayList<>();
		if (c.getLike() != null) {
			conditions.add("(LOWER(i.item_code) LIKE " + SEARCH + " OR LOWER(i.name) LIKE " + SEARCH + ")");
			parameters.put("search", c.getLike());
		}
		if (c.getFamilyId() != null) {
			conditions.add("i.item_family_id = :familyId");
			parameters.put("familyId", c.getFamilyId());
		}
		if (c.getSubFamilyId() != null) {
			conditions.add("i.item_sub_family_id = :subFamilyId");
			parameters.put("subFamilyId", c.getSubFamilyId());
		}
		if (!blank(c.getDivisionCode())) {
			conditions.add("i.division_code = CAST(:divisionCode AS VARCHAR(255))");
			parameters.put("divisionCode", c.getDivisionCode().trim());
		}
		if (!blank(c.getCategoryCode())) {
			conditions.add("i.category_code = CAST(:categoryCode AS VARCHAR(255))");
			parameters.put("categoryCode", c.getCategoryCode().trim());
		}
		if (!blank(c.getSectionCode())) {
			conditions.add("i.section_code = CAST(:sectionCode AS VARCHAR(255))");
			parameters.put("sectionCode", c.getSectionCode().trim());
		}
		String stock = stockCondition(c, parameters);
		if (stock != null) {
			conditions.add(stock);
		}
		String where = ITEMS + (conditions.isEmpty() ? "" : " AND " + String.join(" AND ", conditions));
		String lines = SELECT + where + " ORDER BY i.item_code, i.id";
		String count = "SELECT COUNT(*) AS " + COUNT_COLUMN + " FROM item i" + where;
		return new Built(lines, count, parameters);
	}

	/**
	 * The stock filter on the columns shown: IN_STOCK one above 0, BELOW_ZERO one below 0, ZERO none other than 0 (a
	 * store without the item counts as 0); null for ALL.
	 */
	static String stockCondition(Criteria c, Map<String, Object> parameters) {
		String stock = c.getStock() == null ? "" : c.getStock().trim().toUpperCase();
		String test;
		if (IN_STOCK.equals(stock)) {
			test = "> 0";
		} else if (BELOW_ZERO.equals(stock)) {
			test = "< 0";
		} else if (ZERO.equals(stock)) {
			test = "<> 0";
		} else {
			return null;
		}
		String stores;
		if (c.getStoreId() != 0) {
			parameters.put("storeId", c.getStoreId());
			stores = "EXISTS (SELECT 1 FROM ho_store_stock s WHERE s.item_code = i.item_code AND s.store_id = :storeId"
					+ " AND s.quantity " + test + ")";
		} else {
			stores = "EXISTS (SELECT 1 FROM ho_store_stock s JOIN ho_store st ON st.id = s.store_id"
					+ " WHERE s.item_code = i.item_code AND (st.active IS NULL OR st.active = 1) AND s.quantity " + test + ")";
		}
		String headOffice = c.isHeadOfficeColumn() ? "COALESCE(i.stock_quantity, 0) " + test : null;
		if (ZERO.equals(stock)) {
			return "NOT " + (headOffice == null ? stores : "(" + headOffice + " OR " + stores + ")");
		}
		return headOffice == null ? stores : "(" + headOffice + " OR " + stores + ")";
	}

	private static boolean blank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
