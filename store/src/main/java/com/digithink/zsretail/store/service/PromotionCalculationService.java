package com.digithink.zsretail.store.service;

import java.math.BigDecimal;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.digithink.zsretail.core.dto.PricingResult;
import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.Promotion;
import com.digithink.zsretail.core.model.enumeration.PromotionBenefitType;
import com.digithink.zsretail.core.model.enumeration.PromotionScope;
import com.digithink.zsretail.core.repository.CustomerRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.PromotionRepository;
import com.digithink.zsretail.core.service.CustomerService;
import com.digithink.zsretail.core.service.PricingService;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.store.dto.CartCalculateRequestDTO;
import com.digithink.zsretail.store.dto.CartCalculateResponseDTO;
import com.digithink.zsretail.store.dto.CrossProductAdjustmentDTO;
import com.digithink.zsretail.store.dto.PriceCalculateResponseDTO;

import lombok.extern.log4j.Log4j2;

/**
 * Core POS calculation engine.
 *
 * Precedence per item:
 *   1. Effective unit price = SalesPrice if found, else item base price.
 *   2. FREE_QUANTITY promotion: always applied on the effective price. Free pieces only when scanned (decision of
 *      2026-10-12): the line's quantity Q is all its pieces; per group of G = N + M pieces, free = floor(Q / G) x M
 *      + min(M, max(0, (Q mod G) - N)) (freePieces), the free pieces of the group in progress still to scan:
 *      freeAvailable. It takes the
 *      line only when it gives at least 1 free piece (Q >= N + 1); with 0 the line is priced as if it did not exist
 *      (another promotion, else ERP pricing) and the answer carries it as a hint (freeQuantityAvailable,
 *      freePromotionName). No N: never selected, no free piece.
 *   3. PERCENTAGE/FIXED promotion vs SalesDiscount: best discount wins (higher % applied).
 *   4. No promotion: ERP pricing (SalesPrice / SalesDiscount) applied as-is.
 *
 * Cart promotions (CART scope) are independent and always considered.
 *
 * Promotion selection:
 *   - Scope priority: ITEM > ITEM_GROUP > SECTION > ITEM_SUBFAMILY > ITEM_FAMILY > CATEGORY > ALL_ITEMS (most specific wins;
 *     a scope with no eligible candidate falls through to the next one)
 *   - Within scope: highest priority wins (ABSOLUTE — no best-value override)
 *   - requiresCode promotions: only active when their code is in appliedCodes
 *   - Filters: active + date valid + time valid + day valid + minimum quantity met
 *
 * 3.1.0, rule 6a (2026-10-23): with the cart's other lines (POST /price-calculate cartLines, the cart pass) a quantity
 * promotion counts the whole pieces of every line of its target, level by level (resolveCart); alone, as above.
 */
@Service
@Log4j2
public class PromotionCalculationService {

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private CustomerRepository customerRepository;

	@Autowired
	private CustomerService customerService;

	@Autowired
	private PricingService pricingService;

	@Autowired
	private PromotionRepository promotionRepository;

	// ─── Item Price Calculation ──────────────────────────────────────────────────

	/**
	 * Calculate the final price for a single item in the POS cart.
	 *
	 * @param itemId       ID of the item
	 * @param quantity     Quantity in the cart (used for threshold promotions)
	 * @param customerId   ID of the selected customer (may be null → default customer)
	 * @param appliedCodes List of promo codes entered by the cashier
	 * @return Full pricing breakdown
	 */
	public PriceCalculateResponseDTO calculateItemPrice(Long itemId, BigDecimal quantity,
			Long customerId, List<String> appliedCodes) {
		return calculateItemPrice(itemId, quantity, customerId, appliedCodes, null);
	}

	/**
	 * 3.1.0 (rule 6a, 2026-10-23): the line priced with the cart around it. cartLines: the cart's lines in cart order,
	 * this one included (absent: first); null, empty or no line of another item: priced alone, exactly as before. With
	 * other lines the quantity promotions count the pieces of every line of their target (resolveCart) and the answer
	 * says whether it differs from the line priced alone (acrossLines).
	 */
	public PriceCalculateResponseDTO calculateItemPrice(Long itemId, BigDecimal quantity, Long customerId,
			List<String> appliedCodes, List<CartCalculateRequestDTO.CartItemDTO> cartLines) {

		// Load item
		Item item = itemRepository.findById(itemId)
				.orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

		// Load customer
		Customer customer = resolveCustomer(customerId);

		BigDecimal qty = (quantity != null && quantity.signum() > 0) ? quantity : BigDecimal.ONE;
		List<String> codes = (appliedCodes != null) ? appliedCodes : new ArrayList<>();

		// Delegate SalesPrice / SalesDiscount to existing PricingService
		PricingResult pricingResult = pricingService.calculateItemPrice(item, customer, qty, null);

		List<CartLine> lines = cartLines(cartLines, item, qty);
		if (lines == null) {
			return price(item, qty, pricingResult, findBestItemPromotion(item, qty, codes));
		}
		CartLine self = lines.stream().filter(line -> line.self).findFirst().orElseThrow(IllegalStateException::new);
		PriceCalculateResponseDTO answer = price(item, qty, pricingResult, resolveCart(lines, codes).get(self));
		PriceCalculateResponseDTO alone = price(item, qty, pricingResult, findBestItemPromotion(item, qty, codes));
		answer.setAcrossLines(!sameAnswer(answer, alone));
		return answer;
	}

