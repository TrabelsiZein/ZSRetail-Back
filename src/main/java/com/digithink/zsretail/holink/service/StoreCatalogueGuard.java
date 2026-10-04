package com.digithink.zsretail.holink.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeCatalogue;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.repository.SalesPriceRepository;

/**
 * Head office plan, tasks 6.3, 6.5, 6.6: the rules of a store whose catalogue is the head office's. The APIs ask it
 * first; without this bean (every other store, a franchise customer) they run exactly as before. Each check returns the
 * text of a 409 answer, or null when the request goes on.
 * <ul>
 * <li>A head office record (origin HEAD_OFFICE) is consult-only: edit and delete answer 409. Exceptions: the stock
 * adjustment (always), and the own price of an item with the right "may change its selling prices".</li>
 * <li>Right "can purchase from its own suppliers" off: purchases, purchase invoices and vendors answer 409, creating an
 * item, family, sub-family or barcode is refused; the store's own items stay sellable and editable. On: everything is
 * open, but a purchase line with a head office item is refused.</li>
 * <li>Selling prices come from the head office: sales_price writes and imports answer 409.</li>
 * </ul>
 * See docs/modules/head-office.md, "Catalogue owned by the head office".
 */
@Service
@ConditionalOnHeadOfficeCatalogue
public class StoreCatalogueGuard {

	public static final String ITEM_MANAGED = "This item is managed by the head office: it is consult-only on this store.";
	public static final String FAMILY_MANAGED = "This family is managed by the head office: it is consult-only on this store.";
	public static final String SUB_FAMILY_MANAGED = "This sub-family is managed by the head office: it is consult-only on this store.";
	public static final String BARCODE_MANAGED = "This barcode is managed by the head office: it is consult-only on this store.";
	public static final String CREATE_REFUSED = "This store cannot create items, families, sub-families or barcodes: its"
			+ " items come from the head office (purchase right off on the head office Stores page).";
	public static final String PURCHASES_CLOSED = "Purchases are closed on this store: its goods come from the head office"
			+ " (purchase right off on the head office Stores page).";
	public static final String PRICE_RIGHT_OFF = "This store may not change the price of head office items: the head"
			+ " office gives the right on its Stores page.";
	public static final String SALES_PRICES_FROM_HEAD_OFFICE = "Selling prices come from the head office on this store:"
			+ " sales prices cannot be written here.";
	public static final String IMPORT_REFUSED = "Items, families, sub-families, barcodes and sales prices come from the head"
			+ " office on this store: they cannot be imported here.";

	/** Data import types refused whatever the rights. */
	static final List<String> IMPORT_TYPES_FROM_HEAD_OFFICE = Arrays.asList("FAMILIES", "SUBFAMILIES", "ITEMS",
			"BARCODES", "SALES_PRICES");

	private final CatalogueRights rights;
	private final ItemRepository items;
	private final ItemFamilyRepository families;
	private final ItemSubFamilyRepository subFamilies;
	private final ItemBarcodeRepository barcodes;
	private final SalesPriceRepository salesPrices;
	private final CatalogueCopyWriter writer;
	private final HeadOfficeLinkStatus linkStatus;

	public StoreCatalogueGuard(CatalogueRights rights, ItemRepository items, ItemFamilyRepository families,
			ItemSubFamilyRepository subFamilies, ItemBarcodeRepository barcodes, SalesPriceRepository salesPrices,
			CatalogueCopyWriter writer, HeadOfficeLinkStatus linkStatus) {
		this.rights = rights;
		this.items = items;
		this.families = families;
		this.subFamilies = subFamilies;
		this.barcodes = barcodes;
		this.salesPrices = salesPrices;
		this.writer = writer;
		this.linkStatus = linkStatus;
	}

	// ─── Head office records: consult-only ───────────────────────

	/** Edit or delete of an item, its pack flag or its pack: 409 for a head office item. */
	public String itemWrite(Long itemId) {
		return itemId != null && items.findById(itemId).map(this::fromHeadOffice).orElse(false) ? ITEM_MANAGED : null;
	}

	public String familyWrite(Long familyId) {
		return familyId != null && families.findById(familyId).map(f -> f.getOrigin() == RecordOrigin.HEAD_OFFICE)
				.orElse(false) ? FAMILY_MANAGED : null;
	}

	public String subFamilyWrite(Long subFamilyId) {
		return subFamilyId != null && subFamilies.findById(subFamilyId)
				.map(s -> s.getOrigin() == RecordOrigin.HEAD_OFFICE).orElse(false) ? SUB_FAMILY_MANAGED : null;
	}

	public String barcodeWrite(Long barcodeId) {
		return barcodeId != null && barcodes.findById(barcodeId).map(b -> b.getOrigin() == RecordOrigin.HEAD_OFFICE)
				.orElse(false) ? BARCODE_MANAGED : null;
	}

	// ─── Creating (purchase right) ───────────────────────────────

	/** Creating an item, a family or a sub-family: 409 without the purchase right. */
	public String create() {
		return rights.canPurchase() ? null : CREATE_REFUSED;
	}

	/** A code already used by a head office record of that kind: the clear 409 instead of the database error. */
	public String itemCodeTaken(String itemCode) {
		return itemCode != null && items.findByItemCode(itemCode.trim()).map(this::fromHeadOffice).orElse(false)
				? "The code " + itemCode.trim() + " is used by a head office item." : null;
	}

