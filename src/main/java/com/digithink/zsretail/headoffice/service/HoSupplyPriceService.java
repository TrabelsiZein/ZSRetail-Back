package com.digithink.zsretail.headoffice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeOwnSupply;
import com.digithink.zsretail.headoffice.dto.SupplyPriceDTO;
import com.digithink.zsretail.headoffice.enumeration.SupplyPriceMode;
import com.digithink.zsretail.headoffice.model.HoItemSupplyPrice;
import com.digithink.zsretail.headoffice.model.HoPriceListLine;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoItemSupplyPriceRepository;
import com.digithink.zsretail.headoffice.repository.HoPriceListLineRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.repository.ItemRepository;

/**
 * Head office plan, step 7B: the supply price, what a store whose deliveries are invoiced pays for a head office item.
 * <ul>
 * <li>PRICE_LIST mode (default): the line of the store's supply price list (a list of kind SUPPLY) for the item,
 * otherwise the item's base supply price (ho_item_supply_price), otherwise none.</li>
 * <li>PERCENT_OFF mode: the selling price the head office works out for that store (the line of its selling price list,
 * otherwise item.unitPrice; never a price the store set itself) minus ho_store.supply_discount_percent.</li>
 * </ul>
 * Before VAT, rounded to the millime (half up). Used only when an invoice is created: a supply price never travels to a
 * store. Head office without an ERP only. See docs/modules/head-office.md, "Supply prices".
 */
@Service
@ConditionalOnHeadOfficeOwnSupply
public class HoSupplyPriceService {

	static final List<ItemType> PRICED_TYPES = Arrays.asList(ItemType.PRODUCT, ItemType.PACKAGE);
	static final int DEFAULT_SIZE = 20;
	static final int MAX_SIZE = 200;

	private final HoItemSupplyPriceRepository supplyPrices;
	private final HoPriceListLineRepository listLines;
	private final ItemRepository items;

	public HoSupplyPriceService(HoItemSupplyPriceRepository supplyPrices, HoPriceListLineRepository listLines,
			ItemRepository items) {
		this.supplyPrices = supplyPrices;
		this.listLines = listLines;
		this.items = items;
	}

	// ─── The price of a store ────────────────────────────────────

	/**
	 * The supply price of each item for this store, by item id; an item without one is absent. 409 (IllegalState) in
	 * PERCENT_OFF mode when the store has no percentage.
	 */
	public Map<Long, Double> pricesFor(Store store, Collection<Item> forItems) {
		List<Long> ids = forItems.stream().map(Item::getId).collect(Collectors.toList());
		Map<Long, Double> prices = new HashMap<>();
		if (ids.isEmpty()) {
			return prices;
		}
		if (store.getSupplyPriceMode() == SupplyPriceMode.PERCENT_OFF) {
			Double percent = store.getSupplyDiscountPercent();
			if (percent == null) {
				throw new IllegalStateException("The store " + store.getCode()
						+ " takes a percentage off its selling price, but no percentage is set on the Stores page.");
			}
			Map<Long, Double> selling = linePrices(store.getSellingPriceListId(), ids);
			for (Item item : forItems) {
				Double price = selling.containsKey(item.getId()) ? selling.get(item.getId()) : item.getUnitPrice();
				if (price != null) {
					prices.put(item.getId(), round(price * (1 - percent / 100.0)));
				}
			}
			return prices;
		}
		Map<Long, Double> list = linePrices(store.getSupplyPriceListId(), ids);
		Map<Long, Double> base = new HashMap<>();
		for (HoItemSupplyPrice row : supplyPrices.findByItemIdIn(ids)) {
			base.put(row.getItemId(), row.getPrice());
		}
		for (Long id : ids) {
			Double price = list.containsKey(id) ? list.get(id) : base.get(id);
			if (price != null) {
				prices.put(id, round(price));
			}
		}
		return prices;
	}

