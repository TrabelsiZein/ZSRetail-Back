package com.digithink.zsretail.core.repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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

import lombok.Data;

/**
 * Fiches items (3.1.0), in core since the store's Articles page (3.1.1): the list of the head office page "Fiches items"
 * and of the store's grouped Articles page, and its filter choices, native SQL built with only the filters set (each
 * optional filter written as ":x IS NULL OR ..." was planned for every row on ZEN's test head office, see 2fe0b34). The
 * head office adds its point de stock filter as an item condition (HoItemCatalogueQuery). One line per NAV item number when the item has one, else one line per item: GROUP BY
 * nav_item_no and, for the items without one, their id, so an item code equal to another item's NAV number stays its
 * own line. Standard SQL (it runs on H2 in ItemCatalogueQueryTest); text parameters cast to varchar (the JDBC driver
 * sends text as nvarchar, the columns are varchar); every selected column has its alias (SQL Server names an aggregate
 * without one "" and Hibernate refuses two empty names).
 * <p>
 * Filters on the items (search, family, sub-family, division, catégorie, section, point de stock, price) keep whole
 * lines: a NAV item shows when one of its variants of the status matches, and every variant of the status is counted.
 * "Has variants" counts the variants of the status (2 or more: yes). The code sort is natural for the codes made of
 * digits only (by their number, first), then the other codes as text.
 */
@Repository
public class ItemCatalogueQuery {

	/** Status Active: the active items only (a null active counts as active, as everywhere). */
	static final String STATUS_OF_I = "(:allStatuses = 1 OR i.active IS NULL OR i.active = 1)";

	static final String STATUS_OF_M = "(:allStatuses = 1 OR m.active IS NULL OR m.active = 1)";

	static final String SEARCH = "CAST(:search AS VARCHAR(255))";

	/** One line: a NAV item number, else one item. */
	static final String GROUP = " GROUP BY i.nav_item_no, CASE WHEN i.nav_item_no IS NULL THEN i.id END";

	/** An item's own price with VAT (the price of the column and of the price filter). */
	static final String PRICE_OF_I = "(i.unit_price * (1 + COALESCE(i.defaultvat, 0) / 100.0))";

	static final String PRICE_OF_M = "(m.unit_price * (1 + COALESCE(m.defaultvat, 0) / 100.0))";

	/**
	 * The columns of a line, each with its alias, in this order (the service reads them by the index constants below;
	 * ItemCatalogueQueryTest checks the result column names).
	 */
	public static final List<String> LINE_COLUMNS = Collections.unmodifiableList(Arrays.asList("nav_item_no", "item_id",
			"item_code", "item_name", "family_name", "sub_family_name", "category_code", "section_code", "variant_count",
			"any_active", "stock_quantity", "price_min", "price_max"));

	public static final int COL_NAV_ITEM_NO = 0;
	public static final int COL_ITEM_ID = 1;
	public static final int COL_ITEM_CODE = 2;
	public static final int COL_ITEM_NAME = 3;
	public static final int COL_FAMILY_NAME = 4;
	public static final int COL_SUB_FAMILY_NAME = 5;
	public static final int COL_CATEGORY_CODE = 6;
	public static final int COL_SECTION_CODE = 7;
	public static final int COL_VARIANT_COUNT = 8;
	public static final int COL_ANY_ACTIVE = 9;
	public static final int COL_STOCK_QUANTITY = 10;
	public static final int COL_PRICE_MIN = 11;
	public static final int COL_PRICE_MAX = 12;

	/** The alias of the counts. */
	public static final String COUNT_COLUMN = "line_count";

	static final String SELECT = "SELECT i.nav_item_no AS nav_item_no, MIN(i.id) AS item_id,"
			+ " MIN(i.item_code) AS item_code, MIN(i.name) AS item_name, MIN(f.name) AS family_name, MIN(sf.name) AS sub_family_name,"
			+ " MIN(i.category_code) AS category_code, MIN(i.section_code) AS section_code, COUNT(*) AS variant_count,"
			+ " MAX(CASE WHEN i.active IS NULL OR i.active = 1 THEN 1 ELSE 0 END) AS any_active,"
			+ " SUM(i.stock_quantity) AS stock_quantity, MIN(" + PRICE_OF_I + ") AS price_min, MAX(" + PRICE_OF_I
			+ ") AS price_max FROM item i LEFT JOIN item_family f ON f.id = i.item_family_id"
			+ " LEFT JOIN item_sub_family sf ON sf.id = i.item_sub_family_id";