	/**
	 * The line's price from its promotions (found): SalesPrice / SalesDiscount against the promotion, as before 3.1.0.
	 * found.free / available / hintAvailable, when set (a line counted with others), replace the line's own count.
	 */
	private PriceCalculateResponseDTO price(Item item, BigDecimal qty, PricingResult pricingResult, ItemPromotions found) {
		Long itemId = item.getId();
		PriceCalculateResponseDTO response = new PriceCalculateResponseDTO();
		response.setItemId(itemId);
		response.setPriceIncludesVat(pricingResult.getPriceIncludesVat());

		// Effective unit price: SalesPrice if found, else item base price
		boolean hasSalesPrice = "SALES_PRICE".equals(pricingResult.getSource());
		Double salesDiscountPct = pricingResult.getDiscountPercentage();
		boolean hasSalesDiscount = salesDiscountPct != null && salesDiscountPct > 0;
		Double effectiveUnitPrice = pricingResult.getUnitPrice();

		Promotion bestPromotion = found.applied;

		// A free promotion that gives no free piece yet (Q = N): not applied, only its hint (2026-10-12)
		if (found.freeHint != null) {
			response.setFreeQuantity(0);
			response.setFreeQuantityAvailable(
					found.hintAvailable != null ? found.hintAvailable : freeAvailable(found.freeHint, qty));
			response.setFreePromotionId(found.freeHint.getId());
			response.setFreePromotionName(found.freeHint.getName());
		}

		// No promotion found → apply ERP pricing as-is (unchanged behavior)
		if (bestPromotion == null) {
			response.setUnitPrice(effectiveUnitPrice);
			if (hasSalesDiscount) {
				response.setDiscountPercentage(salesDiscountPct);
				response.setSource(hasSalesPrice ? "SALES_PRICE" : "SALES_DISCOUNT");
			} else {
				response.setSource(hasSalesPrice ? "SALES_PRICE" : "BASE_PRICE");
			}
			log.debug("calculateItemPrice: item={} → {} price={}", itemId, response.getSource(), effectiveUnitPrice);
			return response;
		}

		// FREE_QUANTITY: always apply on effective price — quantity benefit, no comparison needed
		if (bestPromotion.getBenefitType() == PromotionBenefitType.FREE_QUANTITY) {
			applyItemPromotionBenefit(response, bestPromotion, effectiveUnitPrice);
			// 3.1.0: counted with other lines, the line's share of the group's free pieces (0 possible: the line is
			// still taken by the promotion, decision C of 2026-10-23)
			int free = found.free != null ? found.free : freePieces(bestPromotion, qty);
			response.setFreeQuantity(free);
			response.setFreeQuantityAvailable(
					found.available != null ? found.available : freeAvailable(bestPromotion, qty));
			response.setFreePromotionId(bestPromotion.getId());
			response.setFreePromotionName(bestPromotion.getName());
			response.setPromotionId(bestPromotion.getId());
			response.setPromotionName(bestPromotion.getName());
			response.setPromotionCode(bestPromotion.getCode());
			response.setPromotionType(bestPromotion.getPromotionType().name());
			response.setSource("PROMOTION");
			log.debug("calculateItemPrice: item={} → FREE_QUANTITY promotion '{}' on price={}", itemId,
					bestPromotion.getName(), effectiveUnitPrice);
			return response;
		}

		// Compute promotion's effective discount % for comparison with SalesDiscount
		double promotionEffectivePct;
		if (bestPromotion.getBenefitType() == PromotionBenefitType.PERCENTAGE_DISCOUNT) {
			promotionEffectivePct = bestPromotion.getDiscountPercentage() != null
					? bestPromotion.getDiscountPercentage() : 0.0;
		} else { // FIXED_DISCOUNT: convert to % based on effective unit price
			promotionEffectivePct = (effectiveUnitPrice != null && effectiveUnitPrice > 0
					&& bestPromotion.getDiscountAmount() != null)
					? (bestPromotion.getDiscountAmount() / effectiveUnitPrice) * 100.0
					: 0.0;
		}

		double salesDiscountValue = hasSalesDiscount ? salesDiscountPct : 0.0;

		// Apply whichever gives the best discount
		if (promotionEffectivePct > salesDiscountValue) {
			// Promotion wins — applied on the effective price (base or SalesPrice)
			applyItemPromotionBenefit(response, bestPromotion, effectiveUnitPrice);
			response.setPromotionId(bestPromotion.getId());
			response.setPromotionName(bestPromotion.getName());
			response.setPromotionCode(bestPromotion.getCode());
			response.setPromotionType(bestPromotion.getPromotionType().name());
			response.setSource("PROMOTION");
			log.debug("calculateItemPrice: item={} → PROMOTION '{}' ({}%) beats SalesDiscount ({}%)", itemId,
					bestPromotion.getName(), promotionEffectivePct, salesDiscountValue);
		} else {
			// SalesDiscount is better or equal — keep ERP pricing
			response.setUnitPrice(effectiveUnitPrice);
			if (hasSalesDiscount) {
				response.setDiscountPercentage(salesDiscountPct);
			}
			response.setSource(hasSalesPrice ? "SALES_PRICE" : (hasSalesDiscount ? "SALES_DISCOUNT" : "BASE_PRICE"));
			log.debug("calculateItemPrice: item={} → {} ({}%) beats PROMOTION ({}%)", itemId,
					response.getSource(), salesDiscountValue, promotionEffectivePct);
		}

		return response;
	}

	// ─── Cart Promotion Calculation ──────────────────────────────────────────────

	/**
	 * Calculate the best CART-scope promotion for the current cart, plus any
	 * cross-product adjustments ("buy N of A → benefit on Z") triggered by the
	 * cart lines. Called on cart changes, promo-code entry, and before payment.
	 *
	 * @param cartTotal    Cart total TTC (after all line-level discounts)
	 * @param cartItems    Current cart lines (auto-added free lines excluded by the POS)
	 * @param appliedCodes List of promo codes entered by the cashier
	 * @return Cart discount result (cartDiscountAmount=0 if no promotion matched)
	 */
	public CartCalculateResponseDTO calculateCartPromotion(Double cartTotal,
			List<CartCalculateRequestDTO.CartItemDTO> cartItems, List<String> appliedCodes) {
		CartCalculateResponseDTO response = new CartCalculateResponseDTO();
		response.setCartDiscountAmount(0.0);
		response.setFinalTotal(cartTotal != null ? cartTotal : 0.0);

		LocalDate today = LocalDate.now();
		LocalTime now = LocalTime.now();
		DayOfWeek todayDay = today.getDayOfWeek();
		List<String> codes = (appliedCodes != null) ? appliedCodes : new ArrayList<>();

		// Cross-product benefits are independent of the CART-scope promotion below
		response.setCrossProductAdjustments(
				evaluateCrossProductPromotions(cartItems, codes, today, now, todayDay));

		if (cartTotal == null || cartTotal <= 0) {
			return response;
		}

		// Find all active CART-scope promotions valid today
		List<Promotion> cartPromos = promotionRepository.findActiveByScope(PromotionScope.CART, today);

		// Select best (highest priority; tiebreaker: highest discount value)
		Optional<Promotion> best = cartPromos.stream()
				.filter(p -> passesCodeFilter(p, codes))
				.filter(p -> passesAmountFilter(p, cartTotal))
				.filter(p -> passesTimeFilter(p, now))
				.filter(p -> passesDayFilter(p, todayDay))
				.max(Comparator
						.comparingInt((Promotion p) -> p.getPriority() != null ? p.getPriority() : 0)
						.thenComparingDouble(p -> p.getDiscountPercentage() != null ? p.getDiscountPercentage() : 0.0)
						.thenComparingDouble(p -> p.getDiscountAmount() != null ? p.getDiscountAmount() : 0.0));

		if (!best.isPresent()) {
			return response;
		}

		Promotion promo = best.get();
		double discount = 0.0;
		Double discountPct = null;

		if (promo.getBenefitType() == PromotionBenefitType.PERCENTAGE_DISCOUNT
				&& promo.getDiscountPercentage() != null) {
			discountPct = promo.getDiscountPercentage();
			discount = cartTotal * promo.getDiscountPercentage() / 100.0;
		} else if (promo.getBenefitType() == PromotionBenefitType.FIXED_DISCOUNT
				&& promo.getDiscountAmount() != null) {
			discount = Math.min(promo.getDiscountAmount(), cartTotal);
		}

		response.setPromotionId(promo.getId());
		response.setPromotionName(promo.getName());
		response.setPromotionCode(promo.getCode());
		response.setDiscountPercentage(discountPct);
		response.setCartDiscountAmount(discount);
		response.setFinalTotal(cartTotal - discount);

		log.debug("calculateCartPromotion: cartTotal={} → CART_PROMOTION '{}' discount={}", cartTotal,
				promo.getName(), discount);

		return response;
	}

