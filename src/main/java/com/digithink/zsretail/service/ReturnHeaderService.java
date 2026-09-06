package com.digithink.zsretail.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.dto.ProcessReturnRequestDTO;
import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.model.ReturnHeader;
import com.digithink.zsretail.model.ReturnLine;
import com.digithink.zsretail.model.ReturnVoucher;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.ReturnType;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.repository.CashierSessionRepository;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.repository.ReturnHeaderRepository;
import com.digithink.zsretail.repository.ReturnLineRepository;
import com.digithink.zsretail.repository.ReturnVoucherRepository;
import com.digithink.zsretail.repository.SalesHeaderRepository;
import com.digithink.zsretail.repository.SalesLineRepository;
import com.digithink.zsretail.repository._BaseRepository;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class ReturnHeaderService extends _BaseService<ReturnHeader, Long> {

	@Autowired
	private ReturnHeaderRepository returnHeaderRepository;

	@Autowired
	private ReturnLineRepository returnLineRepository;

	@Autowired
	private ReturnVoucherRepository returnVoucherRepository;

	@Autowired
	private SalesHeaderRepository salesHeaderRepository;

	@Autowired
	private SalesLineRepository salesLineRepository;

	@Autowired
	private CashierSessionRepository cashierSessionRepository;

	@Autowired
	private GeneralSetupRepository generalSetupRepository;

	@Autowired
	private CashierSessionService cashierSessionService;

	@Autowired
	private StockService stockService;

	@Autowired
	private StockMovementService stockMovementService;

	@Autowired
	private LoyaltyService loyaltyService;

	@Override
	protected _BaseRepository<ReturnHeader, Long> getRepository() {
		return returnHeaderRepository;
	}

	/**
	 * Get current cashier session for a user (helper method)
	 */
	public Optional<CashierSession> getCurrentCashierSession(UserAccount user) {
		return cashierSessionService.getCurrentOpenSession(user);
	}

	/**
	 * Check if a ticket can be returned based on max days configuration
	 */
	public boolean canReturnTicket(SalesHeader salesHeader) {
		// Get max days for return from GeneralSetup
		Optional<GeneralSetup> maxDaysSetup = generalSetupRepository.findByCode("MAX_DAYS_FOR_RETURN");

		if (!maxDaysSetup.isPresent()) {
			log.warn("MAX_DAYS_FOR_RETURN not found in GeneralSetup, allowing return");
			return true; // If not configured, allow return
		}

		try {
			int maxDays = Integer.parseInt(maxDaysSetup.get().getValeur());
			LocalDateTime salesDate = salesHeader.getSalesDate();
			LocalDateTime now = LocalDateTime.now();
			long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(salesDate.toLocalDate(), now.toLocalDate());

			return daysDiff <= maxDays;
		} catch (NumberFormatException e) {
			log.error("Invalid MAX_DAYS_FOR_RETURN value: " + maxDaysSetup.get().getValeur(), e);
			return true; // If invalid, allow return
		}
	}

	/**
	 * Check if simple return is enabled
	 */
	public boolean isSimpleReturnEnabled() {
		Optional<GeneralSetup> enableSimpleReturn = generalSetupRepository.findByCode("ENABLE_SIMPLE_RETURN");

		if (!enableSimpleReturn.isPresent()) {
			return false; // Default to disabled if not configured
		}

		return Boolean.parseBoolean(enableSimpleReturn.get().getValeur());
	}

	/**
	 * Check if returns for promoted items are blocked
	 */
	public boolean isBlockReturnForPromotion() {
		Optional<GeneralSetup> config = generalSetupRepository.findByCode("BLOCK_RETURN_FOR_PROMOTION");
		if (!config.isPresent()) {
			return false;
		}
		return Boolean.parseBoolean(config.get().getValeur());
	}

	/**
	 * Get return voucher validity days
	 */
	public int getReturnVoucherValidityDays() {
		Optional<GeneralSetup> validityDaysSetup = generalSetupRepository.findByCode("RETURN_VOUCHER_VALIDITY_DAYS");

		if (!validityDaysSetup.isPresent()) {
			return 30; // Default to 30 days
		}

		try {
			return Integer.parseInt(validityDaysSetup.get().getValeur());
		} catch (NumberFormatException e) {
			log.error("Invalid RETURN_VOUCHER_VALIDITY_DAYS value: " + validityDaysSetup.get().getValeur(), e);
			return 30; // Default to 30 days
		}
	}

	/**
	 * Process return transactionally
	 */
	@Transactional(rollbackFor = Exception.class)
	public ReturnHeader processReturn(ProcessReturnRequestDTO request, UserAccount currentUser) throws Exception {
		log.info("Processing return for ticket: " + request.getTicketNumber() + " by user: "
				+ currentUser.getUsername());

		// Get current cashier session
		CashierSession currentSession = cashierSessionRepository
				.findByCashierAndStatus(currentUser, com.digithink.zsretail.model.enumeration.SessionStatus.OPENED)
				.orElseThrow(() -> new IllegalStateException("No open cashier session found"));

		// Find original sales header
		SalesHeader originalSalesHeader = salesHeaderRepository.findBySalesNumber(request.getTicketNumber())
				.orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + request.getTicketNumber()));

		// Check if ticket is completed
		if (originalSalesHeader.getStatus() != TransactionStatus.COMPLETED) {
			throw new IllegalStateException("Only completed tickets can be returned");
		}

		// Check if ticket can be returned (max days check)
		if (!canReturnTicket(originalSalesHeader)) {
			throw new IllegalStateException("This ticket is too old to be returned");
		}

		// Validate return type
		if (request.getReturnType() == ReturnType.SIMPLE_RETURN && !isSimpleReturnEnabled()) {
			throw new IllegalStateException("Simple return is not enabled");
		}

		// Validate return lines
		if (request.getReturnLines() == null || request.getReturnLines().isEmpty()) {
			throw new IllegalArgumentException("At least one return line is required");
		}

		// Block return if the ticket had a cart-level promotion
		if (isBlockReturnForPromotion() && "PROMOTION".equals(originalSalesHeader.getDiscountSource())) {
			throw new IllegalStateException("Ce ticket bénéficie d'une promotion globale. Aucun retour n'est autorisé pour les articles en promotion.");
		}

		// Get all sales lines for the original ticket
		List<SalesLine> originalSalesLines = salesLineRepository.findBySalesHeader(originalSalesHeader);

		// Get all previous returns for this sales header to calculate remaining
		// quantities
		List<ReturnHeader> previousReturns = returnHeaderRepository.findAllByOriginalSalesHeader(originalSalesHeader);

		// Calculate already returned quantities for each sales line
		Map<Long, Integer> returnedQuantities = new HashMap<>();
		for (ReturnHeader prevReturn : previousReturns) {
			List<ReturnLine> prevReturnLines = returnLineRepository.findByReturnHeader(prevReturn);
			for (ReturnLine prevReturnLine : prevReturnLines) {
				Long salesLineId = prevReturnLine.getOriginalSalesLine().getId();
				int returnedQty = prevReturnLine.getQuantity();
				returnedQuantities.put(salesLineId, returnedQuantities.getOrDefault(salesLineId, 0) + returnedQty);
			}
		}

		// Validate return quantities
		double totalReturnAmount = 0.0;
		List<ReturnLine> returnLines = new ArrayList<>();

		for (ProcessReturnRequestDTO.ReturnLineDTO returnLineDTO : request.getReturnLines()) {
			// Find original sales line
			SalesLine originalSalesLine = originalSalesLines.stream()
					.filter(line -> line.getId().equals(returnLineDTO.getSalesLineId())).findFirst()
					.orElseThrow(() -> new IllegalArgumentException(
							"Sales line not found: " + returnLineDTO.getSalesLineId()));

			// Block return if this line was sold via a promotion
			if (isBlockReturnForPromotion() && "PROMOTION".equals(originalSalesLine.getDiscountSource())) {
				String itemName = originalSalesLine.getItem() != null ? originalSalesLine.getItem().getName() : "Article";
				throw new IllegalStateException("L'article '" + itemName + "' a été vendu en promotion et ne peut pas être retourné.");
			}

			// Validate quantity
			if (returnLineDTO.getQuantity() == null || returnLineDTO.getQuantity() <= 0) {
				throw new IllegalArgumentException("Return quantity must be greater than 0");
			}

			// Calculate remaining returnable quantity
			int alreadyReturned = returnedQuantities.getOrDefault(originalSalesLine.getId(), 0);
			int remainingReturnable = originalSalesLine.getQuantity() - alreadyReturned;

			if (returnLineDTO.getQuantity() > remainingReturnable) {
				throw new IllegalArgumentException("Return quantity (" + returnLineDTO.getQuantity()
						+ ") cannot exceed remaining returnable quantity (" + remainingReturnable + ")");
			}

			// Calculate line totals (both HT and TTC)
			// Calculate proportional amounts based on return quantity
			double unitPriceHT = originalSalesLine.getUnitPrice();
			double unitPriceTTC = originalSalesLine.getUnitPriceIncludingVat();
			double lineTotalHT = (originalSalesLine.getLineTotal() / originalSalesLine.getQuantity())
					* returnLineDTO.getQuantity();
			double lineTotalTTC = (originalSalesLine.getLineTotalIncludingVat() / originalSalesLine.getQuantity())
					* returnLineDTO.getQuantity();

			// Create return line
			ReturnLine returnLine = new ReturnLine();
			returnLine.setReturnHeader(null); // Will be set after header is created
			returnLine.setOriginalSalesLine(originalSalesLine);
			returnLine.setItem(originalSalesLine.getItem());
			returnLine.setQuantity(returnLineDTO.getQuantity());
			returnLine.setUnitPrice(unitPriceHT); // Store HT unit price
			returnLine.setUnitPriceIncludingVat(unitPriceTTC); // Store TTC unit price
			returnLine.setLineTotal(lineTotalHT); // Store HT line total
			returnLine.setLineTotalIncludingVat(lineTotalTTC); // Store TTC line total
			returnLine.setNotes("");

			returnLines.add(returnLine);
			totalReturnAmount += lineTotalTTC;
		}

		// Apply the original ticket's header discount to the return amount so the voucher
		// refunds exactly what the customer paid for the returned lines.
		// Prefer the stored discountPercentage (the exact rate applied at checkout to
		// non-tax-stamp lines) over a ratio derived from header totals — a ratio including
		// the non-discounted tax stamp would dilute the rate and overpay the customer.
		double effectiveTotalReturnAmount = totalReturnAmount;
		Double headerDiscountPct = originalSalesHeader.getDiscountPercentage();
		if (headerDiscountPct != null && headerDiscountPct > 0) {
			effectiveTotalReturnAmount = totalReturnAmount * (1.0 - headerDiscountPct / 100.0);
			log.info("Applied ticket discountPercentage to return amount: pct={}, totalReturnAmount={}, effectiveTotalReturnAmount={}",
					headerDiscountPct, totalReturnAmount, effectiveTotalReturnAmount);
		} else {
			double ticketTtcBeforeDiscount = 0.0;
			for (SalesLine line : originalSalesLines) {
				if (line.getLineTotalIncludingVat() != null) {
					ticketTtcBeforeDiscount += line.getLineTotalIncludingVat();
				}
			}
			if (ticketTtcBeforeDiscount > 0 && originalSalesHeader.getTotalAmount() != null && originalSalesHeader.getTotalAmount() >= 0) {
				double ratio = originalSalesHeader.getTotalAmount() / ticketTtcBeforeDiscount;
				effectiveTotalReturnAmount = totalReturnAmount * ratio;
				log.info("Applied ticket discount ratio fallback to return amount: ticketTtcBeforeDiscount={}, ratio={}, totalReturnAmount={}, effectiveTotalReturnAmount={}",
						ticketTtcBeforeDiscount, ratio, totalReturnAmount, effectiveTotalReturnAmount);
			}
		}

		// Generate return number
		String returnNumber = generateReturnNumber();

		// Create return header
		ReturnHeader returnHeader = new ReturnHeader();
		returnHeader.setReturnNumber(returnNumber);
		returnHeader.setReturnDate(LocalDateTime.now());
		returnHeader.setOriginalSalesHeader(originalSalesHeader);
		returnHeader.setCreatedByUser(currentUser);
		returnHeader.setCashierSession(currentSession);
		returnHeader.setReturnType(request.getReturnType());
		returnHeader.setStatus(TransactionStatus.COMPLETED);
		returnHeader.setTotalReturnAmount(effectiveTotalReturnAmount);
		returnHeader.setNotes(request.getNotes());
		returnHeader.setDiscountPercentage(originalSalesHeader.getDiscountPercentage());

		// Save return header
		returnHeader = save(returnHeader);
		log.info("Return header created: " + returnHeader.getId());

		// Save return lines
		for (ReturnLine returnLine : returnLines) {
			returnLine.setReturnHeader(returnHeader);
			returnLineRepository.save(returnLine);
		}

		// Update stock for all return types — goods always come back to stock regardless of refund method.
		for (ReturnLine returnLine : returnLines) {
			if (returnLine.getItem() != null && returnLine.getQuantity() != null && returnLine.getQuantity() > 0) {
				stockService.incrementForReturn(returnLine.getItem().getId(), returnLine.getQuantity());
				if (request.getReturnType() == ReturnType.SIMPLE_RETURN) {
					stockMovementService.recordSimpleReturn(
							returnLine.getItem().getId(), returnLine.getQuantity(),
							returnLine.getUnitPrice(), null, returnLine.getUnitPriceIncludingVat(),
							returnHeader.getId(), returnHeader.getCashierSession());
				} else {
					stockMovementService.recordVoucherReturn(
							returnLine.getItem().getId(), returnLine.getQuantity(),
							returnLine.getUnitPrice(), null, returnLine.getUnitPriceIncludingVat(),
							returnHeader.getId(), returnHeader.getCashierSession());
				}
			}
		}

		// If return type is RETURN_VOUCHER, create voucher
		if (request.getReturnType() == ReturnType.RETURN_VOUCHER) {
			ReturnVoucher voucher = createReturnVoucher(returnHeader, originalSalesHeader.getCustomer());
			returnHeader.setReturnVoucher(voucher);
			returnHeader = save(returnHeader);
		}

		// Reverse loyalty points earned from the original sale (if any)
		if (originalSalesHeader.getLoyaltyMember() != null) {
			try {
				loyaltyService.reversePoints(
						originalSalesHeader.getLoyaltyMember().getId(),
						originalSalesHeader,
						returnHeader);
			} catch (Exception e) {
				log.error("Error reversing loyalty points for return {}: {}", returnHeader.getReturnNumber(), e.getMessage(), e);
				// Do not fail the return if loyalty reversal fails
			}
		}

		return returnHeader;
	}

	/**
	 * Create return voucher. Return header total (and thus voucher amount) already has
	 * the original ticket's header discount applied in processReturn.
	 */
	private ReturnVoucher createReturnVoucher(ReturnHeader returnHeader, Customer customer) throws Exception {
		String voucherNumber = generateVoucherNumber();
		int validityDays = getReturnVoucherValidityDays();
		LocalDate expiryDate = LocalDate.now().plusDays(validityDays);

		double voucherAmount = returnHeader.getTotalReturnAmount() != null ? returnHeader.getTotalReturnAmount() : 0.0;

		ReturnVoucher voucher = new ReturnVoucher();
		voucher.setVoucherNumber(voucherNumber);
		voucher.setVoucherDate(LocalDateTime.now());
		voucher.setReturnHeader(returnHeader);
		voucher.setCustomer(customer);
		voucher.setVoucherAmount(voucherAmount);
		voucher.setExpiryDate(expiryDate);
		voucher.setStatus(TransactionStatus.PENDING);
		voucher.setUsedAmount(0.0);
		voucher.setNotes("Return voucher for return: " + returnHeader.getReturnNumber());

		voucher = returnVoucherRepository.save(voucher);
		log.info("Return voucher created: " + voucher.getVoucherNumber());

		return voucher;
	}

	/**
	 * Generate unique return number
	 */
	private String generateReturnNumber() {
		LocalDateTime now = LocalDateTime.now();
		String prefix = "RET-" + now.getYear() + String.format("%02d", now.getMonthValue());
		long count = returnHeaderRepository.count() + 1;
		return prefix + "-" + String.format("%06d", count);
	}

	/**
	 * Generate unique voucher number
	 */
	private String generateVoucherNumber() {
		LocalDateTime now = LocalDateTime.now();
		String prefix = "VOU-" + now.getYear() + String.format("%02d", now.getMonthValue());
		long count = returnVoucherRepository.count() + 1;
		return prefix + "-" + String.format("%06d", count);
	}

	/**
	 * Get return details by ID
	 */
	public ReturnHeader getReturnDetails(Long returnId) {
		return returnHeaderRepository.findById(returnId)
				.orElseThrow(() -> new IllegalArgumentException("Return not found: " + returnId));
	}

	/**
	 * Get return by return number
	 */
	public Optional<ReturnHeader> getReturnByReturnNumber(String returnNumber) {
		return returnHeaderRepository.findByReturnNumber(returnNumber);
	}
}
