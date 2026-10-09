package com.digithink.zsretail.headoffice.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.persistence.EntityManager;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeErpCatalogue;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.sync.NavPosPagesStockPoints;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointRepository;

/**
 * Stock points, step 2: ho_stock_point and ho_stock_point_item for the items run of the ERP catalogue (NavPosPagesSync).
 * Reads with JPQL projections; each {@link #write} is one transaction. Nothing is recorded for the stores here (step 3).
 */
@Component
@ConditionalOnHeadOfficeErpCatalogue
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
@Transactional(readOnly = true)
public class HoStockPointRows implements NavPosPagesStockPoints {

	/** Values per IN (...) query (SQL Server takes at most 2100 parameters). */
	static final int IN_CHUNK = 1000;

	private final HoStockPointRepository points;
	private final HoStockPointItemRepository rows;
	private final EntityManager entityManager;

	public HoStockPointRows(HoStockPointRepository points, HoStockPointItemRepository rows, EntityManager entityManager) {
		this.points = points;
		this.rows = rows;
		this.entityManager = entityManager;
	}

	@Override
	public List<Point> activePoints() {
		List<Point> active = new ArrayList<>();
		for (HoStockPoint point : points.findAllByOrderBySortOrderAscCodeAsc()) {
			if (!Boolean.FALSE.equals(point.getActive())) {
				active.add(new Point(point.getId(), point.getCode(), point.getName()));
			}
		}
		return active;
	}

	@Override
	public Map<String, Row> rows(long pointId) {
		Map<String, Row> found = new LinkedHashMap<>();
		List<?> result = entityManager.createQuery("select i.itemCode, r.name, r.description, r.familyCode,"
				+ " r.subFamilyCode, r.unitPrice, r.active from HoStockPointItem r, Item i"
				+ " where r.itemId = i.id and r.stockPointId = :point order by i.itemCode")
				.setParameter("point", pointId).getResultList();
		for (Object line : result) {
			Object[] row = (Object[]) line;
			found.put((String) row[0], new Row((String) row[0], (String) row[1], (String) row[2], (String) row[3],
					(String) row[4], (Double) row[5], !Boolean.FALSE.equals(row[6])));
		}
		return found;
	}

	@Override
	@Transactional
	public int write(long pointId, List<Row> packet) {
		Map<String, Long> itemIds = itemIds(packet.stream().map(row -> row.itemCode).collect(Collectors.toList()));
		Map<Long, HoStockPointItem> existing = existing(pointId, new ArrayList<>(itemIds.values()));
		List<HoStockPointItem> toSave = new ArrayList<>();
		for (Row row : packet) {
			Long itemId = itemIds.get(row.itemCode);
			if (itemId == null) {
				continue; // the item is not at the head office: the next run hands the row again
			}
			HoStockPointItem saved = existing.get(itemId);
			if (saved == null) {
				saved = new HoStockPointItem();
				saved.setStockPointId(pointId);
				saved.setItemId(itemId);
			}
			saved.setName(row.name);
			saved.setDescription(row.description);
			saved.setFamilyCode(row.familyCode);
			saved.setSubFamilyCode(row.subFamilyCode);
			saved.setUnitPrice(row.unitPrice == null ? 0.0 : row.unitPrice);
			saved.setActive(row.active);
			toSave.add(saved);
		}
		rows.saveAll(toSave);
		return toSave.size();
	}

	/** item code to item.id, for the codes the head office has. */
	private Map<String, Long> itemIds(List<String> codes) {
		Map<String, Long> ids = new HashMap<>();
		for (int from = 0; from < codes.size(); from += IN_CHUNK) {
			List<?> found = entityManager.createQuery("select i.itemCode, i.id from Item i where i.itemCode in :codes")
					.setParameter("codes", codes.subList(from, Math.min(from + IN_CHUNK, codes.size()))).getResultList();
			for (Object line : found) {
				Object[] row = (Object[]) line;
				ids.put((String) row[0], (Long) row[1]);
			}
		}
		return ids;
	}

	/** The point's rows of these items, by item id. */
	private Map<Long, HoStockPointItem> existing(long pointId, List<Long> itemIds) {
		Map<Long, HoStockPointItem> found = new HashMap<>();
		for (int from = 0; from < itemIds.size(); from += IN_CHUNK) {
			List<HoStockPointItem> result = entityManager
					.createQuery("select r from HoStockPointItem r where r.stockPointId = :point and r.itemId in :ids",
							HoStockPointItem.class)
					.setParameter("point", pointId)
					.setParameter("ids", itemIds.subList(from, Math.min(from + IN_CHUNK, itemIds.size()))).getResultList();
			for (HoStockPointItem row : result) {
				found.put(row.getItemId(), row);
			}
		}
		return found;
	}
}
