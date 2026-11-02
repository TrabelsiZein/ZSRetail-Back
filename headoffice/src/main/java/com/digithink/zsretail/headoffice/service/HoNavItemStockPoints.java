package com.digithink.zsretail.headoffice.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.dto.NavItemVariantsDTO;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.core.service.NavItemStockPoints;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointRepository;

/**
 * Item variants (ZEN), step 11a: the point de stock part of the "NAV items" page, from ho_stock_point_item. On every
 * head office: one without points de stock answers no point (its lines show 0, its variants an empty grid). A null
 * clearance is read as false, as the catalogue engine does.
 * <p>
 * 3.1.1 (Item cards' Stock points tab): each row also gives its own name, description, family and sub-family (the
 * names of the codes read once) and the fields that differ from the item's ({@link #differences}).
 */
@Service
@Transactional(readOnly = true)
public class HoNavItemStockPoints implements NavItemStockPoints {

	/** The keys of {@link NavItemVariantsDTO.PointPrice#getDifferences()}, in this order. */
	static final String NAME = "name";
	static final String DESCRIPTION = "description";
	static final String FAMILY = "family";
	static final String SUB_FAMILY = "subFamily";

	private final HoStockPointItemRepository rowRepository;
	private final HoStockPointRepository pointRepository;
	/** The names of the families and sub-families; null in the tests that read no name. */
	private final ItemFamilyRepository familyRepository;
	private final ItemSubFamilyRepository subFamilyRepository;

	@Autowired
	public HoNavItemStockPoints(HoStockPointItemRepository rowRepository, HoStockPointRepository pointRepository,
			ItemFamilyRepository familyRepository, ItemSubFamilyRepository subFamilyRepository) {
		this.rowRepository = rowRepository;
		this.pointRepository = pointRepository;
		this.familyRepository = familyRepository;
		this.subFamilyRepository = subFamilyRepository;
	}

	/** Without the names of the families and sub-families (their codes only). Used by the tests. */
	public HoNavItemStockPoints(HoStockPointItemRepository rowRepository, HoStockPointRepository pointRepository) {
		this(rowRepository, pointRepository, null, null);
	}

	@Override
	public Map<String, Long> activePointCounts(Collection<String> navItemNos) {
		Map<String, Long> counts = new HashMap<>();
		if (navItemNos.isEmpty()) {
			return counts;
		}
		for (Object[] row : rowRepository.countActivePointsOfNavItems(navItemNos)) {
			counts.put((String) row[0], ((Number) row[1]).longValue());
		}
		return counts;
	}

	@Override
	public Map<Long, Map<Long, NavItemVariantsDTO.PointPrice>> prices(Collection<Long> itemIds) {
		Map<Long, Map<Long, NavItemVariantsDTO.PointPrice>> prices = new HashMap<>();
		if (itemIds.isEmpty()) {
			return prices;
		}
		List<Object[]> rows = rowRepository.findRowsOfItems(itemIds);
		Map<String, String> familyNames = familyNames(rows);
		Map<String, String> subFamilyNames = subFamilyNames(rows);
		for (Object[] row : rows) {
			Double unitPrice = (Double) row[2];
			Boolean clearance = (Boolean) row[4];
			BigDecimal clearancePrice = (BigDecimal) row[5];
			// Item variants (ZEN), clearance (2026-10-17): the price the point's stores sell at, by the one rule
			NavItemVariantsDTO.PointPrice price = new NavItemVariantsDTO.PointPrice(unitPrice,
					!Boolean.FALSE.equals(row[3]), Boolean.TRUE.equals(clearance), clearancePrice,
					StockPointPrice.clearanceApplies(clearance, clearancePrice),
					StockPointPrice.sellingPrice(unitPrice, clearance, clearancePrice));
			price.setName((String) row[6]);
			price.setDescription((String) row[7]);
			price.setFamilyCode((String) row[8]);
			price.setFamilyName(familyNames.get(row[8]));
			price.setSubFamilyCode((String) row[9]);
			price.setSubFamilyName(subFamilyNames.get(row[9]));
			price.setDifferences(differences(row));
			prices.computeIfAbsent((Long) row[0], id -> new LinkedHashMap<>()).put((Long) row[1], price);
		}
		return prices;
	}

	/**
	 * The row's fields that differ from its item's ([6..9] against [10..13] of ROWS_OF_ITEMS): name, description, family
	 * (code), subFamily (code). Trimmed, null and blank the same; otherwise compared exactly.
	 */
	static List<String> differences(Object[] row) {
		List<String> differences = new ArrayList<>();
		String[] keys = { NAME, DESCRIPTION, FAMILY, SUB_FAMILY };
		for (int i = 0; i < keys.length; i++) {
			if (!Objects.equals(normal(row[6 + i]), normal(row[10 + i]))) {
				differences.add(keys[i]);
			}
		}
		return differences;
	}

	/** A value trimmed, blank as null. */
	private static String normal(Object value) {
		String text = value == null ? "" : value.toString().trim();
		return text.isEmpty() ? null : text;
	}

	private Map<String, String> familyNames(List<Object[]> rows) {
		Map<String, String> names = new HashMap<>();
		Set<String> codes = codes(rows, 8);
		if (familyRepository != null && !codes.isEmpty()) {
			for (ItemFamily family : familyRepository.findByCodeIn(codes)) {
				names.put(family.getCode(), family.getName());
			}
		}
		return names;
	}

	private Map<String, String> subFamilyNames(List<Object[]> rows) {
		Map<String, String> names = new HashMap<>();
		Set<String> codes = codes(rows, 9);
		if (subFamilyRepository != null && !codes.isEmpty()) {
			for (ItemSubFamily subFamily : subFamilyRepository.findByCodeIn(codes)) {
				names.put(subFamily.getCode(), subFamily.getName());
			}
		}
		return names;
	}

	private static Set<String> codes(List<Object[]> rows, int column) {
		return rows.stream().map(row -> (String) row[column]).filter(Objects::nonNull)
				.collect(Collectors.toCollection(TreeSet::new));
	}

	@Override
	public List<NavItemVariantsDTO.Point> points(Collection<Long> pointIds) {
		List<HoStockPoint> points = new ArrayList<>(pointRepository.findAllById(pointIds));
		points.sort(Comparator.comparing(HoStockPoint::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(HoStockPoint::getCode, Comparator.nullsLast(Comparator.naturalOrder())));
		return points.stream().map(p -> new NavItemVariantsDTO.Point(p.getId(), p.getCode(), p.getName()))
				.collect(Collectors.toList());
	}
}
