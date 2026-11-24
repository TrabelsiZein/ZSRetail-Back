package com.digithink.zsretail.core.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserRequestDTO {

	private String username;
	private String password;
	private String fullName;
	private String email;
	private Long appRoleId; // dynamic AppRole assignment (source of permissions)
	// 3.4.0: the badge typed in the add popup (it was dropped); checked by UserAccountAPI.badgeRefusal as an update is
	private String badgeCode;
	private String badgeExpirationDate; // the popup's datetime-local, "2026-12-31T23:59"; empty: none
}
