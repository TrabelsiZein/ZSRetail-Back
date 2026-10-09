package com.digithink.zsretail.headoffice.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.config.ConditionalOnHeadOfficeWithoutErp;
import com.digithink.zsretail.headoffice.dto.StockReportDTO;
import com.digithink.zsretail.headoffice.model.HoStoreStock;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoStoreStockRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.utils.Quantities;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 7A.5: the stock of every store, copied up (POST /ho/supply/stock), and the head office page of
 * the stock per store and item next to the head office's own stock. Head office without an ERP only. A store sends the
 * items whose stock changed since the head office last accepted it; each batch is saved in one transaction (a row per
 * store and item code, replaced), the codes the store no longer has are deleted. See docs/modules/head-office.md, "Stock
 * of the stores".
 */
@Service
@ConditionalOnHeadOfficeWithoutErp
@Log4j2
public class HoNetworkStockService {

	static final List<ItemType> STOCK_TYPES = Arrays.asList(ItemType.PRODUCT, ItemType.PACKAGE);
	static final int DEFAULT_SIZE = 20;
	static final int MAX_SIZE = 200;
	static final int CODE_LENGTH = 100;

	private final HoStoreStockRepository stocks;
	private final StoreRepository stores;
	private final TransactionOperations writeTransactions;
	private final Supplier<LocalDateTime> clock;

	/** False on a head office with headoffice.stock.enabled=false: no head office column, belowZero ignores it. */
	private final boolean keepsStock;

	@Autowired
	public HoNetworkStockService(HoStoreStockRepository stocks, StoreRepository stores,
			PlatformTransactionManager transactionManager, ApplicationModeService mode) {
		this(stocks, stores, new TransactionTemplate(transactionManager), LocalDateTime::now,
				!mode.isHeadOfficeWithoutStock());
	}

	/** With given transactions and clock, the head office keeping its stock: used by the tests. */
	public HoNetworkStockService(HoStoreStockRepository stocks, StoreRepository stores,
			TransactionOperations writeTransactions, Supplier<LocalDateTime> clock) {
		this(stocks, stores, writeTransactions, clock, true);
	}

	/** With given transactions, clock, and whether the head office keeps its stock. */
	public HoNetworkStockService(HoStoreStockRepository stocks, StoreRepository stores,
			TransactionOperations writeTransactions, Supplier<LocalDateTime> clock, boolean keepsStock) {
		this.keepsStock = keepsStock;
		this.stocks = stocks;
		this.stores = stores;
		this.writeTransactions = writeTransactions;
		this.clock = clock;
	}

	/**
	 * One batch of a store's stock, in one transaction: each item saved by code (an item without a code or with one over
	 * 100 characters is skipped), each removed code deleted. 400 (IllegalArgument) for a takenAt that cannot be read.
	 */
	public StockReportDTO.Answer receive(Store store, StockReportDTO report) {
		LocalDateTime takenAt = parse(report == null ? null : report.getTakenAt());
		List<StockReportDTO.Item> items = report == null || report.getItems() == null ? new ArrayList<>()
				: report.getItems().stream().filter(i -> i != null && i.getItemCode() != null
						&& !i.getItemCode().trim().isEmpty() && i.getItemCode().trim().length() <= CODE_LENGTH)
						.collect(Collectors.toList());
		List<String> removed = report == null || report.getRemoved() == null ? new ArrayList<>()
				: report.getRemoved().stream().filter(c -> c != null && !c.trim().isEmpty()).map(String::trim)
						.collect(Collectors.toList());
		StockReportDTO.Answer answer = writeTransactions.execute(status -> {
			LocalDateTime now = clock.get();
			Map<String, HoStoreStock> existing = new HashMap<>();
			if (!items.isEmpty()) {
				for (HoStoreStock row : stocks.findByStoreIdAndItemCodeIn(store.getId(),
						items.stream().map(i -> i.getItemCode().trim()).collect(Collectors.toList()))) {
					existing.put(row.getItemCode(), row);
				}
			}
			List<HoStoreStock> rows = new ArrayList<>();
			for (StockReportDTO.Item item : items) {
				String code = item.getItemCode().trim();
				HoStoreStock row = existing.computeIfAbsent(code, c -> {
					HoStoreStock created = new HoStoreStock();
					created.setStoreId(store.getId());
					created.setItemCode(c);
					return created;
				});
				row.setItemName(item.getItemName());
				row.setQuantity(item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity()); // 2.2.1: as sent (9.8)
				row.setOwnItem(item.isOwn());
				row.setStoreTime(takenAt);
				row.setReceivedAt(now);
				rows.add(row);
			}
			stocks.saveAll(rows);
			int deleted = 0;
			if (!removed.isEmpty()) {
				List<HoStoreStock> gone = stocks.findByStoreIdAndItemCodeIn(store.getId(), removed);
				stocks.deleteAll(gone);
				deleted = gone.size();
			}
			return new StockReportDTO.Answer(rows.size(), deleted);
		});
		log.info("Head office stock: store '{}' sent {} items, {} removed", store.getCode(), answer.getSaved(),
				answer.getRemoved());
		return answer;
	}

