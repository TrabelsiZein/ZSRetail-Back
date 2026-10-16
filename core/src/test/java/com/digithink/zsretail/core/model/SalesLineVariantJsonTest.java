package com.digithink.zsretail.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Item variants (ZEN), step 11c: a sale line reaches the front with its whole item, the variant description included,
 * so the till, the printed ticket and a reprinted ticket show "name — variant" from the sale's own answer (the sale,
 * the split, the pending and table tickets, the ticket history). A @JsonIgnore on the field would silently print the
 * name alone. Plain Jackson, no Spring context.
 */
class SalesLineVariantJsonTest {

	private static SalesLine line(String variantDescription) {
		Item item = new Item();
		item.setId(7L);
		item.setItemCode("70061~001");
		item.setName("CHEMISE");
		item.setNavItemNo("70061");
		item.setVariantDescription(variantDescription);
		SalesLine line = new SalesLine();
		line.setItem(item);
		line.setQuantity(BigDecimal.ONE);
		line.setUnitPrice(19.5);
		return line;
	}

	@Test
	@DisplayName("The line's item carries variantDescription (null for an item without variant)")
	void itemVariantDescription() throws Exception {
		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
		JsonNode item = mapper.valueToTree(line("S / Noir / 1er Choix")).get("item");
		assertEquals("CHEMISE", item.get("name").asText());
		assertEquals("S / Noir / 1er Choix", item.get("variantDescription").asText());

		JsonNode plain = mapper.valueToTree(line(null)).get("item");
		assertTrue(plain.has("variantDescription"));
		assertTrue(plain.get("variantDescription").isNull());
	}
}