	/**
	 * The cart pass of the till (POST /price-calculate/cart): the cart promotion and the cross-product adjustments as
	 * above, and 3.1.0 (rule 6a) each line priced with the cart around it (lineResults, acrossLines when it differs
	 * from the line priced alone), so the till corrects the lines another line's change moved. A cart of one item: its line(s) priced alone.
	 */
	public CartCalculateResponseDTO calculateCartPromotion(Double cartTotal,
			List<CartCalculateRequestDTO.CartItemDTO> cartItems, List<String> appliedCodes, Long customerId) {
		CartCalculateResponseDTO response = calculateCartPromotion(cartTotal, cartItems, appliedCodes);
		List<String> codes = (appliedCodes != null) ? appliedCodes : new ArrayList<>();
		List<CartLine> lines = cartLines(cartItems, null, null);
		if (lines == null) {
			// One item left (a line removed, the cart's last one): each line priced alone, so the till never keeps the
			// free pieces or the hint of a group that no longer exists (screen test of 2026-10-23, case 6)
			Customer customer = null;
			for (CartCalculateRequestDTO.CartItemDTO line : cartItems == null ? List.<CartCalculateRequestDTO.CartItemDTO>of()
					: cartItems) {
				if (line == null || line.getItemId() == null || line.getQuantity() == null || line.getQuantity().signum() <= 0) {
					continue;
				}
				Optional<Item> item = itemRepository.findById(line.getItemId());
				if (!item.isPresent()) {
					continue;
				}
				customer = customer != null ? customer : resolveCustomer(customerId);
				PricingResult pricing = pricingService.calculateItemPrice(item.get(), customer, line.getQuantity(), null);
				PriceCalculateResponseDTO alone = price(item.get(), line.getQuantity(), pricing,
						findBestItemPromotion(item.get(), line.getQuantity(), codes));
				alone.setAcrossLines(false);
				response.getLineResults().add(alone);
			}
			return response;
		}
		Customer customer = resolveCustomer(customerId);
		Map<CartLine, ItemPromotions> resolved = resolveCart(lines, codes);
		for (CartLine line : lines) {
			PricingResult pricing = pricingService.calculateItemPrice(line.item, customer, line.quantity, null);
			PriceCalculateResponseDTO answer = price(line.item, line.quantity, pricing, resolved.get(line));
			PriceCalculateResponseDTO alone = price(line.item, line.quantity, pricing,
					findBestItemPromotion(line.item, line.quantity, codes));
			answer.setAcrossLines(!sameAnswer(answer, alone));
			response.getLineResults().add(answer);
		}
		return response;
	}

	/** Two answers give the line the same price, free pieces and promotion. */
	private static boolean sameAnswer(PriceCalculateResponseDTO a, PriceCalculateResponseDTO b) {
		return Objects.equals(a.getUnitPrice(), b.getUnitPrice())
				&& Objects.equals(a.getDiscountPercentage(), b.getDiscountPercentage())
				&& Objects.equals(a.getDiscountAmount(), b.getDiscountAmount())
				&& Objects.equals(a.getFreeQuantity(), b.getFreeQuantity())
				&& Objects.equals(a.getFreeQuantityAvailable(), b.getFreeQuantityAvailable())
				&& Objects.equals(a.getFreePromotionId(), b.getFreePromotionId())
				&& Objects.equals(a.getPromotionId(), b.getPromotionId())
				&& Objects.equals(a.getSource(), b.getSource());
	}

	// ─── Promo Code Validation ───────────────────────────────────────────────────

	/**
	 * Validate a promo code entered by the cashier.
	 * Returns metadata so the frontend knows which cart items to re-calculate.
	 *
	 * @param code The code entered by the cashier
	 * @return Map with valid=true/false and promotion details when valid
	 */
	public Map<String, Object> validatePromoCode(String code) {
		Map<String, Object> result = new HashMap<>();

		if (!StringUtils.hasText(code)) {
			result.put("valid", false);
			result.put("reason", "EMPTY_CODE");
			return result;
		}

		Optional<Promotion> opt = promotionRepository.findByCode(code.trim());
		if (!opt.isPresent()) {
			result.put("valid", false);
			result.put("reason", "NOT_FOUND");
			return result;
		}

		Promotion p = opt.get();
		LocalDate today = LocalDate.now();

		// Must be active
		if (!Boolean.TRUE.equals(p.getActive())) {
			result.put("valid", false);
			result.put("reason", "INACTIVE");
			return result;
		}

		// Must require a code (auto promotions cannot be entered as promo codes)
		if (!Boolean.TRUE.equals(p.getRequiresCode())) {
			result.put("valid", false);
			result.put("reason", "NOT_A_PROMO_CODE");
			return result;
		}

		// Date validity
		if (p.getStartDate() != null && today.isBefore(p.getStartDate())) {
			result.put("valid", false);
			result.put("reason", "NOT_STARTED");
			return result;
		}
		if (p.getEndDate() != null && today.isAfter(p.getEndDate())) {
			result.put("valid", false);
			result.put("reason", "EXPIRED");
			return result;
		}

		// Valid — return metadata so frontend can decide which items to re-calculate
		result.put("valid", true);
		result.put("promotionId", p.getId());
		result.put("promotionName", p.getName());
		result.put("promotionType", p.getPromotionType().name());
		result.put("scope", p.getScope().name());
		result.put("benefitType", p.getBenefitType().name());
		result.put("itemId", p.getItem() != null ? p.getItem().getId() : null);
		result.put("itemFamilyId", p.getItemFamily() != null ? p.getItemFamily().getId() : null);
		result.put("itemSubFamilyId", p.getItemSubFamily() != null ? p.getItemSubFamily().getId() : null);
		// 3.1.0 (ZEN): by code, as the items carry them
		result.put("itemCategoryCode", p.getItemCategory() != null ? p.getItemCategory().getCode() : null);
		result.put("itemSectionCode", p.getItemSection() != null ? p.getItemSection().getCode() : null);
		result.put("groupItemIds", p.getScope() == PromotionScope.ITEM_GROUP
				? p.getGroupItems().stream().map(Item::getId).collect(Collectors.toList())
				: null);
		result.put("getItemId", p.getGetItem() != null ? p.getGetItem().getId() : null);
		// 3.1.0 (ZEN): the targets on every variant of an ERP item number
		result.put("navItemNo", p.getNavItemNo());
		result.put("groupNavItemNos", p.getScope() == PromotionScope.ITEM_GROUP
				? p.getGroupNavItemNos().stream().sorted().collect(Collectors.toList())
				: null);
		result.put("getNavItemNo", p.getGetNavItemNo());

		return result;
	}

	// ─── Cross-Product Promotions ("buy N of A → benefit on Z") ─────────────────

