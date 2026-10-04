package com.digithink.zsretail.support;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.headoffice.model.HoStoreStock;
import com.digithink.zsretail.headoffice.repository.HoStoreStockRepository;
import com.digithink.zsretail.holink.model.StockCopy;
import com.digithink.zsretail.holink.repository.StockCopyRepository;
import com.digithink.zsretail.model.Item;

/**
 * Test support (task 7A.5): hol_stock_copy of a store over its {@link InMemoryCatalogue}, and ho_store_stock of a head
 * office over its own, applying the rules of the JPQL queries of {@link StockCopyRepository} and
 * {@link HoStoreStockRepository}.
 */
public final class InMemoryNetworkStock {

	public final Map<Long, StockCopy> copies = new LinkedHashMap<>();
	public final Map<Long, HoStoreStock> storeStock = new LinkedHashMap<>();
	private long nextId = 800_000;

	/** The store side, over the store's items. */
	public StockCopyRepository copyRepository(InMemoryCatalogue store) {
		return proxy(StockCopyRepository.class, (method, args) -> {
			switch (method) {
				case "findToSend":
					return toSend(store, (Collection<?>) args[0], (String) args[1]).stream()
							.limit(((Pageable) args[2]).getPageSize())
							.map(i -> new Object[] { i.getId(), i.getItemCode(), i.getName(), i.getStockQuantity(),
									i.getOrigin() })
							.collect(Collectors.toList());
				case "countToSend":
					return (long) toSend(store, (Collection<?>) args[0], (String) args[1]).size();
				case "findRemoved":
					return copies.values().stream().filter(c -> !store.items.containsKey(c.getItemId()))
							.sorted(Comparator.comparing(StockCopy::getId)).limit(((Pageable) args[0]).getPageSize())
							.collect(Collectors.toList());
				case "findByItemIdIn":
					return copies.values().stream().filter(c -> ((Collection<?>) args[0]).contains(c.getItemId()))
							.collect(Collectors.toList());
				case "saveAll":
					for (Object o : (Iterable<?>) args[0]) {
						StockCopy copy = (StockCopy) o;
						if (copy.getId() == null) {
							copy.setId(nextId++);
						}
						copies.put(copy.getId(), copy);
					}
					return args[0];
				case "deleteAll":
					for (Object o : (Iterable<?>) args[0]) {
						copies.remove(((StockCopy) o).getId());
					}
					return null;
				case "lastSentAt": {
					LocalDateTime last = copies.values().stream().map(StockCopy::getSentAt).filter(Objects::nonNull)
							.max(Comparator.naturalOrder()).orElse(null);
					List<LocalDateTime> answer = new ArrayList<>();
					answer.add(last); // max() of no row is one null row
					return answer;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	private List<Item> toSend(InMemoryCatalogue store, Collection<?> types, String excluded) {
		return store.items.values().stream()
				.filter(i -> i.getType() == null || types.contains(i.getType()))
				.filter(i -> !excluded.equals(i.getItemCode()))
				.filter(i -> copies.values().stream().noneMatch(c -> c.getItemId().equals(i.getId())
						&& c.getQuantitySent() == (i.getStockQuantity() == null ? 0 : i.getStockQuantity())))
				.sorted(Comparator.comparing(Item::getId)).collect(Collectors.toList());
	}

	/** The head office side, over the head office's items. */
	public HoStoreStockRepository storeStockRepository(InMemoryCatalogue ho) {
		return proxy(HoStoreStockRepository.class, (method, args) -> {
			switch (method) {
				case "findByStoreIdAndItemCodeIn":
					return rows(s -> s.getStoreId().equals(args[0]) && ((Collection<?>) args[1]).contains(s.getItemCode()));
				case "findByCodes": {
					long storeId = (Long) args[1];
					return rows(s -> ((Collection<?>) args[0]).contains(s.getItemCode())
							&& (storeId == 0 || s.getStoreId() == storeId));
				}
				case "saveAll":
					for (Object o : (Iterable<?>) args[0]) {
						HoStoreStock row = (HoStoreStock) o;
						if (row.getId() == null) {
							if (storeStock.values().stream().anyMatch(s -> s.getStoreId().equals(row.getStoreId())
									&& s.getItemCode().equals(row.getItemCode()))) {
								throw new IllegalStateException("uk_ho_store_stock");
							}
							row.setId(nextId++);
						}
						storeStock.put(row.getId(), row);
					}
					return args[0];
				case "deleteAll":
					for (Object o : (Iterable<?>) args[0]) {
						storeStock.remove(((HoStoreStock) o).getId());
					}
					return null;
				case "lastReceivedByStore": {
					Map<Long, LocalDateTime> last = new LinkedHashMap<>();
					storeStock.values().forEach(s -> last.merge(s.getStoreId(), s.getReceivedAt(),
							(a, b) -> a.isAfter(b) ? a : b));
					return last.entrySet().stream().map(e -> new Object[] { e.getKey(), e.getValue() })
							.collect(Collectors.toList());
				}
				case "findHeadOfficeItems": {
					Collection<?> types = (Collection<?>) args[0];
					String search = (String) args[2];
					List<Object[]> items = ho.items.values().stream()
							.filter(i -> i.getType() == null || types.contains(i.getType()))
							.filter(i -> !args[1].equals(i.getItemCode()))
							.filter(i -> search == null || like(i.getItemCode(), search) || like(i.getName(), search))
							.sorted(Comparator.comparing(Item::getItemCode))
							.map(i -> new Object[] { i.getItemCode(), i.getName(), i.getStockQuantity() })
							.collect(Collectors.toList());
					return page(items, (Pageable) args[3]);
				}
				case "findOwnItems": {
					long storeId = (Long) args[1];
					String search = (String) args[2];
					List<HoStoreStock> own = storeStock.values().stream()
							.filter(s -> Objects.equals(s.getOwnItem(), args[0]))
							.filter(s -> storeId == 0 || s.getStoreId() == storeId)
							.filter(s -> search == null || like(s.getItemCode(), search) || like(s.getItemName(), search))
							.sorted(Comparator.comparing(HoStoreStock::getStoreId).thenComparing(HoStoreStock::getItemCode))
							.collect(Collectors.toList());
					return page(own, (Pageable) args[3]);
				}
				default:
					return UNHANDLED;
			}
		});
	}

	public HoStoreStock stockAt(Long storeId, String itemCode) {
		return storeStock.values().stream().filter(s -> s.getStoreId().equals(storeId) && s.getItemCode().equals(itemCode))
				.findFirst().orElse(null);
	}

	private List<HoStoreStock> rows(Predicate<HoStoreStock> filter) {
		return storeStock.values().stream().filter(filter).collect(Collectors.toList());
	}

	private static boolean like(String column, String pattern) {
		return column != null && column.toLowerCase().contains(pattern.substring(1, pattern.length() - 1));
	}

	private static <T> PageImpl<T> page(List<T> rows, Pageable pageable) {
		int from = (int) Math.min(rows.size(), pageable.getOffset());
		int to = Math.min(rows.size(), from + pageable.getPageSize());
		return new PageImpl<>(new ArrayList<>(rows.subList(from, to)), pageable, rows.size());
	}
}
