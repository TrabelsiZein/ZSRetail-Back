package com.digithink.zsretail.support;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.digithink.zsretail.headoffice.model.HoPriceList;
import com.digithink.zsretail.headoffice.model.HoItemSupplyPrice;
import com.digithink.zsretail.headoffice.model.HoPriceListLine;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointRepository;
import com.digithink.zsretail.headoffice.repository.HoItemSupplyPriceRepository;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoPriceListLineRepository;
import com.digithink.zsretail.headoffice.repository.HoPriceListRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.ItemComposition;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository.ItemCompositionRepository;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;

/**
 * Test support (step 6): the catalogue tables of one installation in memory (families, sub-families, items, barcodes,
 * packs; at a head office also the stores and the price lists), with repository stubs that apply the rules of their
 * queries. One instance per installation. Plain Java, no Spring, no mocking library.
 */
public final class InMemoryCatalogue {

	public final Map<Long, ItemFamily> families = new LinkedHashMap<>();
	public final Map<Long, ItemSubFamily> subFamilies = new LinkedHashMap<>();
	public final Map<Long, Item> items = new LinkedHashMap<>();
	public final Map<Long, ItemBarcode> barcodes = new LinkedHashMap<>();
	public final Map<Long, ItemComposition> compositions = new LinkedHashMap<>();
	public final Map<Long, Store> stores = new LinkedHashMap<>();
	public final Map<Long, HoPriceList> priceLists = new LinkedHashMap<>();
	public final Map<Long, HoPriceListLine> priceLines = new LinkedHashMap<>();
	public final Map<Long, HoItemSupplyPrice> supplyPrices = new LinkedHashMap<>();
	public final Map<Long, HoStockPoint> stockPoints = new LinkedHashMap<>();
	public final Map<Long, HoStockPointItem> stockPointItems = new LinkedHashMap<>();

	private long nextId;

	/** Saves per table, to prove that nothing was written. */
	public int itemSaves;
	/** Invoices from the ERP: ItemRepository.updateCost calls. */
	public int costUpdates;
	public int barcodeSaves;