	/**
	 * Evaluate active cross-product quantity promotions against the cart lines.
	 * Entitlement per promotion (3.1.0, rule 6a) = floor(Q / minimumQuantity), Q the whole pieces of every buy line
	 * (before: the sum of each line's floor). One adjustment per benefit target (an item, or 3.1.0 an ERP item
	 * number): the highest-priority matching promotion wins. 2.2.1: a buy line with a quantity not whole (0.2 L) never
	 * counts. The free pieces (or the entitled units of a discount) go to the benefit lines cheapest unit price first,
	 * then cart order (freeByItemId, entitledByItemId), at most each line's whole pieces: never a piece added.
	 */
	private List<CrossProductAdjustmentDTO> evaluateCrossProductPromotions(
			List<CartCalculateRequestDTO.CartItemDTO> cartItems, List<String> codes,
			LocalDate today, LocalTime now, DayOfWeek todayDay) {

		List<CrossProductAdjustmentDTO> adjustments = new ArrayList<>();
		if (cartItems == null || cartItems.isEmpty()) {
			return adjustments;
		}

		List<Promotion> crossPromos = promotionRepository.findActiveCrossProductPromotions(today);
		if (crossPromos.isEmpty()) {
			return adjustments;
		}

		// Load the cart's items once for buy-target matching (family/subfamily/group)
		List<Long> cartItemIds = cartItems.stream()
				.map(CartCalculateRequestDTO.CartItemDTO::getItemId)
				.filter(id -> id != null)
				.distinct()
				.collect(Collectors.toList());
		Map<Long, Item> itemsById = itemRepository.findAllById(cartItemIds).stream()
				.collect(Collectors.toMap(Item::getId, i -> i));

		Set<String> targetedGetItems = new HashSet<>();

		for (Promotion p : crossPromos) { // already ordered by priority DESC
			if (p.getPromotionType() != com.digithink.zsretail.core.model.enumeration.PromotionType.QUANTITY_PROMOTION) continue;
			if (p.getMinimumQuantity() == null || p.getMinimumQuantity() <= 0) continue;
			// 3.1.0 (ZEN): the benefit on one item, or on any variant of an ERP item number
			String getNavItemNo = p.getGetItem() == null ? blankToNull(p.getGetNavItemNo()) : null;
			if (getNavItemNo == null && (p.getGetItem() == null || p.getGetItem().getId() == null)) continue;
			if (!passesCodeFilter(p, codes)) continue;
			if (!passesTimeFilter(p, now)) continue;
			if (!passesDayFilter(p, todayDay)) continue;

			String getKey = getNavItemNo != null ? "nav:" + getNavItemNo : "id:" + p.getGetItem().getId();
			if (targetedGetItems.contains(getKey)) continue; // best promo per benefit target already found

			int pieces = 0;
			List<CartLine> getLines = new ArrayList<>();
			int index = 0;
			for (CartCalculateRequestDTO.CartItemDTO line : cartItems) {
				int position = index++;
				if (line.getItemId() == null || line.getQuantity() == null || line.getQuantity().signum() <= 0) continue;
				Item cartItem = itemsById.get(line.getItemId());
				// The benefit item itself never counts as buy quantity — otherwise a
				// family/group buy-side containing the get-item would self-trigger
				if (isBenefitLine(p, getNavItemNo, line.getItemId(), cartItem)) {
					// a line of the benefit item counts by its id, even when its item was not read with the cart's
					Item benefit = cartItem != null ? cartItem : p.getGetItem();
					getLines.add(new CartLine(benefit, line.getQuantity(), priceOf(line, benefit), position, false));
					continue;
				}
				if (cartItem != null && matchesBuyTarget(p, cartItem)) {
					// 2.2.1: a decimal line is not eligible for a quantity-based promotion (never a free item on a fraction)
					if (!Quantities.isWhole(line.getQuantity())) continue;
					pieces += line.getQuantity().intValue();
				}
			}
			int entitled = pieces / p.getMinimumQuantity();
			if (entitled <= 0) continue;

			CrossProductAdjustmentDTO adj = new CrossProductAdjustmentDTO();
			adj.setPromotionId(p.getId());
			adj.setPromotionName(p.getName());
			adj.setPromotionCode(p.getCode());
			adj.setBenefitType(p.getBenefitType().name());
			if (getNavItemNo == null) {
				Item getItem = p.getGetItem();
				adj.setGetItemId(getItem.getId());
				adj.setGetItemCode(getItem.getItemCode());
				adj.setGetItemName(getItem.getName());
				adj.setGetItemUnitPrice(getItem.getUnitPrice());
				adj.setGetItemDefaultVat(getItem.getDefaultVAT());
			} else {
				adj.setGetNavItemNo(getNavItemNo);
				Item variant = getLines.isEmpty() ? null : getLines.get(0).item;
				adj.setGetItemName(variant != null ? variant.getName() : navItemName(getNavItemNo));
				adj.setGetItemUnitPrice(variant != null ? variant.getUnitPrice() : null);
				adj.setGetItemDefaultVat(variant != null ? variant.getDefaultVAT() : null);
			}
			adj.setEntitledUnits(entitled);

			if (p.getBenefitType() == PromotionBenefitType.FREE_QUANTITY) {
				int freeUnits = entitled * (p.getFreeQuantity() != null ? p.getFreeQuantity() : 0);
				if (freeUnits <= 0) continue;
				adj.setFreeUnits(freeUnits);
				// Free pieces only when scanned (2026-10-12): the pieces of Y in the cart, at most freeUnits
				adj.setFreeByItemId(byItemId(share(getLines, freeUnits)));
				int free = adj.getFreeByItemId().values().stream().mapToInt(Integer::intValue).sum();
				adj.setFreeQuantity(free);
				adj.setFreeQuantityAvailable(freeUnits - free);
			} else if (p.getBenefitType() == PromotionBenefitType.PERCENTAGE_DISCOUNT) {
				if (p.getDiscountPercentage() == null || p.getDiscountPercentage() <= 0) continue;
				adj.setDiscountPercentage(p.getDiscountPercentage());
				adj.setEntitledByItemId(byItemId(share(getLines, entitled)));
			} else { // FIXED_DISCOUNT
				if (p.getDiscountAmount() == null || p.getDiscountAmount() <= 0) continue;
				adj.setDiscountAmount(p.getDiscountAmount());
				adj.setEntitledByItemId(byItemId(share(getLines, entitled)));
			}

			adjustments.add(adj);
			targetedGetItems.add(getKey);

			log.debug("evaluateCrossProductPromotions: promo '{}' → benefit {} entitled={}",
					p.getName(), getKey, entitled);
		}

		return adjustments;
	}

	/** A line of the promotion's benefit target: its item, or 3.1.0 a variant of its ERP item number. */
	private static boolean isBenefitLine(Promotion p, String getNavItemNo, Long itemId, Item item) {
		if (getNavItemNo != null) {
			return item != null && getNavItemNo.equals(blankToNull(item.getNavItemNo()));
		}
		return itemId.equals(p.getGetItem().getId());
	}

