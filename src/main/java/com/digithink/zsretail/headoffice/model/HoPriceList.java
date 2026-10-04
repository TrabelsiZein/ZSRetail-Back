package com.digithink.zsretail.headoffice.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Head office plan, task 6.4: a selling price list of the head office (TOURIST, AIRPORT). It holds only the items whose
 * price differs from the base price (item.unitPrice). A store has one list or none (ho_store.selling_price_list_id); the
 * store never sees the list, it receives one price per item. Head office only data (prefix ho_). {@code active} from
 * {@link _BaseEntity}: a list used by a store cannot be deactivated or deleted.
 */
@Entity
@Table(name = "ho_price_list")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoPriceList extends _BaseEntity {

	public static final int CODE_LENGTH = 50;

	/** Trimmed, uppercase, unique. Cannot be changed after creation. */
	@Column(nullable = false, unique = true, length = CODE_LENGTH)
	private String code;

	@Column(nullable = false)
	private String name;
}
