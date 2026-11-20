package com.digithink.zsretail.core.communication.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.hibernate.annotations.Nationalized;

import com.digithink.zsretail.core.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 3.3.0, communication module, step 2: the module's own state, one row per code. ACTIVE_SINCE: the instant of the
 * first start with the module on (time_value), written once and never moved. A finder's cursor (e.g. TICKET_CURSOR):
 * the change time and the id of the last document read (time_value, id_value). See docs/modules/communication.md.
 */
@Entity
@Table(name = "comm_state", uniqueConstraints = @UniqueConstraint(name = "uk_comm_state_code", columnNames = "code"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class CommunicationState extends _BaseEntity {

	@Nationalized
	@Column(name = "code", nullable = false, length = 50)
	private String code;

	@Column(name = "time_value")
	private LocalDateTime timeValue;

	@Column(name = "id_value")
	private Long idValue;
}
