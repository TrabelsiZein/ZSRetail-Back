package com.digithink.zsretail.store.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.BarcodeLayout;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodBehaviour;
import com.digithink.zsretail.core.repository.AcceptedTicketRepository;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.service.CommonTicketIssuers;
import com.digithink.zsretail.core.service.TicketBarcodeParser;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.service.PaymentMethodService.Refusal;

/**
 * 3.2.0, step 3: the accepted tickets of a scanned-tickets method ("tickets acceptés"). A line belongs to a method of
 * behaviour SCANNED_TICKETS; its code is digits (the barcode's prefix, 1 to 20); its deduction 0-100 % (2 decimals at
 * most); its validity 0-120 months after the printed year, or none; its layout readable (TicketBarcodeParser
 * .layoutProblem). Two lines of the same barcode length whose codes are equal or one starts the other are refused,
 * whatever their method and active switch: a barcode then matches one line at most. The admin's action "add the common
 * issuers" adds CommonTicketIssuers' six lines of a kind, inactive, and skips a code that would overlap a line. A
 * barcode is tested against every line (nothing stored), the method of the screen expected.
 */
@Service
public class AcceptedTicketService extends _BaseService<AcceptedTicket, Long> {

	static final int MAX_VALIDITY_MONTHS = 120;

	@Autowired
	private AcceptedTicketRepository acceptedTicketRepository;

	@Override
	protected _BaseRepository<AcceptedTicket, Long> getRepository() {
		return acceptedTicketRepository;
	}

	public List<AcceptedTicket> lines(PaymentMethod method) {
		return acceptedTicketRepository.findByPaymentMethodOrderByTicketCode(method);
	}

	/** A new line under the method, checked; throws a Refusal. */
	public AcceptedTicket add(PaymentMethod method, AcceptedTicket incoming) throws Exception {
		AcceptedTicket line = new AcceptedTicket();
		line.setPaymentMethod(method);
		copy(incoming, line);
		check(line, null);
		return save(line);
	}

	/** The stored line with the incoming fields, checked; its method never changes. Throws a Refusal. */
	public AcceptedTicket edit(AcceptedTicket stored, AcceptedTicket incoming) throws Exception {
		copy(incoming, stored);
		check(stored, stored.getId());
		return save(stored);
	}

	/** The common issuers of a kind, inactive; a code that would overlap a line is skipped. {added, skipped} codes. */
	public Map<String, List<String>> addCommon(PaymentMethod method, CommonTicketIssuers.Kind kind) throws Exception {
		requireScannedTickets(method);
		List<String> added = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (CommonTicketIssuers.Issuer issuer : CommonTicketIssuers.of(kind)) {
			AcceptedTicket line = CommonTicketIssuers.line(issuer, method);
			if (conflict(line, null).isPresent()) {
				skipped.add(issuer.getCode());
			} else {
				save(line);
				added.add(issuer.getCode());
			}
		}
		Map<String, List<String>> result = new LinkedHashMap<>();
		result.put("added", added);
		result.put("skipped", skipped);
		return result;
	}

	/** The reading of a barcode at a date, against every line, the method expected; nothing stored. */
	public TicketBarcodeParser.Reading test(PaymentMethod method, String barcode, LocalDate date) {
		return TicketBarcodeParser.read(barcode, date, acceptedTicketRepository.findAll(), method.getId());
	}

	/** Per method id: its number of lines. */
	public Map<Long, Long> countPerMethod() {
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] row : acceptedTicketRepository.countPerPaymentMethod()) {
			counts.put((Long) row[0], ((Number) row[1]).longValue());
		}
		return counts;
	}

	private static void copy(AcceptedTicket from, AcceptedTicket to) {
		to.setIssuerName(from.getIssuerName() == null ? null : from.getIssuerName().trim());
		to.setTicketCode(from.getTicketCode() == null ? null : from.getTicketCode().trim());
		to.setDeductionPercent(from.getDeductionPercent() == null ? BigDecimal.ZERO : from.getDeductionPercent());
		to.setValidityMonths(from.getValidityMonths());
		to.setLayout(from.getLayout() == null ? BarcodeLayout.standard() : from.getLayout());
		to.setActive(from.getActive() == null || from.getActive());
	}

	static void requireScannedTickets(PaymentMethod method) {
		if (method.getBehaviour() != PaymentMethodBehaviour.SCANNED_TICKETS) {
			throw new Refusal("NOT_SCANNED_METHOD", "The payment method " + method.getCode()
					+ " does not take scanned tickets: its accepted tickets cannot be set.");
		}
	}

	void check(AcceptedTicket line, Long id) {
		requireScannedTickets(line.getPaymentMethod());
		if (line.getIssuerName() == null || line.getIssuerName().isEmpty()) {
			throw new Refusal("ISSUER_REQUIRED", "The issuer is required.");
		}
		String code = line.getTicketCode();
		if (code == null || code.isEmpty() || code.length() > AcceptedTicket.MAX_CODE_LENGTH
				|| !code.chars().allMatch(Character::isDigit)) {
			throw new Refusal("CODE_INVALID", "The ticket code is 1 to " + AcceptedTicket.MAX_CODE_LENGTH + " digits.");
		}
		BigDecimal deduction = line.getDeductionPercent();
		if (deduction.signum() < 0 || deduction.compareTo(BigDecimal.valueOf(100)) > 0
				|| deduction.stripTrailingZeros().scale() > 2) {
			throw new Refusal("DEDUCTION_INVALID", "The deduction is between 0 and 100 %, 2 decimals at most.");
		}
		Integer months = line.getValidityMonths();
		if (months != null && (months < 0 || months > MAX_VALIDITY_MONTHS)) {
			throw new Refusal("VALIDITY_INVALID",
					"The validity is 0 to " + MAX_VALIDITY_MONTHS + " months after the printed year, or empty.");
		}
		String problem = TicketBarcodeParser.layoutProblem(code, line.getLayout());
		if (problem != null) {
			throw new Refusal("LAYOUT_INVALID", problem);
		}
		Optional<AcceptedTicket> other = conflict(line, id);
		if (other.isPresent()) {
			throw new Refusal("CODE_CONFLICT", "The code " + code + " (" + line.getLayout().getBarcodeLength()
					+ " digits) overlaps the code " + other.get().getTicketCode() + " of " + other.get().getIssuerName()
					+ " (" + other.get().getPaymentMethod().getName() + "): a barcode must match one ticket only.");
		}
	}

	/** A line of the same barcode length whose code equals or starts this one's, or the reverse (itself left out). */
	private Optional<AcceptedTicket> conflict(AcceptedTicket line, Long id) {
		String code = line.getTicketCode();
		Integer length = line.getLayout().getBarcodeLength();
		return acceptedTicketRepository.findAll().stream()
				.filter(other -> id == null || !id.equals(other.getId()))
				.filter(other -> other.getLayout() != null && length.equals(other.getLayout().getBarcodeLength()))
				.filter(other -> other.getTicketCode() != null
						&& (other.getTicketCode().startsWith(code) || code.startsWith(other.getTicketCode())))
				.findFirst();
	}
}