	/**
	 * The head office items, by code, with the head office stock and the stock of each store:
	 * {stores: [{id, code, name, lastStockAt}], content: [{itemCode, itemName, headOffice, byStore: {"&lt;storeId&gt;":
	 * quantity}}], totalElements, totalPages, number, size}. storeId: one store, null or 0: every active store. A store
	 * that never sent an item has no entry for it. belowZero true: only the items whose stock is below zero in a column
	 * shown (the head office, or the store asked for, or any active store). A head office without stock
	 * (headoffice.stock.enabled=false): headOffice is null and belowZero looks at the stores only.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> page(Long storeId, String search, Integer page, Integer size, boolean belowZero) {
		long wanted = storeId == null ? 0L : storeId;
		List<Store> shown = shownStores(wanted);
		PageRequest request = PageRequest.of(pageNumber(page), pageSize(size));
		Page<Object[]> items = keepsStock
				? stocks.findHeadOfficeItems(STOCK_TYPES, CatalogueKind.TAX_STAMP_CODE, like(search), wanted, belowZero,
						request)
				: stocks.findHeadOfficeItemsStoresOnly(STOCK_TYPES, CatalogueKind.TAX_STAMP_CODE, like(search), wanted,
						belowZero, request);
		List<String> codes = items.getContent().stream().map(row -> (String) row[0]).collect(Collectors.toList());
		Map<String, Map<String, BigDecimal>> byCode = new HashMap<>();
		if (!codes.isEmpty()) {
			for (HoStoreStock row : stocks.findByCodes(codes, wanted)) {
				byCode.computeIfAbsent(row.getItemCode(), c -> new LinkedHashMap<>())
						.put(String.valueOf(row.getStoreId()), row.getQuantity());
			}
		}
		List<Map<String, Object>> content = new ArrayList<>();
		for (Object[] row : items.getContent()) {
			Map<String, Object> line = new LinkedHashMap<>();
			line.put("itemCode", row[0]);
			line.put("itemName", row[1]);
			// 2.2.1: the head office stock with its decimals, without trailing zeros
			line.put("headOffice", !keepsStock ? null : row[2] == null ? BigDecimal.ZERO : Quantities.normalize((BigDecimal) row[2]));
			line.put("byStore", byCode.getOrDefault((String) row[0], new LinkedHashMap<>()));
			content.add(line);
		}
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("stores", storeViews(shown));
		answer.put("content", content);
		answer.put("totalElements", items.getTotalElements());
		answer.put("totalPages", items.getTotalPages());
		answer.put("number", items.getNumber());
		answer.put("size", items.getSize());
		return answer;
	}

	/**
	 * The stores' own items (not from the head office): {content: [{storeId, storeCode, storeName, itemCode, itemName,
	 * quantity, storeTime, receivedAt}], totalElements, totalPages, number, size}, by store and code. belowZero true: only
	 * those whose stock is below zero.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> ownItems(Long storeId, String search, Integer page, Integer size, boolean belowZero) {
		Page<HoStoreStock> rows = stocks.findOwnItems(Boolean.TRUE, storeId == null ? 0L : storeId, like(search), belowZero,
				PageRequest.of(pageNumber(page), pageSize(size)));
		Map<Long, Store> byId = new HashMap<>();
		for (Store store : stores.findAll()) {
			byId.put(store.getId(), store);
		}
		List<Map<String, Object>> content = new ArrayList<>();
		for (HoStoreStock row : rows.getContent()) {
			Store store = byId.get(row.getStoreId());
			Map<String, Object> line = new LinkedHashMap<>();
			line.put("storeId", row.getStoreId());
			line.put("storeCode", store == null ? null : store.getCode());
			line.put("storeName", store == null ? null : store.getName());
			line.put("itemCode", row.getItemCode());
			line.put("itemName", row.getItemName());
			line.put("quantity", row.getQuantity());
			line.put("storeTime", row.getStoreTime() == null ? null : row.getStoreTime().toString());
			line.put("receivedAt", row.getReceivedAt() == null ? null : row.getReceivedAt().toString());
			content.add(line);
		}
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("content", content);
		answer.put("totalElements", rows.getTotalElements());
		answer.put("totalPages", rows.getTotalPages());
		answer.put("number", rows.getNumber());
		answer.put("size", rows.getSize());
		return answer;
	}

	private List<Store> shownStores(long storeId) {
		if (storeId != 0) {
			Store store = stores.findById(storeId)
					.orElseThrow(() -> new IllegalArgumentException("Unknown store id " + storeId + "."));
			return new ArrayList<>(Collections.singletonList(store));
		}
		return stores.findAll().stream().filter(s -> !Boolean.FALSE.equals(s.getActive()))
				.sorted(Comparator.comparing(Store::getCode)).collect(Collectors.toList());
	}

	private List<Map<String, Object>> storeViews(List<Store> shown) {
		Map<Long, LocalDateTime> last = new HashMap<>();
		for (Object[] row : stocks.lastReceivedByStore()) {
			last.put(((Number) row[0]).longValue(), (LocalDateTime) row[1]);
		}
		List<Map<String, Object>> views = new ArrayList<>();
		for (Store store : shown) {
			Map<String, Object> view = new LinkedHashMap<>();
			view.put("id", store.getId());
			view.put("code", store.getCode());
			view.put("name", store.getName());
			LocalDateTime at = last.get(store.getId());
			view.put("lastStockAt", at == null ? null : at.toString());
			views.add(view);
		}
		return views;
	}

	private static String like(String search) {
		return search == null || search.trim().isEmpty() ? null : "%" + search.trim().toLowerCase() + "%";
	}

	private static int pageNumber(Integer page) {
		int number = page == null ? 0 : page;
		if (number < 0) {
			throw new IllegalArgumentException("page must be 0 or more");
		}
		return number;
	}

	private static int pageSize(Integer size) {
		return size == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, size));
	}

	private static LocalDateTime parse(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		try {
			return LocalDateTime.parse(value.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("takenAt must be a date and time as yyyy-MM-ddTHH:mm:ss");
		}
	}
}