	public InMemoryCatalogue(long firstId) {
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

	public Store store(String code) {
		Store store = new Store();
		store.setCode(code);
		store.setName("Store " + code);
		store.setApiKeyHash("x");
		return put(stores, store);
	}

	public HoPriceList priceList(String code) {
		HoPriceList list = new HoPriceList();
		list.setCode(code);
		list.setName("List " + code);
		return put(priceLists, list);
	}

	public HoPriceListLine priceLine(HoPriceList list, Item item, double price) {
		HoPriceListLine line = new HoPriceListLine();
		line.setPriceListId(list.getId());
		line.setItemId(item.getId());
		line.setPrice(price);
		return put(priceLines, line);
	}

	public Optional<Item> itemByCode(String code) {
		return items.values().stream().filter(i -> code.equals(i.getItemCode())).findFirst();
	}

	public Optional<ItemBarcode> barcodeByValue(String value) {
		return barcodes.values().stream().filter(b -> value.equals(b.getBarcode())).findFirst();
	}

	private <T extends _BaseEntity> T put(Map<Long, T> table, T row) {
		if (row.getId() == null) {
			row.setId(nextId());
		}
		table.put(row.getId(), row);
		return row;
	}

	private <T extends _BaseEntity> Object common(Map<Long, T> table, String method, Object[] args) {
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
				case "findByItemCodeIn":
					return items.values().stream().filter(i -> ((Collection<?>) args[0]).contains(i.getItemCode()))
							.collect(Collectors.toList());
				case "findAllCodes":
					return items.values().stream().map(Item::getItemCode).collect(Collectors.toList());
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

	public StoreRepository storeRepository() {
		return proxy(StoreRepository.class, (method, args) -> {
			switch (method) {
				case "findIdsBySellingPriceListId":
					return stores.values().stream().filter(s -> Objects.equals(s.getSellingPriceListId(), args[0]))
							.map(Store::getId).collect(Collectors.toList());
				case "countBySellingPriceListId":
					return stores.values().stream().filter(s -> Objects.equals(s.getSellingPriceListId(), args[0]))
							.count();
				case "findByCodeIgnoreCase":
					return stores.values().stream().filter(s -> s.getCode().equalsIgnoreCase((String) args[0])).findFirst();
				case "findByErpCustomerNoIgnoreCase": // invoices from the ERP
					return stores.values().stream()
							.filter(s -> s.getErpCustomerNo() != null && s.getErpCustomerNo().equalsIgnoreCase((String) args[0]))
							.findFirst();
				case "countBySupplyPriceListId":
					return stores.values().stream().filter(s -> Objects.equals(s.getSupplyPriceListId(), args[0]))
							.count();
				case "countByStockPointId": // stock points
					return stores.values().stream().filter(s -> Objects.equals(s.getStockPointId(), args[0])).count();
				default:
					return common(stores, method, args);
			}
		});
	}

	/** Stock points: ho_stock_point, with the rules of its queries. */
	public HoStockPointRepository stockPointRepository() {
		return proxy(HoStockPointRepository.class, (method, args) -> {
			switch (method) {
				case "findByCodeIgnoreCase":
					return stockPoints.values().stream().filter(p -> p.getCode().equalsIgnoreCase((String) args[0]))
							.findFirst();
				case "findAllByOrderBySortOrderAscCodeAsc":
					return stockPoints.values().stream()
							.sorted(java.util.Comparator.comparing(HoStockPoint::getSortOrder)
									.thenComparing(HoStockPoint::getCode))
							.collect(Collectors.toList());
				case "saveAll":
					List<HoStockPoint> saved = new ArrayList<>();
					for (Object o : (Iterable<?>) args[0]) {
						saved.add(put(stockPoints, (HoStockPoint) o));
					}
					return saved;
				default:
					return common(stockPoints, method, args);
			}
		});
	}

	/** Stock points: ho_stock_point_item. */
	public HoStockPointItemRepository stockPointItemRepository() {
		return proxy(HoStockPointItemRepository.class, (method, args) -> {
			if ("countByStockPointId".equals(method)) {
				return stockPointItems.values().stream().filter(r -> Objects.equals(r.getStockPointId(), args[0])).count();
			}
			return common(stockPointItems, method, args);
		});
	}

	/** Stock points: a row of the point for the item. */
	public HoStockPointItem stockPointItem(HoStockPoint point, Item item, double unitPrice) {
		HoStockPointItem row = new HoStockPointItem();
		row.setStockPointId(point.getId());
		row.setItemId(item.getId());
		row.setName(item.getName());
		row.setUnitPrice(unitPrice);
		return put(stockPointItems, row);
	}

	public HoPriceListRepository priceListRepository() {
		return proxy(HoPriceListRepository.class, (method, args) -> {
			if ("findByCodeIgnoreCase".equals(method)) {
				return priceLists.values().stream().filter(l -> l.getCode().equalsIgnoreCase((String) args[0])).findFirst();
			}
			return common(priceLists, method, args);
		});
	}

	/** Step 7B: a base supply price for the item. */
	public HoItemSupplyPrice supplyPrice(Item item, double price) {
		HoItemSupplyPrice row = new HoItemSupplyPrice();
		row.setItemId(item.getId());
		row.setPrice(price);
		return put(supplyPrices, row);
	}

	/** Step 7B: ho_item_supply_price, with the rules of its queries. */
	public HoItemSupplyPriceRepository supplyPriceRepository() {
		return proxy(HoItemSupplyPriceRepository.class, (method, args) -> {
			switch (method) {
				case "findByItemId":
					return supplyPrices.values().stream().filter(s -> s.getItemId().equals(args[0])).findFirst();
				case "findByItemIdIn":
					return supplyPrices.values().stream().filter(s -> ((Collection<?>) args[0]).contains(s.getItemId()))
							.collect(Collectors.toList());
				case "findPage": {
					Collection<?> types = (Collection<?>) args[0];
					String search = (String) args[2];
					List<Object[]> rows = items.values().stream()
							.filter(i -> i.getType() == null || types.contains(i.getType()))
							.filter(i -> !args[1].equals(i.getItemCode()))
							.filter(i -> search == null || contains(i.getItemCode(), search) || contains(i.getName(), search))
							.sorted(java.util.Comparator.comparing(Item::getItemCode))
							.map(i -> new Object[] { i, supplyPrices.values().stream()
									.filter(s -> s.getItemId().equals(i.getId())).map(HoItemSupplyPrice::getPrice)
									.findFirst().orElse(null) })
							.collect(Collectors.toList());
					org.springframework.data.domain.Pageable page = (org.springframework.data.domain.Pageable) args[3];
					int from = (int) Math.min(rows.size(), page.getOffset());
					int to = Math.min(rows.size(), from + page.getPageSize());
					return new org.springframework.data.domain.PageImpl<>(new java.util.ArrayList<>(rows.subList(from, to)),
							page, rows.size());
				}
				default:
					return common(supplyPrices, method, args);
			}
		});
	}

	private static boolean contains(String column, String pattern) {
		return column != null && column.toLowerCase().contains(pattern.substring(1, pattern.length() - 1));
	}

	public HoPriceListLineRepository priceLineRepository() {
		return proxy(HoPriceListLineRepository.class, (method, args) -> {
			switch (method) {
				case "findByPriceListId":
					return lines(l -> l.getPriceListId().equals(args[0]));
				case "findByPriceListIdAndItemIdIn":
					return lines(l -> l.getPriceListId().equals(args[0]) && ((Collection<?>) args[1]).contains(l.getItemId()));
				case "findByPriceListIdAndItemId":
					return lines(l -> l.getPriceListId().equals(args[0]) && l.getItemId().equals(args[1])).stream()
							.findFirst();
				case "findByItemId":
					return lines(l -> l.getItemId().equals(args[0]));
				case "countByPriceListId":
					return (long) lines(l -> l.getPriceListId().equals(args[0])).size();
				case "findLines": { // [line, item] of a list by item code; search ignored by the tests that use it
					List<Object[]> rows = lines(l -> l.getPriceListId().equals(args[0])).stream()
							.map(l -> new Object[] { l, items.get(l.getItemId()) })
							.sorted(java.util.Comparator.comparing(r -> ((Item) r[1]).getItemCode()))
							.collect(Collectors.toList());
					org.springframework.data.domain.Pageable page = (org.springframework.data.domain.Pageable) args[2];
					return new org.springframework.data.domain.PageImpl<>(rows, page, rows.size());
				}
				case "findItemCodes":
					return lines(l -> l.getPriceListId().equals(args[0])).stream()
							.map(l -> items.get(l.getItemId()).getItemCode()).collect(Collectors.toList());
				default:
					return common(priceLines, method, args);
			}
		});
	}

	/** The origin as the @Modifying query writes it (the column is not updatable through a save). */
	private static void setOrigin(_BaseEntity row, RecordOrigin origin) {
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

	private List<HoPriceListLine> lines(java.util.function.Predicate<HoPriceListLine> filter) {
		return priceLines.values().stream().filter(filter).collect(Collectors.toList());
	}
}
