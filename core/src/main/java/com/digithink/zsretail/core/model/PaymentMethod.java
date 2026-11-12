package com.digithink.zsretail.core.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;

import com.digithink.zsretail.core.model.enumeration.PaymentMethodBehaviour;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Payment method entity - payment methods accepted in POS
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class PaymentMethod extends _BaseEntity {

	@Column(nullable = false, unique = true)
	private String code;

	@Column(nullable = false)
	private String name;

	/**
	 * The fixed method the code knows; null for a method the admin creates (3.2.0). One row per type: the filtered
	 * unique index ux_payment_method_type (PaymentMethodsUpgrade), not a JPA constraint, which would refuse two nulls.
	 */
	@Enumerated(EnumType.STRING)
	private PaymentMethodType type;

	/** 3.2.0: what the till asks for this method; null is AMOUNT. */
	@Enumerated(EnumType.STRING)
	private PaymentMethodBehaviour behaviour = PaymentMethodBehaviour.AMOUNT;

	/** 3.2.0: the exact length of the N° titre when the method requires one; null: any length. */
	private Integer titleNumberLength;

	/**
	 * 3.2.0, step 3b: the method's payments go to the ERP's payment export (a header per code, the class; lines);
	 * replaces ERP_SKIP_CHEQUE_PAYMENTS. Never on the return voucher. Null is true (SessionExportService.sentToErp).
	 */
	private Boolean sendToErp = true;

	private String description;

	private Boolean requiresConfirmation = false;

	private Boolean active = true;

	private Boolean requireTitleNumber = false;

	/**
	 * 3.2.0, step 2: the N° titre asked but optional (a card on a terminal: the slip number); with requireTitleNumber,
	 * the three states: off (both false), optional (this one), required (requireTitleNumber). Null is false.
	 */
	private Boolean askTitleNumber = false;

	private Boolean requireDueDate = false;

	private Boolean requireDrawerName = false;

	private Boolean requireIssuingBank = false;

	private Integer displayOrder = 0; // Order of payment method to display in POS
}
