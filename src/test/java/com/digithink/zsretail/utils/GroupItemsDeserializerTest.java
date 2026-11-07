package com.digithink.zsretail.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.Promotion;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

/**
 * 1.12.1: Promotion.groupItems as a request sends it. Item equality leaves out the id, so before GroupItemsDeserializer
 * items sent as { id } only were collapsed into one by Jackson's set (a group saved with one item). Spring's
 * ObjectMapper builder, no Spring context, no database.
 */
class GroupItemsDeserializerTest {

	private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

	@Test
	@DisplayName("items sent as { id } only keep every id")
	void idsOnly() throws Exception {
		Promotion promotion = read("{\"scope\":\"ITEM_GROUP\",\"groupItems\":[{\"id\":3},{\"id\":1},{\"id\":2}]}");

		assertEquals(3, promotion.getGroupItems().size());
		assertEquals(set(1L, 2L, 3L), ids(promotion));
	}

	@Test
	@DisplayName("{ id, itemCode }, a bare id and an id as text are read; a duplicate id counts once")
	void shapesAndDuplicates() throws Exception {
		Promotion promotion = read("{\"groupItems\":[{\"id\":5,\"itemCode\":\"ART-5\"},5,\"6\",{\"id\":7,\"name\":\"x\"}]}");

		assertEquals(3, promotion.getGroupItems().size());
		assertEquals(set(5L, 6L, 7L), ids(promotion));
		assertTrue(promotion.getGroupItems().stream().allMatch(item -> item.getItemCode() == null),
				"only the id is read, the item is loaded from the database at save");
	}

	@Test
	@DisplayName("an element without an id is refused")
	void elementWithoutId() {
		assertThrows(MismatchedInputException.class, () -> read("{\"groupItems\":[{\"id\":1},{\"itemCode\":\"ART-2\"}]}"));
		assertThrows(MismatchedInputException.class, () -> read("{\"groupItems\":[{\"id\":1.5}]}"));
		assertThrows(MismatchedInputException.class, () -> read("{\"groupItems\":{\"id\":1}}"));
	}

	@Test
	@DisplayName("null groupItems stays null, absent groupItems keeps the empty default")
	void nullAndAbsent() throws Exception {
		assertNull(read("{\"groupItems\":null}").getGroupItems());
		assertTrue(read("{\"scope\":\"ITEM\"}").getGroupItems().isEmpty());
	}

	private Promotion read(String json) throws Exception {
		return mapper.readValue(json, Promotion.class);
	}

	private static Set<Long> ids(Promotion promotion) {
		return promotion.getGroupItems().stream().map(Item::getId).collect(Collectors.toSet());
	}

	private static Set<Long> set(Long... ids) {
		return new HashSet<>(Arrays.asList(ids));
	}
}
