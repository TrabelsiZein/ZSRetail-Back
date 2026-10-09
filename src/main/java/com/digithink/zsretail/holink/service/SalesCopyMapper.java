package com.digithink.zsretail.holink.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.digithink.zsretail.headoffice.dto.PaymentCopyDTO;
import com.digithink.zsretail.headoffice.dto.ReturnCopyDTO;
import com.digithink.zsretail.headoffice.dto.ReturnLineCopyDTO;
import com.digithink.zsretail.headoffice.dto.SessionCopyDTO;
import com.digithink.zsretail.headoffice.dto.SessionCountCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketLineCopyDTO;
import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.Payment;
import com.digithink.zsretail.model.PaymentMethod;
import com.digithink.zsretail.model.Promotion;
import com.digithink.zsretail.model.ReturnHeader;
import com.digithink.zsretail.model.ReturnLine;
import com.digithink.zsretail.model.ReturnVoucher;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.SessionCashCount;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.utils.Quantities;

/**
 * Builds the copies sent to the head office (task 2.2) from the store's entities. Pure mapping, no database access:
 * the caller loads the header and its lines. Every reference becomes a business code plus a readable name; no
 * database id is copied. Lines keep the store's order (by id) and are numbered from 1.
 */
public final class SalesCopyMapper {

	private static final Comparator<_BaseEntity> STORE_ORDER = Comparator.comparing(_BaseEntity::getId,
			Comparator.nullsLast(Comparator.naturalOrder()));

	private SalesCopyMapper() {
	}

	public static TicketCopyDTO ticket(SalesHeader header, List<SalesLine> lines, List<Payment> payments) {
		TicketCopyDTO copy = new TicketCopyDTO();
		copy.setSalesNumber(header.getSalesNumber());
		copy.setSalesDate(header.getSalesDate());
		copy.setCompletedDate(header.getCompletedDate());
		copy.setStatus(name(header.getStatus()));
		copy.setSubtotal(header.getSubtotal());
		copy.setTaxAmount(header.getTaxAmount());
		copy.setDiscountAmount(header.getDiscountAmount());
		copy.setDiscountPercentage(header.getDiscountPercentage());
		copy.setTotalAmount(header.getTotalAmount());
		copy.setPaidAmount(header.getPaidAmount());
		copy.setChangeAmount(header.getChangeAmount());
		copy.setDiscountSource(header.getDiscountSource());
		Promotion promotion = header.getPromotion();
		if (promotion != null) {
			copy.setPromotionCode(promotion.getCode());
			copy.setPromotionName(promotion.getName());
		}
		Customer customer = header.getCustomer();
		if (customer != null) {
			copy.setCustomerCode(customer.getCustomerCode());
			copy.setCustomerName(customer.getName());
		}
		UserAccount cashier = header.getCreatedByUser();
		if (cashier != null) {
			copy.setCashierLogin(cashier.getUsername());
			copy.setCashierName(cashier.getFullName());
		}
		copy.setSessionNumber(sessionNumber(header.getCashierSession()));
		LoyaltyMember member = header.getLoyaltyMember();
		if (member != null) {
			copy.setLoyaltyCardNumber(member.getCardNumber());
			copy.setLoyaltyMemberName(fullName(member.getFirstName(), member.getLastName()));
		}
		copy.setLoyaltyPointsEarned(header.getLoyaltyPointsEarned());
		copy.setLoyaltyPointsRedeemed(header.getLoyaltyPointsRedeemed());
		copy.setLoyaltyDeductionAmount(header.getLoyaltyDeductionAmount());
		copy.setInvoiced(header.getInvoiced());
		copy.setInvoiceNumber(header.getInvoiceNumber());
		copy.setTableNumber(header.getTableNumber());
		copy.setNotes(header.getNotes());

		int lineNo = 0;
		for (SalesLine line : inStoreOrder(lines)) {
			TicketLineCopyDTO lineCopy = new TicketLineCopyDTO();
			lineCopy.setLineNo(++lineNo);
			Item item = line.getItem();
			if (item != null) {
				lineCopy.setItemCode(item.getItemCode());
				lineCopy.setItemName(item.getName());
			}
			// 2.2.1: the ticket copy stays whole for now; a decimal quantity is refused here, loudly (copy not built)
			lineCopy.setQuantity(Quantities.wholeOrFail(line.getQuantity(),
					"Ticket " + header.getSalesNumber() + " line " + lineNo + ", copy to the head office"));
			lineCopy.setUnitPrice(line.getUnitPrice());
			lineCopy.setUnitPriceIncludingVat(line.getUnitPriceIncludingVat());
			lineCopy.setVatPercent(line.getVatPercent());
			lineCopy.setVatAmount(line.getVatAmount());
			lineCopy.setDiscountPercentage(line.getDiscountPercentage());
			lineCopy.setDiscountAmount(line.getDiscountAmount());
			lineCopy.setDiscountSource(line.getDiscountSource());
			lineCopy.setPromotionCode(line.getPromotion() == null ? null : line.getPromotion().getCode());
			lineCopy.setLineTotal(line.getLineTotal());
			lineCopy.setLineTotalIncludingVat(line.getLineTotalIncludingVat());
			copy.getLines().add(lineCopy);
		}
		for (Payment payment : inStoreOrder(payments)) {
			PaymentCopyDTO paymentCopy = new PaymentCopyDTO();
			PaymentMethod method = payment.getPaymentMethod();
			if (method != null) {
				paymentCopy.setPaymentMethodCode(method.getCode());
				paymentCopy.setPaymentMethodName(method.getName());
			}
			paymentCopy.setAmount(payment.getTotalAmount());
			paymentCopy.setPaymentDate(payment.getPaymentDate());
			paymentCopy.setTitleNumber(payment.getTitleNumber());
			paymentCopy.setDueDate(payment.getDueDate());
			copy.getPayments().add(paymentCopy);
		}
		return copy;
	}

