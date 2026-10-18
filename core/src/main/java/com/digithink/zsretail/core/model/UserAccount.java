package com.digithink.zsretail.core.model;

import java.time.LocalDateTime;
import java.util.Collection;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Transient;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;


import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * User account entity with authentication and authorization
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class UserAccount extends _BaseEntity implements UserDetails {

	private static final long serialVersionUID = 1L;

	@Column(unique = true, nullable = false)
	private String username;

	@Column(nullable = false)
	private String password;

	@Column(nullable = true)
	private String fullName;

	@Column(nullable = true)
	private String email;

	private Boolean active = true;

	@Transient
	private Long company;

	// Step E (3.1.0): the enum Role is retired; the user's role is appRole (an administrator: the system role ADMIN,
	// SystemRoles). The column user_account.role stays in a database of before, made nullable (TillSchemaUpgrade,
	// db/3.1.0/update.sql), unused but by TillRightsMigration's one reading; dropped in the next version.

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "app_role_id", nullable = true)
	private AppRole appRole;

	// Badge fields
	// Step S (3.1.0): unique when not null, by the filtered index ux_user_account_badge_code (db/3.1.0/update.sql, and
	// UserBadgeCodeIndex at the start of a new database); never unique = true here: Hibernate would create an unfiltered
	// index, which refuses a second user without a badge
	@Column(name = "badge_code", nullable = true)
	private String badgeCode;

	// Step C (3.1.0): the till rights are the role's (app_role_permission "till:..."); the column badge_permissions (the
	// user's list of before) stays in the database for one release (a rollback reads it), never read nor written by the
	// application again, except once by TillRightsMigration

	@Column(name = "badge_expiration_date", nullable = true)
	private LocalDateTime badgeExpirationDate;

	@Column(name = "badge_revoked", nullable = false)
	private Boolean badgeRevoked = false;

	@Column(name = "badge_revoked_at", nullable = true)
	private LocalDateTime badgeRevokedAt;

	@ManyToOne
	@JoinColumn(name = "badge_revoked_by_id", nullable = true)
	private UserAccount badgeRevokedBy;

	@Column(name = "badge_revoke_reason", length = 500, nullable = true)
	private String badgeRevokeReason;

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return null;
	}

	@Override
	public String getPassword() {
		return password;
	}

	@Override
	public String getUsername() {
		return username;
	}

	@Override
	public boolean isAccountNonExpired() {
		return true;
	}

	@Override
	public boolean isAccountNonLocked() {
		return true;
	}

	@Override
	public boolean isCredentialsNonExpired() {
		return true;
	}

	@Override
	public boolean isEnabled() {
		return active;
	}

	public UserAccount(Long id, LocalDateTime createdAt, LocalDateTime updatedAt, String createdBy, String updatedBy,
			String username, String fullName, String email, Boolean active) {
		this.id = id;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.createdBy = createdBy;
		this.updatedBy = updatedBy;
		this.username = username;
		this.fullName = fullName;
		this.email = email;
		this.active = active;
	}

}