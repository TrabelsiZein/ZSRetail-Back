package com.digithink.zsretail.support;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.StockMovement;
import com.digithink.zsretail.model.enumeration.StockMovementType;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.StockMovementRepository;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.StockMovementService;
import com.digithink.zsretail.service.StockService;

/**
 * Test support (step 7A): the stock of one installation in memory, over its {@link InMemoryCatalogue}: the item
 * repository with the three stock updates of ItemRepository (native queries, applied with their rules: null counts as
 * 0, the conditional decrement only when enough), the stock_movement rows, and a real {@link StockService} and
 * {@link StockMovementService} in standalone mode. Every other item repository call goes to the catalogue's stub.
 */
public final class InMemoryStock {

	public final InMemoryCatalogue catalogue;
	public final List<StockMovement> movements = new ArrayList<>();
	public final List<String> stockUpdates = new ArrayList<>();

	/** ALLOW_NEGATIVE_STOCK of the general setup. */
	public boolean allowNegative;

	private final ItemRepository items;

	public InMemoryStock(InMemoryCatalogue catalogue) {
		this.catalogue = catalogue;
		ItemRepository delegate = catalogue.itemRepository();
		this.items = proxy(ItemRepository.class, (method, args) -> {
			switch (method) {
				case "addToStockQuantity":
					return add((Long) args[0], (Integer) args[1]);
				case "decrementStockQuantityIfSufficient": {
					Item item = catalogue.items.get(args[0]);
					if (item == null || stock(item) < (Integer) args[1]) {
						return 0;
					}
					return add((Long) args[0], -(Integer) args[1]);
				}
				case "decrementStockQuantityUnconditional":
					add((Long) args[0], -(Integer) args[1]);
					return null;
				default:
					return call(delegate, method, args);
			}
		});
	}

	public ItemRepository itemRepository() {
		return items;
	}

	public StockMovementRepository movementRepository() {
		return proxy(StockMovementRepository.class, (method, args) -> {
			if ("save".equals(method)) {
				StockMovement movement = (StockMovement) args[0];
				if (movement.getId() == null) {
					movement.setId(catalogue.nextId());
				}
				movements.add(movement);
				return movement;
			}
			return UNHANDLED;
		});
	}

	public StockService stockService() {
		StockService service = new StockService();
		set(service, StockService.class, "applicationModeService", standalone());
		set(service, StockService.class, "itemRepository", items);
		set(service, StockService.class, "generalSetupService", new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return "ALLOW_NEGATIVE_STOCK".equals(code) ? String.valueOf(allowNegative) : null;
			}
		});
		return service;
	}

	public StockMovementService stockMovementService() {
		StockMovementService service = new StockMovementService();
		set(service, StockMovementService.class, "applicationModeService", standalone());
		set(service, StockMovementService.class, "stockMovementRepository", movementRepository());
		set(service, StockMovementService.class, "itemRepository", items);
		return service;
	}

	/** The movements of one type, in order. */
	public List<StockMovement> movements(StockMovementType type) {
		return movements.stream().filter(m -> m.getMovementType() == type).collect(Collectors.toList());
	}

	public int stockOf(String itemCode) {
		Optional<Item> item = catalogue.itemByCode(itemCode);
		return item.map(InMemoryStock::stock).orElseThrow(() -> new IllegalArgumentException(itemCode));
	}

	private int add(Long itemId, int delta) {
		Item item = catalogue.items.get(itemId);
		if (item == null) {
			return 0;
		}
		item.setStockQuantity(stock(item) + delta);
		stockUpdates.add(item.getItemCode() + " " + delta);
		return 1;
	}

	private static int stock(Item item) {
		return item.getStockQuantity() == null ? 0 : item.getStockQuantity();
	}

	private static ApplicationModeService standalone() {
		ApplicationModeService mode = new ApplicationModeService();
		set(mode, ApplicationModeService.class, "standalone", true);
		return mode;
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

	public static void set(Object target, Class<?> declaring, String name, Object value) {
		try {
			Field field = declaring.getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