	public static ReturnCopyDTO returnCopy(ReturnHeader header, List<ReturnLine> lines) {
		ReturnCopyDTO copy = new ReturnCopyDTO();
		copy.setReturnNumber(header.getReturnNumber());
		copy.setReturnDate(header.getReturnDate());
		copy.setStatus(name(header.getStatus()));
		copy.setReturnType(name(header.getReturnType()));
		copy.setOriginalSalesNumber(
				header.getOriginalSalesHeader() == null ? null : header.getOriginalSalesHeader().getSalesNumber());
		copy.setTotalReturnAmount(header.getTotalReturnAmount());
		copy.setDiscountPercentage(header.getDiscountPercentage());
		UserAccount cashier = header.getCreatedByUser();
		if (cashier != null) {
			copy.setCashierLogin(cashier.getUsername());
			copy.setCashierName(cashier.getFullName());
		}
		copy.setSessionNumber(sessionNumber(header.getCashierSession()));
		ReturnVoucher voucher = header.getReturnVoucher();
		if (voucher != null) {
			copy.setVoucherNumber(voucher.getVoucherNumber());
			copy.setVoucherAmount(voucher.getVoucherAmount());
			copy.setVoucherExpiryDate(voucher.getExpiryDate());
		}
		copy.setNotes(header.getNotes());

		int lineNo = 0;
		for (ReturnLine line : inStoreOrder(lines)) {
			ReturnLineCopyDTO lineCopy = new ReturnLineCopyDTO();
			lineCopy.setLineNo(++lineNo);
			Item item = line.getItem();
			if (item != null) {
				lineCopy.setItemCode(item.getItemCode());
				lineCopy.setItemName(item.getName());
			}
			lineCopy.setQuantity(line.getQuantity());
			lineCopy.setUnitPrice(line.getUnitPrice());
			lineCopy.setUnitPriceIncludingVat(line.getUnitPriceIncludingVat());
			lineCopy.setLineTotal(line.getLineTotal());
			lineCopy.setLineTotalIncludingVat(line.getLineTotalIncludingVat());
			lineCopy.setNotes(line.getNotes());
			copy.getLines().add(lineCopy);
		}
		return copy;
	}

	public static SessionCopyDTO session(CashierSession session, List<SessionCashCount> counts) {
		SessionCopyDTO copy = new SessionCopyDTO();
		copy.setSessionNumber(session.getSessionNumber());
		copy.setStatus(name(session.getStatus()));
		UserAccount cashier = session.getCashier();
		if (cashier != null) {
			copy.setCashierLogin(cashier.getUsername());
			copy.setCashierName(cashier.getFullName());
		}
		copy.setOpenedAt(session.getOpenedAt());
		copy.setClosedAt(session.getClosedAt());
		copy.setOpeningCash(session.getOpeningCash());
		copy.setRealCash(session.getRealCash());
		copy.setPosUserClosureCash(session.getPosUserClosureCash());
		copy.setResponsibleClosureCash(session.getResponsibleClosureCash());
		UserAccount verifier = session.getVerifiedBy();
		if (verifier != null) {
			copy.setVerifiedByLogin(verifier.getUsername());
			copy.setVerifiedByName(verifier.getFullName());
		}
		copy.setVerifiedAt(session.getVerifiedAt());
		copy.setVerificationNotes(session.getVerificationNotes());

		int lineNo = 0;
		for (SessionCashCount count : inStoreOrder(counts)) {
			SessionCountCopyDTO countCopy = new SessionCountCopyDTO();
			countCopy.setLineNo(++lineNo);
			countCopy.setCounterType(name(count.getCounterType()));
			PaymentMethod method = count.getPaymentMethod();
			if (method != null) {
				countCopy.setPaymentMethodCode(method.getCode());
				countCopy.setPaymentMethodName(method.getName());
			}
			countCopy.setDenominationValue(count.getDenominationValue());
			countCopy.setQuantity(count.getQuantity());
			countCopy.setLineTotal(count.getLineTotal());
			countCopy.setReferenceNumber(count.getReferenceNumber());
			copy.getCounts().add(countCopy);
		}
		return copy;
	}

	private static <T extends _BaseEntity> List<T> inStoreOrder(List<T> rows) {
		List<T> sorted = new ArrayList<>(rows == null ? new ArrayList<>() : rows);
		sorted.sort(STORE_ORDER);
		return sorted;
	}

	private static String sessionNumber(CashierSession session) {
		return session == null ? null : session.getSessionNumber();
	}

	private static String name(Enum<?> value) {
		return value == null ? null : value.name();
	}

	/** "First Last", without the missing part; null when both are empty. */
	private static String fullName(String first, String last) {
		String name = ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim();
		return name.isEmpty() ? null : name;
	}
}
