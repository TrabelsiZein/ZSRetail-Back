package com.digithink.zsretail.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
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
import com.digithink.zsretail.dto.QuickProductRequestDTO;
import com.digithink.zsretail.holink.service.StoreCatalogueGuard;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.ItemComposition;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.service.CatalogueCodeChangeException;
import com.digithink.zsretail.service.CatalogueCodeTooLongException;
import com.digithink.zsretail.service.CustomerService;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.ItemBarcodeService;
import com.digithink.zsretail.service.ItemCompositionService;
import com.digithink.zsretail.service.ItemService;
import com.digithink.zsretail.service.PricingService;
import com.digithink.zsretail.utils.Quantities;

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
	private GeneralSetupService generalSetupService;

	@Value("${pos.pricing.enable-sales-price-group:false}")
	private boolean priceGroupEnabled;

	/** Step 6: the rules of a store whose catalogue is the head office's; no bean on every other installation. */
	@Autowired(required = false)
	private ObjectProvider<StoreCatalogueGuard> catalogueGuard;

	/** The step 6 guard, or null: then every request runs exactly as before. */
	private StoreCatalogueGuard catalogueGuard() {
		return catalogueGuard == null ? null : catalogueGuard.getIfAvailable();
	}

	/** The first refusal (409) among the guard's answers; null when all are null. */
	private ResponseEntity<?> refused(String... messages) {
		for (String message : messages) {
			if (message != null) {
				return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(message));
			}
		}
		return null;
	}

	/**
	 * Create a product with default family/subfamily and one barcode. Refused with an ERP.
	 */
	@PostMapping("/quick-product")
	public ResponseEntity<?> createQuickProduct(@RequestBody QuickProductRequestDTO request) {
		if (applicationModeService.isCatalogueFromErp()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Product creation is not available with an ERP: products are synchronized from the ERP."));
		}
		StoreCatalogueGuard guard = catalogueGuard();
		if (guard != null) { // step 6: an own item, only with the purchase right, never a head office code
			ItemBarcode wanted = new ItemBarcode();
			wanted.setBarcode(request.getBarcode());
			ResponseEntity<?> refusal = refused(guard.create(), guard.itemCodeTaken(request.getItemCode()),
					guard.barcodeCreate(wanted));
			if (refusal != null) {
				return refusal;
			}
		}
		try {
			if (request.getName() == null || request.getName().trim().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Product name is required"));
			}
			if (request.getUnitPrice() == null || request.getUnitPrice() < 0) {
				return ResponseEntity.badRequest().body(createErrorResponse("Unit price is required and must be >= 0"));
			}
			Item item = service.createQuickProduct(
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
			log.error("ItemAPI::createQuickProduct:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Create item. Refused when the catalogue comes from an ERP.
	 */
	@Override
	@PostMapping
	public ResponseEntity<?> create(@RequestBody Item entity) {
		if (applicationModeService.isCatalogueFromErp()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Item creation is not available with an ERP: items are synchronized from the ERP."));
		}
		StoreCatalogueGuard guard = catalogueGuard();
		if (guard != null) { // step 6: an own item, only with the purchase right, never a head office code
			ResponseEntity<?> refusal = refused(guard.create(), guard.itemCodeTaken(entity.getItemCode()));
			if (refusal != null) {
				return refusal;
			}
		}
		try {
			log.info("ItemAPI::create");
			Item created = service.save(entity);
			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (CatalogueCodeTooLongException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage())); // step 6, head office
		} catch (Exception e) {
			log.error("ItemAPI::create:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Update item. Refused when the catalogue comes from an ERP.
	 */
	@Override
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Item entity) {
		if (applicationModeService.isCatalogueFromErp()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Item update is not available with an ERP: items are synchronized from the ERP."));
		}
		try {
			log.info("ItemAPI::update::" + id);
			Optional<Item> existing = service.findById(id);
			if (!existing.isPresent()) {
				return ResponseEntity.notFound().build();
			}
			Item existingItem = existing.get();
			StoreCatalogueGuard guard = catalogueGuard();
			if (guard != null && guard.itemWrite(id) != null) { // step 6: the price has its own endpoint
				return refused(guard.itemWrite(id));
			}
			entity.setId(existingItem.getId());
			// These fields are computed automatically after each validated purchase.
			// The frontend modal shows them as read-only and may omit them from the update payload,
			// so we must preserve existing values to avoid wiping them to null.
			entity.setLastDirectCost(existingItem.getLastDirectCost());
			entity.setLastDirectNetCost(existingItem.getLastDirectNetCost());
			// The stock changes only through sales, returns, purchases, adjustments and BLs (the form shows it read-only):
			// an edit made while the stock moves must not write back the quantity the page loaded
			entity.setStockQuantity(existingItem.getStockQuantity());
			// Step 6: never read from JSON; null on every store whose catalogue is not the head office's
			entity.setOwnPrice(existingItem.getOwnPrice());
			entity.setHeadOfficePrice(existingItem.getHeadOfficePrice());
			Item updated = service.save(entity);
			return ResponseEntity.ok(updated);
		} catch (CatalogueCodeChangeException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage())); // step 6
		} catch (CatalogueCodeTooLongException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage())); // step 6, head office
		} catch (Exception e) {
			log.error("ItemAPI::update:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Delete item. Refused when the catalogue comes from an ERP.
	 */
	@Override
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		if (applicationModeService.isCatalogueFromErp()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Item deletion is not available with an ERP: items are synchronized from the ERP."));
		}
		try {
			log.info("ItemAPI::deleteById::" + id);
			Optional<Item> existing = service.findById(id);
			if (!existing.isPresent()) {
				return ResponseEntity.notFound().build();
			}
			StoreCatalogueGuard guard = catalogueGuard();
			if (guard != null && guard.itemWrite(id) != null) { // step 6
				return refused(guard.itemWrite(id));
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
			StoreCatalogueGuard guard = catalogueGuard();
			if (guard != null && guard.itemWrite(id) != null) { // step 6: the head office decides the pack
				return refused(guard.itemWrite(id));
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
				// Step 6: HEAD_OFFICE for a head office item, null for an own item (read by the purchase item picker)
				dto.put("origin", item.getOrigin() != null ? item.getOrigin().name() : null);
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

			PricingResult pricingResult = pricingService.calculateItemPrice(item, customer, Quantities.of(quantity), null);

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
	 * Adjust stock for an item (without an ERP only). Delta can be positive or negative.
	 * Body: { "delta": number, "reason": "COUNT" | "CORRECTION" | "DAMAGE" }.
	 */
	@PostMapping("/{id}/adjust-stock")
	public ResponseEntity<?> adjustStock(@PathVariable Long id, @RequestBody AdjustStockRequestDTO request) {
		if (applicationModeService.isSupplyFromErp()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Stock adjustment is not available with an ERP."));
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
			service.adjustStock(id, delta, reason); // step 7A: with its stock movement
			Item updated = service.findById(id).orElse(item);
			return ResponseEntity.ok(updated);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("ItemAPI::adjustStock:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Step 6 (task 6.5): the store's own selling price on a head office item, kept across the pulls. Body {"unitPrice":
	 * 12.5}. 200 the item; 409 without the right "may change its selling prices"; 400 for a local item or a bad price; 404
	 * for an unknown item, or on a store whose catalogue is not the head office's.
	 */
	@PutMapping("/{id}/own-price")
	public ResponseEntity<?> setOwnPrice(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
		StoreCatalogueGuard guard = catalogueGuard();
		if (guard == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createErrorResponse(NOT_FROM_HEAD_OFFICE));
		}
		Object raw = body == null ? null : body.get("unitPrice");
		if (raw != null && !(raw instanceof Number)) {
			return ResponseEntity.badRequest().body(createErrorResponse("unitPrice must be a number."));
		}
		return ownPrice(() -> guard.setOwnPrice(id, raw == null ? null : ((Number) raw).doubleValue()));
	}

	/**
	 * Step 6: the head office price back on a head office item (allowed whatever the right). 200 the item; 400 for a
	 * local item; 404 as above.
	 */
	@DeleteMapping("/{id}/own-price")
	public ResponseEntity<?> giveBackPrice(@PathVariable Long id) {
		StoreCatalogueGuard guard = catalogueGuard();
		if (guard == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createErrorResponse(NOT_FROM_HEAD_OFFICE));
		}
		return ownPrice(() -> guard.giveBackPrice(id));
	}

	private static final String NOT_FROM_HEAD_OFFICE = "The items of this store are not decided by a head office.";

	private ResponseEntity<?> ownPrice(Supplier<Item> call) {
		try {
			return ResponseEntity.ok(call.get());
		} catch (NoSuchElementException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createErrorResponse(e.getMessage()));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (RuntimeException e) {
			log.error("ItemAPI::ownPrice:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}
}
