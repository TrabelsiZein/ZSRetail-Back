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
import com.digithink.zsretail.core.repository.ItemCatalogueQuery;

import lombok.Data;

/**
 * Network stock (3.1.0): the two lists of the page "Network stock" (HoNetworkStockService), native SQL built with only
 * the filters set, as Item cards' {@link ItemCatalogueQuery}.
 * <p>
 * Head office items (step 1): products and packs (or no type), not the tax stamp. The stock filter looks at "the columns
 * shown": the head office's own stock (where it keeps one) and the stores shown (one store, or every active store),
 * ho_store_stock read through its item_code index (an EXISTS per item); a store that never sent an item counts as 0.
 * Step 2, the sorts: code (natural, the default), name, the head office stock, one store's quantity (a LEFT JOIN on the
 * unique key, only for that sort), the total of the active stores (a grouped derived table, only for that sort); a value
 * that is absent is last in both directions; each then by code.
 * <p>
 * The stores' own items (step 2, the rows of the JPQL findOwnItems of before, with the store's code and name): the store,
 * the search, the stock filter on the row's quantity; sorts store (the default), code, name, quantity, store time,
 * received time, each then by store and code. See docs/modules/head-office.md, "Stock of the stores".
 */
@Repository
public class HoNetworkStockQuery {

	/** The columns of a head office item row, in order. */
	public static final int COL_ITEM_CODE = 0;
	public static final int COL_ITEM_NAME = 1;
	public static final int COL_STOCK_QUANTITY = 2;
	public static final int COL_ITEM_ID = 3;
	public static final int COL_NAV_ITEM_NO = 4;

	/** The columns of an own item row, in order. */
	public static final int OWN_STORE_ID = 0;
	public static final int OWN_STORE_CODE = 1;
	public static final int OWN_STORE_NAME = 2;
	public static final int OWN_ITEM_CODE = 3;
	public static final int OWN_ITEM_NAME = 4;
	public static final int OWN_QUANTITY = 5;
	public static final int OWN_STORE_TIME = 6;
	public static final int OWN_RECEIVED_AT = 7;

	/** The alias of the count. */
	public static final String COUNT_COLUMN = "line_count";

	/** The stock filter's values; null or another value: ALL. */
	public static final String IN_STOCK = "IN_STOCK";
	public static final String ZERO = "ZERO";
	public static final String BELOW_ZERO = "BELOW_ZERO";

	/** The head office items' sorts; another value: CODE. STORE needs Criteria.sortStoreId. */
	public static final String SORT_CODE = "code";
	public static final String SORT_NAME = "name";
	public static final String SORT_HEAD_OFFICE = "headOffice";
	public static final String SORT_STORE = "store";
	public static final String SORT_TOTAL = "total";

	/** The own items' sorts (besides code and name); another value: STORE. */
	public static final String SORT_QUANTITY = "quantity";
	public static final String SORT_STORE_TIME = "storeTime";
	public static final String SORT_RECEIVED_AT = "receivedAt";

	static final String SELECT = "SELECT i.item_code AS item_code, i.name AS item_name, i.stock_quantity AS stock_quantity,"
			+ " i.id AS item_id, i.nav_item_no AS nav_item_no FROM item i";

	static final String ITEMS = " WHERE (i.type IS NULL OR i.type IN ('PRODUCT', 'PACKAGE')) AND i.item_code <> '"
			+ CatalogueKind.TAX_STAMP_CODE + "'";

	static final String OWN_SELECT = "SELECT s.store_id AS store_id, st.code AS store_code, st.name AS store_name,"
			+ " s.item_code AS item_code, s.item_name AS item_name, s.quantity AS quantity, s.store_time AS store_time,"
			+ " s.received_at AS received_at FROM ho_store_stock s LEFT JOIN ho_store st ON st.id = s.store_id";

	static final String SEARCH = "CAST(:search AS VARCHAR(255))";

	/** An active store (a null active counts as active), alias st. */
	static final String ACTIVE_STORE = "(st.active IS NULL OR st.active = 1)";

	@PersistenceContext
	private EntityManager entityManager;

	/** True only in the H2 tests: the code sort's digits test in portable SQL. */
	boolean portable;

	public HoNetworkStockQuery() {
	}

