package com.digithink.zsretail.core.repository;

import java.util.ArrayList;
import java.util.Collection;
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

import com.digithink.zsretail.core.dto.ItemLookupLineDTO;

/**
 * Promotions for variants, step 4 (2026-10-23): the lines of the target lookup of the promotion form (GET /item/lookup),
 * native SQL, read only, store and head office.
 * <p>
 * Scope ITEM: one paged list of two kinds of lines in one UNION ALL, the same column types on both sides: a NAV_ITEM line
 * per ERP item number of the active items (its lowest id, the name of its rows, the number of its active variants) and
 * a ROW line per active item. Ordered by name, ERP item number, the NAV_ITEM line before its variants, then by code. A
 * customer without ERP item numbers (EMTOP, Happyness) gets only ROW lines from the same statement. Packs (type PACKAGE)
 * are left out,
 * as by the item pickers (a pack never enters the cart). Search: code, name, ERP item number, and the items of a
 * barcode (their ids, read before by ItemService as for GET /item/search); a NAV_ITEM line matches when one of its
 * variants does. Active: active 1 or null.
 * <p>
 * Scopes FAMILY, SUBFAMILY, CATEGORY, SECTION: code and name of the active rows, the parent's name for a sous-famille
 * (its famille) and a section (its sous-famille, by code).
 */
@Repository
public class ItemLookupQuery {

	public static final String ITEM = "ITEM";
	public static final String FAMILY = "FAMILY";
	public static final String SUBFAMILY = "SUBFAMILY";
	public static final String CATEGORY = "CATEGORY";
	public static final String SECTION = "SECTION";

	@PersistenceContext
	private EntityManager entityManager;

	public ItemLookupQuery() {
	}

