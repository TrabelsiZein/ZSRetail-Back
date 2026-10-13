package com.digithink.zsretail.core.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 3.1.0: the till's search query (PosItemSearchRepository). Native SQL Server (COLLATE), so its text is checked here; it
 * was run on the local copies of a Happyness store and store B (2026-10-13).
 */
class PosItemSearchRepositoryTest {

	@Test
	@DisplayName("One condition per word on name or code, accents ignored, a row limit, by name; the words as LIKE patterns")
	void queryAndParameters() {
		String[] sql = new String[1];
		Object[][] args = new Object[1][];
		JdbcTemplate jdbc = new JdbcTemplate() {
			@Override
			@SuppressWarnings("unchecked")
			public <T> List<T> queryForList(String query, Class<T> type, Object... values) {
				sql[0] = query;
				args[0] = values;
				return (List<T>) Arrays.asList(4L, 2L);
			}
		};

		List<Long> ids = new PosItemSearchRepository(jdbc).findIds(Arrays.asList("vanil", "1l"), 31);

		assertEquals(Arrays.asList(4L, 2L), ids);
		assertTrue(sql[0].startsWith("SELECT TOP (?) i.id FROM item i"), sql[0]);
		assertTrue(sql[0].endsWith(" ORDER BY i.name, i.id"), sql[0]);
		assertEquals(4, sql[0].split("COLLATE Latin1_General_CI_AI LIKE \\? ESCAPE", -1).length - 1,
				"name and code for each of the 2 words");
		assertArrayEquals(new Object[] { 31, "%vanil%", "%vanil%", "%1l%", "%1l%" }, args[0]);
	}

	@Test
	@DisplayName("The items of the grid: the conditions of ItemService.listedInPos and of POS_GRID_COUNTS (active sub-family or none)")
	void gridConditions() {
		String sql = PosItemSearchRepository.sql(1);
		assertTrue(sql.contains("(i.active IS NULL OR i.active = 1)"), sql);
		assertTrue(sql.contains("(i.show_in_pos IS NULL OR i.show_in_pos = 1)"), sql);
		assertTrue(sql.contains("i.unit_price > 0"), sql);
		assertTrue(sql.contains("(sf.id IS NULL OR sf.active IS NULL OR sf.active = 1)"), sql);
		assertTrue(ItemRepository.POS_GRID_COUNTS.contains("(sf.id is null or sf.active is null or sf.active = true)"));
	}

	@Test
	@DisplayName("A typed % _ [ or \\ is searched as itself, never as a wildcard")
	void wildcardsEscaped() {
		assertEquals("10\\%", PosItemSearchRepository.escapeLike("10%"));
		assertEquals("a\\_b", PosItemSearchRepository.escapeLike("a_b"));
		assertEquals("\\[x]", PosItemSearchRepository.escapeLike("[x]"));
		assertEquals("c\\\\d", PosItemSearchRepository.escapeLike("c\\d"));
		assertEquals("vanille", PosItemSearchRepository.escapeLike("vanille"));
	}
}
