package com.digithink.zsretail.service.franchise;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.dto.franchise.FranchiseItemDTO;
import com.digithink.zsretail.dto.franchise.FranchiseItemDTO.BarcodeSyncDTO;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.service.GeneralSetupService;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Franchise client: syncs items from the franchise admin.
 * Only active when franchise.customer=true.
 */
@Service
@RequiredArgsConstructor
@Log4j2
@ConditionalOnProperty(name = "franchise.customer", havingValue = "true")
public class FranchiseSyncService {

	private static final String LAST_SYNC_KEY = "FRANCHISE_LAST_ITEM_SYNC";
	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
	private static final String API_KEY_HEADER = "X-Franchise-Api-Key";

	private final ItemRepository itemRepository;
	private final ItemBarcodeRepository itemBarcodeRepository;
	private final ItemFamilyRepository itemFamilyRepository;
	private final ItemSubFamilyRepository itemSubFamilyRepository;
	private final GeneralSetupService generalSetupService;
	private final RestTemplate restTemplate;

	@Value("${franchise.remote.url}")
	private String remoteUrl;

	@Value("${franchise.api-key}")
	private String apiKey;

	/**
	 * Syncs items from the franchise admin using the last sync date as an incremental filter.
	 * Returns a summary: how many items were created/updated, and any error message.
	 */
	@Transactional(rollbackFor = Exception.class)
	public FranchiseSyncResult syncItems() {
		String lastSyncValue = generalSetupService.findValueByCode(LAST_SYNC_KEY);
		String modifiedAfterParam = (lastSyncValue != null && !lastSyncValue.isBlank()) ? lastSyncValue : null;

		String url = remoteUrl + "/franchise/items";
		if (modifiedAfterParam != null) {
			url += "?modifiedAfter=" + modifiedAfterParam;
		}

		log.info("Franchise item sync started. modifiedAfter={}", modifiedAfterParam);

		FranchiseItemDTO[] items;
		try {
			HttpHeaders headers = buildHeaders();
			ResponseEntity<FranchiseItemDTO[]> response =
					restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), FranchiseItemDTO[].class);
			items = response.getBody();
		} catch (Exception e) {
			log.error("Franchise item sync failed: unable to reach admin", e);
			return FranchiseSyncResult.error("Unable to reach franchise admin: " + e.getMessage());
		}

		if (items == null || items.length == 0) {
			log.info("Franchise item sync: no items to sync");
			updateLastSyncDate();
			return FranchiseSyncResult.success(0, 0);
		}

		int created = 0;
		int updated = 0;

		for (FranchiseItemDTO dto : items) {
			if (dto.getItemCode() == null) continue;

			Item existing = itemRepository.findByItemCode(dto.getItemCode()).orElse(null);
			if (existing != null) {
				applyDtoToItem(existing, dto);
				Item saved = itemRepository.save(existing);
				applyBarcodesAfterSave(saved, dto);
				updated++;
			} else {
				Item newItem = new Item();
				newItem.setItemCode(dto.getItemCode());
				newItem.setCreatedBy("FranchiseSync");
				newItem.setUpdatedBy("FranchiseSync");
				applyDtoToItem(newItem, dto);
				Item saved = itemRepository.save(newItem);
				applyBarcodesAfterSave(saved, dto);
				created++;
			}
		}

		updateLastSyncDate();
		log.info("Franchise item sync complete: created={}, updated={}", created, updated);
		return FranchiseSyncResult.success(created, updated);
	}

	private void applyDtoToItem(Item item, FranchiseItemDTO dto) {
		item.setFromFranchiseAdmin(true);
		item.setName(dto.getName());
		item.setDescription(dto.getDescription());
		// franchiseSalesPrice becomes the client's unit price
		if (dto.getFranchiseSalesPrice() != null) {
			item.setUnitPrice(dto.getFranchiseSalesPrice());
		}
		item.setFranchiseSalesPrice(dto.getFranchiseSalesPrice());
		item.setDefaultVAT(dto.getDefaultVAT());
		item.setBarcode(dto.getBarcode());
		item.setImageUrl(dto.getImageUrl());
		item.setUnitOfMeasure(dto.getUnitOfMeasure());
		item.setCategory(dto.getCategory());
		item.setBrand(dto.getBrand());
		item.setShowInPos(dto.getShowInPos() != null ? dto.getShowInPos() : true);
		item.setActive(dto.getActive() != null ? dto.getActive() : true);
		item.setUpdatedBy("FranchiseSync");

		if (dto.getItemFamilyCode() != null) {
			ItemFamily family = itemFamilyRepository.findByCode(dto.getItemFamilyCode()).orElseGet(() -> {
				ItemFamily f = new ItemFamily();
				f.setCode(dto.getItemFamilyCode());
				f.setName(dto.getItemFamilyName() != null ? dto.getItemFamilyName() : dto.getItemFamilyCode());
				f.setDisplayOrder(0);
				f.setActive(true);
				f.setCreatedBy("FranchiseSync");
				f.setUpdatedBy("FranchiseSync");
				return itemFamilyRepository.save(f);
			});
			item.setItemFamily(family);

			if (dto.getItemSubFamilyCode() != null) {
				final ItemFamily finalFamily = family;
				ItemSubFamily subFamily = itemSubFamilyRepository.findByCode(dto.getItemSubFamilyCode()).orElseGet(() -> {
					ItemSubFamily sf = new ItemSubFamily();
					sf.setCode(dto.getItemSubFamilyCode());
					sf.setName(dto.getItemSubFamilyName() != null ? dto.getItemSubFamilyName() : dto.getItemSubFamilyCode());
					sf.setItemFamily(finalFamily);
					sf.setDisplayOrder(0);
					sf.setActive(true);
					sf.setCreatedBy("FranchiseSync");
					sf.setUpdatedBy("FranchiseSync");
					return itemSubFamilyRepository.save(sf);
				});
				item.setItemSubFamily(subFamily);
			}
		}
	}

	private void applyBarcodesAfterSave(Item savedItem, FranchiseItemDTO dto) {
		syncBarcodes(savedItem, dto.getBarcodes());
	}

	/**
	 * Syncs barcodes from the DTO to the local item.
	 * Upserts each barcode received from the admin.
	 * Barcodes present locally but absent from the admin list are deactivated (not deleted).
	 */
	private void syncBarcodes(Item item, java.util.List<BarcodeSyncDTO> dtoBarcodes) {
		if (dtoBarcodes == null || dtoBarcodes.isEmpty()) return;

		java.util.Set<String> syncedValues = new java.util.HashSet<>();

		for (BarcodeSyncDTO barcodeDto : dtoBarcodes) {
			if (barcodeDto.getBarcode() == null || barcodeDto.getBarcode().isBlank()) continue;

			String barcodeValue = barcodeDto.getBarcode();
			syncedValues.add(barcodeValue);

			ItemBarcode barcode = itemBarcodeRepository.findByBarcode(barcodeValue).orElse(null);

			if (barcode != null && !barcode.getItem().getId().equals(item.getId())) {
				log.warn("Barcode '{}' belongs to a different item on this client — skipping", barcodeValue);
				continue;
			}

			if (barcode == null) {
				barcode = new ItemBarcode();
				barcode.setBarcode(barcodeValue);
				barcode.setItem(item);
				barcode.setCreatedBy("FranchiseSync");
			}

			barcode.setIsPrimary(Boolean.TRUE.equals(barcodeDto.getIsPrimary()));
			barcode.setDescription(barcodeDto.getDescription());
			barcode.setActive(true);
			barcode.setUpdatedBy("FranchiseSync");
			itemBarcodeRepository.save(barcode);
		}

		// Deactivate local barcodes that are no longer sent by the admin
		itemBarcodeRepository.findByItemId(item.getId()).stream()
				.filter(b -> !syncedValues.contains(b.getBarcode()))
				.filter(b -> Boolean.TRUE.equals(b.getActive()))
				.forEach(b -> {
					b.setActive(false);
					b.setUpdatedBy("FranchiseSync");
					itemBarcodeRepository.save(b);
				});
	}

	private void updateLastSyncDate() {
		generalSetupService.updateValue(LAST_SYNC_KEY, LocalDateTime.now().format(ISO_FORMATTER));
	}

	private HttpHeaders buildHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(API_KEY_HEADER, apiKey);
		return headers;
	}

	public static class FranchiseSyncResult {
		public final boolean success;
		public final int created;
		public final int updated;
		public final String errorMessage;

		private FranchiseSyncResult(boolean success, int created, int updated, String errorMessage) {
			this.success = success;
			this.created = created;
			this.updated = updated;
			this.errorMessage = errorMessage;
		}

		public static FranchiseSyncResult success(int created, int updated) {
			return new FranchiseSyncResult(true, created, updated, null);
		}

		public static FranchiseSyncResult error(String message) {
			return new FranchiseSyncResult(false, 0, 0, message);
		}
	}
}
