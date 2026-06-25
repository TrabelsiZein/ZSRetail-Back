package com.digithink.pos.dto;

import com.digithink.pos.model.enumeration.Role;

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
	private Role role; // legacy/optional — kept for backward compat
	private Long appRoleId; // dynamic AppRole assignment (source of permissions)
}
