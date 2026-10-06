package com.digithink.zsretail.inventory;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.inventory.enumeration.InventoryCountStatus;
import com.digithink.zsretail.inventory.enumeration.InventoryLineStatus;
import com.digithink.zsretail.inventory.model.InventoryCount;
import com.digithink.zsretail.inventory.model.InventoryCountLine;
import com.digithink.zsretail.inventory.repository.InventoryCountLineRepository;
import com.digithink.zsretail.inventory.repository.InventoryCountRepository;
import com.digithink.zsretail.inventory.repository.InventoryLineStore;
import com.digithink.zsretail.inventory.service.InventoryCountService;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.StockMovement;
import com.digithink.zsretail.repository.CashierSessionRepository;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.StockBatchRepository;
import com.digithink.zsretail.service.StockMovementService;
import com.digithink.zsretail.service.StockService;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryStock;

/**
 * Test support: the inventory count of one store in memory, over an {@link InMemoryStock}: the count and line tables,
 * the JDBC batch writers (lines, stock, movements) and a real {@link InventoryCountService} without transactions. The
 * stock goes through the real StockService and StockMovementService of the stock.
 */
public final class InMemoryInventory {

	public final InMemoryStock stock;
	public final InMemoryCatalogue catalogue;
	public final Map<Long, InventoryCount> counts = new LinkedHashMap<>();
	public final Map<Long, InventoryCountLine> lines = new LinkedHashMap<>();
	public long openSessions;
	public LocalDateTime now = LocalDateTime.of(2026, 10, 6, 10, 0);

	public InMemoryInventory(InMemoryStock stock) {
		this.stock = stock;
		this.catalogue = stock.catalogue;
	}

	public InventoryCountService service(ApplicationModeService mode) {
		stock.mode = mode;
		StockBatchRepository batches = new StockBatchRepository(null) {
			@Override
			public void addToStockQuantities(Map<Long, Integer> deltas) {
				deltas.forEach((id, delta) -> stock.itemRepository().addToStockQuantity(id, delta));
			}

			@Override
			public void insertMovements(List<MovementRow> rows, String user) {
				for (MovementRow row : rows) {
					StockMovement movement = new StockMovement();
					movement.setId(catalogue.nextId());
					movement.setItem(catalogue.items.get(row.itemId));
					movement.setMovementType(row.type);
					movement.setDirection(row.direction);
					movement.setQuantity(row.quantity);
					movement.setReferenceId(row.referenceId);
					movement.setReferenceType(row.referenceType);
					movement.setNotes(row.notes);
					movement.setCreatedBy(user);
					stock.movements.add(movement);
				}
			}
		};
		StockService stockService = stock.stockService();
		InMemoryStock.set(stockService, StockService.class, "stockBatchRepository", batches);
		StockMovementService movementService = stock.stockMovementService();
		InMemoryStock.set(movementService, StockMovementService.class, "stockBatchRepository", batches);

		return new InventoryCountService(countRepository(), lineRepository(), lineStore(), itemRepository(),
				barcodeRepository(), sessionRepository(), stockService, movementService, mode,
				TransactionOperations.withoutTransaction(), () -> now);
	}

	/** The lines of one count, by id. */
	public List<InventoryCountLine> linesOf(long countId) {
		return lines.values().stream().filter(l -> l.getCount().getId() == countId)
				.sorted(Comparator.comparing(InventoryCountLine::getId)).collect(Collectors.toList());
	}

	/** The line of one count for this code as read. */
	public InventoryCountLine line(long countId, String code) {
		return linesOf(countId).stream().filter(l -> code.equals(l.getCode())).findFirst()
				.orElseThrow(() -> new IllegalArgumentException(code));
	}

	// ─── Repositories ────────────────────────────────────────────

	private InventoryCountRepository countRepository() {
		return proxy(InventoryCountRepository.class, (method, args) -> {
			switch (method) {
				case "save": {
					InventoryCount count = (InventoryCount) args[0];
					if (count.getId() == null) {
						count.setId(catalogue.nextId());
					}
					counts.put(count.getId(), count);
					return count;
				}
				case "findById":
				case "findByIdForUpdate":
					return Optional.ofNullable(counts.get(args[0]));
				case "findTopByOrderByIdDesc":
					return counts.values().stream().max(Comparator.comparing(InventoryCount::getId));
				case "markValidated": {
					InventoryCount count = counts.get(args[0]);
					if (count == null || count.getStatus() != args[3]) {
						return 0;
					}
					count.setStatus((InventoryCountStatus) args[4]);
					count.setValidatedAt((LocalDateTime) args[1]);
					count.setValidatedBy((String) args[2]);
					return 1;
				}
				case "delete":
					counts.remove(((InventoryCount) args[0]).getId());
					return null;
				default:
					return UNHANDLED;
			}
		});
	}

	/** The summary queries, computed from the lines (a draft against the stock now). */
	private InventoryCountLineRepository lineRepository() {
		return proxy(InventoryCountLineRepository.class, (method, args) -> {
			switch (method) {
				case "summaryOfDraft":
					return summary((Long) args[0], false);
				case "summaryOfValidated":
					return summary((Long) args[0], true);
				default:
					return UNHANDLED;
			}
		});
	}

