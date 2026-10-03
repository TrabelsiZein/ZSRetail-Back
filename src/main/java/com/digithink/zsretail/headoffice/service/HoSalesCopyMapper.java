package com.digithink.zsretail.headoffice.service;

import java.util.Collections;
import java.util.List;

import com.digithink.zsretail.headoffice.dto.PaymentCopyDTO;
import com.digithink.zsretail.headoffice.dto.ReturnCopyDTO;
import com.digithink.zsretail.headoffice.dto.ReturnLineCopyDTO;
import com.digithink.zsretail.headoffice.dto.SessionCopyDTO;
import com.digithink.zsretail.headoffice.dto.SessionCountCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketLineCopyDTO;
import com.digithink.zsretail.headoffice.model.HoReturn;
import com.digithink.zsretail.headoffice.model.HoReturnLine;
import com.digithink.zsretail.headoffice.model.HoSession;
import com.digithink.zsretail.headoffice.model.HoSessionCount;
import com.digithink.zsretail.headoffice.model.HoTicket;
import com.digithink.zsretail.headoffice.model.HoTicketLine;
import com.digithink.zsretail.headoffice.model.HoTicketPayment;

/**
 * Writes a received copy into its head office row (task 2.3): every field is replaced, and the lines (payments, count
 * lines) are cleared and added again, so a changed document replaces its content. The collections are changed in
 * place: orphan removal deletes the old lines. Pure mapping; the store, the key and the dates are set by the caller.
 */
final class HoSalesCopyMapper {

	private HoSalesCopyMapper() {
	}

	static void apply(HoTicket target, TicketCopyDTO copy) {
		target.setSalesNumber(copy.getSalesNumber());
		target.setSalesDate(copy.getSalesDate());
		target.setCompletedDate(copy.getCompletedDate());
		target.setStatus(copy.getStatus());
		target.setSubtotal(copy.getSubtotal());
		target.setTaxAmount(copy.getTaxAmount());
		target.setDiscountAmount(copy.getDiscountAmount());
		target.setDiscountPercentage(copy.getDiscountPercentage());
		target.setTotalAmount(copy.getTotalAmount());
		target.setPaidAmount(copy.getPaidAmount());
		target.setChangeAmount(copy.getChangeAmount());
		target.setDiscountSource(copy.getDiscountSource());
		target.setPromotionCode(copy.getPromotionCode());
		target.setPromotionName(copy.getPromotionName());
		target.setCustomerCode(copy.getCustomerCode());
		target.setCustomerName(copy.getCustomerName());
		target.setCashierLogin(copy.getCashierLogin());
		target.setCashierName(copy.getCashierName());
		target.setSessionNumber(copy.getSessionNumber());
		target.setLoyaltyCardNumber(copy.getLoyaltyCardNumber());
		target.setLoyaltyMemberName(copy.getLoyaltyMemberName());
		target.setLoyaltyPointsEarned(copy.getLoyaltyPointsEarned());
		target.setLoyaltyPointsRedeemed(copy.getLoyaltyPointsRedeemed());
		target.setLoyaltyDeductionAmount(copy.getLoyaltyDeductionAmount());
		target.setInvoiced(copy.getInvoiced());
		target.setInvoiceNumber(copy.getInvoiceNumber());
		target.setTableNumber(copy.getTableNumber());
		target.setNotes(copy.getNotes());

		target.getLines().clear();
		int lineNo = 0;
		for (TicketLineCopyDTO line : orEmpty(copy.getLines())) {
			HoTicketLine row = new HoTicketLine();
			row.setTicket(target);
			row.setLineNo(++lineNo);
			row.setItemCode(line.getItemCode());
			row.setItemName(line.getItemName());
			row.setQuantity(line.getQuantity());
			row.setUnitPrice(line.getUnitPrice());
			row.setUnitPriceIncludingVat(line.getUnitPriceIncludingVat());
			row.setVatPercent(line.getVatPercent());
			row.setVatAmount(line.getVatAmount());
			row.setDiscountPercentage(line.getDiscountPercentage());
			row.setDiscountAmount(line.getDiscountAmount());
			row.setDiscountSource(line.getDiscountSource());
			row.setPromotionCode(line.getPromotionCode());
			row.setLineTotal(line.getLineTotal());
			row.setLineTotalIncludingVat(line.getLineTotalIncludingVat());
			target.getLines().add(row);
		}
		target.getPayments().clear();
		int paymentNo = 0;
		for (PaymentCopyDTO payment : orEmpty(copy.getPayments())) {
			HoTicketPayment row = new HoTicketPayment();
			row.setTicket(target);
			row.setLineNo(++paymentNo);
			row.setPaymentMethodCode(payment.getPaymentMethodCode());
			row.setPaymentMethodName(payment.getPaymentMethodName());
			row.setAmount(payment.getAmount());
			row.setPaymentDate(payment.getPaymentDate());
			row.setTitleNumber(payment.getTitleNumber());
			row.setDueDate(payment.getDueDate());
			target.getPayments().add(row);
		}
	}