	/**
	 * The ids of the items matching the search: NAV number, item code, name or legacy barcode, contains; or one of their
	 * barcodes. A UNION, read once: written as "OR m.id IN (barcodes)" SQL Server probed item_barcode once per item (on
	 * ZEN's test head office 294,025 probes, 2.5 s for a page and as much for its count; this way two scans, 0.3 s).
	 */
	static final String MATCHED_IDS = "(SELECT t.id FROM item t WHERE LOWER(t.nav_item_no) LIKE " + SEARCH
			+ " OR LOWER(t.item_code) LIKE " + SEARCH + " OR LOWER(t.name) LIKE " + SEARCH + " OR LOWER(t.barcode) LIKE "
			+ SEARCH + " UNION SELECT b.item_id FROM item_barcode b WHERE LOWER(b.barcode) LIKE " + SEARCH + ")";

	/** The line's code: its NAV item number, or the item code of its one item. */
	static final String CODE = "COALESCE(i.nav_item_no, MIN(i.item_code))";

	/** The code has 1 to 38 characters (the size of a DECIMAL(38,0)). */
	static final String CODE_SIZE = "(" + CODE + " <> '' AND " + CODE + " NOT LIKE '" + repeat('_', 39) + "%')";

	/**
	 * The code is made of digits only, as the applications ask SQL Server (every version): LIKE '[^0-9]' on a binary
	 * collation. Measured on Happyness's test head office (40,839 lines): the page 0.4 to 1.0 s of CPU, against 1.5 to
	 * 2.7 s with the portable form below under the database's collation.
	 */
	static final String CODE_IS_NUMBER = "(" + CODE_SIZE + " AND " + CODE
			+ " COLLATE Latin1_General_BIN NOT LIKE '%[^0-9]%')";

	/** The same test in SQL that H2 knows (the query tests): the code without its digits is empty. */
	static final String CODE_IS_NUMBER_PORTABLE = "(" + CODE_SIZE + " AND " + withoutDigits(CODE) + " = '')";

	@PersistenceContext
	private EntityManager entityManager;

	/** True only in the H2 tests: the code sort's digits test in portable SQL. */
	public boolean portable;

	/** What the list asks for; null (or blank) means no filter. */
	@Data
	public static class Criteria {

		/** Lower case with % around it, or null. */
		private String like;

		private boolean activeOnly = true;

		/**
		 * Status Inactive: only the lines without an active variant (a NAV item whose variants are all inactive, an inactive
		 * item); read with activeOnly false (every variant counted).
		 */
		private boolean inactiveOnly;

		private Long familyId;

		private Long subFamilyId;

		private String divisionCode;

		private String categoryCode;

		private String sectionCode;

		/**
		 * Conditions of an installation on one item row (the head office's point de stock), "{a}" standing for the row's
		 * alias; their parameters in itemParameters.
		 */
		private List<String> itemConditions = new ArrayList<>();

		private Map<String, Object> itemParameters = new LinkedHashMap<>();

		/** ALL (or null), YES (2 variants or more of the status), NO (one). */
		private String variants;

		/** With VAT. */
		private Double priceMin;

		private Double priceMax;

		/** code (default), name or family. */
		private String sort;

		private boolean descending;
	}

	/** The two statements of a criteria and their parameters. */
	@Data
	public static class Built {
		private final String lines;
		private final String count;
		private final Map<String, Object> parameters;
	}

