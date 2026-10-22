package com.digithink.zsretail.core.utils;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.digithink.zsretail.core.model.Item;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * 3.1.0: the items of an ITEM_GROUP promotion as a request sends them. {@link Item} equality leaves out the id (Lombok,
 * {@code callSuper = false}), so items sent as {@code { id }} only were all "equal" and Jackson's set kept one of them
 * (Emtop, 1.12.1: a group saved with one item). This reads the ids only, one per distinct id, each into an item carrying
 * that id, in a set by identity that never compares items. {@code PromotionService.save} then loads these ids from the
 * database; the rest of what the request sends for an item is not used. An element may be {@code { id, ... }} or a bare
 * id; an element without an id is refused (400).
 */
public class GroupItemsDeserializer extends StdDeserializer<Set<Item>> {

	private static final long serialVersionUID = 1L;

	public GroupItemsDeserializer() {
		super(Set.class);
	}

	@Override
	public Set<Item> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
		JsonNode array = p.readValueAsTree();
		if (array == null || array.isNull()) {
			return null;
		}
		if (!array.isArray()) {
			return ctxt.reportInputMismatch(this, "groupItems: an array of items is expected");
		}
		Map<Long, Item> byId = new LinkedHashMap<>();
		for (JsonNode element : array) {
			Long value = idOf(element.isObject() ? element.get("id") : element);
			if (value == null) {
				return ctxt.reportInputMismatch(this, "groupItems: every item needs its id (" + element + ")");
			}
			byId.computeIfAbsent(value, key -> {
				Item item = new Item();
				item.setId(key);
				return item;
			});
		}
		Set<Item> items = Collections.newSetFromMap(new IdentityHashMap<>());
		items.addAll(byId.values());
		return items;
	}

	/** A whole number, or a text holding one; null for anything else (no id, null, 1.5, "abc"). */
	private static Long idOf(JsonNode id) {
		if (id == null) {
			return null;
		}
		if (id.isIntegralNumber() && id.canConvertToLong()) {
			return id.asLong();
		}
		if (id.isTextual()) {
			try {
				return Long.valueOf(id.asText().trim());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}
}
