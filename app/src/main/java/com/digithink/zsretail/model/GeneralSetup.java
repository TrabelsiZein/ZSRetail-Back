package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;

import com.digithink.zsretail.model.enumeration.ConfigType;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * GeneralSetup entity - represents system configuration settings
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class GeneralSetup extends _BaseEntity {

	@Column(nullable = false, unique = true)
	private String code;

	@Column(nullable = false)
	private String valeur;

	private String description;

	@Column(nullable = false)
	private Boolean readOnly = false;

	/**
	 * Data type of this config entry. Drives the input control rendered in the
	 * admin UI (toggle, number input, text input, select, or read-only datetime).
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "config_type", nullable = false)
	private ConfigType configType = ConfigType.STRING;

	/**
	 * Comma-separated list of valid option values for SELECT type entries.
	 * Null/empty for all other types.
	 */
	@Column(name = "config_options")
	private String configOptions;
}