	/** 3.1.0: the name of an ERP item number no line of the cart carries (the hint "scan X"); the number without one. */
	private String navItemName(String navItemNo) {
		List<Object[]> names = itemRepository.findNavItemNames(java.util.Collections.singleton(navItemNo));
		return names.isEmpty() || names.get(0)[1] == null ? navItemNo : (String) names.get(0)[1];
	}

	/**
	 * 3.1.0 (decision 5 of 2026-10-23): units given to lines cheapest unit price first, then cart order, at most each
	 * line's whole pieces (never on a fraction): line → units, the lines given none left out.
	 */
	static Map<CartLine, Integer> share(List<CartLine> lines, int units) {
		Map<CartLine, Integer> given = new LinkedHashMap<>();
		List<CartLine> order = new ArrayList<>(lines);
		order.sort(Comparator.comparingDouble((CartLine line) -> line.price).thenComparingInt(line -> line.index));
		int left = units;
		for (CartLine line : order) {
			if (left <= 0) {
				break;
			}
			int give = Math.min(left, line.pieces());
			if (give > 0) {
				given.put(line, give);
				left -= give;
			}
		}
		return given;
	}

	private static Map<Long, Integer> byItemId(Map<CartLine, Integer> given) {
		Map<Long, Integer> byId = new LinkedHashMap<>();
		given.forEach((line, units) -> byId.merge(line.item.getId(), units, Integer::sum));
		return byId;
	}

	/**
	 * Same-item FREE_QUANTITY "buy N, get M free", repeated for each group of G = N + M pieces (decisions of 2026-10-12:
	 * a free piece is never added on top, it is free only when scanned or read; every group gives its M): of the line's
	 * Q pieces, floor(Q / G) x M + min(M, max(0, (Q mod G) - N)) are free. Buy 2 get 1: Q = 1 to 9 gives 0, 0, 1, 1, 1,
	 * 2, 2, 2, 3. 0 without N or M, and for a quantity not whole.
	 */
	static int freePieces(Promotion p, BigDecimal pieces) {
		int n = p.getMinimumQuantity() != null ? p.getMinimumQuantity() : 0;
		int m = p.getFreeQuantity() != null ? p.getFreeQuantity() : 0;
		if (n <= 0 || m <= 0 || pieces == null || pieces.signum() <= 0 || !Quantities.isWhole(pieces)) {
			return 0;
		}
		int q = pieces.intValue();
		int group = n + m;
		return q / group * m + Math.min(m, Math.max(0, q % group - n));
	}

	/**
	 * The free pieces of the group in progress not scanned yet (the hint "1 more piece is free"): with r = Q mod (N + M),
	 * M - max(0, r - N) once r reaches N, else 0. Buy 2 get 1: Q = 2 or 5 → 1; 3, 4 or 6 → 0. 0 when freePieces would be.
	 */
	static int freeAvailable(Promotion p, BigDecimal pieces) {
		int n = p.getMinimumQuantity() != null ? p.getMinimumQuantity() : 0;
		int m = p.getFreeQuantity() != null ? p.getFreeQuantity() : 0;
		if (n <= 0 || m <= 0 || pieces == null || pieces.signum() <= 0 || !Quantities.isWhole(pieces)) {
			return 0;
		}
		int r = pieces.intValue() % (n + m);
		return r >= n ? m - Math.max(0, r - n) : 0;
	}

	/**
	 * Does this cart item match the promotion's buy-side target (any scope)? 3.1.0 (ZEN): a NAV item number target
	 * matches every variant carrying it; the NAV numbers of a group are read only for an item carrying one.
	 */
	private boolean matchesBuyTarget(Promotion p, Item item) {
		String navItemNo = blankToNull(item.getNavItemNo());
		switch (p.getScope()) {
			case ITEM:
				return p.getItem() != null && p.getItem().getId().equals(item.getId())
						|| navItemNo != null && navItemNo.equals(p.getNavItemNo());
			case ITEM_GROUP:
				return p.getGroupItems().stream()
						.anyMatch(gi -> gi.getId() != null && gi.getId().equals(item.getId()))
						|| navItemNo != null && p.getGroupNavItemNos().contains(navItemNo);
			case ITEM_SUBFAMILY:
				return p.getItemSubFamily() != null && item.getItemSubFamily() != null
						&& p.getItemSubFamily().getId().equals(item.getItemSubFamily().getId());
			case ITEM_FAMILY:
				return p.getItemFamily() != null && item.getItemFamily() != null
						&& p.getItemFamily().getId().equals(item.getItemFamily().getId());
			case SECTION:
				return p.getItemSection() != null && sectionCodeOf(item) != null
					&& sectionCodeOf(item).equals(p.getItemSection().getCode());
			case CATEGORY:
				return p.getItemCategory() != null && categoryCodeOf(item) != null
					&& categoryCodeOf(item).equals(p.getItemCategory().getCode());
			case ALL_ITEMS:
				// Every cart line is a buy line; the caller already skips the get-item's own line
				return true;
			default:
				return false;
		}
	}

	/** 3.1.0 (ZEN): the item's section code; null when it has none (blank counts as none). */
	static String sectionCodeOf(Item item) {
		return blankToNull(item.getSectionCode());
	}

	/** 3.1.0 (ZEN): the item's catégorie code, else its family's; null when neither has one (blank counts as none). */
	static String categoryCodeOf(Item item) {
		String own = blankToNull(item.getCategoryCode());
		if (own != null || item.getItemFamily() == null) {
			return own;
		}
		return blankToNull(item.getItemFamily().getCategoryCode());
	}

	private static String blankToNull(String code) {
		return code == null || code.trim().isEmpty() ? null : code.trim();
	}

	// ─── Private Helpers ────────────────────────────────────────────────────────

	/**
	 * The item's promotions: the one applied, and a free promotion that gives no free piece yet (Q = N) but would take
	 * the line from Q = N + 1, for the hint (null when none).
	 */
	private static final class ItemPromotions {
		Promotion applied;
		Promotion freeHint;
		/**
		 * 3.1.0 (rule 6a), the line counted with others: its share of the group's free pieces, the pieces still to scan
		 * (on the group's last line only) and the hint's; null when the line is priced alone (its own count).
		 */
		Integer free;
		Integer available;
		Integer hintAvailable;
	}

	// ─── 3.1.0: quantity promotions counted across the cart lines (rule 6a, decisions B and C of 2026-10-23) ───

	/** A cart line as the engine counts it: its item, its pieces, its unit price (the free pieces' order), its place. */
	static final class CartLine {
		final Item item;
		final BigDecimal quantity;
		final double price;
		final int index;
		/** The line asked (POST /price-calculate). */
		final boolean self;

		CartLine(Item item, BigDecimal quantity, double price, int index, boolean self) {
			this.item = item;
			this.quantity = quantity;
			this.price = price;
			this.index = index;
			this.self = self;
		}

