package com.digithink.zsretail.store.rfid.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.ForeignKey;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ReturnLine;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * RFID POS step: one tag of a ticket's line. A pending ticket holds its tags here (rfid_tag does not change while a
 * ticket is open); a completed ticket keeps them, with its paid sales line, and those of its tags that were still in
 * stock became SOLD. Keyed on the ticket and the item, not on the line: the lines of a pending ticket are deleted and
 * made again at each save and at its completion (SalesHeaderService). returnLine: the return that put the tag back in
 * stock. docs/modules/rfid.md, "POS".
 */
@Entity
@Table(name = "rfid_sale_tag",
		uniqueConstraints = @UniqueConstraint(name = "uk_rfid_sale_tag_header_epc", columnNames = { "sales_header_id", "epc" }),
		indexes = @Index(name = "ix_rfid_sale_tag_epc", columnList = "epc"))
@Data
@EqualsAndHashCode(callSuper = false)
@ToString(exclude = { "salesHeader", "item", "salesLine", "returnLine" })
@NoArgsConstructor
public class RfidSaleTag extends _BaseEntity {

	@JsonIgnore
	@ManyToOne(optional = false)
	@JoinColumn(name = "sales_header_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rfid_sale_tag_header"))
	private SalesHeader salesHeader;

	@JsonIgnore
	@ManyToOne(optional = false)
	@JoinColumn(name = "item_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rfid_sale_tag_item"))
	private Item item;

	/** The tag's EPC, as rfid_tag keeps it. */
	@Column(name = "epc", nullable = false, length = RfidTag.MAX_EPC_LENGTH)
	private String epc;

	/** The paid line of the item, set when the ticket is completed (never on a pending ticket, whose lines change). */
	@JsonIgnore
	@ManyToOne
	@JoinColumn(name = "sales_line_id", foreignKey = @ForeignKey(name = "fk_rfid_sale_tag_line"))
	private SalesLine salesLine;

	/** The return line that put the tag back in stock; null while it is sold. */
	@JsonIgnore
	@ManyToOne
	@JoinColumn(name = "return_line_id", foreignKey = @ForeignKey(name = "fk_rfid_sale_tag_return_line"))
	private ReturnLine returnLine;
}
