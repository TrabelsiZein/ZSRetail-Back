package com.digithink.zsretail.headoffice.repository;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.Item;

/**
 * Fiches items (3.1.0), step 1: the list of the head office page "Fiches items", one line per NAV item number when the
 * item has one, else one line per item (packs, TAX_STAMP, items made by hand). Native SQL, standard (it runs on H2 in the
 * tests): GROUP BY nav_item_no and, for the items without one, their id, so an item code equal to another item's NAV
 * number stays its own line. The search parameter is cast to varchar (the JDBC driver sends text as nvarchar, the
 * columns are varchar). Nothing here is used by the other item pages (ItemRepository.NAV_ITEMS stays as it is).
 * <p>
 * Parameters: allStatuses 1 (status All: every variant) or 0 (status Active: the active variants only, a line whose
 * variants are all inactive is absent); search lower case with % around it, never null (the plain queries take none).
 */
public interface HoItemCatalogueRepository extends Repository<Item, Long> {

	/** Status Active: the active items only (a null active counts as active, as everywhere). */
	String STATUS_OF_I = "(:allStatuses = 1 OR i.active IS NULL OR i.active = 1)";

	String STATUS_OF_M = "(:allStatuses = 1 OR m.active IS NULL OR m.active = 1)";

	String SEARCH = "CAST(:search AS VARCHAR(255))";

	/** One line: a NAV item number, else one item. */
	String GROUP = " GROUP BY i.nav_item_no, CASE WHEN i.nav_item_no IS NULL THEN i.id END";

	/**
	 * The columns of a line, each with its alias, in this order (the service reads them by the index constants below).
	 * Every column needs its alias: SQL Server names an aggregate without one "" and Hibernate's auto-discovery of a
	 * native query refuses two empty names (NonUniqueDiscoveredSqlAliasException); H2 names them itself
	 * (ItemCatalogueQueryTest checks the names).
	 */
	List<String> LINE_COLUMNS = Collections.unmodifiableList(Arrays.asList("nav_item_no", "item_id", "item_code",
			"item_name", "family_name", "sub_family_name", "category_code", "section_code", "variant_count", "any_active",
			"stock_quantity"));

	int COL_NAV_ITEM_NO = 0;
	int COL_ITEM_ID = 1;
	int COL_ITEM_CODE = 2;
	int COL_ITEM_NAME = 3;
	int COL_FAMILY_NAME = 4;
	int COL_SUB_FAMILY_NAME = 5;
	int COL_CATEGORY_CODE = 6;
	int COL_SECTION_CODE = 7;
	int COL_VARIANT_COUNT = 8;
	int COL_ANY_ACTIVE = 9;
	int COL_STOCK_QUANTITY = 10;

	/** The alias of the counts. */
	String COUNT_COLUMN = "line_count";

	/**
	 * [NAV item number (null for one item), smallest item id, smallest item code, name, family name, sub-family name,
	 * catégorie code, section code, items counted, 1 when one item counted is active else 0, sum of the stock].
	 */
	String SELECT = "SELECT i.nav_item_no AS nav_item_no, MIN(i.id) AS item_id, MIN(i.item_code) AS item_code,"
			+ " MIN(i.name) AS item_name, MIN(f.name) AS family_name, MIN(sf.name) AS sub_family_name,"
			+ " MIN(i.category_code) AS category_code, MIN(i.section_code) AS section_code, COUNT(*) AS variant_count,"
			+ " MAX(CASE WHEN i.active IS NULL OR i.active = 1 THEN 1 ELSE 0 END) AS any_active,"
			+ " SUM(i.stock_quantity) AS stock_quantity"
			+ " FROM item i LEFT JOIN item_family f ON f.id = i.item_family_id"
			+ " LEFT JOIN item_sub_family sf ON sf.id = i.item_sub_family_id";

	/** By code (the NAV number, or the item code of one item), then id. */
	String ORDER = " ORDER BY COALESCE(i.nav_item_no, MIN(i.item_code)), MIN(i.id)";

	/**
	 * The ids of the items matching the search: NAV number, item code, name or legacy barcode, contains; or one of their
	 * barcodes. A UNION, read once: written as "OR m.id IN (barcodes)" SQL Server probed item_barcode once per item (on
	 * ZEN's test head office 294,025 probes, 2.5 s for a page and as much for its count; this way two scans, 0.3 s).
	 */
	String MATCHED_IDS = "(SELECT t.id FROM item t WHERE LOWER(t.nav_item_no) LIKE " + SEARCH
			+ " OR LOWER(t.item_code) LIKE " + SEARCH + " OR LOWER(t.name) LIKE " + SEARCH + " OR LOWER(t.barcode) LIKE "
			+ SEARCH + " UNION SELECT b.item_id FROM item_barcode b WHERE LOWER(b.barcode) LIKE " + SEARCH + ")";

	/**
	 * A search keeps whole lines: a NAV item when one of its variants (of the status) matches, with every variant of the
	 * status counted, not only the matching one (kn); an item without NAV number when it matches (ki). Derived tables
	 * joined, each computed once (an IN on the NAV number was planned per variant row, as above).
	 */
	String SEARCHED_JOINS = " LEFT JOIN (SELECT DISTINCT m.nav_item_no AS nav FROM item m JOIN " + MATCHED_IDS
			+ " x ON x.id = m.id WHERE m.nav_item_no IS NOT NULL AND " + STATUS_OF_M + ") kn ON kn.nav = i.nav_item_no"
			+ " LEFT JOIN (SELECT m.id AS id FROM item m JOIN " + MATCHED_IDS
			+ " x ON x.id = m.id WHERE m.nav_item_no IS NULL) ki ON ki.id = i.id";

	String SEARCHED_WHERE = " AND (kn.nav IS NOT NULL OR ki.id IS NOT NULL)";

	String LINES = SELECT + " WHERE " + STATUS_OF_I + GROUP + ORDER;

	String LINES_COUNT = "SELECT COUNT(*) AS " + COUNT_COLUMN + " FROM (SELECT i.nav_item_no AS n FROM item i WHERE "
			+ STATUS_OF_I + GROUP + ") g";

	String LINES_SEARCHED = SELECT + SEARCHED_JOINS + " WHERE " + STATUS_OF_I + SEARCHED_WHERE + GROUP + ORDER;

	String LINES_SEARCHED_COUNT = "SELECT COUNT(*) AS " + COUNT_COLUMN + " FROM (SELECT i.nav_item_no AS n FROM item i"
			+ SEARCHED_JOINS + " WHERE " + STATUS_OF_I + SEARCHED_WHERE + GROUP + ") g";

	/** [item id, active points where the item has an active row], for these items (one page of single-item lines). */
	String ITEM_POINT_COUNTS = "select r.itemId, count(distinct r.stockPointId) from HoStockPointItem r, HoStockPoint p"
			+ " where p.id = r.stockPointId and r.itemId in :itemIds and (r.active is null or r.active = true)"
			+ " and (p.active is null or p.active = true) group by r.itemId";

	@Query(value = LINES, countQuery = LINES_COUNT, nativeQuery = true)
	Page<Object[]> findLines(@Param("allStatuses") int allStatuses, Pageable page);

	@Query(value = LINES_SEARCHED, countQuery = LINES_SEARCHED_COUNT, nativeQuery = true)
	Page<Object[]> findLinesSearched(@Param("search") String search, @Param("allStatuses") int allStatuses,
			Pageable page);

	@Query(ITEM_POINT_COUNTS)
	List<Object[]> countActivePointsOfItems(@Param("itemIds") Collection<Long> itemIds);
}
