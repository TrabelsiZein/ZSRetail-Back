package com.digithink.zsretail.erp.api.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1 (item images): the images of one ERP item (its NAV item number: every variant of it shares them), as full
 * addresses, in order: the first is the main image. An empty list means the ERP has no image for it any more.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErpItemImagesDTO {

	private String navItemNo;

	private List<String> urls = new ArrayList<>();
}
