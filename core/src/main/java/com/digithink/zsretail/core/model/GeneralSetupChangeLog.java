package com.digithink.zsretail.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.digithink.zsretail.core.model.enumeration.GeneralSetupChangeSource;
import com.digithink.zsretail.core.model.enumeration.GeneralSetupChangeType;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "general_setup_change_log")
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class GeneralSetupChangeLog extends _BaseEntity {

	@ManyToOne(optional = false)
	@JoinColumn(name = "general_setup_id", nullable = false)
	private GeneralSetup generalSetup;

	@Column(nullable = false, length = 128)
	private String code;

	@Column(name = "old_value", columnDefinition = "NVARCHAR(MAX)")
	private String oldValue;

	@Column(name = "new_value", columnDefinition = "NVARCHAR(MAX)")
	private String newValue;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private GeneralSetupChangeType changeType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private GeneralSetupChangeSource source;

	@Column(columnDefinition = "NVARCHAR(512)")
	private String reason;
}
