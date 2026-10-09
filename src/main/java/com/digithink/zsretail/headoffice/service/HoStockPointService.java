package com.digithink.zsretail.headoffice.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeErpCatalogue;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.headoffice.dto.StockPointDTO;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;

/**
 * Stock points, step 1: the points de stock of a head office whose catalogue comes from the ERP (navpospages
 * connector). The head office creates them itself before any items run. The code is the ERP's Location_Code: trimmed,
 * uppercase, unique, final. A point used by a store cannot be deactivated or deleted; a point that has items cannot be
 * deleted (deactivate it). The list order is set as a whole ({@link #reorder}). See docs/modules/head-office.md,
 * "Stock points".
 */
@Service
@ConditionalOnHeadOfficeErpCatalogue
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
public class HoStockPointService {

	static final String CODE_REQUIRED = "The stock point code is required.";
	static final String NAME_REQUIRED = "The stock point name is required.";
	static final String CODE_IS_FINAL = "The stock point code cannot be changed after creation: create a new point and"
			+ " deactivate this one.";
	static final String USED_BY_STORE = "This stock point is the one of %d store(s): choose another point for them on"
			+ " the Stores page first.";
	static final String HAS_ITEMS = "This stock point has %d item(s) read from the ERP: it cannot be deleted, deactivate"
			+ " it instead.";
	static final String ORDER_ALL = "Send the ids of every stock point, each once, in the new order.";

	static final String NO_ROWS = "The stock point %s has no item yet: run the items job first (ERP jobs), then give it"
			+ " to the store.";

	private final HoStockPointRepository points;
	private final HoStockPointItemRepository rows;
	private final StoreRepository stores;
	/** Step 3a: sends everything again to a store whose point changed; null in the tests of step 1. */
	private final Supplier<HoCatalogueService> catalogue;

	@Autowired
	public HoStockPointService(HoStockPointRepository points, HoStockPointItemRepository rows, StoreRepository stores,
			ObjectProvider<HoCatalogueService> catalogue) {
		this(points, rows, stores, (Supplier<HoCatalogueService>) catalogue::getIfAvailable);
	}

	/** Without the catalogue: used by the tests of step 1. */
	public HoStockPointService(HoStockPointRepository points, HoStockPointItemRepository rows, StoreRepository stores) {
		this(points, rows, stores, (Supplier<HoCatalogueService>) null);
	}

	/** With given collaborators: used by the tests. */
	public HoStockPointService(HoStockPointRepository points, HoStockPointItemRepository rows, StoreRepository stores,
			Supplier<HoCatalogueService> catalogue) {
		this.points = points;
		this.rows = rows;
		this.stores = stores;
		this.catalogue = catalogue;
	}

	/** Every point in list order. */
	@Transactional(readOnly = true)
	public List<StockPointDTO> findAll() {
		return points.findAllByOrderBySortOrderAscCodeAsc().stream().map(this::view).collect(Collectors.toList());
	}

	@Transactional(readOnly = true)
	public Optional<StockPointDTO> findById(Long id) {
		return points.findById(id).map(this::view);
	}

	/**
	 * Body {code, name, active}: placed last in the list. 400 (IllegalArgument) when the code or the name is missing or
	 * the code is too long; 409 (IllegalState) when the code exists, at any case.
	 */
	@Transactional
	public StockPointDTO create(StockPointDTO input) {
		String code = normalizeCode(input.getCode());
		if (code.isEmpty()) {
			throw new IllegalArgumentException(CODE_REQUIRED);
		}
		if (code.length() > HoStockPoint.CODE_LENGTH) {
			throw new IllegalArgumentException("The stock point code is longer than " + HoStockPoint.CODE_LENGTH
					+ " characters.");
		}
		String name = name(input.getName());
		if (points.findByCodeIgnoreCase(code).isPresent()) {
			throw new IllegalStateException("A stock point with the code " + code + " already exists.");
		}
		HoStockPoint point = new HoStockPoint();
		point.setCode(code);
		point.setName(name);
		point.setActive(input.getActive() == null || input.getActive());
		point.setSortOrder(points.findAllByOrderBySortOrderAscCodeAsc().stream().mapToInt(HoStockPoint::getSortOrder)
				.max().orElse(0) + 1);
		return view(points.save(point));
	}

