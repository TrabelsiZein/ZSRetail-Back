package com.digithink.zsretail.core.repository;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 3.1.0: the till's search over the whole catalogue (the POS screen's search box, whatever level is open). Native SQL
 * because SQL Server alone ignores accents here ({@link #COLLATION}: "creme" finds "CRÈME") and the number of words
 * varies. Each word must appear anywhere in the item's name or code, in any order. The items are those the grid lists
 * ({@code ItemService.listedInPos}, in an active sub-family or none: the conditions of
 * {@link ItemRepository#POS_GRID_COUNTS}). One scan of the item table with a row limit: 2 to 25 ms on the local copies
 * of 7,806 and 3,181 items (2026-10-13). Barcodes are not searched here (a part of a barcode is never typed; a whole one
 * is matched exactly by ItemService through its unique index).
 */
@Repository
public class PosItemSearchRepository {

	/** Case and accent insensitive; the code page of French_CI_AS, so the varchar columns compare as stored. */
	static final String COLLATION = "Latin1_General_CI_AI";

	static final String WORD = "(i.name COLLATE " + COLLATION + " LIKE ? ESCAPE '\\' OR i.item_code COLLATE " + COLLATION
			+ " LIKE ? ESCAPE '\\')";

	static final String SELECT = "SELECT TOP (?) i.id FROM item i LEFT JOIN item_sub_family sf ON sf.id = i.item_sub_family_id"
			+ " WHERE (i.active IS NULL OR i.active = 1) AND (i.show_in_pos IS NULL OR i.show_in_pos = 1)"
			+ " AND i.unit_price > 0 AND (sf.id IS NULL OR sf.active IS NULL OR sf.active = 1)";

	private final JdbcTemplate jdbc;

	public PosItemSearchRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The ids of the items whose name or code holds every word, ordered by name, at most {@code limit}.
	 *
	 * @param words the words typed, none empty (the caller splits and caps them)
	 * @param limit the most ids returned
	 */
	public List<Long> findIds(List<String> words, int limit) {
		List<Object> args = new ArrayList<>();
		args.add(limit);
		for (String word : words) {
			String pattern = "%" + escapeLike(word) + "%";
			args.add(pattern);
			args.add(pattern);
		}
		return jdbc.queryForList(sql(words.size()), Long.class, args.toArray());
	}

	/** The query for this many words. */
	static String sql(int words) {
		StringBuilder sql = new StringBuilder(SELECT);
		for (int i = 0; i < words; i++) {
			sql.append(" AND ").append(WORD);
		}
		return sql.append(" ORDER BY i.name, i.id").toString();
	}

	/** A typed % _ [ or \ is searched as itself, never as a wildcard. */
	static String escapeLike(String word) {
		return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace("[", "\\[");
	}
}
