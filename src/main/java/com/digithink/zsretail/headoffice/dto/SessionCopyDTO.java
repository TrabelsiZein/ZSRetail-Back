package com.digithink.zsretail.headoffice.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Copy of a store's session closing (task 2.2), sent to POST /ho/sales/sessions when the session is CLOSED, then again
 * when it is TERMINATED. Business codes, never a database id; no store code (the authenticated store).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionCopyDTO {

	private String sessionNumber;

	/** SessionStatus name: CLOSED or TERMINATED. */
	private String status;

	private String cashierLogin;

	private String cashierName;

	private LocalDateTime openedAt;

	private LocalDateTime closedAt;

	private Double openingCash;

	/** Cash expected in the drawer. */
	private Double realCash;

	/** Cash counted by the cashier. */
	private Double posUserClosureCash;

	/** Cash counted by the responsible. */
	private Double responsibleClosureCash;

	private String verifiedByLogin;

	private String verifiedByName;

	private LocalDateTime verifiedAt;

	private String verificationNotes;

	/** Cash count lines, of the cashier then of the responsible, in the store's order. */
	private List<SessionCountCopyDTO> counts = new ArrayList<>();
}
