package com.digithink.zsretail.holink.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeCatalogue;
import com.digithink.zsretail.headoffice.dto.CatalogueBarcodeCopyDTO;
import com.digithink.zsretail.headoffice.dto.CatalogueFamilyCopyDTO;
import com.digithink.zsretail.headoffice.dto.CatalogueItemCopyDTO;
import com.digithink.zsretail.headoffice.dto.CatalogueSubFamilyCopyDTO;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.ItemComposition;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository.ItemCompositionRepository;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;

import lombok.Getter;

/**
 * Head office plan, task 6.2: writes the catalogue the head office sends, by code, on a store whose catalogue is the
 * head office's. Each method runs inside the caller's transaction (one record at a time).
 * <ul>
 * <li>A new code is saved with origin HEAD_OFFICE.</li>
 * <li>A local record with the same code becomes the head office record: the head office values replace the store's,
 * price included; the stock, the costs, the image and the ERP and franchise fields are kept.</li>
 * <li>An item marked as own price keeps its unitPrice; the head office price is saved beside it.</li>
 * <li>A barcode used by another item of the store moves to the head office item, and an old item.barcode field of
 * another item holding the same value is cleared, so a scan finds only the head office item (one exchange log row per
 * barcode, see {@link Outcome#getNotes()}).</li>
 * <li>Removed or inactive at the head office: inactive here, never deleted.</li>
 * <li>A family, sub-family, item or component missing here: WAITING, retried at every cycle.</li>
 * </ul>
 * The store's other local records are not touched. See docs/modules/head-office.md, "Catalogue owned by the head
 * office".
 */
@Component
@ConditionalOnHeadOfficeCatalogue
public class CatalogueCopyWriter {

	private final ItemFamilyRepository families;
	private final ItemSubFamilyRepository subFamilies;
	private final ItemRepository items;
	private final ItemBarcodeRepository barcodes;
	private final ItemCompositionRepository compositions;

	public CatalogueCopyWriter(ItemFamilyRepository families, ItemSubFamilyRepository subFamilies, ItemRepository items,
			ItemBarcodeRepository barcodes, ItemCompositionRepository compositions) {
		this.families = families;
		this.subFamilies = subFamilies;
		this.items = items;
		this.barcodes = barcodes;
		this.compositions = compositions;
	}

	// ─── Families and sub-families ───────────────────────────────

	public Outcome saveFamily(CatalogueFamilyCopyDTO copy) {
		Optional<ItemFamily> found = families.findByCode(copy.getCode());
		if (found.isPresent() && isHeadOffice(found.get().getOrigin())
				&& CatalogueFamilyCopyDTO.of(found.get()).equals(copy)) {
			return Outcome.unchanged();
		}
		ItemFamily family = found.orElseGet(ItemFamily::new);
		family.setCode(copy.getCode());
		family.setName(copy.getName());
		family.setDescription(copy.getDescription());
		family.setDisplayOrder(copy.getDisplayOrder());
		family.setActive(!Boolean.FALSE.equals(copy.getActive()));
		if (family.getId() == null) {
			family.setOrigin(RecordOrigin.HEAD_OFFICE);
			family.setCreatedBy(RecordOrigin.HEAD_OFFICE.name());
		}
		family.setUpdatedBy(RecordOrigin.HEAD_OFFICE.name());
		ItemFamily saved = families.save(family);
		if (!isHeadOffice(saved.getOrigin())) {
			families.setOrigin(saved.getId(), RecordOrigin.HEAD_OFFICE);
		}
		return Outcome.written(null);
	}

	public Outcome saveSubFamily(CatalogueSubFamilyCopyDTO copy) {
		Optional<ItemFamily> family = copy.getFamilyCode() == null ? Optional.empty()
				: families.findByCode(copy.getFamilyCode());
		if (!family.isPresent()) {
			return Outcome.waiting("not in this store: family " + copy.getFamilyCode());
		}
		Optional<ItemSubFamily> found = subFamilies.findByCode(copy.getCode());
		if (found.isPresent() && isHeadOffice(found.get().getOrigin())
				&& CatalogueSubFamilyCopyDTO.of(found.get()).equals(copy)) {
			return Outcome.unchanged();
		}
		ItemSubFamily subFamily = found.orElseGet(ItemSubFamily::new);
		subFamily.setCode(copy.getCode());
		subFamily.setName(copy.getName());
		subFamily.setDescription(copy.getDescription());
		subFamily.setDisplayOrder(copy.getDisplayOrder());
		subFamily.setItemFamily(family.get());
		subFamily.setActive(!Boolean.FALSE.equals(copy.getActive()));
		if (subFamily.getId() == null) {
			subFamily.setOrigin(RecordOrigin.HEAD_OFFICE);
			subFamily.setCreatedBy(RecordOrigin.HEAD_OFFICE.name());
		}
		subFamily.setUpdatedBy(RecordOrigin.HEAD_OFFICE.name());
		ItemSubFamily saved = subFamilies.save(subFamily);
		if (!isHeadOffice(saved.getOrigin())) {
			subFamilies.setOrigin(saved.getId(), RecordOrigin.HEAD_OFFICE);
		}
		return Outcome.written(null);
	}

