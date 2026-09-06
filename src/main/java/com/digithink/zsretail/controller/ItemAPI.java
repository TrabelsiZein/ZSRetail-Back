package com.digithink.zsretail.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.dto.AdjustStockRequestDTO;
import com.digithink.zsretail.dto.PricingResult;
import com.digithink.zsretail.dto.StandaloneQuickProductRequestDTO;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.ItemComposition;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.service.CustomerService;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.ItemBarcodeService;
import com.digithink.zsretail.service.ItemCompositionService;
import com.digithink.zsretail.service.ItemService;
import com.digithink.zsretail.service.PricingService;
import com.digithink.zsretail.service.StockService;

import java.util.Optional;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("item")
@Log4j2
public class ItemAPI extends _BaseController<Item, Long, ItemService> {

	@Autowired
	private PricingService pricingService;

	@Autowired
	private CustomerRepository customerRepository;

	@Autowired
	private CustomerService customerService;

	@Autowired
	private ApplicationModeService applicationModeService;

	@Autowired
	private ItemBarcodeService itemBarcodeService;

	@Autowired
	private ItemCompositionService itemCompositionService;

	@Autowired
	private StockService stockService;

	@Autowired
	private GeneralSetupService generalSetupService;

	@Value("${pos.pricing.enable-sales-price-group:false}")
	private boolean priceGroupEnabled;

