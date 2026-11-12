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

	private String description;

	private Boolean requiresConfirmation = false;

	private Boolean active = true;

	private Boolean requireTitleNumber = false;

	private Boolean requireDueDate = false;

	private Boolean requireDrawerName = false;

	private Boolean requireIssuingBank = false;

	private Integer displayOrder = 0; // Order of payment method to display in POS
}
