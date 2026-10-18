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
}
