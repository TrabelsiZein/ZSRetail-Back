package com.digithink.zsretail.core.support;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;


import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemComposition;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemCompositionRepository;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;

/**
 * Test support (step 6): the catalogue tables of one installation in memory (families, sub-families, items, barcodes,
 * packs), with repository stubs that apply the rules of their queries. One instance per installation. Plain Java, no
 * Spring, no mocking library. Code cleanup step 7: the head office's tables (stores, price lists, supply prices,
 * points de stock) are in headoffice.support.InMemoryCatalogue, which extends this, so the tests of core and of a store
 * need no head office class.
 */
public class InMemoryItems {


	public final Map<Long, ItemFamily> families = new LinkedHashMap<>();
	public final Map<Long, ItemSubFamily> subFamilies = new LinkedHashMap<>();
	public final Map<Long, Item> items = new LinkedHashMap<>();
	public final Map<Long, ItemBarcode> barcodes = new LinkedHashMap<>();
	public final Map<Long, ItemComposition> compositions = new LinkedHashMap<>();

	private long nextId;

	/** Saves per table, to prove that nothing was written. */
	public int itemSaves;
	/** Invoices from the ERP: ItemRepository.updateCost calls. */
	public int costUpdates;
	public int barcodeSaves;

	public InMemoryItems(long firstId) {
		this.nextId = firstId;
	}

	public long nextId() {
		return nextId++;
	}

	// ─── Rows ────────────────────────────────────────────────────

	public ItemFamily family(String code) {
		ItemFamily family = new ItemFamily();
		family.setCode(code);
		family.setName("Family " + code);
		family.setDisplayOrder(0);
		return put(families, family);
	}

	public ItemSubFamily subFamily(String code, ItemFamily family) {
		ItemSubFamily subFamily = new ItemSubFamily();
		subFamily.setCode(code);
		subFamily.setName("Sub-family " + code);
		subFamily.setDisplayOrder(0);
		subFamily.setItemFamily(family);
		return put(subFamilies, subFamily);
	}

