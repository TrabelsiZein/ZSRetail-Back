package com.digithink.zsretail.core.dto.report;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import lombok.NoArgsConstructor;

/** 3.1.2, one movement of an item's stock card (GET /report/stock-movements/item/{id}). */
@Data
@NoArgsConstructor
public class StockCardLineDTO {
    private Long id;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime date;
    /** StockMovementType: SALE, PURCHASE_RECEPTION, INVENTORY_IN... (never COST_ADJUSTMENT). */
    private String type;
    /** IN or OUT. */
    private String direction;
    private BigDecimal quantity;
    /** The item's stock right after this movement. */
    private BigDecimal balanceAfter;
    /** SALE, RETURN, PURCHASE, BL, INVENTORY, ADJUSTMENT, OPENING_STOCK, or null. */
    private String referenceType;
    private Long referenceId;
    /**
     * The document's number as printed: the ticket's, the return's, the purchase's, the BL's, the inventory count's;
     * null when unknown (the page shows the id).
     */
    private String reference;
    /** The movement's note when it is not the document number (an adjustment's reason, an opening stock's origin). */
    private String note;
    /** Who made it: the user, or for a till movement written by "System" the cashier of its session. */
    private String user;
}