	/** One page of lines ([LINE_COLUMNS]) and the number of lines. */
	@SuppressWarnings("unchecked")
	public Page<Object[]> lines(Criteria criteria, Pageable page) {
		Built built = build(criteria, portable);
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

	/**
	 * The statements of a criteria (public for the query test). The matching lines come from two derived tables, each
	 * computed once and joined: kn, the NAV numbers with a matching variant of the status (the line keeps all its
	 * variants), and ki, the matching items without NAV number. Measured on Happyness's test head office (40,839 items):
	 * the filters of the single items written in the outer WHERE, beside "kn.nav IS NOT NULL OR", took 2.3 s for a point
	 * de stock instead of 0.2 s.
	 */
	public static Built build(Criteria c) {
		return build(c, false);
	}

	/** The statements of a criteria; portable: the code sort in SQL that H2 knows (the query tests). */
	public static Built build(Criteria c, boolean portable) {
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("allStatuses", c.isActiveOnly() && !c.isInactiveOnly() ? 0 : 1);
		List<String> match = conditions(c, "m", parameters);
		boolean search = c.getLike() != null;
		if (search) {
			parameters.put("search", c.getLike());
		}
		String joins = "";
		String where = " WHERE " + STATUS_OF_I;
		if (search || !match.isEmpty()) {
			String from = " FROM item m" + (search ? " JOIN " + MATCHED_IDS + " x ON x.id = m.id" : "");
			joins = " LEFT JOIN (SELECT DISTINCT m.nav_item_no AS nav" + from + " WHERE m.nav_item_no IS NOT NULL AND "
					+ STATUS_OF_M + and(match) + ") kn ON kn.nav = i.nav_item_no LEFT JOIN (SELECT m.id AS id" + from
					+ " WHERE m.nav_item_no IS NULL" + and(match) + ") ki ON ki.id = i.id";
			where += " AND (kn.nav IS NOT NULL OR ki.id IS NOT NULL)";
		}
		List<String> groups = new ArrayList<>();
		if ("YES".equalsIgnoreCase(trim(c.getVariants()))) {
			groups.add("COUNT(*) > 1");
		} else if ("NO".equalsIgnoreCase(trim(c.getVariants()))) {
			groups.add("COUNT(*) = 1");
		}
		if (c.isInactiveOnly()) {
			groups.add("MAX(CASE WHEN i.active IS NULL OR i.active = 1 THEN 1 ELSE 0 END) = 0");
		}
		String having = groups.isEmpty() ? "" : " HAVING " + String.join(" AND ", groups);
		String lines = SELECT + joins + where + GROUP + having + " ORDER BY " + order(c, portable);
		String count = "SELECT COUNT(*) AS " + COUNT_COLUMN + " FROM (SELECT i.nav_item_no AS n FROM item i" + joins + where
				+ GROUP + having + ") g";
		return new Built(lines, count, parameters);
	}

	/** The filters on one item row (alias m), their parameters added. */
	static List<String> conditions(Criteria c, String a, Map<String, Object> parameters) {
		List<String> conditions = new ArrayList<>();
		if (c.getFamilyId() != null) {
			conditions.add(a + ".item_family_id = :familyId");
			parameters.put("familyId", c.getFamilyId());
		}
		if (c.getSubFamilyId() != null) {
			conditions.add(a + ".item_sub_family_id = :subFamilyId");
			parameters.put("subFamilyId", c.getSubFamilyId());
		}
		if (!blank(c.getDivisionCode())) {
			conditions.add(a + ".division_code = CAST(:divisionCode AS VARCHAR(255))");
			parameters.put("divisionCode", c.getDivisionCode().trim());
		}
		if (!blank(c.getCategoryCode())) {
			conditions.add(a + ".category_code = CAST(:categoryCode AS VARCHAR(255))");
			parameters.put("categoryCode", c.getCategoryCode().trim());
		}
		if (!blank(c.getSectionCode())) {
			conditions.add(a + ".section_code = CAST(:sectionCode AS VARCHAR(255))");
			parameters.put("sectionCode", c.getSectionCode().trim());
		}
		for (String condition : c.getItemConditions()) {
			conditions.add(condition.replace("{a}", a));
		}
		parameters.putAll(c.getItemParameters());
		String price = "i".equals(a) ? PRICE_OF_I : PRICE_OF_M;
		if (c.getPriceMin() != null) {
			conditions.add(price + " >= :priceMin");
			parameters.put("priceMin", c.getPriceMin());
		}
		if (c.getPriceMax() != null) {
			conditions.add(price + " <= :priceMax");
			parameters.put("priceMax", c.getPriceMax());
		}
		return conditions;
	}

	private static String and(List<String> conditions) {
		return conditions.isEmpty() ? "" : " AND " + String.join(" AND ", conditions);
	}

	/**
	 * The order: code (natural), name, family (lines without a family last), variants (the line's variant_count) or price
	 * (its price_min, lines without a price last), each then by code.
	 */
	static String order(Criteria c, boolean portable) {
		String dir = c.isDescending() ? " DESC" : " ASC";
		String sort = trim(c.getSort());
		if ("name".equalsIgnoreCase(sort)) {
			return "MIN(i.name)" + dir + ", " + codeOrder(" ASC", portable);
		}
		if ("variants".equalsIgnoreCase(sort)) {
			return "COUNT(*)" + dir + ", " + codeOrder(" ASC", portable);
		}
		if ("price".equalsIgnoreCase(sort)) {
			return "CASE WHEN MIN(" + PRICE_OF_I + ") IS NULL THEN 1 ELSE 0 END, MIN(" + PRICE_OF_I + ")" + dir + ", "
					+ codeOrder(" ASC", portable);
		}
		if ("family".equalsIgnoreCase(sort)) {
			return "CASE WHEN MIN(f.name) IS NULL THEN 1 ELSE 0 END, MIN(f.name)" + dir + ", MIN(sf.name)" + dir + ", "
					+ codeOrder(" ASC", portable);
		}
		return codeOrder(dir, portable);
	}

	/** Codes of digits only first, by their number; then the others as text; then the line's smallest id. */
	static String codeOrder(String dir, boolean portable) {
		String number = portable ? CODE_IS_NUMBER_PORTABLE : CODE_IS_NUMBER;
		return "CASE WHEN " + number + " THEN 0 ELSE 1 END" + dir + ", CASE WHEN " + number + " THEN CAST("
				+ CODE + " AS DECIMAL(38,0)) END" + dir + ", " + CODE + dir + ", MIN(i.id)" + dir;
	}

	/**
	 * The same natural order on any code column (Network stock, 3.1.0): the codes of 1 to 38 digits first, by their
	 * number, then the others as text, then id; the same SQL as {@link #codeOrder(String, boolean)} on another column.
	 */
	public static String codeOrder(String code, String id, String dir, boolean portable) {
		String size = "(" + code + " <> '' AND " + code + " NOT LIKE '" + repeat('_', 39) + "%')";
		String number = "(" + size + " AND " + (portable ? withoutDigits(code) + " = ''"
				: code + " COLLATE Latin1_General_BIN NOT LIKE '%[^0-9]%'") + ")";
		return "CASE WHEN " + number + " THEN 0 ELSE 1 END" + dir + ", CASE WHEN " + number + " THEN CAST(" + code
				+ " AS DECIMAL(38,0)) END" + dir + ", " + code + dir + ", " + id + dir;
	}

	/**
	 * The flags of the page, from the data: [has_variants, has_families, has_division, has_category, has_section,
	 * has_barcodes]; one existence check each (ZEN has catégories and divisions but no section). The head office counts
	 * its points de stock apart (HoItemCatalogueQuery).
	 */
	public static final String FLAGS = "SELECT"
			+ " CASE WHEN EXISTS (SELECT 1 FROM item v WHERE v.nav_item_no IS NOT NULL GROUP BY v.nav_item_no"
			+ " HAVING COUNT(*) > 1) THEN 1 ELSE 0 END AS has_variants,"
			+ " CASE WHEN EXISTS (SELECT 1 FROM item v WHERE v.item_family_id IS NOT NULL) THEN 1 ELSE 0 END AS has_families,"
			+ " CASE WHEN EXISTS (SELECT 1 FROM item v WHERE v.division_code IS NOT NULL) THEN 1 ELSE 0 END AS has_division,"
			+ " CASE WHEN EXISTS (SELECT 1 FROM item v WHERE v.category_code IS NOT NULL) THEN 1 ELSE 0 END AS has_category,"
			+ " CASE WHEN EXISTS (SELECT 1 FROM item v WHERE v.section_code IS NOT NULL) THEN 1 ELSE 0 END AS has_section,"
			+ " CASE WHEN EXISTS (SELECT 1 FROM item_barcode b) THEN 1 ELSE 0 END AS has_barcodes";

	/** The families that have an item: [id, code, name, catégorie code]. */
	public static final String FAMILIES = "SELECT f.id AS id, f.code AS code, f.name AS name,"
			+ " f.category_code AS category_code FROM item_family f"
			+ " WHERE f.id IN (SELECT v.item_family_id FROM item v) ORDER BY f.name, f.code";

	/** The sub-families that have an item: [id, code, name, family id]. */
	public static final String SUB_FAMILIES = "SELECT sf.id AS id, sf.code AS code, sf.name AS name,"
			+ " sf.item_family_id AS family_id FROM item_sub_family sf WHERE sf.id IN (SELECT v.item_sub_family_id FROM item v)"
			+ " ORDER BY sf.name, sf.code";

	/** The division codes of the items (no table): [code]. */
	public static final String DIVISIONS = "SELECT DISTINCT v.division_code AS code FROM item v"
			+ " WHERE v.division_code IS NOT NULL ORDER BY code";

	/** The catégorie codes of the items with their name where the table has the code: [code, name]. */
	public static final String CATEGORIES = "SELECT v.category_code AS code, MIN(c.name) AS name FROM item v"
			+ " LEFT JOIN item_category c ON c.code = v.category_code WHERE v.category_code IS NOT NULL"
			+ " GROUP BY v.category_code ORDER BY v.category_code";

	/** The section codes of the items with their name and their sub-family's code: [code, name, sub-family code]. */
	public static final String SECTIONS = "SELECT v.section_code AS code, MIN(s.name) AS name,"
			+ " MIN(s.sub_family_code) AS sub_family_code FROM item v"
			+ " LEFT JOIN item_section s ON s.code = v.section_code WHERE v.section_code IS NOT NULL"
			+ " GROUP BY v.section_code ORDER BY v.section_code";

	/**
	 * 3.1.1 (the store's Articles page): the items of a page's lines that match the search, every status: [item id,
	 * nav_item_no], the items of these NAV item numbers and these single items (ids) found by the same search as the list
	 * (NAV number, item code, name, legacy barcode or a barcode, contains). Empty without a search or without a line.
	 */
	public static Built matched(String like, Collection<String> navItemNos, Collection<Long> itemIds) {
		Map<String, Object> parameters = new LinkedHashMap<>();
		List<String> keys = new ArrayList<>();
		if (navItemNos != null && !navItemNos.isEmpty()) {
			keys.add("m.nav_item_no IN (:navItemNos)");
			parameters.put("navItemNos", new ArrayList<>(navItemNos));
		}
		if (itemIds != null && !itemIds.isEmpty()) {
			keys.add("m.id IN (:itemIds)");
			parameters.put("itemIds", new ArrayList<>(itemIds));
		}
		if (like == null || keys.isEmpty()) {
			return null;
		}
		parameters.put("search", like);
		String sql = "SELECT m.id AS item_id, m.nav_item_no AS nav_item_no FROM item m JOIN " + MATCHED_IDS
				+ " x ON x.id = m.id WHERE " + String.join(" OR ", keys) + " ORDER BY m.id";
		return new Built(sql, null, parameters);
	}

	/** The items of a page's lines that match the search ({@link #matched}): [item id, nav_item_no]. */
	public List<Object[]> matchedItems(String like, Collection<String> navItemNos, Collection<Long> itemIds) {
		Built built = matched(like, navItemNos, itemIds);
		if (built == null) {
			return new ArrayList<>();
		}
		Query query = entityManager.createNativeQuery(built.getLines());
		built.getParameters().forEach(query::setParameter);
		List<Object[]> answer = new ArrayList<>();
		for (Object row : query.getResultList()) {
			answer.add((Object[]) row);
		}
		return answer;
	}

	/** The result of a statement without parameters. */
	@SuppressWarnings("unchecked")
	public List<Object[]> rows(String sql) {
		List<?> rows = entityManager.createNativeQuery(sql).getResultList();
		List<Object[]> answer = new ArrayList<>();
		for (Object row : rows) {
			answer.add(row instanceof Object[] ? (Object[]) row : new Object[] { row });
		}
		return answer;
	}

	private static String withoutDigits(String expression) {
		String text = expression;
		for (char digit = '0'; digit <= '9'; digit++) {
			text = "REPLACE(" + text + ", '" + digit + "', '')";
		}
		return text;
	}

	private static String repeat(char c, int times) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < times; i++) {
			text.append(c);
		}
		return text.toString();
	}

	private static boolean blank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private static String trim(String value) {
		return value == null ? null : value.trim();
	}
}
