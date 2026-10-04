package com.digithink.zsretail.holink.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 6: the last value of a store right received with a heartbeat answer (MAY_CHANGE_PRICES,
 * CAN_PURCHASE), kept so the store applies it after a restart and while the head office is unreachable. No row: never
 * received, the right is off. Written only when the value changes.
 */
@Entity
@Table(name = "hol_link_right", uniqueConstraints = @UniqueConstraint(name = "uk_hol_link_right_code",
		columnNames = "code"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class LinkRight extends _BaseEntity {

	@Column(nullable = false, length = 50)
	private String code;

	@Column(nullable = false)
	private Boolean granted;

	/** Store clock of the heartbeat answer that brought this value. */
	@Column(name = "received_at")
	private LocalDateTime receivedAt;
}