	/** The rows of the two summary queries (same columns), computed from the lines. */
	private List<Object[]> summary(long countId, boolean validated) {
		Map<InventoryLineStatus, long[]> byStatus = new EnumMap<>(InventoryLineStatus.class);
		for (InventoryCountLine line : linesOf(countId)) {
			long[] sums = byStatus.computeIfAbsent(line.getStatus(), s -> new long[7]);
			sums[0]++;
			sums[1] += line.getMergedRows();
			if (validated) {
				Integer difference = line.getDifferenceApplied();
				if (difference != null && difference != 0) {
					sums[2]++;
					sums[3] += Math.max(difference, 0);
					sums[4] += Math.min(difference, 0);
				}
			} else if (line.getCountedQuantity() != null && line.getItemId() != null) {
				int counted = line.getCountedQuantity();
				int stock = stockOf(line.getItemId());
				if (counted != stock) {
					sums[2]++;
				}
				if (counted > stock) {
					sums[3] += counted;
					sums[4] += stock;
				} else if (counted < stock) {
					sums[5] += stock;
					sums[6] += counted;
				}
			}
		}
		List<Object[]> rows = new ArrayList<>();
		byStatus.forEach((status, s) -> rows.add(validated ? new Object[] { status, s[0], s[1], s[2], s[3], s[4] }
				: new Object[] { status, s[0], s[1], s[2], s[3], s[4], s[5], s[6] }));
		return rows;
	}

	private int stockOf(long itemId) {
		Item item = catalogue.items.get(itemId);
		return item == null || item.getStockQuantity() == null ? 0 : item.getStockQuantity();
	}

	private InventoryLineStore lineStore() {
		return new InventoryLineStore(null) {
			@Override
			public void insert(long countId, List<InventoryCountLine> rows, String user) {
				for (InventoryCountLine line : rows) {
					line.setId(catalogue.nextId());
					line.setCount(counts.get(countId));
					line.setCreatedBy(user);
					lines.put(line.getId(), line);
				}
			}

			@Override
			public int deleteByCount(long countId) {
				List<InventoryCountLine> of = linesOf(countId);
				of.forEach(l -> lines.remove(l.getId()));
				return of.size();
			}

			@Override
			public List<OkLine> okLines(long countId) {
				return linesOf(countId).stream().filter(l -> l.getStatus() == InventoryLineStatus.OK)
						.map(l -> new OkLine(l.getId(), l.getItemId(), l.getCountedQuantity()))
						.collect(Collectors.toList());
			}

			@Override
			public void saveValidation(List<OkLine> okLines, String user) {
				for (OkLine ok : okLines) {
					InventoryCountLine line = lines.get(ok.lineId);
					line.setSystemQuantityAtValidation(ok.systemQuantity);
					line.setDifferenceApplied(ok.difference);
					line.setMessage(ok.message);
				}
			}
		};
	}

	/** The two inventory reads of the items; any other call goes to the stock's item repository. */
	private ItemRepository itemRepository() {
		ItemRepository delegate = stock.itemRepository();
		return proxy(ItemRepository.class, (method, args) -> {
			switch (method) {
				case "findInventorySnapshot":
					return catalogue.items.values().stream()
							.map(i -> new Object[] { i.getId(), i.getItemCode(), i.getType(), i.getStockQuantity() })
							.collect(Collectors.toList());
				case "findStockByIds": {
					@SuppressWarnings("unchecked")
					Collection<Long> ids = (Collection<Long>) args[0];
					return ids.stream().map(catalogue.items::get).filter(i -> i != null)
							.map(i -> new Object[] { i.getId(), i.getStockQuantity() }).collect(Collectors.toList());
				}
				default:
					return call(delegate, method, args);
			}
		});
	}

	private ItemBarcodeRepository barcodeRepository() {
		return proxy(ItemBarcodeRepository.class, (method, args) -> {
			if ("findActiveBarcodeItemIds".equals(method)) {
				return catalogue.barcodes.values().stream()
						.filter(b -> b.getActive() == null || Boolean.TRUE.equals(b.getActive()))
						.map(b -> new Object[] { b.getBarcode(), b.getItem().getId() }).collect(Collectors.toList());
			}
			return UNHANDLED;
		});
	}

	private CashierSessionRepository sessionRepository() {
		return proxy(CashierSessionRepository.class,
				(method, args) -> "countByStatus".equals(method) ? openSessions : UNHANDLED);
	}

	private static Object call(Object target, String name, Object[] args) {
		for (Method method : ItemRepository.class.getMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == args.length) {
				try {
					return method.invoke(target, args);
				} catch (InvocationTargetException e) {
					throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause()
							: new IllegalStateException(e.getCause());
				} catch (IllegalAccessException e) {
					throw new IllegalStateException(e);
				}
			}
		}
		return UNHANDLED;
	}
}
