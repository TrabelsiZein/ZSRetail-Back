package com.digithink.zsretail.controller.franchise;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.dto.franchise.FranchiseItemDTO;
import com.digithink.zsretail.dto.franchise.FranchiseItemDTO.BarcodeSyncDTO;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository.ItemRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Exposes item data to franchise clients for incremental sync.
 * Only active when franchise.admin=true. Secured by X-Franchise-Api-Key header (FranchiseApiKeyFilter).
 */
@RestController
@RequestMapping("franchise/items")
@RequiredArgsConstructor
@Log4j2
@ConditionalOnProperty(name = "franchise.admin", havingValue = "true")
public class FranchiseItemSyncController {

	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	private final ItemRepository itemRepository;
	private final ItemBarcodeRepository itemBarcodeRepository;

	/**
	 * Returns items modified after the given datetime (or all items if no date provided).
	 * Franchise clients use this for incremental sync, passing their last sync datetime.
	 *
	 * @param modifiedAfter ISO-8601 datetime string (yyyy-MM-dd'T'HH:mm:ss), optional
	 */
	@GetMapping
	public ResponseEntity<List<FranchiseItemDTO>> getItems(
			@RequestParam(required = false) String modifiedAfter) {

		LocalDateTime threshold = null;
		if (modifiedAfter != null && !modifiedAfter.isBlank()) {
			try {
				threshold = LocalDateTime.parse(modifiedAfter, ISO_FORMATTER);
			} catch (DateTimeParseException e) {
				log.warn("Franchise item sync: invalid modifiedAfter format '{}', falling back to full sync", modifiedAfter);
			}
		}

		final LocalDateTime finalThreshold = threshold;

		List<Item> items = (finalThreshold != null)
				? itemRepository.findByUpdatedAtAfter(finalThreshold)
				: itemRepository.findAll();

		List<FranchiseItemDTO> dtos = items.stream()
				.map(this::toDTO)
				.collect(Collectors.toList());

		log.info("Franchise item sync: returning {} items (modifiedAfter={})", dtos.size(), modifiedAfter);
		return ResponseEntity.ok(dtos);
	}

	private FranchiseItemDTO toDTO(Item item) {
		FranchiseItemDTO dto = new FranchiseItemDTO();
		dto.setItemCode(item.getItemCode());
		dto.setName(item.getName());
		dto.setDescription(item.getDescription());
		dto.setFranchiseSalesPrice(item.getFranchiseSalesPrice());
		dto.setDefaultVAT(item.getDefaultVAT());
		dto.setBarcode(item.getBarcode());
		dto.setImageUrl(item.getImageUrl());
		dto.setUnitOfMeasure(item.getUnitOfMeasure());
		dto.setCategory(item.getCategory());
		dto.setBrand(item.getBrand());
		dto.setShowInPos(item.getShowInPos());
		dto.setActive(item.getActive());
		dto.setUpdatedAt(item.getUpdatedAt());

		if (item.getItemFamily() != null) {
			dto.setItemFamilyCode(item.getItemFamily().getCode());
			dto.setItemFamilyName(item.getItemFamily().getName());
		}
		if (item.getItemSubFamily() != null) {
			dto.setItemSubFamilyCode(item.getItemSubFamily().getCode());
			dto.setItemSubFamilyName(item.getItemSubFamily().getName());
		}

		List<ItemBarcode> barcodes = itemBarcodeRepository.findByItemId(item.getId());
		if (!barcodes.isEmpty()) {
			dto.setBarcodes(barcodes.stream()
					.map(b -> new BarcodeSyncDTO(b.getBarcode(), b.getIsPrimary(), b.getDescription()))
					.collect(Collectors.toList()));
		}

		return dto;
	}
}
