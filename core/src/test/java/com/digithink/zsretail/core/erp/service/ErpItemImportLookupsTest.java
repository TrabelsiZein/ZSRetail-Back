package com.digithink.zsretail.core.erp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.support.InMemoryItems;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;

/**
 * 3.1.0 speed (items step of the ERP import): one packet of items reads its existing items in one query by ERP id and
 * one by code, the families and sub-families once, never one SELECT per row (each also flushed the packet). The rows
 * are written as before: the existing one updated in place, a new one created, family and sub-family by ERP id or code,
 * a blank brand never erasing the item's.
 */
class ErpItemImportLookupsTest {

	private InMemoryItems tables;
	private final List<String> calls = new ArrayList<>();
	private ErpItemBootstrapService imports;
	private ItemSubFamily chem;

	/** The repository, its calls recorded by method name. */
	@SuppressWarnings("unchecked")
	private <T> T counted(Class<T> type, T target) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			calls.add(type.getSimpleName() + "." + method.getName());
			try {
				return method.invoke(target, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		});
	}

	@BeforeEach
	void setUp() {
		tables = new InMemoryItems(1);
		ItemFamily hom = tables.family("HOM");
		hom.setErpExternalId("HOM");
		chem = tables.subFamily("CHEM", hom);
		Item shirt = tables.item("70061~001", 50.0, chem);
		shirt.setErpExternalId("70061~001");
		shirt.setBrand("Marque Zen");
		Item tie = tables.item("14ZDU05~001", 19.5, null); // known by its code only
		tie.setBrand("Zen");
		GeneralSetupService setup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return null;
			}
		};
		imports = new ErpItemBootstrapService(counted(ItemFamilyRepository.class, tables.familyRepository()),
				counted(ItemSubFamilyRepository.class, tables.subFamilyRepository()),
				counted(ItemRepository.class, tables.itemRepository()), tables.barcodeRepository(), null, null, null,
				null, null, setup, tables.categoryRepository(), tables.sectionRepository());
	}

	private static ErpItemDTO dto(String code, String name, String subFamily, String brand) {
		ErpItemDTO dto = new ErpItemDTO();
		dto.setExternalId(code);
		dto.setCode(code);
		dto.setName(name);
		dto.setUnitPrice(new BigDecimal("19.5"));
		dto.setDefaultVAT(19);
		dto.setActive(true);
		dto.setSubFamilyExternalId(subFamily);
		dto.setBrand(brand);
		return dto;
	}

	@Test
	@DisplayName("A packet of 3 rows: one items query by ERP id, one by code, families and sub-families read once; no"
			+ " query per row")
	void oneQueryPerPacket() {
		imports.importItems(Arrays.asList(dto("70061~001", "CHEMISE S", "CHEM", null),
				dto("14ZDU05~001", "CRAVATE", "CHEM", "ZEN"), dto("NEW~001", "NOUVEAU", "CHEM", null)));

		List<String> reads = new ArrayList<>(calls);
		reads.removeIf(call -> call.endsWith(".save"));
		assertEquals(Arrays.asList("ItemRepository.findByErpExternalIdIn", "ItemRepository.findByItemCodeIn",
				"ItemSubFamilyRepository.findAll"), reads, "no findByErpExternalId / findByItemCode / findByCode per row");
		assertEquals(3, calls.stream().filter("ItemRepository.save"::equals).count());
	}

	@Test
	@DisplayName("The rows as before: the item found by ERP id or by code updated in place, a new one created, the"
			+ " sub-family and its family set, a blank brand keeps the item's")
	void sameResults() {
		Item shirtBefore = tables.itemByCode("70061~001").get();
		Item tieBefore = tables.itemByCode("14ZDU05~001").get();
		List<Item> saved = imports.importItems(Arrays.asList(dto("70061~001", "CHEMISE S", "CHEM", null),
				dto("14ZDU05~001", "CRAVATE", "CHEM", "ZEN"), dto("NEW~001", "NOUVEAU", "CHEM", null),
				dto("NEW~001", "NOUVEAU again", "CHEM", null)));

		assertSame(shirtBefore, saved.get(0), "found by its ERP id");
		assertEquals("CHEMISE S", shirtBefore.getName());
		assertEquals("Marque Zen", shirtBefore.getBrand(), "a blank brand never erases the item's");
		assertSame(tieBefore, saved.get(1), "found by its code");
		assertEquals("ZEN", tieBefore.getBrand(), "a brand given is written");
		assertEquals("14ZDU05~001", tieBefore.getErpExternalId(), "its ERP id set");
		Item created = saved.get(2);
		assertEquals("NEW~001", created.getItemCode(), "created");
		assertSame(chem, created.getItemSubFamily());
		assertSame(chem.getItemFamily(), created.getItemFamily(), "the family from the sub-family");
		assertSame(created, saved.get(3), "the same code twice in a packet: the row created first, updated");
		assertEquals("NOUVEAU again", created.getName());
		assertNull(tables.itemByCode("NONE").orElse(null));
	}
}