	private Map<Long, Double> linePrices(Long listId, List<Long> itemIds) {
		Map<Long, Double> prices = new HashMap<>();
		if (listId != null) {
			for (HoPriceListLine line : listLines.findByPriceListIdAndItemIdIn(listId, itemIds)) {
				prices.put(line.getItemId(), line.getPrice());
			}
		}
		return prices;
	}

	/** To the millime, half up. */
	public static double round(double amount) {
		return BigDecimal.valueOf(amount).setScale(3, RoundingMode.HALF_UP).doubleValue();
	}

	// ─── Base supply prices ──────────────────────────────────────

	/**
	 * The products and packs (not the tax stamp) by code with their base selling price and base supply price: {content,
	 * totalElements, totalPages, number, size}; search on code and name.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> page(String search, Integer page, Integer size) {
		int number = page == null ? 0 : page;
		if (number < 0) {
			throw new IllegalArgumentException("page must be 0 or more");
		}
		int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, size));
		String like = search == null || search.trim().isEmpty() ? null
				: "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
		Page<Object[]> rows = supplyPrices.findPage(PRICED_TYPES, CatalogueKind.TAX_STAMP_CODE, like,
				PageRequest.of(number, pageSize));
		List<SupplyPriceDTO> content = new ArrayList<>();
		for (Object[] row : rows.getContent()) {
			Item item = (Item) row[0];
			content.add(new SupplyPriceDTO(item.getId(), item.getItemCode(), item.getName(), item.getUnitPrice(),
					row[1] == null ? null : ((Number) row[1]).doubleValue()));
		}
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("content", content);
		answer.put("totalElements", rows.getTotalElements());
		answer.put("totalPages", rows.getTotalPages());
		answer.put("number", rows.getNumber());
		answer.put("size", rows.getSize());
		return answer;
	}

	/**
	 * Sets base supply prices, all or none: each line names its item (itemCode, or itemId) and a price of 0 or more, or
	 * null to delete it. 400 (IllegalArgument) naming the first problem. Nothing is sent to any store.
	 */
	@Transactional
	public List<SupplyPriceDTO> putPrices(List<SupplyPriceDTO> input) {
		if (input == null || input.isEmpty()) {
			throw new IllegalArgumentException("Send at least one line.");
		}
		Map<Item, Double> wanted = new LinkedHashMap<>();
		for (SupplyPriceDTO line : input) {
			Item item = resolve(line);
			Double price = line.getSupplyPrice();
			if (price != null && (price.isNaN() || price.isInfinite() || price < 0)) {
				throw new IllegalArgumentException("The supply price of " + item.getItemCode() + " must be 0 or more.");
			}
			wanted.put(item, price);
		}
		List<SupplyPriceDTO> result = new ArrayList<>();
		for (Map.Entry<Item, Double> entry : wanted.entrySet()) {
			Item item = entry.getKey();
			HoItemSupplyPrice row = supplyPrices.findByItemId(item.getId()).orElse(null);
			if (entry.getValue() == null) {
				if (row != null) {
					supplyPrices.delete(row);
				}
			} else {
				if (row == null) {
					row = new HoItemSupplyPrice();
					row.setItemId(item.getId());
				}
				row.setPrice(round(entry.getValue()));
				supplyPrices.save(row);
			}
			result.add(new SupplyPriceDTO(item.getId(), item.getItemCode(), item.getName(), item.getUnitPrice(),
					entry.getValue() == null ? null : round(entry.getValue())));
		}
		return result;
	}

	private Item resolve(SupplyPriceDTO line) {
		if (line.getItemCode() != null && !line.getItemCode().trim().isEmpty()) {
			String code = line.getItemCode().trim();
			return items.findByItemCode(code)
					.orElseThrow(() -> new IllegalArgumentException("Unknown item code " + code + "."));
		}
		if (line.getItemId() != null) {
			return items.findById(line.getItemId())
					.orElseThrow(() -> new IllegalArgumentException("Unknown item id " + line.getItemId() + "."));
		}
		throw new IllegalArgumentException("Each line needs an itemCode (or an itemId).");
	}
}
