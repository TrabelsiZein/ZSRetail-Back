package com.digithink.zsretail.model;

import java.math.BigDecimal;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.digithink.zsretail.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.model.enumeration.StockMovementType;
import com.digithink.zsretail.utils.Quantities;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Stock movement entity - immutable audit log of every stock quantity change.
 * Records are never updated or deleted; all corrections create new rows.
 * Only active when the stock is kept here (the ERP manages it otherwise).
 */
@Entity
@Table(name = "stock_movement")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class StockMovement extends _BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private StockMovementType movementType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private StockMovementDirection direction;

    @ManyToOne
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    /**
     * Always a positive number; direction (IN/OUT) determines the sign. 2.2.1: up to 3 decimals; read through
     * {@link #getQuantity()}, so 2.000 travels as 2.
     */
    @Column(nullable = false, precision = Quantities.PRECISION, scale = Quantities.SCALE)
    private BigDecimal quantity;

    /** Unit price excluding VAT at the time of the movement */
    @Column(name = "unit_price_ht")
    private Double unitPriceHt;

    /** VAT rate applied at the time of the movement (e.g. 19) */
    @Column(name = "vat_percent")
    private Integer vatPercent;

    /** Unit price including VAT at the time of the movement */
    @Column(name = "unit_price_ttc")
    private Double unitPriceTtc;

    /** ID of the source document (SalesHeader, ReturnHeader, PurchaseHeader) */
    @Column(name = "reference_id")
    private Long referenceId;

    /** Type of the source document: SALE, RETURN, PURCHASE, ADJUSTMENT */
    @Column(name = "reference_type", length = 20)
    private String referenceType;

    /** The cashier session during which this movement occurred (null for purchases/adjustments) */
    @ManyToOne
    @JoinColumn(name = "cashier_session_id")
    @JsonIgnore
    private CashierSession cashierSession;

    /** Free-text note — mandatory for adjustments, optional otherwise */
    @Column(length = 500)
    private String notes;

    /** The quantity without trailing zeros (the column has 3 decimals): 2.000 is 2, 0.200 is 0.2. */
    public BigDecimal getQuantity() {
        return Quantities.normalize(quantity);
    }
}
