package com.digithink.zsretail.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.exception.InsufficientStockException;
import com.digithink.zsretail.repository.ItemRepository;

import lombok.extern.log4j.Log4j2;

/**
 * Single point of truth for all inventory (stock) updates in the system.
 * <p>
 * All stock changes—sales, returns, purchases, and future adjustments—must go through this service.
 * Uses atomic database updates only (single UPDATE per item) so that concurrent transactions
 * (e.g. two tickets for the same item at the same time, or a sale and a purchase simultaneously)
 * cannot produce wrong stock values (no lost updates).
 * <p>
 * When the application is not in standalone mode ({@link ApplicationModeService#isStandalone()} is false),
 * all methods are no-ops: stock is not updated. In ERP mode, inventory is typically managed by the ERP.
 */
@Service
@Log4j2
public class StockService {

	@Autowired
	private ApplicationModeService applicationModeService;

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private GeneralSetupService generalSetupService;

	/**
	 * Decrement stock for a sale (one completed ticket line). Called once per item line.
	 * When ALLOW_NEGATIVE_STOCK=true in GeneralSetup, stock is decremented unconditionally (may go negative).
	 * When false (default), throws {@link InsufficientStockException} if stock would go negative.
	 * No-op when not in standalone mode.
	 *
	 * @param itemId   item id
	 * @param quantity quantity sold (positive)
	 * @throws InsufficientStockException if stock is insufficient and negative stock is not allowed
	 */
	@Transactional(rollbackFor = Exception.class)
	public void decrementForSale(Long itemId, int quantity) {
		if (!applicationModeService.isStandalone()) {
			return;
		}
		if (quantity <= 0) {
			return;
		}
		boolean allowNegative = "true".equalsIgnoreCase(
				generalSetupService.findValueByCode("ALLOW_NEGATIVE_STOCK"));
		if (allowNegative) {
			itemRepository.decrementStockQuantityUnconditional(itemId, quantity);
			log.debug("Stock decremented for sale (negative allowed): itemId={}, quantity={}", itemId, quantity);
		} else {
			int updated = itemRepository.decrementStockQuantityIfSufficient(itemId, quantity);
			if (updated == 0) {
				log.warn("Insufficient stock for sale: itemId={}, quantity={}", itemId, quantity);
				throw new InsufficientStockException(itemId, quantity, -1);
			}
			log.debug("Stock decremented for sale: itemId={}, quantity={}", itemId, quantity);
		}
	}

	/**
	 * Increment stock for a return. Called once per return line.
	 * No-op when not in standalone mode.
	 *
	 * @param itemId   item id
	 * @param quantity quantity returned (positive)
	 */
	@Transactional(rollbackFor = Exception.class)
	public void incrementForReturn(Long itemId, int quantity) {
		if (!applicationModeService.isStandalone()) {
			return;
		}
		if (quantity <= 0) {
			return;
		}
		itemRepository.addToStockQuantity(itemId, quantity);
		log.debug("Stock incremented for return: itemId={}, quantity={}", itemId, quantity);
	}

	/**
	 * Increment stock for a purchase. Called once per purchase line.
	 * No-op when not in standalone mode.
	 *
	 * @param itemId   item id
	 * @param quantity quantity purchased (positive)
	 */
	@Transactional(rollbackFor = Exception.class)
	public void incrementForPurchase(Long itemId, int quantity) {
		if (!applicationModeService.isStandalone()) {
			return;
		}
		if (quantity <= 0) {
			return;
		}
		itemRepository.addToStockQuantity(itemId, quantity);
		log.debug("Stock incremented for purchase: itemId={}, quantity={}", itemId, quantity);
	}

	/**
	 * Adjust stock by a delta (positive or negative). Used for inventory count, correction, damage.
	 * No-op when not in standalone mode.
	 *
	 * @param itemId item id
	 * @param delta  quantity to add (positive) or subtract (negative)
	 * @param reason reason code: COUNT, CORRECTION, DAMAGE (for audit/logging)
	 */
	@Transactional(rollbackFor = Exception.class)
	public void adjustStock(Long itemId, int delta, String reason) {
		if (!applicationModeService.isStandalone()) {
			return;
		}
		if (delta == 0) {
			return;
		}
		itemRepository.addToStockQuantity(itemId, delta);
		log.debug("Stock adjusted: itemId={}, delta={}, reason={}", itemId, delta, reason);
	}
}