		/** Its whole pieces; none for a quantity not whole (never counted by a quantity promotion, 2.2.1). */
		int pieces() {
			return Quantities.isWhole(quantity) ? quantity.intValue() : 0;
		}
	}

	/** The cascade's levels, most specific first (decision B): each counts only the lines no earlier level took. */
	private enum Level {
		ROW, NAV_ITEM, GROUP, SECTION, SUB_FAMILY, FAMILY, CATEGORY, ALL_ITEMS
	}

	/**
	 * The cart lines sent, as the engine counts them, in cart order: the line asked (self, when given) with its own
	 * quantity, first when absent from the list. Lines without an item or a quantity are left out. Null when the cart
	 * holds no two items: the line is priced alone, as before.
	 */
	private List<CartLine> cartLines(List<CartCalculateRequestDTO.CartItemDTO> sent, Item self, BigDecimal selfQuantity) {
		if (sent == null || sent.isEmpty()) {
			return null;
		}
		List<CartCalculateRequestDTO.CartItemDTO> usable = sent.stream()
				.filter(line -> line != null && line.getItemId() != null && line.getQuantity() != null
						&& line.getQuantity().signum() > 0)
				.collect(Collectors.toList());
		Set<Long> others = usable.stream().map(CartCalculateRequestDTO.CartItemDTO::getItemId)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (self != null) {
			others.remove(self.getId());
		}
		if (others.size() < (self != null ? 1 : 2)) {
			return null;
		}
		Map<Long, Item> items = new HashMap<>();
		itemRepository.findAllById(others).forEach(item -> items.put(item.getId(), item));
		List<CartLine> lines = new ArrayList<>();
		boolean selfPlaced = false;
		if (self != null && usable.stream().noneMatch(line -> line.getItemId().equals(self.getId()))) {
			lines.add(new CartLine(self, selfQuantity, priceOf(null, self), 0, true));
			selfPlaced = true;
		}
		for (CartCalculateRequestDTO.CartItemDTO line : usable) {
			if (self != null && line.getItemId().equals(self.getId())) {
				if (!selfPlaced) { // the line asked counts once, with its own quantity
					lines.add(new CartLine(self, selfQuantity, priceOf(line, self), lines.size(), true));
					selfPlaced = true;
				}
				continue;
			}
			Item item = items.get(line.getItemId());
			if (item != null) {
				lines.add(new CartLine(item, line.getQuantity(), priceOf(line, item), lines.size(), false));
			}
		}
		return lines;
	}

	/** The unit price the till sent for the line, else the item's; 0 without one. */
	private static double priceOf(CartCalculateRequestDTO.CartItemDTO line, Item item) {
		if (line != null && line.getUnitPrice() != null) {
			return line.getUnitPrice();
		}
		return item.getUnitPrice() != null ? item.getUnitPrice() : 0.0;
	}

	/**
	 * Rule 6a (2026-10-23): every line's promotions with the cart around it. Level by level, most specific first
	 * (decision B), over the lines no earlier level took: a promotion counts Q = the whole pieces of those lines it
	 * targets (its minimum quantity and its free pieces read Q, a line not whole keeps its own quantity: never a
	 * quantity promotion), each line takes its best promotion of the level as the cascade does (priority, then value),
	 * and a line taken leaves the next levels. A free promotion giving at least 1 free piece takes every line it counts
	 * (decision C: a line given 0 is priced at full price, no other promotion); its free pieces go cheapest unit price
	 * first, then cart order, and the pieces still to scan to its last line only. Below N + 1 it takes no line, the
	 * hint as before. Lines of one level that take another promotion of the same level still count in Q.
	 */
	private Map<CartLine, ItemPromotions> resolveCart(List<CartLine> lines, List<String> codes) {
		LocalDate today = LocalDate.now();
		LocalTime now = LocalTime.now();
		DayOfWeek todayDay = today.getDayOfWeek();
		Map<CartLine, ItemPromotions> found = new LinkedHashMap<>();
		lines.forEach(line -> found.put(line, new ItemPromotions()));
		Set<CartLine> hintDone = new HashSet<>();
		Map<Long, List<CartLine>> hintLines = new LinkedHashMap<>();
		Map<Long, Integer> hintAvailable = new HashMap<>();
		List<CartLine> open = new ArrayList<>(lines);

		for (Level level : Level.values()) {
			if (open.isEmpty()) {
				break;
			}
			Map<String, List<Promotion>> reads = new HashMap<>();
			Map<CartLine, List<Promotion>> candidates = new LinkedHashMap<>();
			for (CartLine line : open) {
				candidates.put(line, read(level, line.item, today, reads));
			}
			Map<Long, Integer> counted = new HashMap<>();
			for (List<Promotion> list : candidates.values()) {
				for (Promotion p : list) {
					counted.computeIfAbsent(p.getId(), id -> open.stream().filter(line -> matches(level, p, line.item))
							.mapToInt(CartLine::pieces).sum());
				}
			}
			Map<Long, List<CartLine>> takenBy = new LinkedHashMap<>();
			Map<Long, Promotion> taking = new HashMap<>();
			for (CartLine line : open) {
				Function<Promotion, BigDecimal> quantityOf = p -> line.pieces() > 0
						? BigDecimal.valueOf(counted.getOrDefault(p.getId(), 0)) : line.quantity;
				List<Promotion> list = candidates.get(line);
				ItemPromotions promotions = found.get(line);
				Promotion applied = selectBestFromList(list, quantityOf, codes, now, todayDay, false);
				if (!hintDone.contains(line)) {
					Promotion withZeroFree = selectBestFromList(list, quantityOf, codes, now, todayDay, true);
					if (withZeroFree != null) {
						hintDone.add(line);
						BigDecimal q = quantityOf.apply(withZeroFree);
						if (withZeroFree.getBenefitType() == PromotionBenefitType.FREE_QUANTITY
								&& freePieces(withZeroFree, q) == 0) {
							promotions.freeHint = withZeroFree;
							hintLines.computeIfAbsent(withZeroFree.getId(), id -> new ArrayList<>()).add(line);
							hintAvailable.put(withZeroFree.getId(), freeAvailable(withZeroFree, q));
						}
					}
				}
				if (applied != null) {
					promotions.applied = applied;
					takenBy.computeIfAbsent(applied.getId(), id -> new ArrayList<>()).add(line);
					taking.put(applied.getId(), applied);
				}
			}
			for (Map.Entry<Long, List<CartLine>> taken : takenBy.entrySet()) {
				Promotion p = taking.get(taken.getKey());
				if (p.getBenefitType() == PromotionBenefitType.FREE_QUANTITY) {
					List<CartLine> group = taken.getValue();
					BigDecimal q = BigDecimal.valueOf(counted.getOrDefault(p.getId(), 0));
					Map<CartLine, Integer> shares = share(group, freePieces(p, q));
					CartLine last = group.get(group.size() - 1);
					for (CartLine line : group) {
						found.get(line).free = shares.getOrDefault(line, 0);
						found.get(line).available = line == last ? freeAvailable(p, q) : 0;
					}
				}
				open.removeAll(taken.getValue());
			}
		}
		// The pieces still to scan of a free promotion below its threshold: on its last line only
		hintLines.forEach((promotionId, group) -> {
			CartLine last = group.get(group.size() - 1);
			group.forEach(line -> found.get(line).hintAvailable = line == last ? hintAvailable.get(promotionId) : 0);
		});
		return found;
	}

