package com.digithink.zsretail.headoffice.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeStandalone;
import com.digithink.zsretail.headoffice.dto.PriceListDTO;
import com.digithink.zsretail.headoffice.dto.PriceListLineDTO;
import com.digithink.zsretail.headoffice.model.HoPriceList;
import com.digithink.zsretail.headoffice.model.HoPriceListLine;
import com.digithink.zsretail.headoffice.repository.HoPriceListLineRepository;
import com.digithink.zsretail.headoffice.repository.HoPriceListRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.repository.ItemRepository;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 6.4: the selling price lists of a head office without an ERP. A list holds only the items whose
 * price differs from the base price; a store has one list or none. A line created, changed or deleted sends the item
 * again to the stores on that list only; a store's list change sends the items of the old and the new list to that
 * store only ({@link #storeListChanged}). A list used by a store cannot be deactivated or deleted. See
 * docs/modules/head-office.md, "Price lists".
 */
@Service
@ConditionalOnHeadOfficeStandalone
@Log4j2
public class HoPriceListService {

	static final String CODE_REQUIRED = "The price list code is required.";
	static final String NAME_REQUIRED = "The price list name is required.";
	static final String CODE_IS_FINAL = "The price list code cannot be changed after creation.";
	static final String USED_BY_STORE = "This price list is the selling price list of %d store(s): choose another list"
			+ " for them on the Stores page first.";

	private final HoPriceListRepository lists;
	private final HoPriceListLineRepository lines;
	private final StoreRepository stores;
	private final ItemRepository items;
	private final Supplier<HoCatalogueService> catalogue;

	@Autowired
	public HoPriceListService(HoPriceListRepository lists, HoPriceListLineRepository lines, StoreRepository stores,
			ItemRepository items, ObjectProvider<HoCatalogueService> catalogue) {
		this(lists, lines, stores, items, (Supplier<HoCatalogueService>) catalogue::getObject);
	}

	/** With given collaborators: used by the tests. */
	public HoPriceListService(HoPriceListRepository lists, HoPriceListLineRepository lines, StoreRepository stores,
			ItemRepository items, Supplier<HoCatalogueService> catalogue) {
		this.lists = lists;
		this.lines = lines;
		this.stores = stores;
		this.items = items;
		this.catalogue = catalogue;
	}

	// ─── Lists ───────────────────────────────────────────────────

	public List<PriceListDTO> findAll() {
		return lists.findAll().stream().sorted((a, b) -> a.getCode().compareTo(b.getCode())).map(this::view)
				.collect(Collectors.toList());
	}

	public Optional<PriceListDTO> findById(Long id) {
		return lists.findById(id).map(this::view);
	}

	/** 400 (IllegalArgument) when code or name is missing or the code is too long; 409 (IllegalState) when it exists. */
	@Transactional
	public PriceListDTO create(PriceListDTO input) {
		String code = normalizeCode(input.getCode());
		if (code.isEmpty()) {
			throw new IllegalArgumentException(CODE_REQUIRED);
		}
		if (code.length() > HoPriceList.CODE_LENGTH) {
			throw new IllegalArgumentException("The price list code is longer than " + HoPriceList.CODE_LENGTH
					+ " characters.");
		}
		String name = input.getName() == null ? "" : input.getName().trim();
		if (name.isEmpty()) {
			throw new IllegalArgumentException(NAME_REQUIRED);
		}
		if (lists.findByCodeIgnoreCase(code).isPresent()) {
			throw new IllegalStateException("A price list with the code " + code + " already exists.");
		}
		HoPriceList list = new HoPriceList();
		list.setCode(code);
		list.setName(name);
		list.setActive(input.getActive() == null || input.getActive());
		return view(lists.save(list));
	}

	/**
	 * Name and active (each when sent). 400 when the code differs; 409 when it deactivates a list used by a store. A list
	 * used by no store changes no price, so nothing is sent.
	 */
	@Transactional
	public Optional<PriceListDTO> update(Long id, PriceListDTO input) {
		Optional<HoPriceList> found = lists.findById(id);
		if (!found.isPresent()) {
			return Optional.empty();
		}
		HoPriceList list = found.get();
		if (input.getCode() != null && !normalizeCode(input.getCode()).equals(list.getCode())) {
			throw new IllegalArgumentException(CODE_IS_FINAL);
		}
		if (input.getName() != null) {
			String name = input.getName().trim();
			if (name.isEmpty()) {
				throw new IllegalArgumentException(NAME_REQUIRED);
			}
			list.setName(name);
		}
		if (Boolean.FALSE.equals(input.getActive())) {
			refuseWhenUsed(id);
		}
		if (input.getActive() != null) {
			list.setActive(input.getActive());
		}
		return Optional.of(view(lists.save(list)));
	}

	/** False when unknown. 409 (IllegalState) when a store uses it; otherwise the list and its lines are deleted. */
	@Transactional
	public boolean delete(Long id) {
		if (!lists.findById(id).isPresent()) {
			return false;
		}
		refuseWhenUsed(id);
		List<HoPriceListLine> rows = lines.findByPriceListId(id);
		if (!rows.isEmpty()) {
			lines.deleteAll(rows);
		}
		lists.deleteById(id);
		return true;
	}

	private void refuseWhenUsed(Long id) {
		long used = stores.countBySellingPriceListId(id);
		if (used > 0) {
			throw new IllegalStateException(String.format(USED_BY_STORE, used));
		}
	}

	/** For the stores page: 400 (IllegalArgument) unless the list exists and is active. */
	public void checkAssignable(Long id) {
		HoPriceList list = lists.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Unknown price list id " + id + "."));
		if (Boolean.FALSE.equals(list.getActive())) {
			throw new IllegalArgumentException("The price list " + list.getCode() + " is inactive.");
		}
	}

	// ─── Lines ───────────────────────────────────────────────────

	/** A page of the list's lines by item code; search on the item code and name (contains, any case). */
	public Optional<Page<PriceListLineDTO>> lines(Long id, String search, int page, int size) {
		if (!lists.findById(id).isPresent()) {
			return Optional.empty();
		}
		String like = search == null || search.trim().isEmpty() ? null
				: "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
		PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
		return Optional.of(lines.findLines(id, like, request).map(row -> lineView((HoPriceListLine) row[0], (Item) row[1])));
	}

	/**
	 * Creates or replaces lines, all or none: 400 (IllegalArgument) naming the first problem (unknown item, price
	 * missing or below zero). Each line whose price changed sends its item again to the stores on this list. Empty when
	 * the list does not exist.
	 */
	@Transactional
	public Optional<List<PriceListLineDTO>> putLines(Long id, List<PriceListLineDTO> input) {
		if (!lists.findById(id).isPresent()) {
			return Optional.empty();
		}
		if (input == null || input.isEmpty()) {
			throw new IllegalArgumentException("Send at least one line.");
		}
		Map<Long, Item> byItem = new LinkedHashMap<>();
		Map<Long, Double> prices = new LinkedHashMap<>();
		for (PriceListLineDTO line : input) {
			Item item = resolveItem(line);
			Double price = line.getPrice();
			if (price == null || price.isNaN() || price.isInfinite() || price < 0) {
				throw new IllegalArgumentException("The price of " + item.getItemCode() + " must be 0 or more.");
			}
			byItem.put(item.getId(), item);
			prices.put(item.getId(), price);
		}
		StoreTargets onList = StoreTargets.of(stores.findIdsBySellingPriceListId(id));
		List<PriceListLineDTO> result = new ArrayList<>();
		for (Map.Entry<Long, Item> entry : byItem.entrySet()) {
			Item item = entry.getValue();
			Double price = prices.get(entry.getKey());
			HoPriceListLine line = lines.findByPriceListIdAndItemId(id, item.getId()).orElse(null);
			boolean changed = line == null || !price.equals(line.getPrice());
			if (line == null) {
				line = new HoPriceListLine();
				line.setPriceListId(id);
				line.setItemId(item.getId());
			}
			line.setPrice(price);
			line = lines.save(line);
			if (changed && !onList.getStoreIds().isEmpty()) {
				catalogue.get().recordItem(item.getItemCode(), onList);
			}
			result.add(lineView(line, item));
		}
		return Optional.of(result);
	}

	private Item resolveItem(PriceListLineDTO line) {
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

	/** False when the list or the line is unknown. The item goes back to the base price for the stores on the list. */
	@Transactional
	public boolean deleteLine(Long listId, Long lineId) {
		Optional<HoPriceListLine> found = lines.findById(lineId).filter(line -> line.getPriceListId().equals(listId));
		if (!found.isPresent()) {
			return false;
		}
		HoPriceListLine line = found.get();
		lines.delete(line);
		StoreTargets onList = StoreTargets.of(stores.findIdsBySellingPriceListId(listId));
		if (!onList.getStoreIds().isEmpty()) {
			items.findById(line.getItemId()).ifPresent(item -> catalogue.get().recordItem(item.getItemCode(), onList));
		}
		return true;
	}

	/**
	 * A store's list changed (StoreService, same transaction): the items of the old and the new list are sent again to
	 * that store only, each with its price for the store as it is now.
	 */
	@Transactional
	public void storeListChanged(Long storeId, Long previousListId, Long newListId) {
		Set<String> codes = new LinkedHashSet<>();
		if (previousListId != null) {
			codes.addAll(lines.findItemCodes(previousListId));
		}
		if (newListId != null) {
			codes.addAll(lines.findItemCodes(newListId));
		}
		StoreTargets store = StoreTargets.of(java.util.Collections.singleton(storeId));
		for (String code : codes) {
			catalogue.get().recordItem(code, store);
		}
	}

	private PriceListDTO view(HoPriceList list) {
		return new PriceListDTO(list.getId(), list.getCode(), list.getName(), !Boolean.FALSE.equals(list.getActive()),
				lines.countByPriceListId(list.getId()), stores.countBySellingPriceListId(list.getId()));
	}

	private static PriceListLineDTO lineView(HoPriceListLine line, Item item) {
		return new PriceListLineDTO(line.getId(), item.getId(), item.getItemCode(), item.getName(), item.getUnitPrice(),
				line.getPrice());
	}

	static String normalizeCode(String code) {
		return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
	}
}
