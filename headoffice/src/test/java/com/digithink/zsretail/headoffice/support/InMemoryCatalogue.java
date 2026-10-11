package com.digithink.zsretail.headoffice.support;

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
import com.digithink.zsretail.core.support.InMemoryItems;
import com.digithink.zsretail.headoffice.model.HoItemSupplyPrice;
import com.digithink.zsretail.headoffice.model.HoPriceList;
import com.digithink.zsretail.headoffice.model.HoPriceListLine;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoItemSupplyPriceRepository;
import com.digithink.zsretail.headoffice.repository.HoPriceListLineRepository;
import com.digithink.zsretail.headoffice.repository.HoPriceListRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;

/**
 * Test support (step 6): the catalogue tables of one installation in memory, and at a head office also the stores, the
 * price lists, the supply prices and the points de stock. Code cleanup step 7: the item tables are core's
 * ({@link InMemoryItems}); this adds the head office's.
 */
public final class InMemoryCatalogue extends InMemoryItems {

	public final Map<Long, Store> stores = new LinkedHashMap<>();
	public final Map<Long, HoPriceList> priceLists = new LinkedHashMap<>();
	public final Map<Long, HoPriceListLine> priceLines = new LinkedHashMap<>();
	public final Map<Long, HoItemSupplyPrice> supplyPrices = new LinkedHashMap<>();
	public final Map<Long, HoStockPoint> stockPoints = new LinkedHashMap<>();
	public final Map<Long, HoStockPointItem> stockPointItems = new LinkedHashMap<>();

	public InMemoryCatalogue(long firstId) {
		super(firstId);
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
				case "findIdsByStockPointId":
					return stores.values().stream().filter(s -> Objects.equals(s.getStockPointId(), args[0]))
							.map(Store::getId).collect(Collectors.toList());
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
			if ("findByStockPointIdAndItemIdIn".equals(method)) {
				return stockPointItems.values().stream().filter(r -> Objects.equals(r.getStockPointId(), args[0])
						&& ((Collection<?>) args[1]).contains(r.getItemId())).collect(Collectors.toList());
			}
			if ("findStoreAndItemIds".equals(method)) {
				List<Object[]> pairs = new ArrayList<>();
				for (HoStockPointItem row : stockPointItems.values()) {
					if (((Collection<?>) args[0]).contains(row.getItemId())) {
						stores.values().stream().filter(s -> Objects.equals(s.getStockPointId(), row.getStockPointId()))
								.forEach(s -> pairs.add(new Object[] { s.getId(), row.getItemId() }));
					}
				}
				return pairs;
			}
			if ("findItemCodesOfPoints".equals(method)) {
				return itemsOfPoints((Collection<?>) args[0], (String) args[1]).stream().map(Item::getItemCode)
						.distinct().sorted().collect(Collectors.toList());
			}
			if ("findBarcodesOfPoints".equals(method)) {
				List<Long> ofPoints = itemsOfPoints((Collection<?>) args[0], (String) args[1]).stream().map(Item::getId)
						.collect(Collectors.toList());
				return barcodes.values().stream().filter(b -> b.getItem() != null && ofPoints.contains(b.getItem().getId()))
						.map(ItemBarcode::getBarcode).distinct().sorted().collect(Collectors.toList());
			}
			return common(stockPointItems, method, args);
		});
	}


	/** The items with a row in these points, except the one of this code (the rules of the two queries). */
	private List<Item> itemsOfPoints(Collection<?> pointIds, String excludedItemCode) {
		return stockPointItems.values().stream().filter(r -> pointIds.contains(r.getStockPointId()))
				.map(r -> items.get(r.getItemId())).filter(i -> i != null && !excludedItemCode.equals(i.getItemCode()))
				.distinct().collect(Collectors.toList());
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

	private List<HoPriceListLine> lines(java.util.function.Predicate<HoPriceListLine> filter) {
		return priceLines.values().stream().filter(filter).collect(Collectors.toList());
	}
}