	// ─── Items ───────────────────────────────────────────────────

	public Outcome saveItem(CatalogueItemCopyDTO copy) {
		List<String> missing = new ArrayList<>();
		ItemFamily family = null;
		if (copy.getFamilyCode() != null) {
			family = families.findByCode(copy.getFamilyCode()).orElse(null);
			if (family == null) {
				missing.add("family " + copy.getFamilyCode());
			}
		}
		ItemSubFamily subFamily = null;
		if (copy.getSubFamilyCode() != null) {
			subFamily = subFamilies.findByCode(copy.getSubFamilyCode()).orElse(null);
			if (subFamily == null) {
				missing.add("sub-family " + copy.getSubFamilyCode());
			}
		}
		Map<String, Item> components = new LinkedHashMap<>();
		List<String> missingComponents = new ArrayList<>();
		for (CatalogueItemCopyDTO.Component component : copy.getComponents()) {
			Optional<Item> found = items.findByItemCode(component.getItemCode());
			if (found.isPresent()) {
				components.put(component.getItemCode(), found.get());
			} else {
				missingComponents.add(component.getItemCode());
			}
		}
		if (!missingComponents.isEmpty()) {
			missing.add("component items " + String.join(", ", missingComponents));
		}
		if (!missing.isEmpty()) {
			return Outcome.waiting("not in this store: " + String.join(", ", missing));
		}

		Optional<Item> found = items.findByItemCode(copy.getItemCode());
		if (found.isPresent() && isUnchanged(found.get(), copy)) {
			return Outcome.unchanged();
		}
		Item item = found.orElseGet(Item::new);
		boolean takenOver = item.getId() != null && !isHeadOffice(item.getOrigin());
		item.setItemCode(copy.getItemCode());
		item.setName(copy.getName());
		item.setDescription(copy.getDescription());
		item.setType(copy.getType());
		item.setDefaultVAT(copy.getDefaultVAT());
		item.setUnitOfMeasure(copy.getUnitOfMeasure());
		item.setCategory(copy.getCategory());
		item.setBrand(copy.getBrand());
		item.setItemDiscGroup(copy.getItemDiscGroup());
		item.setMaximumAuthorizedDiscount(copy.getMaximumAuthorizedDiscount());
		item.setShowInPos(!Boolean.FALSE.equals(copy.getShowInPos()));
		item.setBarcode(copy.getBarcode());
		item.setItemFamily(family);
		item.setItemSubFamily(subFamily);
		item.setActive(!Boolean.FALSE.equals(copy.getActive()));
		item.setHeadOfficePrice(copy.getUnitPrice());
		if (takenOver || !Boolean.TRUE.equals(item.getOwnPrice())) {
			item.setOwnPrice(null);
			item.setUnitPrice(copy.getUnitPrice()); // an own price is kept, the head office price saved beside
		}
		if (item.getId() == null) {
			item.setOrigin(RecordOrigin.HEAD_OFFICE);
			item.setCreatedBy(RecordOrigin.HEAD_OFFICE.name());
		}
		item.setUpdatedBy(RecordOrigin.HEAD_OFFICE.name());
		Item saved = items.save(item);
		if (!isHeadOffice(saved.getOrigin())) {
			items.setOrigin(saved.getId(), RecordOrigin.HEAD_OFFICE);
		}
		savePack(saved, copy, components);
		List<String> notes = new ArrayList<>();
		String legacy = clearLegacyBarcode(copy.getBarcode(), saved);
		if (legacy != null) {
			notes.add("barcode " + copy.getBarcode() + " removed from the old barcode field of the store's item " + legacy
					+ ": it belongs to the head office item " + saved.getItemCode());
		}
		return Outcome.written(takenOver ? "a local item of this store became the head office item" : null, notes);
	}

