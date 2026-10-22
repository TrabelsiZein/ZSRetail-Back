package com.digithink.zsretail.headoffice.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.link.dto.StockReportDTO;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.headoffice.model.HoStoreStock;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoNetworkStockQuery;
import com.digithink.zsretail.headoffice.repository.HoStoreStockRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 7A.5: the stock of every store, copied up (POST /ho/supply/stock), and the head office page of
 * the stock per store and item next to the head office's own stock. Head office without an ERP only. A store sends the
 * items whose stock changed since the head office last accepted it; each batch is saved in one transaction (a row per
 * store and item code, replaced), the codes the store no longer has are deleted. See docs/modules/head-office.md, "Stock
 * of the stores".
 */
@Service
@ConditionalOnInstallation(Installation.HEAD_OFFICE_WITHOUT_ERP)
@Log4j2
public class HoNetworkStockService {

	static final int DEFAULT_SIZE = 20;
	static final int MAX_SIZE = 200;
	static final int CODE_LENGTH = 100;

	private final HoStoreStockRepository stocks;
	private final StoreRepository stores;
	private final HoNetworkStockQuery query;
	private final TransactionOperations writeTransactions;
	private final Supplier<LocalDateTime> clock;

	/** False on a head office with headoffice.stock.enabled=false: no head office column, the stock filter ignores it. */
	private final boolean keepsStock;

	/** I-24: the items' variants on the list (a field: the tests build the service without it). */
	@Autowired(required = false)
	private ItemRepository items;

	@Autowired
	public HoNetworkStockService(HoStoreStockRepository stocks, StoreRepository stores, HoNetworkStockQuery query,
			PlatformTransactionManager transactionManager, ApplicationModeService mode) {
		this(stocks, stores, query, new TransactionTemplate(transactionManager), LocalDateTime::now,
				!mode.isHeadOfficeWithoutStock());
	}

	/** With given transactions and clock, the head office keeping its stock: used by the tests. */
	public HoNetworkStockService(HoStoreStockRepository stocks, StoreRepository stores, HoNetworkStockQuery query,
			TransactionOperations writeTransactions, Supplier<LocalDateTime> clock) {
		this(stocks, stores, query, writeTransactions, clock, true);
	}

