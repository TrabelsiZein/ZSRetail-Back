package com.digithink.zsretail.headoffice.service;

import java.time.format.DateTimeFormatter;

import com.digithink.zsretail.headoffice.dto.DeliveryCopyDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryCopyDTO.DeliveryLineCopyDTO;
import com.digithink.zsretail.headoffice.dto.ErpInvoiceCopyDTO;
import com.digithink.zsretail.headoffice.dto.SupplyInvoiceCopyDTO;
import com.digithink.zsretail.headoffice.model.HoDelivery;
import com.digithink.zsretail.headoffice.model.HoDeliveryLine;
import com.digithink.zsretail.headoffice.model.HoErpInvoice;
import com.digithink.zsretail.headoffice.model.HoErpInvoiceLine;
import com.digithink.zsretail.headoffice.model.HoSupplyInvoice;
import com.digithink.zsretail.headoffice.model.HoSupplyInvoiceLine;

/**
 * The supply documents of the head office as they travel down to the stores: a BL, an invoice of the head office, an
 * ERP invoice. Code cleanup step 5 (knot a): was the of(...) methods of the three link messages, which go to core while
 * the head office entities stay in headoffice; same values, same JSON.
 */
public final class SupplyCopies {

	private SupplyCopies() {
	}

	/** A BL. */
	public static DeliveryCopyDTO delivery(HoDelivery delivery) {
		DeliveryCopyDTO copy = new DeliveryCopyDTO();
		copy.setNumber(delivery.getNumber());
		copy.setDocumentDate(delivery.getDocumentDate() == null ? null : delivery.getDocumentDate().toString());
		copy.setSentAt(delivery.getSentAt() == null ? null
				: delivery.getSentAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
		copy.setStatus(delivery.getStatus() == null ? null : delivery.getStatus().name());
		copy.setNote(delivery.getNote());
		for (HoDeliveryLine line : delivery.getLines()) {
			DeliveryLineCopyDTO lineCopy = new DeliveryLineCopyDTO();
			lineCopy.setLineNo(line.getLineNo());
			lineCopy.setItemCode(line.getItemCode());
			lineCopy.setItemName(line.getItemName());
			lineCopy.setQuantitySent(line.getQuantitySent());
			copy.getLines().add(lineCopy);
		}
		copy.getLines().sort((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()));
		return copy;
	}

	/** An ERP invoice, with the seller named on the store's purchase invoice. */
	public static ErpInvoiceCopyDTO erpInvoice(HoErpInvoice invoice, String sellerName) {
		ErpInvoiceCopyDTO copy = new ErpInvoiceCopyDTO();
		copy.setInvoiceNumber(invoice.getBcNumber());
		copy.setDocumentDate(invoice.getDocumentDate() == null ? null : invoice.getDocumentDate().toString());
		copy.setPostingDate(invoice.getPostingDate() == null ? null : invoice.getPostingDate().toString());
		copy.setSellerName(sellerName);
		copy.setCustomerNo(invoice.getCustomerNo());
		copy.setCustomerName(invoice.getCustomerName());
		copy.setSentAt(invoice.getSentAt() == null ? null
				: invoice.getSentAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
		copy.setTotalExclVat(invoice.getTotalExclVat());
		copy.setTotalVat(invoice.getTotalVat());
		copy.setTotalInclVat(invoice.getTotalInclVat());
		for (HoErpInvoiceLine line : invoice.getLines()) {
			ErpInvoiceCopyDTO.Line row = new ErpInvoiceCopyDTO.Line();
			row.setLineNo(line.getLineNo());
			row.setLineType(line.getLineType() == null ? null : line.getLineType().name());
			row.setItemCode(line.getItemCode());
			row.setDescription(line.getDescription());
			row.setQuantity(line.getQuantity());
			row.setUnitPrice(line.getUnitPrice());
			row.setLineDiscountPercent(line.getLineDiscountPercent());
			row.setLineAmount(line.getLineAmount());
			row.setUnitCost(line.getUnitCost());
			copy.getLines().add(row);
		}
		copy.getLines().sort((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()));
		return copy;
	}

	/** An invoice of the head office. */
	public static SupplyInvoiceCopyDTO supplyInvoice(HoSupplyInvoice invoice) {
		SupplyInvoiceCopyDTO copy = new SupplyInvoiceCopyDTO();
		copy.setInvoiceNumber(invoice.getInvoiceNumber());
		copy.setInvoiceDate(invoice.getInvoiceDate() == null ? null : invoice.getInvoiceDate().toString());
		copy.setSellerName(invoice.getSellerName());
		copy.setSellerTaxNumber(invoice.getSellerTaxNumber());
		copy.setSellerAddress(invoice.getSellerAddress());
		copy.setBuyerName(invoice.getBuyerName());
		copy.setBuyerTaxNumber(invoice.getBuyerTaxNumber());
		copy.setBuyerAddress(invoice.getBuyerAddress());
		copy.setNote(invoice.getNote());
		for (HoSupplyInvoiceLine line : invoice.getLines()) {
			if (line.getDeliveryNumber() != null && !copy.getDeliveryNumbers().contains(line.getDeliveryNumber())) {
				copy.getDeliveryNumbers().add(line.getDeliveryNumber());
			}
			copy.getLines().add(supplyInvoiceLine(line));
		}
		copy.getLines().sort((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()));
		copy.setSubtotal(invoice.getSubtotal());
		copy.setTaxAmount(invoice.getTaxAmount());
		copy.setTotalAmount(invoice.getTotalAmount());
		return copy;
	}

	/** One line of an invoice of the head office (also a preview line). */
	public static SupplyInvoiceCopyDTO.Line supplyInvoiceLine(HoSupplyInvoiceLine line) {
		SupplyInvoiceCopyDTO.Line copy = new SupplyInvoiceCopyDTO.Line();
		copy.setLineNo(line.getLineNo());
		copy.setDeliveryNumber(line.getDeliveryNumber());
		copy.setItemCode(line.getItemCode());
		copy.setItemName(line.getItemName());
		copy.setQuantity(line.getQuantity());
		copy.setUnitPrice(line.getUnitPrice());
		copy.setVatPercent(line.getVatPercent());
		copy.setVatAmount(line.getVatAmount());
		copy.setLineTotal(line.getLineTotal());
		copy.setLineTotalIncludingVat(line.getLineTotalIncludingVat());
		return copy;
	}
}
