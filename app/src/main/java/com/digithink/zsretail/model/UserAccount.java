package com.digithink.zsretail.model;

import java.time.LocalDateTime;
import java.util.Collection;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Transient;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.digithink.zsretail.model.enumeration.Role;

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

	@Column(nullable = false)
	@Enumerated(EnumType.STRING)
	private Role role;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "app_role_id", nullable = true)
	private AppRole appRole;

	// Badge fields
	@Column(name = "badge_code", nullable = true)
	private String badgeCode;

	@Column(name = "badge_permissions", length = 500, nullable = true)
	private String badgePermissions; // Comma-separated BadgePermission enum values

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