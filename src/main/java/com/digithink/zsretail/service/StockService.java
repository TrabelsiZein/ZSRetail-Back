package com.digithink.zsretail.service;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.exception.InsufficientStockException;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.StockBatchRepository;
import com.digithink.zsretail.utils.Quantities;

import lombok.extern.log4j.Log4j2;

/**
 * Single point of truth for all inventory (stock) updates in the system.
 * <p>
 * All stock changes—sales, returns, purchases, and future adjustments—must go through this service.
 * Uses atomic database updates only (single UPDATE per item) so that concurrent transactions
 * (e.g. two tickets for the same item at the same time, or a sale and a purchase simultaneously)
 * cannot produce wrong stock values (no lost updates).
 * <p>
 * When the supply is the ERP's ({@link ApplicationModeService#isSupplyFromErp()} is true),
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

	@Autowired
	private StockBatchRepository stockBatchRepository;

	/**
	 * Decrement stock for a sale (one completed ticket line). Called once per item line.
	 * When ALLOW_NEGATIVE_STOCK=true in GeneralSetup, stock is decremented unconditionally (may go negative).
	 * When false (default), throws {@link InsufficientStockException} if stock would go negative.
	 * No-op when the supply is the ERP's.
	 *
	 * @param itemId   item id
	 * @param quantity quantity sold (positive)
	 * @throws InsufficientStockException if stock is insufficient and negative stock is not allowed
	 */
	@Transactional(rollbackFor = Exception.class)
	public void decrementForSale(Long itemId, BigDecimal quantity) {
		if (applicationModeService.isSupplyFromErp()) {
			return;
		}
		if (quantity == null || quantity.signum() <= 0) {
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
				throw new InsufficientStockException(itemId, quantity, null);
			}
			log.debug("Stock decremented for sale: itemId={}, quantity={}", itemId, quantity);
		}
	}

	/**
	 * Increment stock for a return. Called once per return line.
	 * No-op when the supply is the ERP's.
	 *
	 * @param itemId   item id
	 * @param quantity quantity returned (positive)
	 */
	@Transactional(rollbackFor = Exception.class)
	public void incrementForReturn(Long itemId, BigDecimal quantity) {
		if (applicationModeService.isSupplyFromErp()) {
			return;
		}
		if (quantity == null || quantity.signum() <= 0) {
			return;
		}
		itemRepository.addToStockQuantity(itemId, quantity);
		log.debug("Stock incremented for return: itemId={}, quantity={}", itemId, quantity);
	}

	/**
	 * Increment stock for a purchase. Called once per purchase line.
	 * No-op when the supply is the ERP's.
	 *
	 * @param itemId   item id
	 * @param quantity quantity purchased (positive)
	 */
	@Transactional(rollbackFor = Exception.class)
	public void incrementForPurchase(Long itemId, BigDecimal quantity) {
		if (applicationModeService.isSupplyFromErp()) {
			return;
		}
		if (quantity == null || quantity.signum() <= 0) {
			return;
		}
		itemRepository.addToStockQuantity(itemId, quantity);
		log.debug("Stock incremented for purchase: itemId={}, quantity={}", itemId, quantity);
	}

	/**
	 * Adjust stock by a delta (positive or negative). Used for inventory count, correction, damage.
	 * No-op when the supply is the ERP's.
	 *
	 * @param itemId item id
	 * @param delta  quantity to add (positive) or subtract (negative)
	 * @param reason reason code: COUNT, CORRECTION, DAMAGE (for audit/logging)
	 */
	@Transactional(rollbackFor = Exception.class)
	public void adjustStock(Long itemId, BigDecimal delta, String reason) {
		if (applicationModeService.isSupplyFromErp()) {
			return;
		}
		if (delta == null || delta.signum() == 0) {
			return;
		}
		itemRepository.addToStockQuantity(itemId, delta);
		log.debug("Stock adjusted: itemId={}, delta={}, reason={}", itemId, delta, reason);
	}

	/**
	 * Inventory count: adds each difference (item id to counted minus the stock read) to the item's stock, in JDBC
	 * batches of the same atomic relative update as {@link #adjustStock} (a sale between the read and the update stays
	 * counted: the movements always add up to the stock). Zero differences are skipped. No-op when the supply is the ERP's.
	 */
	@Transactional(rollbackFor = Exception.class)
	public void applyInventoryDifferences(Map<Long, Integer> differences) {
		if (applicationModeService.isSupplyFromErp()) {
			return;
		}
		Map<Long, BigDecimal> nonZero = new java.util.LinkedHashMap<>();
		differences.forEach((itemId, delta) -> {
			if (delta != null && delta != 0) {
				nonZero.put(itemId, Quantities.of(delta));
			}
		});
		if (!nonZero.isEmpty()) {
			stockBatchRepository.addToStockQuantities(nonZero);
		}
		log.debug("Stock adjusted by an inventory count: {} items", nonZero.size());
	}

	/** True when ALLOW_NEGATIVE_STOCK=true in GeneralSetup: a sale or a BL may take the stock below zero. */
	public boolean isNegativeStockAllowed() {
		return "true".equalsIgnoreCase(generalSetupService.findValueByCode("ALLOW_NEGATIVE_STOCK"));
	}

	/**
	 * Step 7A: goods leave the head office stock with a BL (one call per line, at its validation). Same rule as a sale:
	 * with ALLOW_NEGATIVE_STOCK=true the stock is decremented unconditionally; otherwise only when it is sufficient, in
	 * one atomic update. Returns false (nothing changed) when the stock is not sufficient; true otherwise. No-op (true)
	 * when the supply is the ERP's.
	 */
	@Transactional(rollbackFor = Exception.class)
	public boolean decrementForDelivery(Long itemId, BigDecimal quantity) {
		if (applicationModeService.isSupplyFromErp() || quantity == null || quantity.signum() <= 0) {
			return true;
		}
		if (isNegativeStockAllowed()) {
			itemRepository.decrementStockQuantityUnconditional(itemId, quantity);
		} else if (itemRepository.decrementStockQuantityIfSufficient(itemId, quantity) == 0) {
			return false;
		}
		log.debug("Stock decremented for a BL: itemId={}, quantity={}", itemId, quantity);
		return true;
	}

	/**
	 * Step 7A: goods received by a store with a BL (one call per line, at its confirmation). No-op when the supply is the
	 * ERP's.
	 */
	@Transactional(rollbackFor = Exception.class)
	public void incrementForDelivery(Long itemId, BigDecimal quantity) {
		if (applicationModeService.isSupplyFromErp() || quantity == null || quantity.signum() <= 0) {
			return;
		}
		itemRepository.addToStockQuantity(itemId, quantity);
		log.debug("Stock incremented for a BL: itemId={}, quantity={}", itemId, quantity);
	}
}
