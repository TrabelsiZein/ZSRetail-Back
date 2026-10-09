package com.digithink.zsretail.headoffice.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeErpCatalogue;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.headoffice.dto.StockPointItemDTO;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;

/**
 * Stock points, step 4: the rows of the points, read only, for the page "Items by point de stock": a page filtered and
 * paged by the server (a point can hold tens of thousands of rows) and one row with its item's details. See
 * docs/modules/head-office.md, "Stock points".
 */
@Service
@ConditionalOnHeadOfficeErpCatalogue
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
@Transactional(readOnly = true)
public class HoStockPointItemsService {

	static final int MAX_SIZE = 200;

	private final HoStockPointItemRepository rows;
	private final HoStockPointRepository points;
	private final ItemRepository items;
	private final ItemBarcodeRepository barcodes;
	private final ItemFamilyRepository families;
	private final ItemSubFamilyRepository subFamilies;

	public HoStockPointItemsService(HoStockPointItemRepository rows, HoStockPointRepository points, ItemRepository items,
			ItemBarcodeRepository barcodes, ItemFamilyRepository families, ItemSubFamilyRepository subFamilies) {
		this.rows = rows;
		this.points = points;
		this.items = items;
		this.barcodes = barcodes;
		this.families = families;
		this.subFamilies = subFamilies;
	}

	/**
	 * One page of rows, by point (list order) then item code. search: item code, name or barcode, contains, any case.
	 * status: ALL (or blank), ACTIVE, INACTIVE; 400 (IllegalArgument) for another value. size from 1 to 200.
	 */
	public Page<StockPointItemDTO> rows(Long pointId, String search, String familyCode, String subFamilyCode,
			Double priceMin, Double priceMax, String status, int page, int size) {
		String like = blank(search) ? null : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
		Page<Object[]> found = rows.findRows(pointId, like, blank(familyCode) ? null : familyCode.trim(),
				blank(subFamilyCode) ? null : subFamilyCode.trim(), priceMin, priceMax, active(status),
				PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE)));
		List<HoStockPointItem> pageRows = new ArrayList<>();
		List<Item> pageItems = new ArrayList<>();
		List<HoStockPoint> pagePoints = new ArrayList<>();
		for (Object[] line : found.getContent()) {
			pageRows.add((HoStockPointItem) line[0]);
			pageItems.add((Item) line[1]);
			pagePoints.add((HoStockPoint) line[2]);
		}
		Views views = new Views(pageRows, pageItems);
		List<StockPointItemDTO> content = new ArrayList<>();
		for (int i = 0; i < pageRows.size(); i++) {
			content.add(views.view(pageRows.get(i), pageItems.get(i), pagePoints.get(i)));
		}
		return new org.springframework.data.domain.PageImpl<>(content, found.getPageable(), found.getTotalElements());
	}

	/** One row with its item's details; empty when unknown. */
	public Optional<StockPointItemDTO> row(Long id) {
		Optional<HoStockPointItem> row = rows.findById(id);
		if (!row.isPresent()) {
			return Optional.empty();
		}
		Optional<Item> item = items.findById(row.get().getItemId());
		Optional<HoStockPoint> point = points.findById(row.get().getStockPointId());
		if (!item.isPresent() || !point.isPresent()) {
			return Optional.empty();
		}
		return Optional.of(new Views(Collections.singletonList(row.get()), Collections.singletonList(item.get()))
				.view(row.get(), item.get(), point.get()));
	}

	/** ALL or blank: null; ACTIVE: true; INACTIVE: false. */
	static Boolean active(String status) {
		if (blank(status) || "ALL".equalsIgnoreCase(status.trim())) {
			return null;
		}
		if ("ACTIVE".equalsIgnoreCase(status.trim())) {
			return Boolean.TRUE;
		}
		if ("INACTIVE".equalsIgnoreCase(status.trim())) {
			return Boolean.FALSE;
		}
		throw new IllegalArgumentException("Invalid status '" + status + "': allowed values are ALL, ACTIVE, INACTIVE.");
	}

	private static boolean blank(String value) {
		return value == null || value.trim().isEmpty();
	}

	/** The names of the families and the barcodes of one page, read once. */
	private final class Views {
		final Map<String, String> familyNames = new HashMap<>();
		final Map<String, String> subFamilyNames = new HashMap<>();
		final Map<Long, List<ItemBarcode>> barcodesByItem;

		Views(List<HoStockPointItem> pageRows, List<Item> pageItems) {
			List<String> familyCodes = pageRows.stream().map(HoStockPointItem::getFamilyCode).filter(Objects::nonNull)
					.distinct().collect(Collectors.toList());
			List<String> subFamilyCodes = pageRows.stream().map(HoStockPointItem::getSubFamilyCode)
					.filter(Objects::nonNull).distinct().collect(Collectors.toList());
			if (!familyCodes.isEmpty()) {
				for (ItemFamily family : families.findByCodeIn(familyCodes)) {
					familyNames.put(family.getCode(), family.getName());
				}
			}
			if (!subFamilyCodes.isEmpty()) {
				for (ItemSubFamily subFamily : subFamilies.findByCodeIn(subFamilyCodes)) {
					subFamilyNames.put(subFamily.getCode(), subFamily.getName());
				}
			}
			List<Long> itemIds = pageItems.stream().map(Item::getId).distinct().collect(Collectors.toList());
			barcodesByItem = itemIds.isEmpty() ? new HashMap<>()
					: barcodes.findByItemIdIn(itemIds).stream().collect(Collectors.groupingBy(b -> b.getItem().getId()));
		}

		StockPointItemDTO view(HoStockPointItem row, Item item, HoStockPoint point) {
			StockPointItemDTO view = new StockPointItemDTO();
			view.setId(row.getId());
			view.setStockPointId(point.getId());
			view.setStockPointCode(point.getCode());
			view.setStockPointName(point.getName());
			view.setItemId(item.getId());
			view.setItemCode(item.getItemCode());
			view.setName(row.getName());
			view.setDescription(row.getDescription());
			view.setFamilyCode(row.getFamilyCode());
			view.setFamilyName(familyNames.get(row.getFamilyCode()));
			view.setSubFamilyCode(row.getSubFamilyCode());
			view.setSubFamilyName(subFamilyNames.get(row.getSubFamilyCode()));
			view.setUnitPrice(row.getUnitPrice());
			view.setActive(!Boolean.FALSE.equals(row.getActive()));
			view.setDefaultVAT(item.getDefaultVAT());
			view.setType(item.getType() == null ? null : item.getType().name());
			view.setUnitOfMeasure(item.getUnitOfMeasure());
			view.setCategory(item.getCategory());
			view.setBrand(item.getBrand());
			view.setItemDiscGroup(item.getItemDiscGroup());
			view.setMaximumAuthorizedDiscount(item.getMaximumAuthorizedDiscount());
			view.setShowInPos(!Boolean.FALSE.equals(item.getShowInPos()));
			view.setItemActive(!Boolean.FALSE.equals(item.getActive()));
			for (ItemBarcode barcode : barcodesByItem.getOrDefault(item.getId(), Collections.emptyList())) {
				view.getBarcodes().add(new StockPointItemDTO.Barcode(barcode.getBarcode(),
						Boolean.TRUE.equals(barcode.getIsPrimary()), barcode.getDescription(),
						!Boolean.FALSE.equals(barcode.getActive())));
			}
			view.getBarcodes().sort((a, b) -> a.getBarcode().compareTo(b.getBarcode()));
			return view;
		}
	}
}