	/** Over a given entity manager (the H2 tests: a Hibernate session), portable SQL. */
	public HoNetworkStockQuery(EntityManager entityManager) {
		this.entityManager = entityManager;
		this.portable = true;
	}

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

		/** One of the SORT_ values; null: the list's default. */
		private String sort;

		/** SORT_STORE: the store whose column is sorted (a store shown, checked by the service). */
		private Long sortStoreId;

		private boolean descending;
	}

	/** The two statements of a criteria and their parameters. */
	@Data
	public static class Built {
		private final String lines;
		private final String count;
		/** The parameters of both statements. */
		private final Map<String, Object> parameters;
		/** The parameters of the lines only (a sort's join); empty when none. */
		private final Map<String, Object> sortParameters;
	}

	/** One page of head office item rows [item_code, item_name, stock_quantity, item_id, nav_item_no] and their number. */
	public Page<Object[]> lines(Criteria criteria, Pageable page) {
		return run(build(criteria, portable), page);
	}

	/**
	 * One page of own item rows [store_id, store_code, store_name, item_code, item_name, quantity, store_time,
	 * received_at] and their number.
	 */
	public Page<Object[]> ownLines(Criteria criteria, Pageable page) {
		return run(buildOwn(criteria, portable), page);
	}

	@SuppressWarnings("unchecked")
	private Page<Object[]> run(Built built, Pageable page) {
		Query lines = entityManager.createNativeQuery(built.getLines());
		Query count = entityManager.createNativeQuery(built.getCount());
		built.getParameters().forEach((name, value) -> {
			lines.setParameter(name, value);
			count.setParameter(name, value);
		});
		built.getSortParameters().forEach(lines::setParameter);
		lines.setFirstResult((int) page.getOffset());
		lines.setMaxResults(page.getPageSize());
		List<Object[]> rows = lines.getResultList();
		long total = ((Number) count.getSingleResult()).longValue();
		return new PageImpl<>(rows, page, total);
	}

	/** The statements of the head office items (public for the query test). */
	public static Built build(Criteria c) {
		return build(c, false);
	}

	/** The statements of the head office items; portable: the code sort in SQL that H2 knows. */
	public static Built build(Criteria c, boolean portable) {
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
		String dir = c.isDescending() ? " DESC" : " ASC";
		String code = ItemCatalogueQuery.codeOrder("i.item_code", "i.id", " ASC", portable);
		String sort = c.getSort() == null ? "" : c.getSort();
		String join = "";
		Map<String, Object> sortParameters = new LinkedHashMap<>();
		String order;
		if (SORT_NAME.equals(sort)) {
			order = "i.name" + dir + ", " + code;
		} else if (SORT_HEAD_OFFICE.equals(sort) && c.isHeadOfficeColumn()) {
			order = "COALESCE(i.stock_quantity, 0)" + dir + ", " + code;
		} else if (SORT_STORE.equals(sort) && c.getSortStoreId() != null) {
			join = " LEFT JOIN ho_store_stock ss ON ss.item_code = i.item_code AND ss.store_id = :sortStoreId";
			sortParameters.put("sortStoreId", c.getSortStoreId());
			order = "CASE WHEN ss.quantity IS NULL THEN 1 ELSE 0 END, ss.quantity" + dir + ", " + code;
		} else if (SORT_TOTAL.equals(sort)) {
			join = " LEFT JOIN (SELECT s.item_code AS item_code, SUM(s.quantity) AS total FROM ho_store_stock s"
					+ " JOIN ho_store st ON st.id = s.store_id WHERE " + ACTIVE_STORE + " GROUP BY s.item_code) t"
					+ " ON t.item_code = i.item_code";
			order = "CASE WHEN t.total IS NULL THEN 1 ELSE 0 END, t.total" + dir + ", " + code;
		} else {
			order = ItemCatalogueQuery.codeOrder("i.item_code", "i.id", dir, portable);
		}
		String lines = SELECT + join + where + " ORDER BY " + order;
		String count = "SELECT COUNT(*) AS " + COUNT_COLUMN + " FROM item i" + where;
		return new Built(lines, count, parameters, sortParameters);
	}

	/**
	 * The stock filter on the columns shown: IN_STOCK one above 0, BELOW_ZERO one below 0, ZERO none other than 0 (a
	 * store without the item counts as 0); null for ALL.
	 */
	static String stockCondition(Criteria c, Map<String, Object> parameters) {
		String test = stockTest(c);
		if (test == null) {
			return null;
		}
		String stores;
		if (c.getStoreId() != 0) {
			parameters.put("storeId", c.getStoreId());
			stores = "EXISTS (SELECT 1 FROM ho_store_stock s WHERE s.item_code = i.item_code AND s.store_id = :storeId"
					+ " AND s.quantity " + test + ")";
		} else {
			stores = "EXISTS (SELECT 1 FROM ho_store_stock s JOIN ho_store st ON st.id = s.store_id"
					+ " WHERE s.item_code = i.item_code AND " + ACTIVE_STORE + " AND s.quantity " + test + ")";
		}
		String headOffice = c.isHeadOfficeColumn() ? "COALESCE(i.stock_quantity, 0) " + test : null;
		String any = headOffice == null ? stores : "(" + headOffice + " OR " + stores + ")";
		return ZERO.equals(stock(c)) ? "NOT " + any : any;
	}

	/**
	 * The statements of the stores' own items (public for the query test): own_item true, one store or every store, the
	 * search on the code and the name, the stock filter on the row's quantity (ZERO: equal to 0).
	 */
	public static Built buildOwn(Criteria c, boolean portable) {
		Map<String, Object> parameters = new LinkedHashMap<>();
		List<String> conditions = new ArrayList<>();
		conditions.add("s.own_item = 1");
		if (c.getStoreId() != 0) {
			conditions.add("s.store_id = :storeId");
			parameters.put("storeId", c.getStoreId());
		}
		if (c.getLike() != null) {
			conditions.add("(LOWER(s.item_code) LIKE " + SEARCH + " OR LOWER(s.item_name) LIKE " + SEARCH + ")");
			parameters.put("search", c.getLike());
		}
		String test = stockTest(c);
		if (test != null) {
			conditions.add("s.quantity " + (ZERO.equals(stock(c)) ? "= 0" : test));
		}
		String where = " WHERE " + String.join(" AND ", conditions);
		String dir = c.isDescending() ? " DESC" : " ASC";
		String byStoreAndCode = "st.code ASC, " + ItemCatalogueQuery.codeOrder("s.item_code", "s.id", " ASC", portable);
		String sort = c.getSort() == null ? "" : c.getSort();
		String order;
		if (SORT_CODE.equals(sort)) {
			order = ItemCatalogueQuery.codeOrder("s.item_code", "s.id", dir, portable);
		} else if (SORT_NAME.equals(sort)) {
			order = "CASE WHEN s.item_name IS NULL THEN 1 ELSE 0 END, s.item_name" + dir + ", " + byStoreAndCode;
		} else if (SORT_QUANTITY.equals(sort)) {
			order = "s.quantity" + dir + ", " + byStoreAndCode;
		} else if (SORT_STORE_TIME.equals(sort)) {
			order = "CASE WHEN s.store_time IS NULL THEN 1 ELSE 0 END, s.store_time" + dir + ", " + byStoreAndCode;
		} else if (SORT_RECEIVED_AT.equals(sort)) {
			order = "CASE WHEN s.received_at IS NULL THEN 1 ELSE 0 END, s.received_at" + dir + ", " + byStoreAndCode;
		} else {
			order = "st.code" + dir + ", " + ItemCatalogueQuery.codeOrder("s.item_code", "s.id", " ASC", portable);
		}
		String lines = OWN_SELECT + where + " ORDER BY " + order;
		String count = "SELECT COUNT(*) AS " + COUNT_COLUMN + " FROM ho_store_stock s" + where;
		return new Built(lines, count, parameters, new LinkedHashMap<>());
	}

	private static String stock(Criteria c) {
		return c.getStock() == null ? "" : c.getStock().trim().toUpperCase();
	}

	/** The comparison of the stock filter (ZERO: the test of a value that is not zero); null for ALL. */
	private static String stockTest(Criteria c) {
		String stock = stock(c);
		if (IN_STOCK.equals(stock)) {
			return "> 0";
		}
		if (BELOW_ZERO.equals(stock)) {
			return "< 0";
		}
		if (ZERO.equals(stock)) {
			return "<> 0";
		}
		return null;
	}

	private static boolean blank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