	/**
	 * Body {name, active} (each when sent). 400 when the code differs or the name is blank; 409 when it deactivates a
	 * point used by a store. Empty when the point does not exist.
	 */
	@Transactional
	public Optional<StockPointDTO> update(Long id, StockPointDTO input) {
		Optional<HoStockPoint> found = points.findById(id);
		if (!found.isPresent()) {
			return Optional.empty();
		}
		HoStockPoint point = found.get();
		if (input.getCode() != null && !normalizeCode(input.getCode()).equals(point.getCode())) {
			throw new IllegalArgumentException(CODE_IS_FINAL);
		}
		if (input.getName() != null) {
			point.setName(name(input.getName()));
		}
		if (Boolean.FALSE.equals(input.getActive())) {
			refuseWhenUsed(id);
		}
		if (input.getActive() != null) {
			point.setActive(input.getActive());
		}
		return Optional.of(view(points.save(point)));
	}

	/** False when unknown. 409 (IllegalState) when a store uses it or it has items. */
	@Transactional
	public boolean delete(Long id) {
		if (!points.findById(id).isPresent()) {
			return false;
		}
		refuseWhenUsed(id);
		long items = rows.countByStockPointId(id);
		if (items > 0) {
			throw new IllegalStateException(String.format(HAS_ITEMS, items));
		}
		points.deleteById(id);
		return true;
	}

	/**
	 * The ids of every point, each once, in the new order: numbered 1, 2, 3... 400 (IllegalArgument) when one is
	 * missing, unknown or twice. Returns the list in its new order.
	 */
	@Transactional
	public List<StockPointDTO> reorder(List<Long> ids) {
		List<HoStockPoint> all = points.findAll();
		Set<Long> known = all.stream().map(HoStockPoint::getId).collect(Collectors.toSet());
		if (ids == null || ids.size() != all.size() || !known.equals(new HashSet<>(ids))) {
			throw new IllegalArgumentException(ORDER_ALL);
		}
		for (HoStockPoint point : all) {
			point.setSortOrder(ids.indexOf(point.getId()) + 1);
		}
		points.saveAll(all);
		return findAll();
	}

	/**
	 * Step 3a, for the stores: 400 (IllegalArgument) unless the point exists, is active and has rows (the items job has
	 * read it); a store given a point without rows would receive no item.
	 */
	@Transactional(readOnly = true)
	public void checkAssignable(Long id) {
		HoStockPoint point = points.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Unknown stock point id " + id + "."));
		if (Boolean.FALSE.equals(point.getActive())) {
			throw new IllegalArgumentException("The stock point " + point.getCode() + " is inactive.");
		}
		if (rows.countByStockPointId(id) == 0) {
			throw new IllegalArgumentException(String.format(NO_ROWS, point.getCode()));
		}
	}

	/**
	 * Step 3a: a store's point changed (StoreService, same transaction): every item and barcode is sent again to that
	 * store only. Returns how many codes were recorded (0 when nothing changed).
	 */
	@Transactional
	public int storeStockPointChanged(Long storeId, Long previousId, Long newId) {
		if (java.util.Objects.equals(previousId, newId) || catalogue == null || catalogue.get() == null) {
			return 0;
		}
		return catalogue.get().storeStockPointChanged(storeId);
	}

	private void refuseWhenUsed(Long id) {
		long used = stores.countByStockPointId(id);
		if (used > 0) {
			throw new IllegalStateException(String.format(USED_BY_STORE, used));
		}
	}

	private static String name(String value) {
		String name = value == null ? "" : value.trim();
		if (name.isEmpty()) {
			throw new IllegalArgumentException(NAME_REQUIRED);
		}
		return name;
	}

	private StockPointDTO view(HoStockPoint point) {
		return new StockPointDTO(point.getId(), point.getCode(), point.getName(), !Boolean.FALSE.equals(point.getActive()),
				point.getSortOrder(), rows.countByStockPointId(point.getId()), stores.countByStockPointId(point.getId()));
	}

	static String normalizeCode(String code) {
		return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
	}
}