	/** With an entity manager of its own (the query test). */
	public ItemLookupQuery(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	/**
	 * One page of lines of a scope. like: the search, lower case with % around it, null = every line; barcodeItemIds:
	 * the items whose barcode matches the search (scope ITEM), empty when none.
	 */
	@SuppressWarnings("unchecked")
	public Page<ItemLookupLineDTO> page(String scope, String like, Collection<Long> barcodeItemIds, Pageable page) {
		boolean filtered = like != null;
		boolean withIds = filtered && barcodeItemIds != null && !barcodeItemIds.isEmpty();
		String from = from(scope, filtered, withIds);
		Map<String, Object> parameters = new LinkedHashMap<>();
		if (filtered) {
			parameters.put("q", like);
		}
		if (withIds) {
			parameters.put("ids", barcodeItemIds);
		}
		Query lines = entityManager.createNativeQuery(linesOf(scope, from));
		parameters.forEach(lines::setParameter);
		lines.setFirstResult((int) page.getOffset());
		lines.setMaxResults(page.getPageSize());
		List<Object[]> rows = lines.getResultList();
		List<ItemLookupLineDTO> content = new ArrayList<>();
		for (Object[] row : rows) {
			content.add(line(row));
		}
		// The total comes with the lines (one scan, half the time of a count query on 22,000 items: 2026-10-23, ZEN);
		// counted apart only for a page past the end
		long total;
		if (!rows.isEmpty()) {
			total = ((Number) rows.get(0)[8]).longValue();
		} else if (page.getOffset() == 0) {
			total = 0;
		} else {
			Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM (" + from + ") x");
			parameters.forEach(count::setParameter);
			total = ((Number) count.getSingleResult()).longValue();
		}
		return new PageImpl<>(content, page, total);
	}

	/**
	 * The lines' statement of a scope (public for the query test): columns as {@link #line}, then the number of all the
	 * lines (COUNT(*) OVER(), before the paging), in the lookup's order.
	 */
	public static String linesOf(String scope, String from) {
		// ITEM: by name, then ERP item number (two ERP items of one name: each "all variants" line right above its own
		// variants), the NAV line before its rows, then code
		String order = ITEM.equals(scope) ? " ORDER BY x.name, x.nav_item_no, x.kind_order, x.code"
				: " ORDER BY x.name, x.code, x.id";
		return "SELECT x.kind, x.id, x.code, x.name, x.variant_description, x.nav_item_no, x.variant_count, x.parent_name,"
				+ " COUNT(*) OVER() AS total_count FROM (" + from + ") x" + order;
	}

	/** The derived table of a scope (public for the query test); :q and :ids as {@link #page} sets them. */
	public static String from(String scope, boolean filtered, boolean withIds) {
		switch (scope) {
			case ITEM: {
				// A NAV line matches when one of its active variants does: the matching numbers in a derived table joined
				// to the group (as the item cards' query), so the group still counts all its active variants
				String matched = !filtered ? ""
						: " JOIN (SELECT DISTINCT m.nav_item_no AS nav FROM item m WHERE " + active("m")
								+ " AND m.nav_item_no IS NOT NULL AND " + match("m", withIds) + ") kn ON kn.nav = i.nav_item_no";
				String navItems = "SELECT CAST('NAV_ITEM' AS varchar(10)) AS kind, 0 AS kind_order, MIN(i.id) AS id,"
						+ " i.nav_item_no AS code, MIN(i.name) AS name, CAST(NULL AS varchar(255)) AS variant_description,"
						+ " i.nav_item_no AS nav_item_no, COUNT(*) AS variant_count, CAST(NULL AS varchar(255)) AS parent_name"
						+ " FROM item i" + matched + " WHERE " + active("i") + " AND i.nav_item_no IS NOT NULL"
						+ " GROUP BY i.nav_item_no";
				String rows = "SELECT CAST('ROW' AS varchar(10)), 1, i.id, i.item_code, i.name, i.variant_description,"
						+ " i.nav_item_no, 1, CAST(NULL AS varchar(255)) FROM item i WHERE " + active("i")
						+ (filtered ? " AND " + match("i", withIds) : "");
				return navItems + " UNION ALL " + rows;
			}
			case FAMILY:
				return simple("FAMILY", "item_family", "CAST(NULL AS varchar(255))", "", filtered);
			case SUBFAMILY:
				return simple("SUBFAMILY", "item_sub_family", "p.name", " LEFT JOIN item_family p ON p.id = t.item_family_id",
						filtered);
			case CATEGORY:
				return simple("CATEGORY", "item_category", "CAST(NULL AS varchar(255))", "", filtered);
			case SECTION:
				return simple("SECTION", "item_section", "p.name", " LEFT JOIN item_sub_family p ON p.code = t.sub_family_code",
						filtered);
			default:
				throw new IllegalArgumentException("Unknown lookup scope: " + scope);
		}
	}

	/** An item that can be a line: active (1 or null), not a pack. */
	private static String active(String alias) {
		return "(" + alias + ".active IS NULL OR " + alias + ".active = 1) AND (" + alias + ".type IS NULL OR " + alias
				+ ".type <> 'PACKAGE')";
	}

	/** The search on an item: code, name, ERP item number, and the items of a matching barcode (:ids). */
	private static String match(String alias, boolean withIds) {
		return "(LOWER(" + alias + ".item_code) LIKE :q OR LOWER(" + alias + ".name) LIKE :q OR LOWER(" + alias
				+ ".nav_item_no) LIKE :q" + (withIds ? " OR " + alias + ".id IN (:ids)" : "") + ")";
	}

	/** Code and name of the active rows of a classification table, its parent's name when joined. */
	private static String simple(String kind, String table, String parent, String join, boolean filtered) {
		return "SELECT CAST('" + kind + "' AS varchar(10)) AS kind, 0 AS kind_order, t.id AS id, t.code AS code,"
				+ " t.name AS name, CAST(NULL AS varchar(255)) AS variant_description,"
				+ " CAST(NULL AS varchar(255)) AS nav_item_no, CAST(NULL AS int) AS variant_count, " + parent
				+ " AS parent_name FROM " + table + " t" + join + " WHERE (t.active IS NULL OR t.active = 1)"
				+ (filtered ? " AND (LOWER(t.code) LIKE :q OR LOWER(t.name) LIKE :q)" : "");
	}

	private static ItemLookupLineDTO line(Object[] row) {
		return new ItemLookupLineDTO(trimmed(row[0]), row[1] == null ? null : ((Number) row[1]).longValue(), (String) row[2],
				(String) row[3], (String) row[4], (String) row[5], row[6] == null ? null : ((Number) row[6]).intValue(),
				(String) row[7]);
	}

	/** CAST(... AS varchar(10)) may come back padded (char semantics on some databases). */
	private static String trimmed(Object value) {
		return value == null ? null : value.toString().trim();
	}
}