	/** The promotions of one level for an item, read once per key in a cart (as findBestItemPromotion reads them). */
	private List<Promotion> read(Level level, Item item, LocalDate today, Map<String, List<Promotion>> reads) {
		String navItemNo = blankToNull(item.getNavItemNo());
		switch (level) {
			case ROW:
				return item.getId() == null ? List.of()
						: reads.computeIfAbsent("row:" + item.getId(),
								key -> promotionRepository.findActiveByItemId(item.getId(), today));
			case NAV_ITEM:
				return item.getId() == null || navItemNo == null ? List.of()
						: reads.computeIfAbsent("nav:" + navItemNo,
								key -> promotionRepository.findActiveByNavItemNo(navItemNo, today));
			case GROUP: {
				if (item.getId() == null) {
					return List.of();
				}
				List<Promotion> groups = new ArrayList<>(reads.computeIfAbsent("group:" + item.getId(),
						key -> promotionRepository.findActiveByGroupItemId(item.getId(), today)));
				if (navItemNo != null) {
					groups.addAll(reads.computeIfAbsent("groupNav:" + navItemNo,
							key -> promotionRepository.findActiveByGroupNavItemNo(navItemNo, today)));
				}
				return groups;
			}
			case SECTION: {
				String code = sectionCodeOf(item);
				return code == null ? List.of()
						: reads.computeIfAbsent("section:" + code,
								key -> promotionRepository.findActiveBySectionCode(code, today));
			}
			case SUB_FAMILY:
				return item.getItemSubFamily() == null ? List.of()
						: reads.computeIfAbsent("subFamily:" + item.getItemSubFamily().getId(),
								key -> promotionRepository.findActiveByItemSubFamilyId(item.getItemSubFamily().getId(), today));
			case FAMILY:
				return item.getItemFamily() == null ? List.of()
						: reads.computeIfAbsent("family:" + item.getItemFamily().getId(),
								key -> promotionRepository.findActiveByItemFamilyId(item.getItemFamily().getId(), today));
			case CATEGORY: {
				String code = categoryCodeOf(item);
				return code == null ? List.of()
						: reads.computeIfAbsent("category:" + code,
								key -> promotionRepository.findActiveByCategoryCode(code, today));
			}
			default:
				return reads.computeIfAbsent("all", key -> promotionRepository.findActiveByScope(PromotionScope.ALL_ITEMS, today));
		}
	}

	/** Does the promotion, read at this level, target this item (the lines counted in its Q)? */
	private static boolean matches(Level level, Promotion p, Item item) {
		String navItemNo = blankToNull(item.getNavItemNo());
		switch (level) {
			case ROW:
				return p.getItem() != null && Objects.equals(p.getItem().getId(), item.getId());
			case NAV_ITEM:
				return navItemNo != null && navItemNo.equals(p.getNavItemNo());
			case GROUP:
				return p.getGroupItems().stream().anyMatch(gi -> gi.getId() != null && gi.getId().equals(item.getId()))
						|| navItemNo != null && p.getGroupNavItemNos().contains(navItemNo);
			case SECTION:
				return p.getItemSection() != null && sectionCodeOf(item) != null
						&& sectionCodeOf(item).equals(p.getItemSection().getCode());
			case SUB_FAMILY:
				return p.getItemSubFamily() != null && item.getItemSubFamily() != null
						&& Objects.equals(p.getItemSubFamily().getId(), item.getItemSubFamily().getId());
			case FAMILY:
				return p.getItemFamily() != null && item.getItemFamily() != null
						&& Objects.equals(p.getItemFamily().getId(), item.getItemFamily().getId());
			case CATEGORY:
				return p.getItemCategory() != null && categoryCodeOf(item) != null
						&& categoryCodeOf(item).equals(p.getItemCategory().getCode());
			default:
				return true;
		}
	}

	/**
	 * Find the best promotion for a single item.
	 * Scope priority: ITEM > ITEM_GROUP > SECTION > ITEM_SUBFAMILY > ITEM_FAMILY > CATEGORY > ALL_ITEMS (most specific wins);
	 * 3.1.0, for an item carrying a NAV item number: ITEM on its row > ITEM on its NAV number > its groups (rows and NAV).
	 * Filters are applied per scope: a scope with no eligible candidate falls through to the next one.
	 * Within scope: highest priority wins (ABSOLUTE).
	 * <p>
	 * 2026-10-12: a free promotion takes the line only with at least 1 free piece; the same cascade (each scope read
	 * once) also finds the winner had a free promotion with 0 free piece been allowed: when that winner is such a free
	 * promotion, it is the hint.
	 */
	private ItemPromotions findBestItemPromotion(Item item, BigDecimal quantity, List<String> codes) {
		LocalDate today = LocalDate.now();
		LocalTime now = LocalTime.now();
		DayOfWeek todayDay = today.getDayOfWeek();

		List<Supplier<List<Promotion>>> scopes = new ArrayList<>();
		// 3.1.0 (ZEN): an item carrying a NAV item number also reads the promotions on that number (every variant): right
		// after the ones on its own row, and its groups with NAV numbers at the level of its groups of rows (priority
		// decides between them). An item without one (EMTOP, Happyness) reads exactly what it read before.
		String navItemNo = blankToNull(item.getNavItemNo());
		if (item.getId() != null) {
			// ITEM scope (most specific), then ITEM_GROUP (curated set — more specific than taxonomy, less than direct item)
			scopes.add(() -> promotionRepository.findActiveByItemId(item.getId(), today));
			if (navItemNo != null) {
				scopes.add(() -> promotionRepository.findActiveByNavItemNo(navItemNo, today));
				scopes.add(() -> {
					List<Promotion> groups = new ArrayList<>(promotionRepository.findActiveByGroupItemId(item.getId(), today));
					groups.addAll(promotionRepository.findActiveByGroupNavItemNo(navItemNo, today));
					return groups; // a group holding the row and its NAV number comes twice: same best, harmless
				});
			} else {
				scopes.add(() -> promotionRepository.findActiveByGroupItemId(item.getId(), today));
			}
		}
		// SECTION (3.1.0, ZEN): below the sub-family, so before it; skipped for an item without a section code
		String sectionCode = sectionCodeOf(item);
		if (sectionCode != null) {
			scopes.add(() -> promotionRepository.findActiveBySectionCode(sectionCode, today));
		}
		if (item.getItemSubFamily() != null) {
			scopes.add(() -> promotionRepository.findActiveByItemSubFamilyId(item.getItemSubFamily().getId(), today));
		}
		if (item.getItemFamily() != null) {
			scopes.add(() -> promotionRepository.findActiveByItemFamilyId(item.getItemFamily().getId(), today));
		}
		// CATEGORY (3.1.0, ZEN): above the family, so after it; skipped for an item without a catégorie
		String categoryCode = categoryCodeOf(item);
		if (categoryCode != null) {
			scopes.add(() -> promotionRepository.findActiveByCategoryCode(categoryCode, today));
		}
		// ALL_ITEMS scope (least specific): fallback when no targeted promotion matched
		scopes.add(() -> promotionRepository.findActiveByScope(PromotionScope.ALL_ITEMS, today));

		ItemPromotions found = new ItemPromotions();
		Promotion withZeroFree = null;
		for (Supplier<List<Promotion>> scope : scopes) {
			List<Promotion> candidates = scope.get();
			if (found.applied == null) {
				found.applied = selectBestFromList(candidates, quantity, codes, now, todayDay, false);
			}
			if (withZeroFree == null) {
				withZeroFree = selectBestFromList(candidates, quantity, codes, now, todayDay, true);
			}
			if (found.applied != null && withZeroFree != null) {
				break;
			}
		}
		if (withZeroFree != null && withZeroFree.getBenefitType() == PromotionBenefitType.FREE_QUANTITY
				&& freePieces(withZeroFree, quantity) == 0) {
			found.freeHint = withZeroFree;
		}
		return found;
	}

