package com.digithink.zsretail.core.dto;

import java.time.LocalDateTime;

import com.digithink.zsretail.core.model.UserAccount;

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

	// Dynamic role assignment
	private Long appRoleId;
	private String appRoleName;
	private String appRoleLabel;

	// Badge-related fields (step C, 3.1.0: the badge is a credential; the till rights are the role's)
	private String badgeCode;
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

		if (user.getAppRole() != null) {
			dto.setAppRoleId(user.getAppRole().getId());
			dto.setAppRoleName(user.getAppRole().getName());
			dto.setAppRoleLabel(user.getAppRole().getLabel());
		}

		// Badge fields
		dto.setBadgeCode(user.getBadgeCode());
		dto.setBadgeExpirationDate(user.getBadgeExpirationDate());
		dto.setBadgeRevoked(user.getBadgeRevoked());
		dto.setBadgeRevokedAt(user.getBadgeRevokedAt());
		if (user.getBadgeRevokedBy() != null) {
			dto.setBadgeRevokedById(user.getBadgeRevokedBy().getId());
		}
		dto.setBadgeRevokeReason(user.getBadgeRevokeReason());

		return dto;
	}

	/**
	 * One's own account, as GET /user-account/current answers it to any logged user: everything but the badge code. The code
	 * grants a till right and no screen reads it there (the till reads a code only where it is typed). The Users pages keep it:
	 * they read it from the answers behind read:admin-users and send it back on a save, so it is not write-only here.
	 */
	public static UserAccountDTO ownAccount(UserAccount user) {
		UserAccountDTO dto = fromEntity(user);
		dto.setBadgeCode(null);
		return dto;
	}
}
