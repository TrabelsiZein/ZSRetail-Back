package com.digithink.zsretail.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for GET /admin/invoices/eligible-tickets. Exposes only fields needed for
 * the eligible tickets table (no customer, no createdByUser) to avoid raw JSON in UI.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EligibleTicketDTO {

	private Long id;
	private String salesNumber;
	private LocalDateTime salesDate;
	private Double totalAmount;
}