	/**
	 * From a list of pre-filtered (active + date-valid) promotions for a given scope,
	 * apply remaining filters and return the highest-priority match.
	 * Tiebreaker when priorities are equal: highest discount value wins (percentage → amount → freeQuantity).
	 * 2026-10-12: a free promotion needs its N, and at least 1 free piece unless zeroFreeAllowed (the hint's cascade).
	 */
	private Promotion selectBestFromList(List<Promotion> candidates, BigDecimal quantity,
			List<String> codes, LocalTime now, DayOfWeek todayDay, boolean zeroFreeAllowed) {
		return selectBestFromList(candidates, p -> quantity, codes, now, todayDay, zeroFreeAllowed);
	}

	/** 3.1.0 (rule 6a): each promotion with its own quantity Q (the pieces of the cart lines it counts). */
	private Promotion selectBestFromList(List<Promotion> candidates, Function<Promotion, BigDecimal> quantityOf,
			List<String> codes, LocalTime now, DayOfWeek todayDay, boolean zeroFreeAllowed) {
		return candidates.stream()
				// Cross-product promotions (getItem set) never discount the buy line
				// itself — they are evaluated by the cart pass and applied to the
				// get-item's line. Existing promotions all have getItem = null. 3.1.0: the same for a benefit on a NAV
				// item number (getNavItemNo).
				.filter(p -> p.getGetItem() == null && p.getGetNavItemNo() == null)
				.filter(p -> p.getBenefitType() != PromotionBenefitType.FREE_QUANTITY
						|| (zeroFreeAllowed ? p.getMinimumQuantity() != null && p.getMinimumQuantity() > 0
								&& p.getFreeQuantity() != null && p.getFreeQuantity() > 0
								: freePieces(p, quantityOf.apply(p)) > 0))
				.filter(p -> passesCodeFilter(p, codes))
				.filter(p -> passesQuantityFilter(p, quantityOf.apply(p)))
				.filter(p -> passesTimeFilter(p, now))
				.filter(p -> passesDayFilter(p, todayDay))
				.max(Comparator
						.comparingInt((Promotion p) -> p.getPriority() != null ? p.getPriority() : 0)
						.thenComparingDouble(p -> p.getDiscountPercentage() != null ? p.getDiscountPercentage() : 0.0)
						.thenComparingDouble(p -> p.getDiscountAmount() != null ? p.getDiscountAmount() : 0.0)
						.thenComparingInt(p -> p.getFreeQuantity() != null ? p.getFreeQuantity() : 0))
				.orElse(null);
	}

	/** Auto promotions always pass. Code-required ones need their code in appliedCodes. */
	private boolean passesCodeFilter(Promotion p, List<String> codes) {
		if (!Boolean.TRUE.equals(p.getRequiresCode())) return true;
		return codes != null && codes.stream().anyMatch(c -> c.equalsIgnoreCase(p.getCode()));
	}

	/**
	 * If minimumQuantity is set, the cart quantity must meet or exceed it. 2.2.1: a quantity not whole (0.2 L) is not
	 * eligible for a quantity-based promotion: one with a minimum quantity, or a free quantity. A percentage or a fixed
	 * amount without a minimum applies as in 2.2.0.
	 */
	static boolean passesQuantityFilter(Promotion p, BigDecimal quantity) {
		if (!Quantities.isWhole(quantity)) {
			return (p.getMinimumQuantity() == null || p.getMinimumQuantity() <= 0)
					&& p.getBenefitType() != PromotionBenefitType.FREE_QUANTITY;
		}
		return p.getMinimumQuantity() == null || quantity.compareTo(BigDecimal.valueOf(p.getMinimumQuantity())) >= 0;
	}

	/** If minimumAmount is set, the cart total must meet or exceed it. */
	private boolean passesAmountFilter(Promotion p, Double cartTotal) {
		return p.getMinimumAmount() == null
				|| (cartTotal != null && cartTotal >= p.getMinimumAmount());
	}

	/** If timeStart/timeEnd are set, current time must be within the window. */
	private boolean passesTimeFilter(Promotion p, LocalTime now) {
		if (p.getTimeStart() == null && p.getTimeEnd() == null) return true;
		if (p.getTimeStart() != null && now.isBefore(p.getTimeStart())) return false;
		if (p.getTimeEnd() != null && now.isAfter(p.getTimeEnd())) return false;
		return true;
	}

	/** If dayOfWeek is set, today's day must be in the comma-separated list. */
	private boolean passesDayFilter(Promotion p, DayOfWeek todayDay) {
		if (!StringUtils.hasText(p.getDayOfWeek())) return true;
		return Arrays.stream(p.getDayOfWeek().split(","))
				.map(String::trim)
				.anyMatch(d -> d.equalsIgnoreCase(todayDay.name()));
	}

	/** Set the appropriate discount field on the response based on the promotion's benefit type. */
	private void applyItemPromotionBenefit(PriceCalculateResponseDTO response, Promotion p, double basePrice) {
		response.setUnitPrice(basePrice);
		switch (p.getBenefitType()) {
			case PERCENTAGE_DISCOUNT:
				response.setDiscountPercentage(p.getDiscountPercentage());
				break;
			case FIXED_DISCOUNT:
				response.setDiscountAmount(p.getDiscountAmount());
				break;
			case FREE_QUANTITY:
				// the free pieces of the line: calculateItemPrice, from its quantity (freePieces)
				break;
		}
	}

	/** Load customer by ID; fall back to default customer if not found. */
	private Customer resolveCustomer(Long customerId) {
		if (customerId != null) {
			Optional<Customer> customer = customerRepository.findById(customerId);
			if (customer.isPresent()) return customer.get();
		}
		return customerService.getDefaultCustomer();
	}
}
