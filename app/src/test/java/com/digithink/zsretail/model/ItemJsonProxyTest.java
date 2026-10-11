package com.digithink.zsretail.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.hibernate.bytecode.internal.bytebuddy.BytecodeProviderImpl;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.ProxyFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.digithink.zsretail.model.enumeration.PromotionScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON of an Item that Hibernate hands out as a proxy. Promotion.getItem is LAZY: when a page of
 * promotions holds a cross-product promotion whose benefit item is also the target item (or a group
 * item) of a later promotion, both fields get the same proxy, and GET /promotion/paginated failed
 * with "No serializer found for class ByteBuddyInterceptor". Uses Hibernate's real ByteBuddy proxy
 * and Spring's ObjectMapper builder (no Spring context, no database).
 */
class ItemJsonProxyTest {

	private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

	@Test
	@DisplayName("promotion list: target item shared with another promotion's benefit item serializes")
	void promotionPageWithProxiedTargetItem() throws Exception {
		Item shared = initializedProxy(item(7L, "ART-7"));
		Promotion crossProduct = promotion(18L, PromotionScope.ITEM, item(3L, "ART-3"));
		crossProduct.setGetItem(shared);
		Promotion target = promotion(12L, PromotionScope.ITEM, shared);

		JsonNode page = toTree(new PageImpl<>(Arrays.asList(crossProduct, target), PageRequest.of(0, 20), 2));

		JsonNode targetItem = page.get("content").get(1).get("item");
		assertEquals(7L, targetItem.get("id").asLong());
		assertEquals("ART-7", targetItem.get("itemCode").asText());
		assertEquals("Family 1", targetItem.get("itemFamily").get("name").asText());
		assertFalse(targetItem.has("hibernateLazyInitializer"));
		assertEquals("ART-7", page.get("content").get(0).get("getItem").get("itemCode").asText());
	}

	@Test
	@DisplayName("group promotion: a proxied group item serializes")
	void groupPromotionWithProxiedItem() throws Exception {
		Promotion group = promotion(20L, PromotionScope.ITEM_GROUP, null);
		group.getGroupItems().add(initializedProxy(item(7L, "ART-7")));

		JsonNode groupItem = toTree(group).get("groupItems").get(0);

		assertEquals("ART-7", groupItem.get("itemCode").asText());
		assertFalse(groupItem.has("hibernateLazyInitializer"));
		assertFalse(groupItem.has("itemFamily"), "the field's own ignore list still applies");
	}

	@Test
	@DisplayName("a proxied item serializes exactly like the loaded item")
	void proxyJsonEqualsLoadedItemJson() throws Exception {
		Item loaded = item(7L, "ART-7");

		assertEquals(toTree(loaded), toTree(initializedProxy(loaded)));
	}

	@Test
	@DisplayName("a loaded item keeps all its fields, and a promotion read back from the list keeps its item")
	void loadedItemUnchangedAndRoundTrip() throws Exception {
		JsonNode loaded = toTree(item(7L, "ART-7"));
		for (String field : Arrays.asList("id", "itemCode", "name", "unitPrice", "itemFamily", "itemSubFamily",
				"active", "createdAt")) {
			assertTrue(loaded.has(field), field);
		}

		Promotion target = promotion(12L, PromotionScope.ITEM, initializedProxy(item(7L, "ART-7")));
		Promotion readBack = mapper.readValue(mapper.writeValueAsString(target), Promotion.class);

		assertEquals(7L, readBack.getItem().getId().longValue());
		assertEquals("ART-7", readBack.getItem().getItemCode());
		assertEquals(Item.class, readBack.getItem().getClass());
	}

	private JsonNode toTree(Object value) throws Exception {
		return mapper.readTree(mapper.writeValueAsString(value));
	}

	/** The proxy Hibernate stores in a lazy Item field, once loaded (as an EAGER field sharing it does). */
	private static Item initializedProxy(Item loaded) throws Exception {
		ProxyFactory factory = new BytecodeProviderImpl().getProxyFactoryFactory().buildProxyFactory(null);
		factory.postInstantiate(Item.class.getName(), Item.class, Collections.<Class>singleton(HibernateProxy.class),
				Item.class.getMethod("getId"), Item.class.getMethod("setId", Long.class), null);
		HibernateProxy proxy = factory.getProxy(loaded.getId(), null);
		proxy.getHibernateLazyInitializer().setImplementation(loaded);
		return (Item) proxy;
	}

	private static Item item(long id, String code) {
		ItemFamily family = new ItemFamily();
		family.setId(1L);
		family.setName("Family 1");
		Item item = new Item();
		item.setId(id);
		item.setItemCode(code);
		item.setName("Item " + code);
		item.setUnitPrice(10.0);
		item.setItemFamily(family);
		return item;
	}

	private static Promotion promotion(long id, PromotionScope scope, Item item) {
		Promotion promotion = new Promotion();
		promotion.setId(id);
		promotion.setCode("P" + id);
		promotion.setName("Promotion " + id);
		promotion.setScope(scope);
		promotion.setItem(item);
		return promotion;
	}
}