	static void apply(HoReturn target, ReturnCopyDTO copy) {
		target.setReturnNumber(copy.getReturnNumber());
		target.setReturnDate(copy.getReturnDate());
		target.setStatus(copy.getStatus());
		target.setReturnType(copy.getReturnType());
		target.setOriginalSalesNumber(copy.getOriginalSalesNumber());
		target.setTotalReturnAmount(copy.getTotalReturnAmount());
		target.setDiscountPercentage(copy.getDiscountPercentage());
		target.setCashierLogin(copy.getCashierLogin());
		target.setCashierName(copy.getCashierName());
		target.setSessionNumber(copy.getSessionNumber());
		target.setVoucherNumber(copy.getVoucherNumber());
		target.setVoucherAmount(copy.getVoucherAmount());
		target.setVoucherExpiryDate(copy.getVoucherExpiryDate());
		target.setNotes(copy.getNotes());

		target.getLines().clear();
		int lineNo = 0;
		for (ReturnLineCopyDTO line : orEmpty(copy.getLines())) {
			HoReturnLine row = new HoReturnLine();
			row.setReturnCopy(target);
			row.setLineNo(++lineNo);
			row.setItemCode(line.getItemCode());
			row.setItemName(line.getItemName());
			row.setQuantity(line.getQuantity());
			row.setUnitPrice(line.getUnitPrice());
			row.setUnitPriceIncludingVat(line.getUnitPriceIncludingVat());
			row.setLineTotal(line.getLineTotal());
			row.setLineTotalIncludingVat(line.getLineTotalIncludingVat());
			row.setNotes(line.getNotes());
			target.getLines().add(row);
		}
	}

	static void apply(HoSession target, SessionCopyDTO copy) {
		target.setSessionNumber(copy.getSessionNumber());
		target.setStatus(copy.getStatus());
		target.setCashierLogin(copy.getCashierLogin());
		target.setCashierName(copy.getCashierName());
		target.setOpenedAt(copy.getOpenedAt());
		target.setClosedAt(copy.getClosedAt());
		target.setOpeningCash(copy.getOpeningCash());
		target.setRealCash(copy.getRealCash());
		target.setPosUserClosureCash(copy.getPosUserClosureCash());
		target.setResponsibleClosureCash(copy.getResponsibleClosureCash());
		target.setVerifiedByLogin(copy.getVerifiedByLogin());
		target.setVerifiedByName(copy.getVerifiedByName());
		target.setVerifiedAt(copy.getVerifiedAt());
		target.setVerificationNotes(copy.getVerificationNotes());

		target.getCounts().clear();
		int lineNo = 0;
		for (SessionCountCopyDTO count : orEmpty(copy.getCounts())) {
			HoSessionCount row = new HoSessionCount();
			row.setSession(target);
			row.setLineNo(++lineNo);
			row.setCounterType(count.getCounterType());
			row.setPaymentMethodCode(count.getPaymentMethodCode());
			row.setPaymentMethodName(count.getPaymentMethodName());
			row.setDenominationValue(count.getDenominationValue());
			row.setQuantity(count.getQuantity());
			row.setLineTotal(count.getLineTotal());
			row.setReferenceNumber(count.getReferenceNumber());
			target.getCounts().add(row);
		}
	}

	private static <T> List<T> orEmpty(List<T> list) {
		return list == null ? Collections.emptyList() : list;
	}
}
