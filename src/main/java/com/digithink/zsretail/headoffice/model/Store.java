package com.digithink.zsretail.headoffice.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;

import com.digithink.zsretail.headoffice.enumeration.StoreKind;
import com.digithink.zsretail.model._BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A store known to the head office (head office design 4.6). Head office only: the ho_store table also exists in
 * a store database, where it stays empty. {@code active} comes from {@link _BaseEntity}.
 */
@Entity
@Table(name = "ho_store")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class Store extends _BaseEntity {

	/** Length of the app_version column (the JPA default); a longer version sent by a store is cut. */
	public static final int APP_VERSION_LENGTH = 255;

	/** The store's DEFAULT_LOCATION value: trimmed, uppercase, unique. Cannot be changed after creation. */
	@Column(nullable = false, unique = true, length = 50)
	private String code;

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private StoreKind kind = StoreKind.OWN;

	/** Last heartbeat of the store (task 1.4), head office clock; null until the first contact. Never written by the client. */
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	private LocalDateTime lastContact;

	/** Application version the store sent at its last heartbeat; null until then. Never written by the client. */
	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	@Column(length = APP_VERSION_LENGTH)
	private String appVersion;

	/** SHA-256 (hex) of the store's API key; the key itself is never stored. Never serialized, never logged. */
	@JsonIgnore
	@ToString.Exclude
	@Column(nullable = false, length = 64)
	private String apiKeyHash;
}