	/** With given transactions, clock, and whether the head office keeps its stock. */
	public HoNetworkStockService(HoStoreStockRepository stocks, StoreRepository stores, HoNetworkStockQuery query,
			TransactionOperations writeTransactions, Supplier<LocalDateTime> clock, boolean keepsStock) {
		this.keepsStock = keepsStock;
		this.stocks = stocks;
		this.stores = stores;
		this.query = query;
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
	 * shown (the head office, or the store asked for, or any active store), as stock=BELOW_ZERO. A head office without
	 * stock (headoffice.stock.enabled=false): headOffice is null and the stock filter looks at the stores only.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> page(Long storeId, String search, Integer page, Integer size, boolean belowZero) {
		HoNetworkStockQuery.Criteria filters = new HoNetworkStockQuery.Criteria();
		if (belowZero) {
			filters.setStock(HoNetworkStockQuery.BELOW_ZERO);
		}
		return page(storeId, search, page, size, filters);
	}

	/**
	 * Network stock (3.1.0): the same page with the filters of {@link HoNetworkStockQuery.Criteria} (step 1: family,
	 * sub-family, division, catégorie, section; stock ALL, IN_STOCK, ZERO, BELOW_ZERO on the columns shown); its search,
	 * store and head office column are set here. Step 2: sort code (natural, the default), name, headOffice (where the
	 * head office keeps stock), store-&lt;id&gt; (a store shown; another one sorts by code), total (2 stores shown or
	 * more, else by code); descending. Each row gains itemId and navItemNo (the item card), and with 2 stores shown or
	 * more totalStores (the sum of the stores shown that sent the item, null when none did); the answer gains
	 * withTotal.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> page(Long storeId, String search, Integer page, Integer size,
			HoNetworkStockQuery.Criteria filters, String sort, boolean descending) {
		long wanted = storeId == null ? 0L : storeId;
		List<Store> shown = shownStores(wanted);
		boolean withTotal = shown.size() >= 2;
		PageRequest request = PageRequest.of(pageNumber(page), pageSize(size));
		filters.setLike(like(search));
		filters.setStoreId(wanted);
		filters.setHeadOfficeColumn(keepsStock);
		sortOf(filters, sort, shown, withTotal);
		filters.setDescending(descending);
		Page<Object[]> items = query.lines(filters, request);
		List<String> codes = items.getContent().stream().map(row -> (String) row[HoNetworkStockQuery.COL_ITEM_CODE])
				.collect(Collectors.toList());
		Map<String, Map<String, BigDecimal>> byCode = new HashMap<>();
		if (!codes.isEmpty()) {
			for (HoStoreStock row : stocks.findByCodes(codes, wanted)) {
				byCode.computeIfAbsent(row.getItemCode(), c -> new LinkedHashMap<>())
						.put(String.valueOf(row.getStoreId()), row.getQuantity());
			}
		}
		Map<String, String> variants = ItemVariantsByCode.of(this.items, codes);
		List<Map<String, Object>> content = new ArrayList<>();
		for (Object[] row : items.getContent()) {
			String code = (String) row[HoNetworkStockQuery.COL_ITEM_CODE];
			Object stock = row[HoNetworkStockQuery.COL_STOCK_QUANTITY];
			Object id = row[HoNetworkStockQuery.COL_ITEM_ID];
			Map<String, BigDecimal> byStore = byCode.getOrDefault(code, new LinkedHashMap<>());
			Map<String, Object> line = new LinkedHashMap<>();
			line.put("itemCode", code);
			line.put("itemName", row[HoNetworkStockQuery.COL_ITEM_NAME]);
			if (variants.get(code) != null) line.put("variantDescription", variants.get(code));
			line.put("itemId", id == null ? null : ((Number) id).longValue());
			line.put("navItemNo", row[HoNetworkStockQuery.COL_NAV_ITEM_NO]);
			// 2.2.1: the head office stock with its decimals, without trailing zeros
			line.put("headOffice", !keepsStock ? null : stock == null ? BigDecimal.ZERO : Quantities.normalize(new BigDecimal(stock.toString())));
			line.put("byStore", byStore);
			if (withTotal) line.put("totalStores", totalOf(byStore, shown));
			content.add(line);
		}
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("stores", storeViews(shown));
		answer.put("withTotal", withTotal);
		answer.put("content", content);
		answer.put("totalElements", items.getTotalElements());
		answer.put("totalPages", items.getTotalPages());
		answer.put("number", items.getNumber());
		answer.put("size", items.getSize());
		return answer;
	}

	/** The same page by code (step 1's callers). */
	@Transactional(readOnly = true)
	public Map<String, Object> page(Long storeId, String search, Integer page, Integer size,
			HoNetworkStockQuery.Criteria filters) {
		return page(storeId, search, page, size, filters, null, false);
	}

	/**
	 * The sort asked for, as the query reads it: a store column only for a store shown, the total only with 2 stores
	 * shown or more; anything else (absent, unknown, an old link's store) by code.
	 */
	private static void sortOf(HoNetworkStockQuery.Criteria filters, String sort, List<Store> shown, boolean withTotal) {
		String value = sort == null ? "" : sort.trim();
		filters.setSort(HoNetworkStockQuery.SORT_CODE);
		filters.setSortStoreId(null);
		if (HoNetworkStockQuery.SORT_NAME.equals(value) || HoNetworkStockQuery.SORT_HEAD_OFFICE.equals(value)) {
			filters.setSort(value);
		} else if (HoNetworkStockQuery.SORT_TOTAL.equals(value) && withTotal) {
			filters.setSort(value);
		} else if (value.startsWith(HoNetworkStockQuery.SORT_STORE + "-")) {
			try {
				long id = Long.parseLong(value.substring(HoNetworkStockQuery.SORT_STORE.length() + 1));
				if (shown.stream().anyMatch(store -> store.getId() == id)) {
					filters.setSort(HoNetworkStockQuery.SORT_STORE);
					filters.setSortStoreId(id);
				}
			} catch (NumberFormatException e) {
				// by code
			}
		}
	}

	/** The sum of the stores shown that sent the item; null when none did. */
	private static BigDecimal totalOf(Map<String, BigDecimal> byStore, List<Store> shown) {
		BigDecimal total = null;
		for (Store store : shown) {
			BigDecimal quantity = byStore.get(String.valueOf(store.getId()));
			if (quantity != null) {
				total = total == null ? quantity : total.add(quantity);
			}
		}
		return total == null ? null : Quantities.normalize(total);
	}

	/**
	 * The stores' own items (not from the head office): {content: [{storeId, storeCode, storeName, itemCode, itemName,
	 * quantity, storeTime, receivedAt}], totalElements, totalPages, number, size}, by store code and item code. belowZero
	 * true: only those whose stock is below zero.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> ownItems(Long storeId, String search, Integer page, Integer size, boolean belowZero) {
		return ownItems(storeId, search, page, size, belowZero ? HoNetworkStockQuery.BELOW_ZERO : null, null, false);
	}

	/**
	 * Network stock (3.1.0), step 2: the own items with the stock filter on their quantity (IN_STOCK, ZERO, BELOW_ZERO;
	 * other: all) and the sort store (code, the default), code (natural), name, quantity, storeTime, receivedAt; each
	 * then by store and code. The same rows and values as before.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> ownItems(Long storeId, String search, Integer page, Integer size, String stock, String sort,
			boolean descending) {
		HoNetworkStockQuery.Criteria filters = new HoNetworkStockQuery.Criteria();
		filters.setStoreId(storeId == null ? 0L : storeId);
		filters.setLike(like(search));
		filters.setStock(stock);
		filters.setSort(sort == null ? null : sort.trim());
		filters.setDescending(descending);
		Page<Object[]> rows = query.ownLines(filters, PageRequest.of(pageNumber(page), pageSize(size)));
		List<Map<String, Object>> content = new ArrayList<>();
		for (Object[] row : rows.getContent()) {
			Object quantity = row[HoNetworkStockQuery.OWN_QUANTITY];
			Map<String, Object> line = new LinkedHashMap<>();
			line.put("storeId", ((Number) row[HoNetworkStockQuery.OWN_STORE_ID]).longValue());
			line.put("storeCode", row[HoNetworkStockQuery.OWN_STORE_CODE]);
			line.put("storeName", row[HoNetworkStockQuery.OWN_STORE_NAME]);
			line.put("itemCode", row[HoNetworkStockQuery.OWN_ITEM_CODE]);
			line.put("itemName", row[HoNetworkStockQuery.OWN_ITEM_NAME]);
			line.put("quantity", quantity == null ? BigDecimal.ZERO : Quantities.normalize(new BigDecimal(quantity.toString())));
			line.put("storeTime", time(row[HoNetworkStockQuery.OWN_STORE_TIME]));
			line.put("receivedAt", time(row[HoNetworkStockQuery.OWN_RECEIVED_AT]));
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

	/** A time of a native row (a Timestamp, or a LocalDateTime) as LocalDateTime.toString(), as the entity gave it. */
	private static String time(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof java.sql.Timestamp) {
			return ((java.sql.Timestamp) value).toLocalDateTime().toString();
		}
		return value.toString();
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
