package com.digithink.zsretail.headoffice.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Head office plan, step 4: answer of POST /ho/loyalty/members, one result per member in batch order. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoyaltyMemberAnswerDTO {

	private List<LoyaltyMemberResultDTO> results = new ArrayList<>();
}
