package com.digithink.zsretail.dto;

import java.time.LocalDateTime;

import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.Role;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserAccountDTO {

	private Long id;
	private String username;
	private String fullName;
	private String email;
	private Boolean active;
	private Role role; // legacy enum (backward compat)

	// Dynamic role assignment
	private Long appRoleId;
	private String appRoleName;
	private String appRoleLabel;

	// Badge-related fields
	private String badgeCode;
	private String badgePermissions; // Comma-separated string
	private LocalDateTime badgeExpirationDate;
	private Boolean badgeRevoked;
	private LocalDateTime badgeRevokedAt;
	private Long badgeRevokedById;
	private String badgeRevokeReason;
	
	public static UserAccountDTO fromEntity(UserAccount user) {
		UserAccountDTO dto = new UserAccountDTO();
		dto.setId(user.getId());
		dto.setUsername(user.getUsername());
		dto.setFullName(user.getFullName());
		dto.setEmail(user.getEmail());
		dto.setActive(user.getActive());
		dto.setRole(user.getRole());

		if (user.getAppRole() != null) {
			dto.setAppRoleId(user.getAppRole().getId());
			dto.setAppRoleName(user.getAppRole().getName());
			dto.setAppRoleLabel(user.getAppRole().getLabel());
		}

		// Badge fields
		dto.setBadgeCode(user.getBadgeCode());
		dto.setBadgePermissions(user.getBadgePermissions());
		dto.setBadgeExpirationDate(user.getBadgeExpirationDate());
		dto.setBadgeRevoked(user.getBadgeRevoked());
		dto.setBadgeRevokedAt(user.getBadgeRevokedAt());
		if (user.getBadgeRevokedBy() != null) {
			dto.setBadgeRevokedById(user.getBadgeRevokedBy().getId());
		}
		dto.setBadgeRevokeReason(user.getBadgeRevokeReason());
		
		return dto;
	}
}