	public String familyCodeTaken(String code) {
		return code != null && families.findByCode(code.trim()).map(f -> f.getOrigin() == RecordOrigin.HEAD_OFFICE)
				.orElse(false) ? "The code " + code.trim() + " is used by a head office family." : null;
	}

	public String subFamilyCodeTaken(String code) {
		return code != null && subFamilies.findByCode(code.trim()).map(s -> s.getOrigin() == RecordOrigin.HEAD_OFFICE)
				.orElse(false) ? "The code " + code.trim() + " is used by a head office sub-family." : null;
	}

	/** Creating a barcode: 409 on a head office item; without the purchase right; or for a head office barcode. */
	public String barcodeCreate(ItemBarcode barcode) {
		if (barcode != null && barcode.getItem() != null && itemWrite(barcode.getItem().getId()) != null) {
			return ITEM_MANAGED;
		}
		if (!rights.canPurchase()) {
			return CREATE_REFUSED;
		}
		if (barcode != null && barcode.getBarcode() != null && barcodes.findByBarcode(barcode.getBarcode().trim())
				.map(b -> b.getOrigin() == RecordOrigin.HEAD_OFFICE).orElse(false)) {
			return "The barcode " + barcode.getBarcode().trim() + " is used by a head office item.";
		}
		return null;
	}

	// ─── Purchases ───────────────────────────────────────────────

	/** Purchases, purchase invoices and vendors (writes): 409 without the purchase right. */
	public String purchase() {
		return rights.canPurchase() ? null : PURCHASES_CLOSED;
	}

	/** A purchase with these items: 409 without the right, or naming the head office items among them. */
	public String purchaseLines(Collection<Long> itemIds) {
		String closed = purchase();
		if (closed != null) {
			return closed;
		}
		List<String> fromHeadOffice = new ArrayList<>();
		for (Long id : itemIds) {
			if (id != null) {
				items.findById(id).filter(this::fromHeadOffice).ifPresent(item -> fromHeadOffice.add(item.getItemCode()));
			}
		}
		return fromHeadOffice.isEmpty() ? null
				: "Head office items come only from the head office and cannot be purchased here: "
						+ String.join(", ", fromHeadOffice) + ".";
	}

	/** Data import: catalogue types and sales prices always refused; vendors follow the purchase right. */
	public String dataImport(String entityType) {
		String type = entityType == null ? "" : entityType.trim().toUpperCase(Locale.ROOT);
		if (IMPORT_TYPES_FROM_HEAD_OFFICE.contains(type)) {
			return IMPORT_REFUSED;
		}
		return "VENDORS".equals(type) ? purchase() : null;
	}

	/** Writes to sales_price: selling prices come from the head office. */
	public String salesPriceWrite() {
		return SALES_PRICES_FROM_HEAD_OFFICE;
	}

	// ─── Own price (task 6.5) ────────────────────────────────────

	/**
	 * The store's own selling price on a head office item, kept across the pulls. NoSuchElement for an unknown item;
	 * IllegalArgument for a local item (edited with the item form) or a price missing or below zero; IllegalState
	 * (409) without the right.
	 */
	@Transactional
	public Item setOwnPrice(Long itemId, Double price) {
		Item item = items.findById(itemId).orElseThrow(() -> new NoSuchElementException("Item not found: " + itemId));
		if (!fromHeadOffice(item)) {
			throw new IllegalArgumentException("This item is the store's own item: change its price with the item form.");
		}
		if (price == null || price.isNaN() || price.isInfinite() || price < 0) {
			throw new IllegalArgumentException("unitPrice is required and must be 0 or more.");
		}
		if (!rights.mayChangePrices()) {
			throw new IllegalStateException(PRICE_RIGHT_OFF);
		}
		if (item.getHeadOfficePrice() == null) {
			item.setHeadOfficePrice(item.getUnitPrice());
		}
		item.setUnitPrice(price);
		item.setOwnPrice(true);
		return items.save(item);
	}

	/** The head office price back on a head office item; allowed whatever the right. */
	@Transactional
	public Item giveBackPrice(Long itemId) {
		Item item = items.findById(itemId).orElseThrow(() -> new NoSuchElementException("Item not found: " + itemId));
		if (!fromHeadOffice(item)) {
			throw new IllegalArgumentException("This item is the store's own item: it has no head office price.");
		}
		return writer.giveBack(item);
	}

	// ─── Status ──────────────────────────────────────────────────

	/**
	 * GET /catalogue/network and the catalogue block of GET /admin/holink/status: {fromHeadOffice, linkState,
	 * mayChangePrices, canPurchase, ownPriceCount, salesPriceRowsOnHeadOfficeItems}. The rights are the saved values, null
	 * when never received (the rules read them as off).
	 */
	public Map<String, Object> status() {
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("fromHeadOffice", true);
		HeadOfficeLinkStatus.Snapshot snapshot = linkStatus.get();
		status.put("linkState", snapshot.getState());
		status.put("mayChangePrices", rights.saved(CatalogueRights.MAY_CHANGE_PRICES));
		status.put("canPurchase", rights.saved(CatalogueRights.CAN_PURCHASE));
		status.put("ownPriceCount", items.countByOwnPriceTrue());
		status.put("salesPriceRowsOnHeadOfficeItems", salesPrices.countOnItemsOfOrigin(RecordOrigin.HEAD_OFFICE));
		return status;
	}

	private boolean fromHeadOffice(Item item) {
		return item.getOrigin() == RecordOrigin.HEAD_OFFICE;
	}
}
