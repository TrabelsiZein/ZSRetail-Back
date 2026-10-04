package com.digithink.zsretail.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.holink.service.StoreCatalogueGuard;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.service.CatalogueCodeTooLongException;
import com.digithink.zsretail.service.ItemBarcodeService;
import com.digithink.zsretail.service.ItemService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("item-barcode")
@Log4j2
public class ItemBarcodeAPI extends _BaseController<ItemBarcode, Long, ItemBarcodeService> {

	@Autowired
	private ItemBarcodeService itemBarcodeService;

	@Autowired
	private ItemService itemService;

	/** Step 6: the rules of a store whose catalogue is the head office's; no bean on every other installation. */
	@Autowired(required = false)
	private ObjectProvider<StoreCatalogueGuard> catalogueGuard;

	private StoreCatalogueGuard catalogueGuard() {
		return catalogueGuard == null ? null : catalogueGuard.getIfAvailable();
	}

	/**
	 * The generic create. Step 6: 409 for a head office item, without the purchase right, or a head office barcode; 400
	 * for a barcode longer than 90 characters on a head office that sends its catalogue.
	 */
	@Override
	@PostMapping
	public ResponseEntity<?> create(@RequestBody ItemBarcode entity) {
		StoreCatalogueGuard guard = catalogueGuard();
		String refusal = guard == null ? null : guard.barcodeCreate(entity);
		if (refusal != null) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(refusal));
		}
		try {
			log.info("ItemBarcodeAPI::create");
			return ResponseEntity.status(HttpStatus.CREATED).body(service.save(entity));
		} catch (CatalogueCodeTooLongException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage())); // step 6, head office
		} catch (Exception e) {
			log.error("ItemBarcodeAPI::create:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/** The generic update. Step 6: 409 for a head office barcode, or moving a barcode to a head office item. */
	@Override
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody ItemBarcode entity) {
		StoreCatalogueGuard guard = catalogueGuard();
		if (guard != null) {
			String refusal = guard.barcodeWrite(id);
			if (refusal == null && entity != null && entity.getItem() != null) {
				refusal = guard.itemWrite(entity.getItem().getId());
			}
			if (refusal != null) {
				return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(refusal));
			}
		}
		try {
			log.info("ItemBarcodeAPI::update::" + id);
			Optional<ItemBarcode> existing = service.findById(id);
			if (!existing.isPresent()) {
				return ResponseEntity.notFound().build();
			}
			entity.setId(existing.get().getId());
			return ResponseEntity.ok(service.save(entity));
		} catch (CatalogueCodeTooLongException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage())); // step 6, head office
		} catch (Exception e) {
			log.error("ItemBarcodeAPI::update:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/** The generic delete. Step 6: 409 for a head office barcode. */
	@Override
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		StoreCatalogueGuard guard = catalogueGuard();
		String refusal = guard == null ? null : guard.barcodeWrite(id);
		if (refusal != null) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(refusal));
		}
		return super.deleteById(id);
	}

	/**
	 * Get all barcodes for an item
	 */
	@GetMapping("/item/{itemId}")
	public ResponseEntity<?> getBarcodesByItemId(@PathVariable Long itemId) {
		try {
			log.info("ItemBarcodeAPI::getBarcodesByItemId: " + itemId);
			List<ItemBarcode> barcodes = itemBarcodeService.getBarcodesByItemId(itemId);
			return ResponseEntity.ok(barcodes);
		} catch (Exception e) {
			log.error("ItemBarcodeAPI::getBarcodesByItemId:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Find item by barcode (for POS scanning)
	 * Returns the item without price calculation - price calculation is handled in addToCart
	 */
	@GetMapping("/barcode/{barcode}")
	public ResponseEntity<?> getItemByBarcode(@PathVariable String barcode) {
		try {
			log.info("ItemBarcodeAPI::getItemByBarcode: " + barcode);
			Optional<Item> itemOpt = itemBarcodeService.getItemByBarcode(barcode);
			if (itemOpt.isPresent()) {
				Item item = itemOpt.get();
				// Return item as-is - price calculation will be done in addToCart
				return ResponseEntity.ok(item);
			} else {
				return ResponseEntity.status(404).body(createErrorResponse("Item not found with barcode: " + barcode));
			}
		} catch (Exception e) {
			log.error("ItemBarcodeAPI::getItemByBarcode:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get all items with their barcodes (for admin/responsible view)
	 */
	@GetMapping("/items-with-barcodes")
	public ResponseEntity<?> getAllItemsWithBarcodes(
			@org.springframework.web.bind.annotation.RequestParam(name = "page", defaultValue = "0") int page,
			@org.springframework.web.bind.annotation.RequestParam(name = "size", defaultValue = "20") int size,
			@org.springframework.web.bind.annotation.RequestParam(name = "search", required = false) String search,
			@org.springframework.web.bind.annotation.RequestParam(name = "familyId", required = false) Long familyId,
			@org.springframework.web.bind.annotation.RequestParam(name = "subFamilyId", required = false) Long subFamilyId,
			@org.springframework.web.bind.annotation.RequestParam(name = "priceMin", required = false) Double priceMin,
			@org.springframework.web.bind.annotation.RequestParam(name = "priceMax", required = false) Double priceMax,
			@org.springframework.web.bind.annotation.RequestParam(name = "withBarcodesOnly", required = false) Boolean withBarcodesOnly) {
		try {
			log.info(
					"ItemBarcodeAPI::getAllItemsWithBarcodes page={}, size={}, search={}, familyId={}, subFamilyId={}, priceMin={}, priceMax={}, withBarcodesOnly={}",
					page, size, search, familyId, subFamilyId, priceMin, priceMax, withBarcodesOnly);

			int safePage = Math.max(page, 0);
			int safeSize = Math.min(Math.max(size, 1), 200);

			org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(safePage,
					safeSize, org.springframework.data.domain.Sort.by("itemCode").ascending());

			org.springframework.data.domain.Page<Item> itemsPage = itemService.findActiveItems(search, familyId,
					subFamilyId, priceMin, priceMax, withBarcodesOnly, pageable);

			List<Long> itemIds = itemsPage.stream().map(Item::getId).collect(Collectors.toList());
			Map<Long, List<ItemBarcode>> barcodesByItem = itemBarcodeService.getActiveBarcodesForItems(itemIds).stream()
					.collect(Collectors.groupingBy(barcode -> barcode.getItem().getId()));

			List<Map<String, Object>> content = itemsPage.stream().map(item -> {
				Map<String, Object> map = new HashMap<>();
				map.put("item", item);
				map.put("barcodes", barcodesByItem.getOrDefault(item.getId(), List.of()));
				return map;
			}).collect(Collectors.toList());

			Map<String, Object> response = new HashMap<>();
			response.put("content", content);
			response.put("page", itemsPage.getNumber());
			response.put("size", itemsPage.getSize());
			response.put("totalElements", itemsPage.getTotalElements());
			response.put("totalPages", itemsPage.getTotalPages());

			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("ItemBarcodeAPI::getAllItemsWithBarcodes:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}
}
