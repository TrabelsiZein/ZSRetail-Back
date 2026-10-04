package com.digithink.zsretail.model.enumeration;

public enum StockMovementType {
    SALE,
    PURCHASE_RECEPTION,
    CUSTOMER_RETURN_SIMPLE,
    CUSTOMER_RETURN_VOUCHER,
    ADJUSTMENT_IN,
    ADJUSTMENT_OUT,
    OPENING_STOCK,
    /** Step 7A: goods leaving the head office stock with a BL (at its validation). */
    DELIVERY_OUT,
    /** Step 7A: goods received by a store with a BL (at its confirmation). */
    DELIVERY_IN
}
