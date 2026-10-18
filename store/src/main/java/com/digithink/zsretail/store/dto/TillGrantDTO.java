package com.digithink.zsretail.store.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Step D (3.1.0): the grant POST /badge/scan gives for an action the server checks on its request: the till sends
 * {@code token} with that request (a sale or pending save line, the header discount, the return, the close). Good once,
 * for the scanning user's open session, until {@code expiresAt}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TillGrantDTO {

	private String token;
	private String action;
	private Long badgeOwnerId;
	private LocalDateTime issuedAt;
	private LocalDateTime expiresAt;
}
