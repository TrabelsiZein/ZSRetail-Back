package com.digithink.pos.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.pos.dto.CartCalculateRequestDTO;
import com.digithink.pos.dto.CartCalculateResponseDTO;
import com.digithink.pos.dto.PriceCalculateRequestDTO;
import com.digithink.pos.dto.PriceCalculateResponseDTO;
import com.digithink.pos.service.PromotionCalculationService;

import lombok.extern.log4j.Log4j2;

/**
 * POS Promotion Calculation Engine endpoints.
 *
 * POST /price-calculate   — per-item price (called on scan, qty change, customer change)
 * POST /cart-calculate    — cart-level promotion (called at payment redirect or CART promo code)
 * GET  /promo-code/{code} — validate a cashier-entered promo code
 */
@RestController
@RequestMapping("price-calculate")
@Log4j2
public class PriceCalculateController {

	@Autowired
	private PromotionCalculationService promotionCalculationService;

	/**
	 * Calculate the final price for a single item.
	 *
	 * Request: { itemId, quantity, customerId, appliedCodes }
	 * Response: { unitPrice, discountPercentage, discountAmount, freeQuantity,
	 *             promotionName, promotionType, source, ... }
	 *
	 * Precedence: SalesPrice > SalesDiscount > Promotion > BasePrice
	 */
	@PostMapping
	public ResponseEntity<?> calculateItemPrice(@RequestBody PriceCalculateRequestDTO request) {
		try {
			log.info("PriceCalculateController::calculateItemPrice: itemId={}, qty={}, customerId={}",
					request.getItemId(), request.getQuantity(), request.getCustomerId());

			if (request.getItemId() == null) {
				return ResponseEntity.badRequest().body(Map.of("error", "itemId is required"));
			}

			PriceCalculateResponseDTO response = promotionCalculationService.calculateItemPrice(
					request.getItemId(),
					request.getQuantity(),
					request.getCustomerId(),
					request.getAppliedCodes());

			return ResponseEntity.ok(response);

		} catch (IllegalArgumentException e) {
			log.warn("PriceCalculateController::calculateItemPrice:badRequest: {}", e.getMessage());
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			log.error("PriceCalculateController::calculateItemPrice:error: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", "Calculation failed: " + e.getMessage()));
		}
	}

	/**
	 * Calculate the best CART-scope promotion for the current cart.
	 * Called: (1) when cashier clicks "Proceed to Payment", (2) when a CART-scoped promo code is entered.
	 *
	 * Request: { customerId, cartTotal, cartItems, appliedCodes }
	 * Response: { promotionName, cartDiscountAmount, discountPercentage, finalTotal }
	 */
	@PostMapping("/cart")
	public ResponseEntity<?> calculateCartPromotion(@RequestBody CartCalculateRequestDTO request) {
		try {
			log.info("PriceCalculateController::calculateCartPromotion: cartTotal={}, codes={}",
					request.getCartTotal(), request.getAppliedCodes());

			CartCalculateResponseDTO response = promotionCalculationService.calculateCartPromotion(
					request.getCartTotal(),
					request.getCartItems(),
					request.getAppliedCodes());

			return ResponseEntity.ok(response);

		} catch (Exception e) {
			log.error("PriceCalculateController::calculateCartPromotion:error: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", "Cart calculation failed: " + e.getMessage()));
		}
	}

	/**
	 * Validate a promo code entered by the cashier.
	 * Returns promotion metadata so the frontend knows:
	 *   - which cart items to re-calculate (for ITEM/FAMILY/SUBFAMILY scope)
	 *   - to call /cart-calculate (for CART scope)
	 *
	 * Response when valid:
	 *   { valid: true, scope, promotionType, benefitType, itemId, itemFamilyId, itemSubFamilyId, ... }
	 * Response when invalid:
	 *   { valid: false, reason: "NOT_FOUND" | "INACTIVE" | "EXPIRED" | "NOT_A_PROMO_CODE" | ... }
	 */
	@GetMapping("/promo-code/{code}")
	public ResponseEntity<?> validatePromoCode(@PathVariable String code) {
		try {
			log.info("PriceCalculateController::validatePromoCode: code={}", code);
			Map<String, Object> result = promotionCalculationService.validatePromoCode(code);
			return ResponseEntity.ok(result);
		} catch (Exception e) {
			log.error("PriceCalculateController::validatePromoCode:error: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", "Validation failed: " + e.getMessage()));
		}
	}
}
