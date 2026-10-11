package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;

import com.digithink.zsretail.model.enumeration.PaymentMethodType;

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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, unique = true)
	private PaymentMethodType type;

	private String description;

	private Boolean requiresConfirmation = false;

	private Boolean active = true;

	private Boolean requireTitleNumber = false;

	private Boolean requireDueDate = false;

	private Boolean requireDrawerName = false;

	private Boolean requireIssuingBank = false;

	private Integer displayOrder = 0; // Order of payment method to display in POS
}
