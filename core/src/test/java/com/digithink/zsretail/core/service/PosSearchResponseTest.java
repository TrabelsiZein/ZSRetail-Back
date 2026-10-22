package com.digithink.zsretail.core.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.inject;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.controller.ItemAPI;
import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 3.1.0 (ZEN), GET /item/pos/search as JSON (ItemAPI.searchForPos over the real PosCatalogueService and stubs). The
 * regression of EMTOP and Happyness: without catégories and sections the answer is, byte for byte, the one built before
 * the levels ({families, subFamilies, content, more, exactId}, the entities as they are) and the two tables are never
 * read. With the levels: the groups categories and sections, every hit the entity's own fields plus its path.
 */
class PosSearchResponseTest {

	private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 22, 20, 0);

	private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
	private final List<ItemCategory> categories = new ArrayList<>();
	private final List<ItemSection> sections = new ArrayList<>();
	private boolean withLevels;
	private ItemFamily hom;
	private ItemSubFamily chem;
	private Item shirt;
	private ItemAPI api;

	@BeforeEach
	void setUp() throws Exception {
		hom = at(new ItemFamily());
		hom.setId(11L);
		hom.setCode("HOM");
		hom.setName("Homme chemises");
		hom.setCategoryCode("VET");
		hom.setImageFilename("hom.jpg");
		chem = at(new ItemSubFamily());
		chem.setId(21L);
		chem.setCode("CHEM");
		chem.setName("Chemise");
		chem.setItemFamily(hom);
		shirt = at(new Item());
		shirt.setId(7L);
		shirt.setItemCode("CHEM-001");
		shirt.setName("Chemise blanche");
		shirt.setUnitPrice(30.0);
		ItemCategory vet = at(new ItemCategory());
		vet.setId(1L);
		vet.setCode("VET");
		vet.setName("Vêtements chemises");
		categories.add(vet);
		ItemSection manche = at(new ItemSection());
		manche.setId(31L);
		manche.setCode("MANCHE");
		manche.setName("Chemise avec manche");
		manche.setSubFamilyCode("CHEM");
		sections.add(manche);

		PosCatalogueService catalogue = new PosCatalogueService();
		inject(catalogue, "itemRepository", proxy(ItemRepository.class, (method, args) -> {
			switch (method) {
				case "countPosGridItems": return List.<Object[]>of(new Object[] { 11L, 11L, 21L, 1L });
				case "countPosSectionItems": return List.<Object[]>of(new Object[] { 21L, "MANCHE", 1L });
				case "countPosCategoryItems": return List.of();
				default: return UNHANDLED;
			}
		}));
		inject(catalogue, "itemFamilyRepository", proxy(ItemFamilyRepository.class,
				(method, args) -> "findAll".equals(method) && args.length == 0 ? List.of(hom) : UNHANDLED));
		inject(catalogue, "itemSubFamilyRepository", proxy(ItemSubFamilyRepository.class, (method, args) -> {
			switch (method) {
				case "findAll": return List.of(chem);
				case "findByCodeIn": return List.of(chem);
				default: return UNHANDLED;
			}
		}));
		// without the levels, any read of these two tables fails the test (UNHANDLED)
		inject(catalogue, "itemCategoryRepository", proxy(ItemCategoryRepository.class,
				(method, args) -> withLevels && "findAll".equals(method) ? categories : UNHANDLED));
		inject(catalogue, "itemSectionRepository", proxy(ItemSectionRepository.class,
				(method, args) -> withLevels && "findAll".equals(method) ? sections : UNHANDLED));
		inject(catalogue, "itemTreeService", new ItemTreeService(null, null, null, null) {
			@Override
			public boolean hasCategories() {
				return withLevels;
			}

			@Override
			public boolean hasSections() {
				return withLevels;
			}
		});
		GeneralSetupService setup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return null; // POS_SHOW_IMAGES unset: images shown, as by default
			}
		};
		inject(catalogue, "generalSetupService", setup);
		ItemService items = new ItemService() {
			@Override
			public PosSearch searchPos(String query) {
				return new PosSearch(new ArrayList<>(List.of(shirt)), false, 7L);
			}
		};
		api = new ItemAPI();
		set(api, _BaseController.class, "service", items);
		set(api, ItemAPI.class, "posCatalogue", catalogue);
		set(api, ItemAPI.class, "generalSetupService", setup);
	}

	private static <T extends _BaseEntity> T at(T entity) {
		entity.setCreatedAt(AT);
		entity.setUpdatedAt(AT);
		return entity;
	}

	private static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private String answer(String q) throws Exception {
		return json.writeValueAsString(api.searchForPos(q).getBody());
	}

	private static Set<String> keys(JsonNode node) {
		Set<String> keys = new TreeSet<>();
		node.fieldNames().forEachRemaining(keys::add);
		return keys;
	}

	@Test
	@DisplayName("Without catégories and sections: the very JSON of before, five keys, no path, the two tables never read")
	void levelsOffSameAnswer() throws Exception {
		Map<String, Object> before = new HashMap<>(); // ItemAPI.searchForPos before 3.1.0 (ZEN)'s levels
		before.put("families", List.of(hom));
		before.put("subFamilies", List.of(chem));
		before.put("content", List.of(shirt));
		before.put("more", false);
		before.put("exactId", 7L);

		String answer = answer("chemise");

		assertEquals(json.writeValueAsString(before), answer);
		assertEquals(new TreeSet<>(Arrays.asList("content", "exactId", "families", "more", "subFamilies")),
				keys(json.readTree(answer)));
		assertFalse(answer.contains("\"path\""));
	}

	@Test
	@DisplayName("With the levels: categories and sections, each hit the entity's own fields unchanged plus its path")
	void levelsOnHitsWithPaths() throws Exception {
		withLevels = true;
		JsonNode answer = json.readTree(answer("chemises"));
		assertEquals(new TreeSet<>(
				Arrays.asList("categories", "content", "exactId", "families", "more", "sections", "subFamilies")),
				keys(answer));

		ObjectNode family = (ObjectNode) answer.get("families").get(0);
		assertEquals("[{\"level\":\"category\",\"id\":1,\"code\":\"VET\",\"name\":\"Vêtements chemises\"},"
				+ "{\"level\":\"family\",\"id\":11,\"code\":\"HOM\",\"name\":\"Homme chemises\"}]",
				json.writeValueAsString(family.get("path")));
		family.remove("path");
		assertEquals(json.readTree(json.writeValueAsString(hom)), family, "the family's own fields, as before");

		assertEquals("VET", answer.get("categories").get(0).get("code").asText());
		assertEquals(1, answer.get("categories").get(0).get("path").size());
		assertEquals(0, answer.get("subFamilies").size(), "\"chemises\" is not in \"Chemise\"");
		assertEquals(0, answer.get("sections").size(), "\"chemises\" is not in \"Chemise avec manche\" either");

		JsonNode chemise = json.readTree(answer("chemise"));
		ObjectNode subFamily = (ObjectNode) chemise.get("subFamilies").get(0);
		assertEquals(3, subFamily.get("path").size());
		subFamily.remove("path");
		assertEquals(json.readTree(json.writeValueAsString(chem)), subFamily);
		JsonNode manche = chemise.get("sections").get(0);
		assertEquals("MANCHE", manche.get("code").asText());
		assertEquals(Arrays.asList("category", "family", "subFamily", "section"),
				Arrays.asList(manche.get("path").get(0).get("level").asText(), manche.get("path").get(1).get("level").asText(),
						manche.get("path").get(2).get("level").asText(), manche.get("path").get(3).get("level").asText()));
		assertEquals("CHEM-001", chemise.get("content").get(0).get("itemCode").asText(), "the items group unchanged");
		assertEquals(7L, chemise.get("exactId").asLong());
	}
}
