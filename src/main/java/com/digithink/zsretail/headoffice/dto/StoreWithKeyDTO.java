package com.digithink.zsretail.headoffice.dto;

import com.digithink.zsretail.headoffice.model.Store;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Answer of the store creation and of "regenerate key": the only responses that carry the plain API key.
 * The key is shown once by the page and goes in the store's settings (headoffice.api-key).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoreWithKeyDTO {

	private Store store;

	@ToString.Exclude
	private String apiKey;
}
