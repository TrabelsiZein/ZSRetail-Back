package com.digithink.zsretail.store.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.AcceptedTicketRepository;
import com.digithink.zsretail.core.service.TicketBarcodeParser;
import com.digithink.zsretail.core.service.TicketBarcodeParser.Reading;
import com.digithink.zsretail.core.service.TicketBarcodeParser.Status;
import com.digithink.zsretail.store.model.ScannedTicketPayment;
import com.digithink.zsretail.store.repository.ScannedTicketPaymentRepository;

/**
 * 3.2.0, step 4: the scanned tickets of a sale (meal tickets, gift cheques), one payment each. The till sends the method
 * and the barcodes in scan order, nothing else: each barcode is read again here (TicketBarcodeParser, today's date),
 * then counted = face value - deduction %, applied = min(counted, what is left to pay), excess = counted - applied (kept
 * by the store: no change, no cash back, no voucher). Refused, the whole sale with it (an IllegalArgumentException in
 * the sale's transaction, a French message naming the barcode): not a ticket of the method (unknown, other method,
 * switched off, wrong length, not digits), expired, scanned twice in the sale, held by another sale, or nothing left to
 * pay. The database has the last word on "used once" (the filtered unique index on live_barcode): two tills scanning
 * the same ticket at the same moment, the second insert fails and is answered "already used". The check of the till and
 * the admin's test read a barcode the same way, nothing reserved.
 */
@Service
public class ScannedTicketService {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");

	@Autowired
	private AcceptedTicketRepository acceptedTicketRepository;

	@Autowired
	private ScannedTicketPaymentRepository scannedTicketPaymentRepository;

	@Autowired
	private PaymentService paymentService;

	/** Today's date for the validity (a test sets its own clock). */
	Clock clock = Clock.systemDefaultZone();

	/**
	 * The reading of a barcode for the method, at today's date, and the sale that holds it: the till's check (status
	 * ALREADY_USED when a sale holds it), nothing reserved.
	 */
	public Reading check(PaymentMethod method, String barcode) {
		Reading reading = TicketBarcodeParser.read(barcode, LocalDate.now(clock), acceptedTicketRepository.findAll(),
				method.getId());
		Optional<ScannedTicketPayment> used = reading.getStatus() == Status.NOT_DIGITS ? Optional.empty()
				: scannedTicketPaymentRepository.findFirstByLiveBarcode(reading.getBarcode());
		if (used.isPresent()) {
			describeUse(reading, used.get());
			if (reading.getStatus() == Status.OK) {
				reading.setStatus(Status.ALREADY_USED);
			}
		}
		return reading;
	}

	/**
	 * The admin's test: the reading against every line, the method expected; the sale that holds the barcode given beside
	 * (its status kept as read: the admin screen shows the reading of the line).
	 */
	public Reading test(PaymentMethod method, String barcode, LocalDate date) {
		Reading reading = TicketBarcodeParser.read(barcode, date, acceptedTicketRepository.findAll(), method.getId());
		if (reading.getStatus() != Status.NOT_DIGITS) {
			scannedTicketPaymentRepository.findFirstByLiveBarcode(reading.getBarcode())
					.ifPresent(used -> describeUse(reading, used));
		}
		return reading;
	}

	private static void describeUse(Reading reading, ScannedTicketPayment used) {
		SalesHeader sale = used.getPayment().getSalesHeader();
		reading.setUsedSaleNumber(sale.getSalesNumber());
		reading.setUsedAt(sale.getCompletedDate() != null ? sale.getCompletedDate() : sale.getSalesDate());
		reading.setUsedSessionNumber(sale.getCashierSession() != null ? sale.getCashierSession().getSessionNumber() : null);
		UserAccount cashier = sale.getCreatedByUser();
		reading.setUsedCashier(cashier == null ? null
				: (cashier.getFullName() != null ? cashier.getFullName() : cashier.getUsername()));
	}

