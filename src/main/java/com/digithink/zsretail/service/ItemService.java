package com.digithink.zsretail.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Subquery;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.repository._BaseRepository;

@Service
public class ItemService extends _BaseService<Item, Long> {

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private ItemFamilyRepository itemFamilyRepository;

	@Autowired
	private ItemSubFamilyRepository itemSubFamilyRepository;

	@Autowired
	private ApplicationModeService applicationModeService;

	@Override
	protected _BaseRepository<Item, Long> getRepository() {
		return itemRepository;
	}

	public Page<Item> findActiveItems(String search, Long familyId, Long subFamilyId, Double priceMin, Double priceMax,
			Boolean withBarcodesOnly, Pageable pageable) {
		Specification<Item> specification = (root, query, cb) -> {
			query.distinct(true);
			Predicate predicate = cb.conjunction();
			predicate = cb.and(predicate, cb.isTrue(root.get("active")));

			// Only show items visible in POS (hide system items e.g. Tax Stamp)
			Predicate showInPos = cb.or(cb.isTrue(root.get("showInPos")), cb.isNull(root.get("showInPos")));
			predicate = cb.and(predicate, showInPos);

			// Filter out items with price 0 or null
//			Predicate priceNotNull = cb.isNotNull(root.get("unitPrice"));
//			Predicate priceNotZero = cb.notEqual(root.get("unitPrice"), 0.0);
//			predicate = cb.and(predicate, priceNotNull, priceNotZero);

			if (Boolean.TRUE.equals(withBarcodesOnly)) {
				// Item has barcode in ItemBarcode table OR in legacy Item.barcode field
				Subquery<Long> hasItemBarcode = query.subquery(Long.class);
				var bcRoot = hasItemBarcode.from(com.digithink.zsretail.model.ItemBarcode.class);
				hasItemBarcode.select(bcRoot.get("item").get("id"));
				hasItemBarcode.where(cb.equal(bcRoot.get("item").get("id"), root.get("id")),
						cb.or(cb.isTrue(bcRoot.get("active")), cb.isNull(bcRoot.get("active"))));
				Predicate hasLegacyBarcode = cb.and(cb.isNotNull(root.get("barcode")),
						cb.notEqual(root.get("barcode"), ""));
				predicate = cb.and(predicate, cb.or(cb.exists(hasItemBarcode), hasLegacyBarcode));
			}

			if (StringUtils.hasText(search)) {
				String likeValue = "%" + search.toLowerCase() + "%";
				// Search in item code and name
				Predicate codeNameMatch = cb.or(cb.like(cb.lower(root.get("itemCode")), likeValue),
						cb.like(cb.lower(root.get("name")), likeValue));

				// Search in barcodes
				Subquery<Long> barcodeSubquery = query.subquery(Long.class);
				var barcodeRoot = barcodeSubquery.from(com.digithink.zsretail.model.ItemBarcode.class);
				barcodeSubquery.select(barcodeRoot.get("item").get("id"));
				barcodeSubquery.where(cb.equal(barcodeRoot.get("item").get("id"), root.get("id")),
						cb.or(cb.isTrue(barcodeRoot.get("active")), cb.isNull(barcodeRoot.get("active"))),
						cb.like(cb.lower(barcodeRoot.get("barcode")), likeValue));
				Predicate barcodeMatch = cb.exists(barcodeSubquery);

				// Match if code/name OR barcode matches
				predicate = cb.and(predicate, cb.or(codeNameMatch, barcodeMatch));
			}

			if (familyId != null) {
				predicate = cb.and(predicate, cb.equal(root.get("itemFamily").get("id"), familyId));
			}

			if (subFamilyId != null) {
				predicate = cb.and(predicate, cb.equal(root.get("itemSubFamily").get("id"), subFamilyId));
			}

			if (priceMin != null) {
				predicate = cb.and(predicate, cb.greaterThanOrEqualTo(root.get("unitPrice"), priceMin));
			}

			if (priceMax != null) {
				predicate = cb.and(predicate, cb.lessThanOrEqualTo(root.get("unitPrice"), priceMax));
			}

			return predicate;
		};

		return itemRepository.findAll(specification, pageable);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Item save(Item item) throws Exception {
		if (applicationModeService.isFranchiseAdmin()) {
			if (item.getFranchiseSalesPrice() == null || item.getFranchiseSalesPrice() <= 0) {
				throw new IllegalArgumentException(
						"Franchise sales price is required and must be greater than zero in franchise admin mode");
			}
		}

		if (item.getItemSubFamily() != null) {
			if (item.getItemSubFamily().getId() == null) {
				throw new IllegalArgumentException("Item sub-family ID is required");
			}
			ItemSubFamily persistedSubFamily = itemSubFamilyRepository.findById(item.getItemSubFamily().getId())
					.orElseThrow(() -> new IllegalArgumentException(
							"Item sub-family not found: " + item.getItemSubFamily().getId()));
			item.setItemSubFamily(persistedSubFamily);
			item.setItemFamily(persistedSubFamily.getItemFamily());
		} else if (item.getItemFamily() != null) {
			if (item.getItemFamily().getId() == null) {
				throw new IllegalArgumentException("Item family ID is required");
			}
			ItemFamily persistedFamily = itemFamilyRepository.findById(item.getItemFamily().getId()).orElseThrow(
					() -> new IllegalArgumentException("Item family not found: " + item.getItemFamily().getId()));
			item.setItemFamily(persistedFamily);
		}

		return super.save(item);
	}

	/**
	 * Lightweight search for purchase forms. Returns all active items matching the
	 * search term (code, name, or barcode). No POS-specific filters (showInPos,
	 * unitPrice > 0) applied.
	 */
	public Page<Item> searchItemsForPurchase(String search, Pageable pageable) {
		Specification<Item> spec = (root, query, cb) -> {
			query.distinct(true);
			Predicate predicate = cb.isTrue(root.get("active"));

			if (StringUtils.hasText(search)) {
				String likeValue = "%" + search.toLowerCase() + "%";
				Predicate codeNameMatch = cb.or(cb.like(cb.lower(root.get("itemCode")), likeValue),
						cb.like(cb.lower(root.get("name")), likeValue));

				Subquery<Long> barcodeSubquery = query.subquery(Long.class);
				var barcodeRoot = barcodeSubquery.from(com.digithink.zsretail.model.ItemBarcode.class);
				barcodeSubquery.select(barcodeRoot.get("item").get("id"));
				barcodeSubquery.where(cb.equal(barcodeRoot.get("item").get("id"), root.get("id")),
						cb.or(cb.isTrue(barcodeRoot.get("active")), cb.isNull(barcodeRoot.get("active"))),
						cb.like(cb.lower(barcodeRoot.get("barcode")), likeValue));

				predicate = cb.and(predicate, cb.or(codeNameMatch, cb.exists(barcodeSubquery)));
			}

			return predicate;
		};
		return itemRepository.findAll(spec, pageable);
	}

	public List<Item> findActiveByFamilyId(Long familyId) {
		ItemFamily family = itemFamilyRepository.findById(familyId)
				.orElseThrow(() -> new IllegalArgumentException("Item family not found: " + familyId));

		return itemRepository.findByItemFamily(family).stream()
				.filter(item -> item.getActive() == null || Boolean.TRUE.equals(item.getActive()))
				.filter(item -> item.getShowInPos() == null || Boolean.TRUE.equals(item.getShowInPos()))
				.filter(item -> item.getUnitPrice() != null && item.getUnitPrice() > 0).collect(Collectors.toList());
	}

	public List<Item> findActiveBySubFamilyId(Long subFamilyId) {
		ItemSubFamily subFamily = itemSubFamilyRepository.findById(subFamilyId)
				.orElseThrow(() -> new IllegalArgumentException("Item sub-family not found: " + subFamilyId));

		return itemRepository.findByItemSubFamily(subFamily).stream()
				.filter(item -> item.getActive() == null || Boolean.TRUE.equals(item.getActive()))
				.filter(item -> item.getShowInPos() == null || Boolean.TRUE.equals(item.getShowInPos()))
				.filter(item -> item.getUnitPrice() != null && item.getUnitPrice() > 0).collect(Collectors.toList());
	}

	/**
	 * For standalone mode only: ensure a default family and subfamily exist, then
	 * create an item. Caller is responsible for creating ItemBarcode. Returns the
	 * created item.
	 */
	@Transactional(rollbackFor = Exception.class)
	public Item createStandaloneQuickProduct(String name, String itemCode, Double unitPrice) throws Exception {
		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException("Product name is required");
		}
		if (unitPrice == null || unitPrice < 0) {
			throw new IllegalArgumentException("Unit price is required and must be >= 0");
		}
		ItemFamily family = itemFamilyRepository.findByCode("STANDALONE_DEFAULT").orElseGet(() -> {
			ItemFamily f = new ItemFamily();
			f.setCode("STANDALONE_DEFAULT");
			f.setName("Default");
			f.setDisplayOrder(0);
			f.setActive(true);
			return itemFamilyRepository.save(f);
		});
		ItemSubFamily subFamily = itemSubFamilyRepository.findByCode("STANDALONE_DEFAULT").orElseGet(() -> {
			ItemSubFamily sf = new ItemSubFamily();
			sf.setCode("STANDALONE_DEFAULT");
			sf.setName("Default");
			sf.setItemFamily(family);
			sf.setDisplayOrder(0);
			sf.setActive(true);
			return itemSubFamilyRepository.save(sf);
		});
		String code = StringUtils.hasText(itemCode) ? itemCode.trim() : generateStandaloneItemCode();
		if (itemRepository.findByItemCode(code).isPresent()) {
			throw new IllegalArgumentException("Item code already exists: " + code);
		}
		Item item = new Item();
		item.setItemCode(code);
		item.setName(name.trim());
		item.setUnitPrice(unitPrice);
		item.setItemFamily(family);
		item.setItemSubFamily(subFamily);
		item.setType(ItemType.PRODUCT);
		item.setShowInPos(true);
		item.setActive(true);
		return super.save(item);
	}

	private String generateStandaloneItemCode() {
		String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
		long count = itemRepository.count();
		return "ITEM-" + dateStr + "-" + String.format("%03d", (count % 1000) + 1);
	}
}