	/**
	 * Create a product with default family/subfamily and one barcode. Only allowed in standalone mode.
	 */
	@PostMapping("/standalone-quick-product")
	public ResponseEntity<?> createStandaloneQuickProduct(@RequestBody StandaloneQuickProductRequestDTO request) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Product creation is only available in standalone mode. In ERP mode products are synchronized from the ERP."));
		}
		try {
			if (request.getName() == null || request.getName().trim().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Product name is required"));
			}
			if (request.getUnitPrice() == null || request.getUnitPrice() < 0) {
				return ResponseEntity.badRequest().body(createErrorResponse("Unit price is required and must be >= 0"));
			}
			Item item = service.createStandaloneQuickProduct(
					request.getName(),
					request.getItemCode(),
					request.getUnitPrice());
			String barcodeValue = (request.getBarcode() != null && !request.getBarcode().trim().isEmpty())
					? request.getBarcode().trim()
					: item.getItemCode();
			ItemBarcode barcode = new ItemBarcode();
			barcode.setItem(item);
			barcode.setBarcode(barcodeValue);
			barcode.setIsPrimary(true);
			barcode.setActive(true);
			itemBarcodeService.save(barcode);
			Map<String, Object> response = new HashMap<>();
			response.put("item", item);
			response.put("barcode", barcodeValue);
			return ResponseEntity.status(HttpStatus.CREATED).body(response);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("ItemAPI::createStandaloneQuickProduct:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Create item. Requires standalone mode.
	 * Franchise clients: only allowed when allow-local-items=true; new items are always local (fromFranchiseAdmin=false).
	 */
	@Override
	@PostMapping
	public ResponseEntity<?> create(@RequestBody Item entity) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Item creation is only available in standalone mode. In ERP mode items are synchronized from the ERP."));
		}
		if (applicationModeService.isFranchiseClient() && !applicationModeService.isLocalItemsAllowed()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Item creation is not allowed in franchise client mode. Items are synchronized from the franchise admin."));
		}
		// Locally-created items by franchise client are never from the admin
		if (applicationModeService.isFranchiseClient()) {
			entity.setFromFranchiseAdmin(false);
		}
		try {
			log.info("ItemAPI::create");
			Item created = service.save(entity);
			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (Exception e) {
			log.error("ItemAPI::create:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Update item. Requires standalone mode.
	 * Franchise clients with allow-local-items=true may only edit their own local items (fromFranchiseAdmin=false).
	 */
	@Override
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Item entity) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Item update is only available in standalone mode. In ERP mode items are synchronized from the ERP."));
		}
		if (applicationModeService.isFranchiseClient() && !applicationModeService.isLocalItemsAllowed()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Item update is not allowed in franchise client mode. Items are synchronized from the franchise admin."));
		}
		try {
			log.info("ItemAPI::update::" + id);
			Optional<Item> existing = service.findById(id);
			if (!existing.isPresent()) {
				return ResponseEntity.notFound().build();
			}
			Item existingItem = existing.get();
			// Franchise client cannot edit items that originated from the admin
			if (applicationModeService.isFranchiseClient() && Boolean.TRUE.equals(existingItem.getFromFranchiseAdmin())) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN)
						.body(createErrorResponse("Items synced from the franchise admin are read-only and cannot be edited."));
			}
			entity.setId(existingItem.getId());
			// Preserve the fromFranchiseAdmin flag — cannot be changed via update
			entity.setFromFranchiseAdmin(existingItem.getFromFranchiseAdmin());
			// These fields are computed automatically after each validated purchase.
			// The frontend modal shows them as read-only and may omit them from the update payload,
			// so we must preserve existing values to avoid wiping them to null.
			entity.setLastDirectCost(existingItem.getLastDirectCost());
			entity.setLastDirectNetCost(existingItem.getLastDirectNetCost());
			Item updated = service.save(entity);
			return ResponseEntity.ok(updated);
		} catch (Exception e) {
			log.error("ItemAPI::update:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Delete item. Requires standalone mode.
	 * Franchise clients with allow-local-items=true may only delete their own local items (fromFranchiseAdmin=false).
	 */
	@Override
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Item deletion is only available in standalone mode. In ERP mode items are synchronized from the ERP."));
		}
		if (applicationModeService.isFranchiseClient() && !applicationModeService.isLocalItemsAllowed()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Item deletion is not allowed in franchise client mode. Items are synchronized from the franchise admin."));
		}
		try {
			log.info("ItemAPI::deleteById::" + id);
			Optional<Item> existing = service.findById(id);
			if (!existing.isPresent()) {
				return ResponseEntity.notFound().build();
			}
			// Franchise client cannot delete items that originated from the admin
			if (applicationModeService.isFranchiseClient() && Boolean.TRUE.equals(existing.get().getFromFranchiseAdmin())) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN)
						.body(createErrorResponse("Items synced from the franchise admin cannot be deleted."));
			}
			// Kit components block deletion (FK); a kit's own recipe is deleted with it
			if (!itemCompositionService.getCompositionsByComponentItemId(id).isEmpty()) {
				return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(
						"Cet article est un composant d'un pack — retirez-le du pack avant de le supprimer."));
			}
			for (ItemComposition composition : itemCompositionService.getCompositionsByParentItemId(id)) {
				itemCompositionService.deleteById(composition.getId());
			}
			service.deleteById(id);
			return ResponseEntity.noContent().build();
		} catch (Exception e) {
			log.error("ItemAPI::deleteById:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Toggle the Pack (kit) flag on an item. Intentionally allowed in ALL modes
	 * (including ERP): the pack concept is POS-only — ERP sync never reads or
	 * writes the type field, so flipping it does not conflict with NAV data.
	 * Only the type field is changed; every other field keeps its current value.
	 */
	@PutMapping("/{id}/package-flag")
	public ResponseEntity<?> setPackageFlag(@PathVariable Long id, @RequestBody Map<String, Object> body) {
		try {
			log.info("ItemAPI::setPackageFlag::" + id);
			boolean isPackage = Boolean.TRUE.equals(body.get("isPackage"));
			Optional<Item> existing = service.findById(id);
			if (!existing.isPresent()) {
				return ResponseEntity.notFound().build();
			}
			Item item = existing.get();
			if (isPackage) {
				if (!itemCompositionService.getCompositionsByComponentItemId(id).isEmpty()) {
					return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(
							"Cet article est un composant d'un pack — il ne peut pas devenir un pack lui-même."));
				}
				item.setType(ItemType.PACKAGE);
			} else {
				item.setType(ItemType.PRODUCT);
			}
			Item updated = service.save(item);
			return ResponseEntity.ok(updated);
		} catch (Exception e) {
			log.error("ItemAPI::setPackageFlag:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Lightweight paginated item search for purchase forms and lookups.
	 * Returns id, itemCode, name, unitPrice only (lightweight for dropdowns).
	 */
	@GetMapping("/search")
	public ResponseEntity<?> searchItems(
			@RequestParam(defaultValue = "") String q,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		try {
			PageRequest pageable = PageRequest.of(page, Math.min(size, 50), Sort.by("name").ascending());
			Page<Item> result = service.searchItemsForPurchase(
					q.trim().isEmpty() ? null : q.trim(), pageable);
			List<Map<String, Object>> items = result.getContent().stream().map(item -> {
				Map<String, Object> dto = new HashMap<>();
				dto.put("id", item.getId());
				dto.put("itemCode", item.getItemCode());
				dto.put("name", item.getName());
				dto.put("unitPrice", item.getUnitPrice() != null ? item.getUnitPrice() : 0.0);
				dto.put("type", item.getType() != null ? item.getType().name() : null);
				dto.put("lastDirectCost", item.getLastDirectCost());
				dto.put("lastDirectNetCost", item.getLastDirectNetCost());
				dto.put("defaultVAT", item.getDefaultVAT() != null ? item.getDefaultVAT() : 0);
				return dto;
			}).collect(Collectors.toList());
			Map<String, Object> response = new HashMap<>();
			response.put("content", items);
			response.put("totalElements", result.getTotalElements());
			response.put("totalPages", result.getTotalPages());
			response.put("number", result.getNumber());
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("ItemAPI::searchItems:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	@GetMapping("/by-family/{familyId}")
	public ResponseEntity<?> getByFamily(@PathVariable Long familyId) {
		try {
			return ResponseEntity.ok(service.findActiveByFamilyId(familyId));
		} catch (Exception e) {
			return ResponseEntity.badRequest().body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	@GetMapping("/by-sub-family/{subFamilyId}")
	public ResponseEntity<?> getBySubFamily(@PathVariable Long subFamilyId) {
		try {
			List<Item> items = service.findActiveBySubFamilyId(subFamilyId);
			if (!isPosShowImages()) {
				items.forEach(i -> i.setImageUrl(null));
			}
			return ResponseEntity.ok(items);
		} catch (Exception e) {
			return ResponseEntity.badRequest().body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	private boolean isPosShowImages() {
		String val = generalSetupService.findValueByCode("POS_SHOW_IMAGES");
		return val == null || !"false".equalsIgnoreCase(val);
	}

	/**
	 * Get pricing configuration (for frontend optimization)
	 * Frontend can check this to decide whether to call calculate-price API
	 */
	@GetMapping("/pricing-config")
	public ResponseEntity<?> getPricingConfig() {
		Map<String, Object> config = new HashMap<>();
		config.put("priceGroupEnabled", priceGroupEnabled);
		return ResponseEntity.ok(config);
	}

	/**
	 * Calculate price for an item (for POS cart) Returns calculated price,
	 * discount, and source
	 */
	@GetMapping("/{itemId}/calculate-price")
	public ResponseEntity<?> calculateItemPrice(@PathVariable Long itemId,
			@RequestParam(name = "customerId", required = false) Long customerId,
			@RequestParam(name = "quantity", defaultValue = "1") Integer quantity) {
		try {
			log.info("ItemAPI::calculateItemPrice: itemId={}, customerId={}, quantity={}", itemId, customerId,
					quantity);

			Item item = service.findById(itemId)
					.orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

			// Early exit: If pricing is disabled, return item price without fetching customer
			// This avoids unnecessary database calls when feature is disabled
			if (!priceGroupEnabled) {
				Map<String, Object> response = new HashMap<>();
				response.put("itemId", itemId);
				response.put("unitPrice", item.getUnitPrice() != null ? item.getUnitPrice() : 0.0);
				response.put("priceIncludesVat", false);
				response.put("discountPercentage", null);
				response.put("source", "ITEM");
				return ResponseEntity.ok(response);
			}

			// Pricing is enabled - fetch customer for price calculation
			Customer customer = null;
			if (customerId != null) {
				customer = customerRepository.findById(customerId).orElse(null);
			}
			// If no customer provided, use default customer from GeneralSetup
			if (customer == null) {
				customer = customerService.getDefaultCustomer();
			}

			PricingResult pricingResult = pricingService.calculateItemPrice(item, customer, quantity, null);

			Map<String, Object> response = new HashMap<>();
			response.put("itemId", itemId);
			response.put("unitPrice", pricingResult.getUnitPrice());
			response.put("priceIncludesVat", pricingResult.getPriceIncludesVat());
			response.put("discountPercentage", pricingResult.getDiscountPercentage());
			response.put("source", pricingResult.getSource());

			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("ItemAPI::calculateItemPrice:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Adjust stock for an item (standalone only). Delta can be positive or negative.
	 * Body: { "delta": number, "reason": "COUNT" | "CORRECTION" | "DAMAGE" }.
	 */
	@PostMapping("/{id}/adjust-stock")
	public ResponseEntity<?> adjustStock(@PathVariable Long id, @RequestBody AdjustStockRequestDTO request) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Stock adjustment is only available in standalone mode."));
		}
		try {
			if (request.getDelta() == null) {
				return ResponseEntity.badRequest().body(createErrorResponse("delta is required"));
			}
			int delta = request.getDelta().intValue();
			if (delta == 0) {
				return ResponseEntity.badRequest().body(createErrorResponse("delta must not be zero"));
			}
			String reason = request.getReason() != null ? request.getReason().trim().toUpperCase() : "CORRECTION";
			if (reason.isEmpty()) {
				reason = "CORRECTION";
			}
			if (!reason.matches("COUNT|CORRECTION|DAMAGE")) {
				reason = "CORRECTION";
			}
			Item item = service.findById(id)
					.orElseThrow(() -> new IllegalArgumentException("Item not found: " + id));
			stockService.adjustStock(id, delta, reason);
			Item updated = service.findById(id).orElse(item);
			return ResponseEntity.ok(updated);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("ItemAPI::adjustStock:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}
}
