package com.digithink.zsretail.headoffice.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.dto.NavItemVariantsDTO;
import com.digithink.zsretail.core.service.NavItemStockPoints;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointRepository;

import lombok.RequiredArgsConstructor;

/**
 * Item variants (ZEN), step 11a: the point de stock part of the "NAV items" page, from ho_stock_point_item. On every
 * head office: one without points de stock answers no point (its lines show 0, its variants an empty grid). A null
 * clearance is read as false, as the catalogue engine does.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HoNavItemStockPoints implements NavItemStockPoints {

	private final HoStockPointItemRepository rowRepository;
	private final HoStockPointRepository pointRepository;

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
		for (Object[] row : rowRepository.findRowsOfItems(itemIds)) {
			Double unitPrice = (Double) row[2];
			Boolean clearance = (Boolean) row[4];
			BigDecimal clearancePrice = (BigDecimal) row[5];
			// Item variants (ZEN), clearance (2026-10-17): the price the point's stores sell at, by the one rule
			prices.computeIfAbsent((Long) row[0], id -> new LinkedHashMap<>()).put((Long) row[1],
					new NavItemVariantsDTO.PointPrice(unitPrice, !Boolean.FALSE.equals(row[3]),
							Boolean.TRUE.equals(clearance), clearancePrice,
							StockPointPrice.clearanceApplies(clearance, clearancePrice),
							StockPointPrice.sellingPrice(unitPrice, clearance, clearancePrice)));
		}
		return prices;
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