	/** Same copy as the store's head office item (price compared with the head office price kept beside). */
	private boolean isUnchanged(Item item, CatalogueItemCopyDTO copy) {
		if (!isHeadOffice(item.getOrigin())) {
			return false;
		}
		boolean ownPrice = Boolean.TRUE.equals(item.getOwnPrice());
		if (!ownPrice && !Objects.equals(item.getUnitPrice(), item.getHeadOfficePrice())) {
			return false;
		}
		CatalogueItemCopyDTO current = CatalogueItemCopyDTO.of(item, item.getHeadOfficePrice(),
				compositions.findByParentItemId(item.getId()));
		return current.equals(copy) && (copy.getBarcode() == null || !legacyBarcodeElsewhere(copy.getBarcode(), item));
	}

	/** The pack's components as sent: missing ones added, others deleted, quantities as sent. */
	private void savePack(Item item, CatalogueItemCopyDTO copy, Map<String, Item> components) {
		Map<String, Integer> wanted = new HashMap<>();
		for (CatalogueItemCopyDTO.Component component : copy.getComponents()) {
			wanted.put(component.getItemCode(), component.getQuantity());
		}
		for (ItemComposition existing : compositions.findByParentItemId(item.getId())) {
			String code = existing.getComponentItem().getItemCode();
			Integer quantity = wanted.remove(code);
			if (quantity == null) {
				compositions.delete(existing);
			} else if (!quantity.equals(existing.getQuantity()) || Boolean.FALSE.equals(existing.getActive())) {
				existing.setQuantity(quantity);
				existing.setActive(true);
				compositions.save(existing);
			}
		}
		for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
			ItemComposition composition = new ItemComposition();
			composition.setParentItem(item);
			composition.setComponentItem(components.get(entry.getKey()));
			composition.setQuantity(entry.getValue());
			composition.setCreatedBy(RecordOrigin.HEAD_OFFICE.name());
			compositions.save(composition);
		}
	}

	// ─── Barcodes ────────────────────────────────────────────────

	public Outcome saveBarcode(CatalogueBarcodeCopyDTO copy) {
		Optional<Item> item = copy.getItemCode() == null ? Optional.empty() : items.findByItemCode(copy.getItemCode());
		if (!item.isPresent()) {
			return Outcome.waiting("not in this store: item " + copy.getItemCode());
		}
		Item target = item.get();
		boolean active = !Boolean.FALSE.equals(copy.getActive());
		Optional<ItemBarcode> found = barcodes.findByBarcode(copy.getBarcode());
		boolean legacyElsewhere = active && legacyBarcodeElsewhere(copy.getBarcode(), target);
		if (found.isPresent() && !legacyElsewhere && isHeadOffice(found.get().getOrigin())
				&& Objects.equals(found.get().getItem().getId(), target.getId())
				&& Objects.equals(found.get().getDescription(), copy.getDescription())
				&& Boolean.TRUE.equals(found.get().getIsPrimary()) == Boolean.TRUE.equals(copy.getIsPrimary())
				&& !Boolean.FALSE.equals(found.get().getActive()) == active) {
			return Outcome.unchanged();
		}
		ItemBarcode barcode = found.orElseGet(ItemBarcode::new);
		Item previousItem = barcode.getItem();
		boolean moved = previousItem != null && !Objects.equals(previousItem.getId(), target.getId());
		barcode.setBarcode(copy.getBarcode());
		barcode.setItem(target);
		barcode.setDescription(copy.getDescription());
		barcode.setIsPrimary(Boolean.TRUE.equals(copy.getIsPrimary()));
		barcode.setActive(active);
		if (barcode.getId() == null) {
			barcode.setOrigin(RecordOrigin.HEAD_OFFICE);
			barcode.setCreatedBy(RecordOrigin.HEAD_OFFICE.name());
		}
		barcode.setUpdatedBy(RecordOrigin.HEAD_OFFICE.name());
		ItemBarcode saved = barcodes.save(barcode);
		if (!isHeadOffice(saved.getOrigin())) {
			barcodes.setOrigin(saved.getId(), RecordOrigin.HEAD_OFFICE);
		}
		String legacy = active ? clearLegacyBarcode(copy.getBarcode(), target) : null;
		List<String> notes = new ArrayList<>();
		if (moved || legacy != null) {
			StringBuilder note = new StringBuilder("barcode ").append(copy.getBarcode());
			if (moved) {
				note.append(" moved from the store's item ").append(previousItem.getItemCode());
			}
			if (legacy != null) {
				note.append(moved ? " and" : "").append(" removed from the old barcode field of the store's item ")
						.append(legacy);
			}
			note.append(" to the head office item ").append(target.getItemCode());
			notes.add(note.toString());
		}
		return Outcome.written(moved ? "moved from the store's item " + previousItem.getItemCode() : null, notes);
	}

	/** True when another item holds this value in its old item.barcode field. */
	private boolean legacyBarcodeElsewhere(String value, Item owner) {
		return items.findAllByBarcode(value).stream().anyMatch(other -> !Objects.equals(other.getId(), owner.getId()));
	}

	/**
	 * Clears the old item.barcode field of the other items that hold this value (a scan at the till looks there after the
	 * barcode table). Returns those items' codes, null when none.
	 */
	private String clearLegacyBarcode(String value, Item owner) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		List<String> cleared = new ArrayList<>();
		for (Item other : items.findAllByBarcode(value)) {
			if (!Objects.equals(other.getId(), owner.getId())) {
				other.setBarcode(null);
				items.save(other);
				cleared.add(other.getItemCode());
			}
		}
		return cleared.isEmpty() ? null : String.join(", ", cleared);
	}

	// ─── Removals and own prices ─────────────────────────────────

	/**
	 * A code removed for this store (deleted at the head office): the head office record becomes inactive, never deleted.
	 * A local record with that code is not touched. True when something changed.
	 */
	public boolean remove(CatalogueKind kind, String code) {
		switch (kind) {
			case FAMILY:
				return families.findByCode(code).filter(f -> isHeadOffice(f.getOrigin()) && isActive(f.getActive()))
						.map(f -> {
							f.setActive(false);
							families.save(f);
							return true;
						}).orElse(false);
			case SUBFAMILY:
				return subFamilies.findByCode(code).filter(s -> isHeadOffice(s.getOrigin()) && isActive(s.getActive()))
						.map(s -> {
							s.setActive(false);
							subFamilies.save(s);
							return true;
						}).orElse(false);
			case ITEM:
				return items.findByItemCode(code).filter(i -> isHeadOffice(i.getOrigin()) && isActive(i.getActive()))
						.map(i -> {
							i.setActive(false);
							items.save(i);
							return true;
						}).orElse(false);
			case BARCODE:
				return barcodes.findByBarcode(code).filter(b -> isHeadOffice(b.getOrigin()) && isActive(b.getActive()))
						.map(b -> {
							b.setActive(false);
							barcodes.save(b);
							return true;
						}).orElse(false);
			default:
				return false;
		}
	}

	/**
	 * The right "may change its selling prices" is off: every own price gives way to the head office price kept beside it.
	 * Returns the item codes changed.
	 */
	public List<String> giveBackOwnPrices() {
		List<String> changed = new ArrayList<>();
		for (Item item : items.findByOwnPriceTrue()) {
			giveBack(item);
			changed.add(item.getItemCode());
		}
		return changed;
	}

	/** The head office price back on one item (unitPrice = headOfficePrice, own price off). */
	public Item giveBack(Item item) {
		if (item.getHeadOfficePrice() != null) {
			item.setUnitPrice(item.getHeadOfficePrice());
		}
		item.setOwnPrice(null);
		return items.save(item);
	}

	private static boolean isHeadOffice(RecordOrigin origin) {
		return origin == RecordOrigin.HEAD_OFFICE;
	}

	private static boolean isActive(Boolean active) {
		return !Boolean.FALSE.equals(active);
	}

	/** What one record did: applied (written or unchanged), or waiting with the reason; information and log notes. */
	@Getter
	public static final class Outcome {

		private final boolean applied;
		private final boolean written;
		private final String reason;
		private final String info;

		/** One exchange log row each (WARNING), written after the record's transaction. */
		private final List<String> notes;

		private Outcome(boolean applied, boolean written, String reason, String info, List<String> notes) {
			this.applied = applied;
			this.written = written;
			this.reason = reason;
			this.info = info;
			this.notes = notes;
		}

		static Outcome unchanged() {
			return new Outcome(true, false, null, null, new ArrayList<>());
		}

		static Outcome written(String info) {
			return written(info, new ArrayList<>());
		}

		static Outcome written(String info, List<String> notes) {
			return new Outcome(true, true, null, info, notes);
		}

		static Outcome waiting(String reason) {
			return new Outcome(false, false, reason, null, new ArrayList<>());
		}
	}
}