	/**
	 * The tickets of one method for a sale, in scan order, against what is left to pay ({@code rest}, after the other
	 * non-cash payments, before cash): one payment each (title number = the barcode, amount = the part applied) and its
	 * ScannedTicketPayment. {@code seen}: the barcodes of the sale so far (the same ticket twice is refused). Returns the
	 * payments made, in order; throws IllegalArgumentException (French) when a ticket is refused.
	 */
	public List<Payment> pay(SalesHeader sale, PaymentMethod method, List<String> barcodes, BigDecimal rest,
			UserAccount user, Set<String> seen) throws Exception {
		if (barcodes == null || barcodes.isEmpty()) {
			throw new IllegalArgumentException("Aucun ticket scanné pour « " + method.getName() + " ».");
		}
		List<AcceptedTicket> lines = acceptedTicketRepository.findAll();
		LocalDate today = LocalDate.now(clock);
		BigDecimal left = rest.max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);
		List<Payment> payments = new ArrayList<>();
		for (String scanned : barcodes) {
			Reading reading = TicketBarcodeParser.read(scanned, today, lines, method.getId());
			String barcode = reading.getBarcode();
			if (reading.getStatus() != Status.OK) {
				throw new IllegalArgumentException(refusal(reading, method));
			}
			if (!seen.add(barcode)) {
				throw new IllegalArgumentException("Ticket " + barcode + " scanné deux fois dans cette vente.");
			}
			Optional<ScannedTicketPayment> used = scannedTicketPaymentRepository.findFirstByLiveBarcode(barcode);
			if (used.isPresent()) {
				throw new IllegalArgumentException(alreadyUsed(barcode, used.get()));
			}
			if (left.signum() <= 0) {
				throw new IllegalArgumentException("Ticket " + barcode + " (" + reading.getIssuerName()
						+ ") : la vente est déjà entièrement payée, ce ticket n'est pas utilisé.");
			}
			BigDecimal counted = reading.getCountedValue();
			BigDecimal applied = counted.min(left);
			left = left.subtract(applied);

			Payment payment = new Payment();
			payment.setSalesHeader(sale);
			payment.setPaymentMethod(method);
			payment.setCreatedByUser(user);
			payment.setStatus(TransactionStatus.COMPLETED);
			payment.setTotalAmount(applied.doubleValue());
			payment.setPaymentDate(LocalDateTime.now(clock));
			payment.setTitleNumber(barcode);
			payment = paymentService.save(payment);

			AcceptedTicket line = lines.stream().filter(l -> l.getId().equals(reading.getTicketId())).findFirst().get();
			ScannedTicketPayment ticket = new ScannedTicketPayment();
			ticket.setPayment(payment);
			ticket.setAcceptedTicket(line);
			ticket.setBarcode(barcode);
			ticket.setLiveBarcode(barcode);
			ticket.setIssuerName(reading.getIssuerName());
			ticket.setTicketCode(reading.getTicketCode());
			ticket.setPrintedYear(reading.getYear());
			ticket.setFaceValue(reading.getFaceValue());
			ticket.setDeductionPercent(reading.getDeductionPercent() == null ? BigDecimal.ZERO : reading.getDeductionPercent());
			ticket.setCountedValue(counted);
			ticket.setAppliedAmount(applied);
			ticket.setExcessAmount(counted.subtract(applied));
			try {
				scannedTicketPaymentRepository.saveAndFlush(ticket);
			} catch (DataIntegrityViolationException race) {
				// Another till took this ticket between the check and here: the unique index on live_barcode
				throw new IllegalArgumentException("Ticket " + barcode
						+ " déjà utilisé : il vient d'être encaissé sur une autre caisse.");
			}
			payments.add(payment);
		}
		return payments;
	}

	/** The French reason a ticket is refused (the barcode named). */
	static String refusal(Reading reading, PaymentMethod method) {
		String barcode = reading.getBarcode();
		switch (reading.getStatus()) {
			case NOT_DIGITS:
				return "Le code-barres « " + barcode + " » n'est pas un ticket : il doit contenir des chiffres uniquement.";
			case UNKNOWN_TICKET:
				return "Ticket " + barcode + " inconnu : aucun ticket accepté ne commence par ce code.";
			case WRONG_LENGTH:
				return "Ticket " + barcode + " : longueur incorrecte (" + barcode.length() + " chiffres, "
						+ reading.getExpectedLength() + " attendus).";
			case OTHER_METHOD:
				return "Ticket " + barcode + " (" + reading.getIssuerName() + ") : il se paie avec « "
						+ reading.getPaymentMethodName() + " », pas avec « " + method.getName() + " ».";
			case LINE_INACTIVE:
				return "Ticket " + barcode + " (" + reading.getIssuerName() + ") : ce ticket n'est pas accepté (désactivé).";
			case EXPIRED:
				return "Ticket " + barcode + " (" + reading.getIssuerName() + ") périmé : valable jusqu'au "
						+ reading.getValidUntil().format(DAY) + ".";
			default:
				return "Ticket " + barcode + " refusé.";
		}
	}

	private static String alreadyUsed(String barcode, ScannedTicketPayment used) {
		SalesHeader sale = used.getPayment().getSalesHeader();
		LocalDateTime at = sale.getCompletedDate() != null ? sale.getCompletedDate() : sale.getSalesDate();
		return "Ticket " + barcode + " déjà utilisé sur la vente " + sale.getSalesNumber()
				+ (at != null ? " du " + at.format(DAY_TIME) : "") + ".";
	}
}
