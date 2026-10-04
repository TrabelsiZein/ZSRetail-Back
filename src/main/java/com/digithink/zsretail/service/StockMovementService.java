package com.digithink.zsretail.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.StockMovement;
import com.digithink.zsretail.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.model.enumeration.StockMovementType;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.StockMovementRepository;

import lombok.extern.log4j.Log4j2;

/**
 * Records every stock quantity change as an immutable audit row in stock_movement.
 * Must be called alongside StockService (which updates the actual item quantity).
 * Only meaningful when the stock is kept here — all methods are no-ops when the supply is the ERP's,
 * mirroring the behaviour of StockService.
 */
@Service
@Log4j2
public class StockMovementService {

    @Autowired
    private ApplicationModeService applicationModeService;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ItemRepository itemRepository;

    /**
     * Record a stock movement for a completed sale line (OUT).
     */
    @Transactional
    public void recordSale(Long itemId, int quantity,
                           Double unitPriceHt, Integer vatPercent, Double unitPriceTtc,
                           Long salesHeaderId, CashierSession session) {
        if (applicationModeService.isSupplyFromErp()) return;
        StockMovement movement = build(
                itemId, StockMovementType.SALE, StockMovementDirection.OUT,
                quantity, unitPriceHt, vatPercent, unitPriceTtc,
                salesHeaderId, "SALE", session, null);
        stockMovementRepository.save(movement);
        log.debug("Stock movement recorded: SALE OUT itemId={} qty={}", itemId, quantity);
    }

    /**
     * Record a stock movement for a simple return line (IN — cash refund).
     */
    @Transactional
    public void recordSimpleReturn(Long itemId, int quantity,
                                   Double unitPriceHt, Integer vatPercent, Double unitPriceTtc,
                                   Long returnHeaderId, CashierSession session) {
        if (applicationModeService.isSupplyFromErp()) return;
        StockMovement movement = build(
                itemId, StockMovementType.CUSTOMER_RETURN_SIMPLE, StockMovementDirection.IN,
                quantity, unitPriceHt, vatPercent, unitPriceTtc,
                returnHeaderId, "RETURN", session, null);
        stockMovementRepository.save(movement);
        log.debug("Stock movement recorded: CUSTOMER_RETURN_SIMPLE IN itemId={} qty={}", itemId, quantity);
    }

    /**
     * Record a stock movement for a voucher return line (IN — customer gets voucher).
     */
    @Transactional
    public void recordVoucherReturn(Long itemId, int quantity,
                                    Double unitPriceHt, Integer vatPercent, Double unitPriceTtc,
                                    Long returnHeaderId, CashierSession session) {
        if (applicationModeService.isSupplyFromErp()) return;
        StockMovement movement = build(
                itemId, StockMovementType.CUSTOMER_RETURN_VOUCHER, StockMovementDirection.IN,
                quantity, unitPriceHt, vatPercent, unitPriceTtc,
                returnHeaderId, "RETURN", session, null);
        stockMovementRepository.save(movement);
        log.debug("Stock movement recorded: CUSTOMER_RETURN_VOUCHER IN itemId={} qty={}", itemId, quantity);
    }

    /**
     * Record a stock movement for a purchase reception line (IN).
     */
    @Transactional
    public void recordPurchase(Long itemId, int quantity,
                               Double unitPriceHt, Integer vatPercent, Double unitPriceTtc,
                               Long purchaseHeaderId) {
        if (applicationModeService.isSupplyFromErp()) return;
        StockMovement movement = build(
                itemId, StockMovementType.PURCHASE_RECEPTION, StockMovementDirection.IN,
                quantity, unitPriceHt, vatPercent, unitPriceTtc,
                purchaseHeaderId, "PURCHASE", null, null);
        stockMovementRepository.save(movement);
        log.debug("Stock movement recorded: PURCHASE_RECEPTION IN itemId={} qty={}", itemId, quantity);
    }

    /**
     * Record a manual adjustment (positive delta = IN, negative delta = OUT).
     */
    @Transactional
    public void recordAdjustment(Long itemId, int delta, String notes) {
        if (applicationModeService.isSupplyFromErp()) return;
        if (delta == 0) return;
        StockMovementType type = delta > 0 ? StockMovementType.ADJUSTMENT_IN : StockMovementType.ADJUSTMENT_OUT;
        StockMovementDirection direction = delta > 0 ? StockMovementDirection.IN : StockMovementDirection.OUT;
        StockMovement movement = build(
                itemId, type, direction,
                Math.abs(delta), null, null, null,
                null, "ADJUSTMENT", null, notes);
        stockMovementRepository.save(movement);
        log.debug("Stock movement recorded: {} itemId={} delta={}", type, itemId, delta);
    }

    /**
     * Step 7A: a BL line leaving the head office stock (OUT, at the validation). Reference: the BL (ho_delivery.id),
     * reference type BL, its number as note. No price on a BL.
     */
    @Transactional
    public void recordDeliveryOut(Long itemId, int quantity, Long deliveryId, String deliveryNumber) {
        if (applicationModeService.isSupplyFromErp()) return;
        if (quantity <= 0) return;
        stockMovementRepository.save(build(
                itemId, StockMovementType.DELIVERY_OUT, StockMovementDirection.OUT,
                quantity, null, null, null,
                deliveryId, "BL", null, deliveryNumber));
        log.debug("Stock movement recorded: DELIVERY_OUT itemId={} qty={}", itemId, quantity);
    }

    /**
     * Step 7A: a BL line received by a store (IN, at its confirmation). Reference: the store's received BL
     * (hol_delivery.id), reference type BL, its number as note.
     */
    @Transactional
    public void recordDeliveryIn(Long itemId, int quantity, Long receivedDeliveryId, String deliveryNumber) {
        if (applicationModeService.isSupplyFromErp()) return;
        if (quantity <= 0) return;
        stockMovementRepository.save(build(
                itemId, StockMovementType.DELIVERY_IN, StockMovementDirection.IN,
                quantity, null, null, null,
                receivedDeliveryId, "BL", null, deliveryNumber));
        log.debug("Stock movement recorded: DELIVERY_IN itemId={} qty={}", itemId, quantity);
    }

    // -------------------------------------------------------------------------

    private StockMovement build(Long itemId, StockMovementType type, StockMovementDirection direction,
                                int quantity, Double unitPriceHt, Integer vatPercent, Double unitPriceTtc,
                                Long referenceId, String referenceType,
                                CashierSession session, String notes) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

        StockMovement m = new StockMovement();
        m.setMovementType(type);
        m.setDirection(direction);
        m.setItem(item);
        m.setQuantity(quantity);
        m.setUnitPriceHt(unitPriceHt);
        m.setVatPercent(vatPercent);
        m.setUnitPriceTtc(unitPriceTtc);
        m.setReferenceId(referenceId);
        m.setReferenceType(referenceType);
        m.setCashierSession(session);
        m.setNotes(notes);
        return m;
    }
}