	public Item item(String code, double price, ItemSubFamily subFamily) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName("Item " + code);
		item.setUnitPrice(price);
		item.setDefaultVAT(19);
		item.setItemSubFamily(subFamily);
		item.setItemFamily(subFamily == null ? null : subFamily.getItemFamily());
		return put(items, item);
	}

	public ItemBarcode barcode(String value, Item item) {
		ItemBarcode barcode = new ItemBarcode();
		barcode.setBarcode(value);
		barcode.setItem(item);
		return put(barcodes, barcode);
	}

	public ItemComposition component(Item pack, Item component, int quantity) {
		ItemComposition composition = new ItemComposition();
		composition.setParentItem(pack);
		composition.setComponentItem(component);
		composition.setQuantity(quantity);
		return put(compositions, composition);
	}

	public Optional<Item> itemByCode(String code) {
		return items.values().stream().filter(i -> code.equals(i.getItemCode())).findFirst();
	}

	public Optional<ItemBarcode> barcodeByValue(String value) {
		return barcodes.values().stream().filter(b -> value.equals(b.getBarcode())).findFirst();
	}

	protected <T extends _BaseEntity> T put(Map<Long, T> table, T row) {
		if (row.getId() == null) {
			row.setId(nextId());
		}
		table.put(row.getId(), row);
		return row;
	}

	protected <T extends _BaseEntity> Object common(Map<Long, T> table, String method, Object[] args) {
		switch (method) {
			case "findById":
				return Optional.ofNullable(table.get(args[0]));
			case "findAll":
				return new ArrayList<>(table.values());
			case "save":
				@SuppressWarnings("unchecked")
				T row = (T) args[0];
				return put(table, row);
			case "setOrigin": {
				T target = table.get(args[0]);
				if (target == null) {
					return 0;
				}
				setOrigin(target, (RecordOrigin) args[1]);
				return 1;
			}
			case "deleteById":
				table.remove(args[0]);
				return null;
			case "delete":
				table.remove(((_BaseEntity) args[0]).getId());
				return null;
			case "deleteAll":
				for (Object o : (Iterable<?>) args[0]) {
					table.remove(((_BaseEntity) o).getId());
				}
				return null;
			default:
				return UNHANDLED;
		}
	}

	// ─── Repositories ────────────────────────────────────────────

	public ItemFamilyRepository familyRepository() {
		return proxy(ItemFamilyRepository.class, (method, args) -> {
			switch (method) {
				case "findByCode":
					return families.values().stream().filter(f -> f.getCode().equals(args[0])).findFirst();
				case "findByCodeIn":
					return families.values().stream().filter(f -> ((Collection<?>) args[0]).contains(f.getCode()))
							.collect(Collectors.toList());
				case "findAllCodes":
					return families.values().stream().map(ItemFamily::getCode).collect(Collectors.toList());
				case "findByErpExternalId": // the ERP import
					return families.values().stream().filter(f -> Objects.equals(f.getErpExternalId(), args[0])).findFirst();
				default:
					return common(families, method, args);
			}
		});
	}

	public ItemSubFamilyRepository subFamilyRepository() {
		return proxy(ItemSubFamilyRepository.class, (method, args) -> {
			switch (method) {
				case "findByCode":
					return subFamilies.values().stream().filter(f -> f.getCode().equals(args[0])).findFirst();
				case "findByCodeIn":
					return subFamilies.values().stream().filter(f -> ((Collection<?>) args[0]).contains(f.getCode()))
							.collect(Collectors.toList());
				case "findAllCodes":
					return subFamilies.values().stream().map(ItemSubFamily::getCode).collect(Collectors.toList());
				case "findByErpExternalId": // the ERP import
					return subFamilies.values().stream().filter(f -> Objects.equals(f.getErpExternalId(), args[0]))
							.findFirst();
				default:
					return common(subFamilies, method, args);
			}
		});
	}

	public ItemRepository itemRepository() {
		return proxy(ItemRepository.class, (method, args) -> {
			switch (method) {
				case "findByItemCode":
					return itemByCode((String) args[0]);
				case "findByErpExternalId": // the ERP import
					return items.values().stream().filter(i -> Objects.equals(i.getErpExternalId(), args[0])).findFirst();
				case "findByItemCodeIn":
					return items.values().stream().filter(i -> ((Collection<?>) args[0]).contains(i.getItemCode()))
							.collect(Collectors.toList());
				case "findAllCodes":
					return items.values().stream().map(Item::getItemCode).collect(Collectors.toList());
				case "findAllById": // stock points
					return items.values().stream().filter(i -> ((Collection<?>) args[0]).contains(i.getId()))
							.collect(Collectors.toList());
				case "findByBarcode":
					return items.values().stream().filter(i -> Objects.equals(i.getBarcode(), args[0])).findFirst();
				case "findAllByBarcode":
					return items.values().stream().filter(i -> Objects.equals(i.getBarcode(), args[0])).collect(Collectors.toList());
				case "findByOwnPriceTrue":
					return items.values().stream().filter(i -> Boolean.TRUE.equals(i.getOwnPrice())).collect(Collectors.toList());
				case "countByOwnPriceTrue":
					return items.values().stream().filter(i -> Boolean.TRUE.equals(i.getOwnPrice())).count();
				case "updateCost": { // invoices from the ERP: the three costs only, never the stock
					Item item = items.get(args[0]);
					if (item == null) {
						return 0;
					}
					item.setLastDirectCost((Double) args[1]);
					item.setLastDirectNetCost((Double) args[2]);
					item.setCostPrice((Double) args[2]);
					item.setUpdatedBy((String) args[3]);
					costUpdates++;
					return 1;
				}
				case "save":
					itemSaves++;
					return common(items, method, args);
				default:
					return common(items, method, args);
			}
		});
	}

	public ItemBarcodeRepository barcodeRepository() {
		return proxy(ItemBarcodeRepository.class, (method, args) -> {
			switch (method) {
				case "findByBarcode":
					return barcodeByValue((String) args[0]);
				case "findByBarcodeIn":
					return barcodes.values().stream().filter(b -> ((Collection<?>) args[0]).contains(b.getBarcode()))
							.collect(Collectors.toList());
				case "findAllBarcodesExceptItem":
					return barcodes.values().stream().filter(b -> !b.getItem().getItemCode().equals(args[0]))
							.map(ItemBarcode::getBarcode).collect(Collectors.toList());
				case "findByItemId":
					return barcodes.values().stream().filter(b -> b.getItem().getId().equals(args[0]))
							.collect(Collectors.toList());
				case "findByItemIdIn": // stock points
					return barcodes.values().stream().filter(b -> ((Collection<?>) args[0]).contains(b.getItem().getId()))
							.collect(Collectors.toList());
				case "findByItemIdInAndActiveTrue": // stock points, step 4: the rows page counts the active ones
					return barcodes.values().stream().filter(b -> ((Collection<?>) args[0]).contains(b.getItem().getId())
							&& !Boolean.FALSE.equals(b.getActive())).collect(Collectors.toList());
				case "save":
					barcodeSaves++;
					return common(barcodes, method, args);
				default:
					return common(barcodes, method, args);
			}
		});
	}

	public ItemCompositionRepository compositionRepository() {
		return proxy(ItemCompositionRepository.class, (method, args) -> {
			switch (method) {
				case "findByParentItemIdIn":
					return compositions.values().stream()
							.filter(c -> ((Collection<?>) args[0]).contains(c.getParentItem().getId()))
							.collect(Collectors.toList());
				case "findByParentItemId":
					return compositions.values().stream().filter(c -> c.getParentItem().getId().equals(args[0]))
							.collect(Collectors.toList());
				default:
					return common(compositions, method, args);
			}
		});
	}

	protected static boolean contains(String column, String pattern) {
		return column != null && column.toLowerCase().contains(pattern.substring(1, pattern.length() - 1));
	}

	/** The origin as the @Modifying query writes it (the column is not updatable through a save). */
	protected static void setOrigin(_BaseEntity row, RecordOrigin origin) {
		if (row instanceof Item) {
			((Item) row).setOrigin(origin);
		} else if (row instanceof ItemFamily) {
			((ItemFamily) row).setOrigin(origin);
		} else if (row instanceof ItemSubFamily) {
			((ItemSubFamily) row).setOrigin(origin);
		} else if (row instanceof ItemBarcode) {
			((ItemBarcode) row).setOrigin(origin);
		}
	}
}
