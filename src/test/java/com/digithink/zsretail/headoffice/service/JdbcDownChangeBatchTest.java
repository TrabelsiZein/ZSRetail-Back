package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import com.digithink.zsretail.headoffice.model.HoDownChange;
import com.digithink.zsretail.model.enumeration.DataDomain;

/**
 * Stock points (speed): the JDBC batch of a store whose point changed. An UPDATE per code, an INSERT for the codes
 * without a row; a count the driver does not give stops the transaction. Through CopiesDownFeed, the numbers are those of
 * the JPA path.
 */
class JdbcDownChangeBatchTest {

	@Test
	@DisplayName("Updated where the store has a row, inserted where it has none, with the code's number")
	@SuppressWarnings("unchecked")
	void updateThenInsert() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.batchUpdate(eq(JdbcDownChangeBatch.UPDATE), anyList())).thenReturn(new int[] { 1, 0, 1 });
		Map<String, Long> versions = new LinkedHashMap<>();
		versions.put("ITEM:A", 11L);
		versions.put("ITEM:B", 12L);
		versions.put("BARCODE:1", 13L);
		assertEquals(1, new JdbcDownChangeBatch(jdbc).write(DataDomain.CATALOGUE, 7, versions));
		ArgumentCaptor<List<Object[]>> inserts = ArgumentCaptor.forClass(List.class);
		verify(jdbc).batchUpdate(eq(JdbcDownChangeBatch.INSERT), inserts.capture());
		assertEquals(1, inserts.getValue().size());
		Object[] row = inserts.getValue().get(0);
		assertArrayEquals(new Object[] { 12L, "CATALOGUE", "ITEM:B", 7L }, Arrays.copyOfRange(row, 2, 6));
	}

	@Test
	@DisplayName("No count from the driver: the batch stops (the transaction rolls back), nothing inserted")
	void noCount() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.batchUpdate(eq(JdbcDownChangeBatch.UPDATE), anyList())).thenReturn(new int[] { -2 });
		assertThrows(IllegalStateException.class, () -> new JdbcDownChangeBatch(jdbc).write(DataDomain.CATALOGUE, 7,
				Collections.singletonMap("ITEM:A", 1L)));
		verify(jdbc, never()).batchUpdate(eq(JdbcDownChangeBatch.INSERT), anyList());
	}

	@Test
	@DisplayName("Through the feed: the same numbers as the JPA path, in the order given, one sequence update")
	void sameNumbers() {
		InMemoryDownTables jpa = new InMemoryDownTables();
		CopiesDownFeed jpaFeed = jpa.feed(Collections.emptyList());
		jpa.sequences.put(DataDomain.CATALOGUE, 100L);
		jpaFeed.recordChangesForStore(DataDomain.CATALOGUE, Arrays.asList("ITEM:A", "ITEM:B", "ITEM:A", ""), 7);

		InMemoryDownTables jdbcTables = new InMemoryDownTables();
		CopiesDownFeed jdbcFeed = jdbcTables.feed(Collections.emptyList());
		jdbcTables.sequences.put(DataDomain.CATALOGUE, 100L);
		JdbcDownChangeBatch batch = mock(JdbcDownChangeBatch.class);
		jdbcFeed.setBatch(batch);
		jdbcFeed.recordChangesForStore(DataDomain.CATALOGUE, Arrays.asList("ITEM:A", "ITEM:B", "ITEM:A", ""), 7);

		Map<String, Long> expected = new LinkedHashMap<>();
		for (HoDownChange change : jpa.changes) {
			expected.put(change.getRecordCode(), change.getChangeVersion());
		}
		assertEquals(2, expected.size());
		assertEquals(Long.valueOf(101), expected.get("ITEM:A"));
		verify(batch).write(DataDomain.CATALOGUE, 7, expected);
		assertEquals(Long.valueOf(102), jdbcTables.sequences.get(DataDomain.CATALOGUE));
		assertEquals(0, jdbcTables.changes.size(), "the batch wrote the rows, not JPA");
	}
}
