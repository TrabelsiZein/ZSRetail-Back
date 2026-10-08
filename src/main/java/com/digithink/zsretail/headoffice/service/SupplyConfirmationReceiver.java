package com.digithink.zsretail.headoffice.service;

import java.util.List;

import com.digithink.zsretail.headoffice.dto.DeliveryConfirmationDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.headoffice.model.Store;

/**
 * The receiver of a store's supply confirmations (POST /ho/supply/confirmations): {@link HoDeliveryService} on a head
 * office that makes its BLs, {@link HoErpInvoiceService} on a head office whose supply documents are the ERP's invoices
 * (headoffice.supply.source=ERP). Exactly one exists where HeadOfficeSupplyAPI exists. One result per document, in batch
 * order, by its number.
 */
public interface SupplyConfirmationReceiver {

	List<SalesCopyResultDTO> receiveConfirmations(Store store, List<DeliveryConfirmationDTO> confirmations);
}
