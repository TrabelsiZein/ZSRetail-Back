package com.digithink.zsretail.headoffice.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Answer of POST /ho/sales/tickets, /returns and /sessions (task 2.3): one result per document, in batch order. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SalesCopyAnswerDTO {

	private List<SalesCopyResultDTO> results = new ArrayList<>();
}
