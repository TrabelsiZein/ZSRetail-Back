package com.digithink.zsretail.core.erp.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.hibernate.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.digithink.zsretail.core.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.core.erp.enumeration.ErpSyncOperation;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.Location;
import com.digithink.zsretail.core.model.SalesDiscount;
import com.digithink.zsretail.core.model.SalesPrice;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.model.enumeration.SalesDiscountSalesType;
import com.digithink.zsretail.core.model.enumeration.SalesDiscountType;
import com.digithink.zsretail.core.model.enumeration.SalesPriceType;
import com.digithink.zsretail.core.repository.CustomerRepository;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.core.repository.LocationRepository;
import com.digithink.zsretail.core.repository.SalesDiscountRepository;
import com.digithink.zsretail.core.repository.SalesPriceRepository;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.erp.api.dto.ErpCustomerDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpLocationDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesDiscountDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesPriceDTO;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ErpItemBootstrapService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ErpItemBootstrapService.class);
	private static final String SYSTEM_USER = "ERP_SYNC";

	private final ItemFamilyRepository itemFamilyRepository;
	private final ItemSubFamilyRepository itemSubFamilyRepository;
	private final ItemRepository itemRepository;
	private final ItemBarcodeRepository itemBarcodeRepository;
	private final CustomerRepository customerRepository;
	private final LocationRepository locationRepository;
	private final SalesPriceRepository salesPriceRepository;
	private final SalesDiscountRepository salesDiscountRepository;
	private final ErpCommunicationService communicationService;
	private final GeneralSetupService generalSetupService;
	/** Item variants (ZEN), step 4: the catégories and the sections. */
	private final ItemCategoryRepository itemCategoryRepository;
	private final ItemSectionRepository itemSectionRepository;

	@Transactional
	public List<ItemFamily> importItemFamilies(List<ErpItemFamilyDTO> families) {
		if (families == null || families.isEmpty()) {
			return Collections.emptyList();
		}

		List<ItemFamily> persisted = new ArrayList<>();
		for (ErpItemFamilyDTO dto : families) {
			if (dto == null) {
				continue;
			}
			ItemFamily entity = resolveItemFamily(dto);
			boolean isNew = entity.getId() == null;
			applyFamilyValues(entity, dto);
			if (!StringUtils.hasText(entity.getCode())) {
				String message = "Skipping item family import because no code/external identifier provided";
				LOGGER.warn("{}: {}", message, dto);
				recordWarning(ErpSyncOperation.IMPORT_ITEM_FAMILIES, dto,
						resolveIdentifier(dto.getExternalId(), dto.getCode()), message);
				continue;
			}
			if (isNew) {
				entity.setCreatedBy(SYSTEM_USER);
				entity.setCreatedAt(LocalDateTime.now());
			}
			entity.setUpdatedBy(SYSTEM_USER);
			entity.setUpdatedAt(LocalDateTime.now());
			persisted.add(itemFamilyRepository.save(entity));
		}
		return persisted;
	}

	/**
	 * Item variants (ZEN), step 4: the catégories (above the families), found by ERP id then by code, as the families.
	 */
	@Transactional
	public List<ItemCategory> importItemCategories(List<ErpItemCategoryDTO> categories) {
		if (categories == null || categories.isEmpty()) {
			return Collections.emptyList();
		}
		List<ItemCategory> persisted = new ArrayList<>();
		for (ErpItemCategoryDTO dto : categories) {
			if (dto == null) {
				continue;
			}
			ItemCategory entity = findCategoryByExternalOrCode(dto.getExternalId(), dto.getCode())
					.orElseGet(ItemCategory::new);
			boolean isNew = entity.getId() == null;
			entity.setErpExternalId(defaultString(dto.getExternalId()));
			entity.setCode(defaultString(dto.getCode(), dto.getExternalId()));
			entity.setName(defaultString(dto.getName(), dto.getCode(), dto.getExternalId()));
			entity.setDescription(defaultString(dto.getDescription()));
			if (dto.getDisplayOrder() != null) {
				entity.setDisplayOrder(dto.getDisplayOrder());
			} else if (entity.getDisplayOrder() == null) {
				entity.setDisplayOrder(0);
			}
			if (dto.getActive() != null) {
				entity.setActive(dto.getActive());
			}
			if (!StringUtils.hasText(entity.getCode())) {
				String message = "Skipping item category import because no code/external identifier provided";
				LOGGER.warn("{}: {}", message, dto);
				recordWarning(ErpSyncOperation.IMPORT_ITEM_CATEGORIES, dto,
						resolveIdentifier(dto.getExternalId(), dto.getCode()), message);
				continue;
			}
			stamp(entity, isNew);
			persisted.add(itemCategoryRepository.save(entity));
		}
		return persisted;
	}

	/**
	 * Item variants (ZEN), step 4: the sections (below the sub-families), found by ERP id then by code; the sub-family is
	 * kept by code (trimmed, blank as null), never looked up: a section is never held back for its sub-family.
	 */
	@Transactional
	public List<ItemSection> importItemSections(List<ErpItemSectionDTO> sections) {
		if (sections == null || sections.isEmpty()) {
			return Collections.emptyList();
		}
		List<ItemSection> persisted = new ArrayList<>();
		for (ErpItemSectionDTO dto : sections) {
			if (dto == null) {
				continue;
			}
			ItemSection entity = findSectionByExternalOrCode(dto.getExternalId(), dto.getCode()).orElseGet(ItemSection::new);
			boolean isNew = entity.getId() == null;
			entity.setErpExternalId(defaultString(dto.getExternalId()));
			entity.setCode(defaultString(dto.getCode(), dto.getExternalId()));
			entity.setName(defaultString(dto.getName(), dto.getCode(), dto.getExternalId()));
			entity.setDescription(defaultString(dto.getDescription()));
			if (dto.getDisplayOrder() != null) {
				entity.setDisplayOrder(dto.getDisplayOrder());
			} else if (entity.getDisplayOrder() == null) {
				entity.setDisplayOrder(0);
			}
			if (dto.getActive() != null) {
				entity.setActive(dto.getActive());
			}
			entity.setSubFamilyCode(StringUtils.hasText(dto.getSubFamilyExternalId())
					? dto.getSubFamilyExternalId().trim()
					: null);
			if (!StringUtils.hasText(entity.getCode())) {
				String message = "Skipping item section import because no code/external identifier provided";
				LOGGER.warn("{}: {}", message, dto);
				recordWarning(ErpSyncOperation.IMPORT_ITEM_SECTIONS, dto,
						resolveIdentifier(dto.getExternalId(), dto.getCode()), message);
				continue;
			}
			stamp(entity, isNew);
			persisted.add(itemSectionRepository.save(entity));
		}
		return persisted;
	}

	/** Created and updated by the import. */
	private static void stamp(_BaseEntity entity, boolean isNew) {
		if (isNew) {
			entity.setCreatedBy(SYSTEM_USER);
			entity.setCreatedAt(LocalDateTime.now());
		}
		entity.setUpdatedBy(SYSTEM_USER);
		entity.setUpdatedAt(LocalDateTime.now());
	}

	private Optional<ItemCategory> findCategoryByExternalOrCode(String externalId, String code) {
		if (StringUtils.hasText(externalId)) {
			Optional<ItemCategory> byExternalId = itemCategoryRepository.findByErpExternalId(externalId);
			if (byExternalId.isPresent()) {
				return byExternalId;
			}
		}
		return StringUtils.hasText(code) ? itemCategoryRepository.findByCode(code) : Optional.empty();
	}

	private Optional<ItemSection> findSectionByExternalOrCode(String externalId, String code) {
		if (StringUtils.hasText(externalId)) {
			Optional<ItemSection> byExternalId = itemSectionRepository.findByErpExternalId(externalId);
			if (byExternalId.isPresent()) {
				return byExternalId;
			}
		}
		return StringUtils.hasText(code) ? itemSectionRepository.findByCode(code) : Optional.empty();
	}

	@Transactional
	public List<ItemSubFamily> importItemSubFamilies(List<ErpItemSubFamilyDTO> subFamilies) {
		if (subFamilies == null || subFamilies.isEmpty()) {
			return Collections.emptyList();
		}

		List<ItemSubFamily> persisted = new ArrayList<>();
		for (ErpItemSubFamilyDTO dto : subFamilies) {
			if (dto == null) {
				continue;
			}

			Optional<ItemFamily> familyOpt = resolveFamily(dto.getFamilyExternalId());
			if (!familyOpt.isPresent()) {
				String message = String.format("Skipping item subfamily import because parent family %s not found",
						dto.getFamilyExternalId());
				LOGGER.warn("{}: {}", message, dto.getExternalId());
				recordWarning(ErpSyncOperation.IMPORT_ITEM_SUBFAMILIES, dto,
						resolveIdentifier(dto.getExternalId(), dto.getCode()), message);
				continue;
			}

			ItemSubFamily entity = resolveItemSubFamily(dto);
			boolean isNew = entity.getId() == null;
			applySubFamilyValues(entity, dto, familyOpt.get());
			if (!StringUtils.hasText(entity.getCode())) {
				String message = "Skipping item subfamily import because no code/external identifier provided";
				LOGGER.warn("{}: {}", message, dto);
				recordWarning(ErpSyncOperation.IMPORT_ITEM_SUBFAMILIES, dto,
						resolveIdentifier(dto.getExternalId(), dto.getCode()), message);
				continue;
			}
			if (isNew) {
				entity.setCreatedBy(SYSTEM_USER);
				entity.setCreatedAt(LocalDateTime.now());
			}
			entity.setUpdatedBy(SYSTEM_USER);
			entity.setUpdatedAt(LocalDateTime.now());
			persisted.add(itemSubFamilyRepository.save(entity));
		}
		return persisted;
	}

	@Transactional
	public List<Item> importItems(List<ErpItemDTO> items) {
		if (items == null || items.isEmpty()) {
			return Collections.emptyList();
		}

		long started = System.nanoTime();
		batchWrites();
		List<Item> persisted = new ArrayList<>();
		String taxStampErpCode = generalSetupService.findValueByCode("TAX_STAMP_ERP_ITEM_CODE");
		// 3.1.0 speed: the packet's items, families and sub-families read once, not one SELECT per row (each also
		// flushed the whole packet before it ran)
		ItemLookups lookups = new ItemLookups(items);
		int created = 0;
		for (ErpItemDTO dto : items) {
			if (dto == null) {
				continue;
			}
			// Skip ERP item that is configured as tax stamp; we use the local TAX_STAMP item instead
			if (StringUtils.hasText(taxStampErpCode) && taxStampErpCode.equals(dto.getCode())) {
				LOGGER.debug("Skipping ERP item with code {} (tax stamp item - using local TAX_STAMP)", taxStampErpCode);
				continue;
			}
			Item entity = lookups.item(dto.getExternalId(), dto.getCode()).orElseGet(Item::new);
			boolean isNew = entity.getId() == null;
			applyItemValues(entity, dto, lookups);
			if (!StringUtils.hasText(entity.getItemCode())) {
				String message = "Skipping item import because no code/external identifier provided";
				LOGGER.warn("{}: {}", message, dto);
				recordWarning(ErpSyncOperation.IMPORT_ITEMS, dto, resolveIdentifier(dto.getExternalId(), dto.getCode()),
						message);
				continue;
			}
			if (isNew) {
				entity.setCreatedBy(SYSTEM_USER);
				entity.setCreatedAt(LocalDateTime.now());
				created++;
			}
			entity.setUpdatedBy(SYSTEM_USER);
			entity.setUpdatedAt(LocalDateTime.now());
			Item saved = itemRepository.save(entity);
			lookups.saved(saved); // a later row of the packet with the same code finds it
			persisted.add(saved);
		}
		LOGGER.info("ERP items import: {} rows handed, {} written ({} new, {} updated) in {} ms", items.size(),
				persisted.size(), created, persisted.size() - created, (System.nanoTime() - started) / 1_000_000);
		return persisted;
	}

	/** 3.1.0 speed: rows per JDBC batch for the item writes of one import transaction (updates; inserts use IDENTITY). */
	static final int WRITE_BATCH_SIZE = 100;

	/** Most parameters in one IN list (SQL Server takes 2,100 per statement). */
	static final int IN_CHUNK = 1000;

	/** Looked up only for the batch size of this transaction; null in the tests without a database. */
	@PersistenceContext
	private EntityManager entityManager;

	/** The item writes of this transaction sent by JDBC batches (this session only: other writes are unchanged). */
	private void batchWrites() {
		if (entityManager == null) {
			return;
		}
		try {
			entityManager.unwrap(Session.class).setJdbcBatchSize(WRITE_BATCH_SIZE);
		} catch (RuntimeException e) {
			LOGGER.debug("ERP items import: JDBC batch size not set ({})", e.getMessage());
		}
	}

	/**
	 * 3.1.0 speed: the items of one packet by ERP id and by code (two queries per 1,000 rows), the families and
	 * sub-families by ERP id and by code (read whole: a few hundred rows). The same order as the lookups of before: the
	 * ERP id first, then the code.
	 */
	private final class ItemLookups {

		private final Map<String, Item> itemsByExternalId = new HashMap<>();
		private final Map<String, Item> itemsByCode = new HashMap<>();
		private Map<String, ItemFamily> familiesByExternalId;
		private Map<String, ItemFamily> familiesByCode;
		private Map<String, ItemSubFamily> subFamiliesByExternalId;
		private Map<String, ItemSubFamily> subFamiliesByCode;

		ItemLookups(List<ErpItemDTO> packet) {
			Set<String> externalIds = new LinkedHashSet<>();
			Set<String> codes = new LinkedHashSet<>();
			for (ErpItemDTO dto : packet) {
				if (dto == null) {
					continue;
				}
				if (StringUtils.hasText(dto.getExternalId())) {
					externalIds.add(dto.getExternalId());
				}
				if (StringUtils.hasText(dto.getCode())) {
					codes.add(dto.getCode());
				}
			}
			for (List<String> chunk : chunks(externalIds)) {
				itemRepository.findByErpExternalIdIn(chunk).forEach(item -> putFirst(itemsByExternalId,
						item.getErpExternalId(), item));
			}
			for (List<String> chunk : chunks(codes)) {
				itemRepository.findByItemCodeIn(chunk).forEach(item -> putFirst(itemsByCode, item.getItemCode(), item));
			}
		}

		Optional<Item> item(String externalId, String code) {
			if (StringUtils.hasText(externalId) && itemsByExternalId.containsKey(externalId)) {
				return Optional.of(itemsByExternalId.get(externalId));
			}
			return StringUtils.hasText(code) ? Optional.ofNullable(itemsByCode.get(code)) : Optional.empty();
		}

		void saved(Item item) {
			putFirst(itemsByExternalId, item.getErpExternalId(), item);
			putFirst(itemsByCode, item.getItemCode(), item);
		}

		Optional<ItemFamily> family(String identifier) {
			if (!StringUtils.hasText(identifier)) {
				return Optional.empty();
			}
			if (familiesByExternalId == null) {
				familiesByExternalId = new HashMap<>();
				familiesByCode = new HashMap<>();
				for (ItemFamily family : itemFamilyRepository.findAll()) {
					putFirst(familiesByExternalId, family.getErpExternalId(), family);
					putFirst(familiesByCode, family.getCode(), family);
				}
			}
			ItemFamily found = familiesByExternalId.get(identifier);
			return Optional.ofNullable(found != null ? found : familiesByCode.get(identifier));
		}

		Optional<ItemSubFamily> subFamily(String identifier) {
			if (!StringUtils.hasText(identifier)) {
				return Optional.empty();
			}
			if (subFamiliesByExternalId == null) {
				subFamiliesByExternalId = new HashMap<>();
				subFamiliesByCode = new HashMap<>();
				for (ItemSubFamily subFamily : itemSubFamilyRepository.findAll()) {
					putFirst(subFamiliesByExternalId, subFamily.getErpExternalId(), subFamily);
					putFirst(subFamiliesByCode, subFamily.getCode(), subFamily);
				}
			}
			ItemSubFamily found = subFamiliesByExternalId.get(identifier);
			return Optional.ofNullable(found != null ? found : subFamiliesByCode.get(identifier));
		}
	}

	private static <T> void putFirst(Map<String, T> map, String key, T value) {
		if (StringUtils.hasText(key)) {
			map.putIfAbsent(key, value);
		}
	}

	private static List<List<String>> chunks(Set<String> values) {
		List<String> all = new ArrayList<>(values);
		List<List<String>> chunks = new ArrayList<>();
		for (int from = 0; from < all.size(); from += IN_CHUNK) {
			chunks.add(all.subList(from, Math.min(from + IN_CHUNK, all.size())));
		}
		return chunks;
	}

	@Transactional
	public List<ItemBarcode> importItemBarcodes(List<ErpItemBarcodeDTO> barcodes) {
		if (barcodes == null || barcodes.isEmpty()) {
			return Collections.emptyList();
		}

		List<ItemBarcode> persisted = new ArrayList<>();
		for (ErpItemBarcodeDTO dto : barcodes) {
			if (dto == null) {
				continue;
			}
			Optional<Item> itemOpt = resolveItem(dto.getItemExternalId());
			if (!itemOpt.isPresent()) {
				String message = String.format("Skipping item barcode because parent item %s not found",
						dto.getItemExternalId());
				LOGGER.warn("{}: {}", message, dto.getBarcode());
				recordWarning(ErpSyncOperation.IMPORT_ITEM_BARCODES, dto,
						resolveIdentifier(dto.getExternalId(), dto.getBarcode()), message);
				continue;
			}

			ItemBarcode entity = resolveItemBarcode(dto);
			boolean isNew = entity.getId() == null;
			applyBarcodeValues(entity, dto, itemOpt.get());
			if (!StringUtils.hasText(entity.getBarcode())) {
				String message = "Skipping item barcode import because no barcode value provided";
				LOGGER.warn("{}: {}", message, dto);
				recordWarning(ErpSyncOperation.IMPORT_ITEM_BARCODES, dto,
						resolveIdentifier(dto.getExternalId(), dto.getBarcode()), message);
				continue;
			}
			if (isNew) {
				entity.setCreatedBy(SYSTEM_USER);
				entity.setCreatedAt(LocalDateTime.now());
			}
			entity.setUpdatedBy(SYSTEM_USER);
			entity.setUpdatedAt(LocalDateTime.now());
			persisted.add(itemBarcodeRepository.save(entity));
		}
		return persisted;
	}

	@Transactional
	public List<Location> importLocations(List<ErpLocationDTO> locations) {
		if (locations == null || locations.isEmpty()) {
			return Collections.emptyList();
		}

		List<Location> persisted = new ArrayList<>();
		for (ErpLocationDTO dto : locations) {
			if (dto == null) {
				continue;
			}
			Location entity = resolveLocation(dto);
			boolean isNew = entity.getId() == null;
			applyLocationValues(entity, dto);
			if (!StringUtils.hasText(entity.getLocationCode())) {
				String message = "Skipping location import because no code/external identifier provided";
				LOGGER.warn("{}: {}", message, dto);
				recordWarning(ErpSyncOperation.IMPORT_LOCATIONS, dto,
						resolveIdentifier(dto.getExternalId(), dto.getCode()), message);
				continue;
			}
			if (isNew) {
				entity.setCreatedBy(SYSTEM_USER);
				entity.setCreatedAt(LocalDateTime.now());
			}
			entity.setUpdatedBy(SYSTEM_USER);
			entity.setUpdatedAt(LocalDateTime.now());
			persisted.add(locationRepository.save(entity));
		}
		return persisted;
	}

	@Transactional
	public List<Customer> importCustomers(List<ErpCustomerDTO> customers) {
		if (customers == null || customers.isEmpty()) {
			return Collections.emptyList();
		}

		List<Customer> persisted = new ArrayList<>();
		for (ErpCustomerDTO dto : customers) {
			if (dto == null) {
				continue;
			}
			Customer entity = resolveCustomer(dto);
			boolean isNew = entity.getId() == null;
			applyCustomerValues(entity, dto);
			if (!StringUtils.hasText(entity.getCustomerCode())) {
				String message = "Skipping customer import because no code/external identifier provided";
				LOGGER.warn("{}: {}", message, dto);
				recordWarning(ErpSyncOperation.IMPORT_CUSTOMERS, dto,
						resolveIdentifier(dto.getExternalId(), dto.getCode()), message);
				continue;
			}
			if (isNew) {
				entity.setCreatedBy(SYSTEM_USER);
				entity.setCreatedAt(LocalDateTime.now());
			}
			entity.setUpdatedBy(SYSTEM_USER);
			entity.setUpdatedAt(LocalDateTime.now());
			persisted.add(customerRepository.save(entity));
		}
		return persisted;
	}

	private ItemFamily resolveItemFamily(ErpItemFamilyDTO dto) {
		return findFamilyByExternalOrCode(dto.getExternalId(), dto.getCode()).orElseGet(ItemFamily::new);
	}

	private Optional<ItemFamily> resolveFamily(String identifier) {
		if (!StringUtils.hasText(identifier)) {
			return Optional.empty();
		}
		Optional<ItemFamily> byExternal = itemFamilyRepository.findByErpExternalId(identifier);
		if (byExternal.isPresent()) {
			return byExternal;
		}
		return itemFamilyRepository.findByCode(identifier);
	}

	private Optional<ItemFamily> findFamilyByExternalOrCode(String externalId, String code) {
		if (StringUtils.hasText(externalId)) {
			Optional<ItemFamily> byExternalId = itemFamilyRepository.findByErpExternalId(externalId);
			if (byExternalId.isPresent()) {
				return byExternalId;
			}
		}
		if (StringUtils.hasText(code)) {
			return itemFamilyRepository.findByCode(code);
		}
		return Optional.empty();
	}

	private ItemSubFamily resolveItemSubFamily(ErpItemSubFamilyDTO dto) {
		return findSubFamilyByExternalOrCode(dto.getExternalId(), dto.getCode()).orElseGet(ItemSubFamily::new);
	}

	private Optional<ItemSubFamily> findSubFamilyByExternalOrCode(String externalId, String code) {
		if (StringUtils.hasText(externalId)) {
			Optional<ItemSubFamily> byExternalId = itemSubFamilyRepository.findByErpExternalId(externalId);
			if (byExternalId.isPresent()) {
				return byExternalId;
			}
		}
		if (StringUtils.hasText(code)) {
			return itemSubFamilyRepository.findByCode(code);
		}
		return Optional.empty();
	}

	private Optional<Item> resolveItem(String identifier) {
		if (!StringUtils.hasText(identifier)) {
			return Optional.empty();
		}
		Optional<Item> byExternal = itemRepository.findByErpExternalId(identifier);
		if (byExternal.isPresent()) {
			return byExternal;
		}
		return itemRepository.findByItemCode(identifier);
	}

	private Optional<Item> findItemByExternalOrCode(String externalId, String code) {
		if (StringUtils.hasText(externalId)) {
			Optional<Item> byExternalId = itemRepository.findByErpExternalId(externalId);
			if (byExternalId.isPresent()) {
				return byExternalId;
			}
		}
		if (StringUtils.hasText(code)) {
			return itemRepository.findByItemCode(code);
		}
		return Optional.empty();
	}

	private ItemBarcode resolveItemBarcode(ErpItemBarcodeDTO dto) {
		return findBarcodeByExternalOrValue(dto.getExternalId(), dto.getBarcode()).orElseGet(ItemBarcode::new);
	}

	private Optional<ItemBarcode> findBarcodeByExternalOrValue(String externalId, String barcode) {
		if (StringUtils.hasText(externalId)) {
			Optional<ItemBarcode> byExternal = itemBarcodeRepository.findByErpExternalId(externalId);
			if (byExternal.isPresent()) {
				return byExternal;
			}
		}
		if (StringUtils.hasText(barcode)) {
			return itemBarcodeRepository.findByBarcode(barcode);
		}
		return Optional.empty();
	}

	private Location resolveLocation(ErpLocationDTO dto) {
		return findLocationByExternalOrCode(dto.getExternalId(), dto.getCode()).orElseGet(Location::new);
	}

	private Optional<Location> findLocationByExternalOrCode(String externalId, String code) {
		if (StringUtils.hasText(externalId)) {
			Optional<Location> byExternal = locationRepository.findByErpExternalId(externalId);
			if (byExternal.isPresent()) {
				return byExternal;
			}
		}
		if (StringUtils.hasText(code)) {
			return locationRepository.findByLocationCode(code);
		}
		return Optional.empty();
	}

	private Customer resolveCustomer(ErpCustomerDTO dto) {
		return findCustomerByExternalOrCode(dto.getExternalId(), dto.getCode()).orElseGet(Customer::new);
	}

	private Optional<Customer> findCustomerByExternalOrCode(String externalId, String code) {
		if (StringUtils.hasText(externalId)) {
			Optional<Customer> byExternal = customerRepository.findByErpExternalId(externalId);
			if (byExternal.isPresent()) {
				return byExternal;
			}
		}
		if (StringUtils.hasText(code)) {
			return customerRepository.findByCustomerCode(code);
		}
		return Optional.empty();
	}

	private void applyFamilyValues(ItemFamily entity, ErpItemFamilyDTO dto) {
		entity.setErpExternalId(defaultString(dto.getExternalId()));
		entity.setCode(defaultString(dto.getCode(), dto.getExternalId()));
		entity.setName(defaultString(dto.getName(), dto.getCode(), dto.getExternalId()));
		entity.setDescription(defaultString(dto.getDescription()));
		if (dto.getDisplayOrder() != null) {
			entity.setDisplayOrder(dto.getDisplayOrder());
		} else if (entity.getDisplayOrder() == null) {
			entity.setDisplayOrder(0);
		}
		if (dto.getActive() != null) {
			entity.setActive(dto.getActive());
		}
		// Item variants (ZEN), step 4: the catégorie above the family, by code, only when the ERP gives one
		if (StringUtils.hasText(dto.getCategoryExternalId())) {
			entity.setCategoryCode(dto.getCategoryExternalId().trim());
		}
	}

	private void applySubFamilyValues(ItemSubFamily entity, ErpItemSubFamilyDTO dto, ItemFamily family) {
		entity.setErpExternalId(defaultString(dto.getExternalId()));
		entity.setCode(defaultString(dto.getCode(), dto.getExternalId()));
		entity.setName(defaultString(dto.getName(), dto.getCode(), dto.getExternalId()));
		entity.setDescription(defaultString(dto.getDescription()));
		if (dto.getDisplayOrder() != null) {
			entity.setDisplayOrder(dto.getDisplayOrder());
		} else if (entity.getDisplayOrder() == null) {
			entity.setDisplayOrder(0);
		}
		if (dto.getActive() != null) {
			entity.setActive(dto.getActive());
		}
		entity.setItemFamily(family);
	}

	private void applyItemValues(Item entity, ErpItemDTO dto, ItemLookups lookups) {
		entity.setErpExternalId(defaultString(dto.getExternalId()));
		entity.setItemCode(defaultString(dto.getCode(), dto.getExternalId()));
		entity.setName(defaultString(dto.getName(), dto.getCode(), dto.getExternalId()));
		entity.setDescription(defaultString(dto.getDescription()));
		entity.setUnitPrice(toDouble(dto.getUnitPrice()));
		entity.setDefaultVAT(dto.getDefaultVAT());
		entity.setItemDiscGroup(defaultString(dto.getItemDiscGroup()));
		entity.setMaximumAuthorizedDiscount(dto.getMaximumAuthorizedDiscount() != null ? dto.getMaximumAuthorizedDiscount() : null);
		if (dto.getActive() != null) {
			entity.setActive(dto.getActive());
		}
		Optional<ItemFamily> familyOpt = lookups.family(dto.getFamilyExternalId());
		Optional<ItemSubFamily> subFamilyOpt = lookups.subFamily(dto.getSubFamilyExternalId());

		familyOpt.ifPresent(entity::setItemFamily);

		subFamilyOpt.ifPresent(sf -> {
			entity.setItemSubFamily(sf);
			// If NAV only provides subfamily, ensure parent family is set from the
			// subfamily mapping
			if (entity.getItemFamily() == null && sf.getItemFamily() != null) {
				entity.setItemFamily(sf.getItemFamily());
			}
		});
		// Item variants (ZEN), step 3: the 14 variant fields always, null when the ERP gives none; the brand only when the
		// ERP gives one, so a brand typed by hand is never erased by an ERP that has none
		ItemVariant.of(dto).applyTo(entity);
		if (StringUtils.hasText(dto.getBrand())) {
			entity.setBrand(dto.getBrand().trim());
		}
	}

	private void applyBarcodeValues(ItemBarcode entity, ErpItemBarcodeDTO dto, Item item) {
		entity.setErpExternalId(defaultString(dto.getExternalId(), dto.getBarcode()));
		entity.setItem(item);
		entity.setBarcode(defaultString(dto.getBarcode(), dto.getExternalId()));
		if (dto.getPrimaryBarcode() != null) {
			entity.setIsPrimary(dto.getPrimaryBarcode());
		}
		entity.setActive(Boolean.TRUE);
	}

	private void applyLocationValues(Location entity, ErpLocationDTO dto) {
		entity.setErpExternalId(defaultString(dto.getExternalId(), dto.getCode()));
		entity.setLocationCode(defaultString(dto.getCode(), dto.getExternalId()));
		entity.setName(defaultString(dto.getName(), dto.getCode(), dto.getExternalId()));
		entity.setDescription(defaultString(dto.getAddress()));
		entity.setAddress(defaultString(dto.getAddress()));
		entity.setCity(defaultString(dto.getCity()));
		entity.setCountry(defaultString(dto.getCountry()));
		entity.setResponsibilityCenter(dto.getResponsibilityCenter());
		if (dto.getActive() != null) {
			entity.setActive(dto.getActive());
		}
	}

	private void applyCustomerValues(Customer entity, ErpCustomerDTO dto) {
		entity.setErpExternalId(defaultString(dto.getExternalId(), dto.getCode()));
		entity.setCustomerCode(defaultString(dto.getCode(), dto.getExternalId()));
		entity.setName(defaultString(dto.getName(), dto.getCode(), dto.getExternalId()));
		entity.setEmail(defaultString(dto.getEmail()));
		entity.setPhone(defaultString(dto.getPhone()));
		entity.setAddress(defaultString(dto.getAddress()));
		entity.setCity(defaultString(dto.getCity()));
		entity.setCountry(defaultString(dto.getCountry()));
		entity.setTaxId(defaultString(dto.getTaxNumber()));
		if (dto.getActive() != null) {
			entity.setActive(dto.getActive());
		}
		entity.setCustomerPriceGroup(dto.getCustomerPriceGroup());
		entity.setCustomerDiscGroup(dto.getCustomerDiscGroup());
		entity.setAuxiliaryIndex1(dto.getAuxiliaryIndex1());
	}

	private Double toDouble(BigDecimal value) {
		return value != null ? value.doubleValue() : null;
	}

	private String defaultString(String value) {
		return value != null ? value : "";
	}

	private String defaultString(String value, String... fallbacks) {
		if (StringUtils.hasText(value)) {
			return value;
		}
		for (String fallback : fallbacks) {
			if (StringUtils.hasText(fallback)) {
				return fallback;
			}
		}
		return "";
	}

	private void recordWarning(ErpSyncOperation operation, Object payload, String externalReference, String message) {
		communicationService.logOperation(operation, payload, null, ErpCommunicationStatus.WARNING, null, message,
				LocalDateTime.now(), LocalDateTime.now());
	}

	private String resolveIdentifier(String... identifiers) {
		for (String identifier : identifiers) {
			if (StringUtils.hasText(identifier)) {
				return identifier;
			}
		}
		return null;
	}

	@Transactional
	public List<SalesPrice> importSalesPrices(List<ErpSalesPriceDTO> salesPrices) {
		if (salesPrices == null || salesPrices.isEmpty()) {
			return Collections.emptyList();
		}

		List<SalesPrice> persisted = new ArrayList<>();
		for (ErpSalesPriceDTO dto : salesPrices) {
			if (dto == null) {
				continue;
			}

//			// Validate only the most critical required fields (itemNo, salesType,
//			// salesCode)
//			// Other composite key fields can be empty strings but will be set to defaults
//			// if null
//			if (!StringUtils.hasText(dto.getItemNo()) || !StringUtils.hasText(dto.getSalesType())
//					|| !StringUtils.hasText(dto.getSalesCode())) {
//				String message = "Skipping sales price import because required fields (itemNo, salesType, salesCode) are missing";
//				LOGGER.warn("{}: {}", message, dto);
//				recordWarning(ErpSyncOperation.IMPORT_SALES_PRICES, dto,
//						resolveIdentifier(dto.getExternalId(), dto.getItemNo()), message);
//				continue;
//			}

			// Find existing record by externalId (which contains all 9 composite key
			// fields)
			// If not found, resolveSalesPrice will return a new entity
			SalesPrice entity = resolveSalesPrice(dto);
			boolean isNew = entity.getId() == null;

			// Apply all values from DTO to entity (this will update existing or set values
			// for new)
			// If enum conversion fails, skip saving this record
			if (!applySalesPriceValues(entity, dto)) {
				String message = String.format(
						"Skipping sales price import because SalesPriceType conversion failed for value: %s",
						dto.getSalesType());
				LOGGER.warn("{}: ItemNo={}, ExternalId={}", message, dto.getItemNo(), dto.getExternalId());
				recordWarning(ErpSyncOperation.IMPORT_SALES_PRICES, dto,
						resolveIdentifier(dto.getExternalId(), dto.getItemNo()), message);
				continue;
			}

			// Set audit fields
			if (isNew) {
				entity.setCreatedBy(SYSTEM_USER);
				entity.setCreatedAt(LocalDateTime.now());
			}
			entity.setUpdatedBy(SYSTEM_USER);
			entity.setUpdatedAt(LocalDateTime.now());

			// Save: will INSERT if new (id == null) or UPDATE if existing (id != null)
			persisted.add(salesPriceRepository.save(entity));
		}
		return persisted;
	}

	@Transactional
	public List<SalesDiscount> importSalesDiscounts(List<ErpSalesDiscountDTO> salesDiscounts) {
		if (salesDiscounts == null || salesDiscounts.isEmpty()) {
			return Collections.emptyList();
		}

		List<SalesDiscount> persisted = new ArrayList<>();
		for (ErpSalesDiscountDTO dto : salesDiscounts) {
			if (dto == null) {
				continue;
			}

//			// Validate only the most critical required fields (Type, Code, SalesType,
//			// SalesCode)
//			// Other composite key fields can be empty strings but will be set to defaults
//			// if null
//			if (!StringUtils.hasText(dto.getType()) || !StringUtils.hasText(dto.getCode())
//					|| !StringUtils.hasText(dto.getSalesType()) || !StringUtils.hasText(dto.getSalesCode())) {
//				String message = "Skipping sales discount import because required fields (type, code, salesType, salesCode) are missing";
//				LOGGER.warn("{}: {}", message, dto);
//				recordWarning(ErpSyncOperation.IMPORT_SALES_DISCOUNTS, dto,
//						resolveIdentifier(dto.getExternalId(), dto.getCode()), message);
//				continue;
//			}

			// Find existing record by externalId (which contains all 10 composite key
			// fields)
			// If not found, resolveSalesDiscount will return a new entity
			SalesDiscount entity = resolveSalesDiscount(dto);
			boolean isNew = entity.getId() == null;

			// Apply all values from DTO to entity (this will update existing or set values
			// for new)
			// If enum conversion fails, skip saving this record
			if (!applySalesDiscountValues(entity, dto)) {
				String message = String.format(
						"Skipping sales discount import because enum conversion failed. Type: %s, SalesType: %s",
						dto.getType(), dto.getSalesType());
				LOGGER.warn("{}: Code={}, ExternalId={}", message, dto.getCode(), dto.getExternalId());
				recordWarning(ErpSyncOperation.IMPORT_SALES_DISCOUNTS, dto,
						resolveIdentifier(dto.getExternalId(), dto.getCode()), message);
				continue;
			}

			// Set audit fields
			if (isNew) {
				entity.setCreatedBy(SYSTEM_USER);
				entity.setCreatedAt(LocalDateTime.now());
			}
			entity.setUpdatedBy(SYSTEM_USER);
			entity.setUpdatedAt(LocalDateTime.now());

			// Save: will INSERT if new (id == null) or UPDATE if existing (id != null)
			persisted.add(salesDiscountRepository.save(entity));
		}
		return persisted;
	}

	/**
	 * Resolves a SalesPrice entity by finding an existing record or creating a new
	 * one.
	 * 
	 * The externalId is built from all 9 composite key fields, so we only need to
	 * lookup by externalId. If not found, returns a new entity.
	 * 
	 * @param dto The DTO containing the sales price data
	 * @return An existing SalesPrice entity if found by externalId, or a new
	 *         SalesPrice entity if not found
	 */
	private SalesPrice resolveSalesPrice(ErpSalesPriceDTO dto) {
		if (StringUtils.hasText(dto.getExternalId())) {
			return salesPriceRepository.findByErpExternalId(dto.getExternalId()).orElseGet(SalesPrice::new);
		}
		return new SalesPrice();
	}

	/**
	 * Resolves a SalesDiscount entity by finding an existing record or creating a
	 * new one.
	 * 
	 * The externalId is built from all 10 composite key fields, so we only need to
	 * lookup by externalId. If not found, returns a new entity.
	 * 
	 * @param dto The DTO containing the sales discount data
	 * @return An existing SalesDiscount entity if found by externalId, or a new
	 *         SalesDiscount entity if not found
	 */
	private SalesDiscount resolveSalesDiscount(ErpSalesDiscountDTO dto) {
		if (StringUtils.hasText(dto.getExternalId())) {
			return salesDiscountRepository.findByErpExternalId(dto.getExternalId()).orElseGet(SalesDiscount::new);
		}
		return new SalesDiscount();
	}

	/**
	 * Apply values from DTO to SalesPrice entity
	 * 
	 * @param entity The SalesPrice entity to update
	 * @param dto    The DTO containing the values
	 * @return true if enum conversion was successful, false otherwise
	 */
	private boolean applySalesPriceValues(SalesPrice entity, ErpSalesPriceDTO dto) {
		entity.setErpExternalId(defaultString(dto.getExternalId()));
		entity.setItemNo(defaultString(dto.getItemNo()));
		// Convert string to enum - if conversion fails, return false to skip saving
		SalesPriceType salesType = SalesPriceType.fromString(dto.getSalesType());
		if (salesType == null) {
			// Enum conversion failed - return false to skip saving
			return false;
		}
		entity.setSalesType(salesType);
		entity.setSalesCode(defaultString(dto.getSalesCode()));
		entity.setUnitPrice(toDouble(dto.getUnitPrice()));
		entity.setPriceIncludesVat(dto.getPriceIncludesVat());
		entity.setResponsibilityCenter(defaultString(dto.getResponsibilityCenter()));
		entity.setResponsibilityCenterType(defaultString(dto.getResponsibilityCenterType()));
		// startingDate is required (nullable = false), use today's date if ERP returns
		// null/empty
		// This means "valid from today" which is always valid
		LocalDate startingDate = parseLocalDate(dto.getStartingDate());
		if (startingDate == null) {
			startingDate = LocalDate.now();
		}
		entity.setStartingDate(startingDate);
		entity.setEndingDate(parseLocalDate(dto.getEndingDate()));
		entity.setCurrencyCode(defaultString(dto.getCurrencyCode()));
		entity.setVariantCode(defaultString(dto.getVariantCode()));
		entity.setUnitOfMeasureCode(defaultString(dto.getUnitOfMeasureCode()));
		// Set minimumQuantity, default to 0.0 if null (since it's part of composite key
		// and nullable = false)
		entity.setMinimumQuantity(dto.getMinimumQuantity() != null ? dto.getMinimumQuantity() : 0.0);
		entity.setActive(Boolean.TRUE);
		return true;
	}

	/**
	 * Apply values from DTO to SalesDiscount entity
	 * 
	 * @param entity The SalesDiscount entity to update
	 * @param dto    The DTO containing the values
	 * @return true if enum conversions were successful, false otherwise
	 */
	private boolean applySalesDiscountValues(SalesDiscount entity, ErpSalesDiscountDTO dto) {
		entity.setErpExternalId(defaultString(dto.getExternalId()));
		// Convert string to enum for type - if conversion fails, return false to skip
		// saving
		SalesDiscountType type = SalesDiscountType.fromString(dto.getType());
		if (type == null) {
			// Enum conversion failed - return false to skip saving
			return false;
		}
		entity.setType(type);
		entity.setCode(defaultString(dto.getCode()));
		// Convert string to enum for salesType - if conversion fails, return false to
		// skip saving
		SalesDiscountSalesType salesType = SalesDiscountSalesType.fromString(dto.getSalesType());
		if (salesType == null) {
			// Enum conversion failed - return false to skip saving
			return false;
		}
		entity.setSalesType(salesType);
		entity.setSalesCode(defaultString(dto.getSalesCode()));
		entity.setResponsibilityCenterType(defaultString(dto.getResponsibilityCenterType()));
		entity.setResponsibilityCenter(defaultString(dto.getResponsibilityCenter()));
		// startingDate is required (nullable = false), use today's date if ERP returns
		// null/empty
		// This means "valid from today" which is always valid
		LocalDate startingDate = parseLocalDate(dto.getStartingDate());
		if (startingDate == null) {
			startingDate = LocalDate.now();
		}
		entity.setStartingDate(startingDate);
		entity.setEndingDate(parseLocalDate(dto.getEndingDate()));
		entity.setLineDiscount(toDouble(dto.getLineDiscount()));
		// Set AuxiliaryIndex fields with defaults for null values (since they're part
		// of composite key and nullable = false)
		entity.setAuxiliaryIndex1(defaultString(dto.getAuxiliaryIndex1()));
		entity.setAuxiliaryIndex2(defaultString(dto.getAuxiliaryIndex2()));
		entity.setAuxiliaryIndex3(defaultString(dto.getAuxiliaryIndex3()));
		entity.setAuxiliaryIndex4(dto.getAuxiliaryIndex4() != null ? dto.getAuxiliaryIndex4() : 0);
		entity.setActive(Boolean.TRUE);
		return true;
	}

	/**
	 * Parse string date to LocalDate. Handles "0001-01-01" as null (indefinite end
	 * date). Returns null if date string is null, empty, or invalid.
	 */
	private LocalDate parseLocalDate(String dateString) {
		if (!StringUtils.hasText(dateString)) {
			return null;
		}
		String trimmed = dateString.trim();
		// Handle "0001-01-01" as null (indefinite end date)
		if ("0001-01-01".equals(trimmed)) {
			return null;
		}
		try {
			// Try ISO format first (YYYY-MM-DD)
			return LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE);
		} catch (DateTimeParseException e) {
			LOGGER.warn("Failed to parse date string: {}, returning null", dateString);
			return null;
		}
	}
}
