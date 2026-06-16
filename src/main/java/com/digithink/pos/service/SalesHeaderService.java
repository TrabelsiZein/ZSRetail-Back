package com.digithink.pos.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.pos.dto.ChangePaymentMethodRequestDTO;
import com.digithink.pos.dto.PricingResult;
import com.digithink.pos.dto.ProcessSaleRequestDTO;
import com.digithink.pos.dto.SplitBillRequestDTO;
import com.digithink.pos.model.CashierSession;
import com.digithink.pos.model.Customer;
import com.digithink.pos.model.GeneralSetup;
import com.digithink.pos.model.Item;
import com.digithink.pos.model.LoyaltyMember;
import com.digithink.pos.model.Payment;
import com.digithink.pos.model.PaymentChangeLog;
import com.digithink.pos.model.PaymentMethod;
import com.digithink.pos.model.ReturnVoucher;
import com.digithink.pos.model.SalesHeader;
import com.digithink.pos.model.SalesLine;
import com.digithink.pos.model.UserAccount;
import com.digithink.pos.model.enumeration.PaymentMethodType;
import com.digithink.pos.model.enumeration.SessionStatus;
import com.digithink.pos.model.enumeration.SynchronizationStatus;
import com.digithink.pos.model.enumeration.TransactionStatus;
import com.digithink.pos.repository.CashierSessionRepository;
import com.digithink.pos.repository.CustomerRepository;
import com.digithink.pos.repository.GeneralSetupRepository;
import com.digithink.pos.repository.ItemRepository;
import com.digithink.pos.repository.LoyaltyMemberRepository;
import com.digithink.pos.repository.PaymentChangeLogRepository;
import com.digithink.pos.repository.PaymentMethodRepository;
import com.digithink.pos.repository.PaymentRepository;
import com.digithink.pos.repository.PromotionRepository;
import com.digithink.pos.repository.SalesHeaderRepository;
import com.digithink.pos.repository.SalesLineRepository;
import com.digithink.pos.repository._BaseRepository;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class SalesHeaderService extends _BaseService<SalesHeader, Long> {

	@Autowired
	private SalesHeaderRepository salesHeaderRepository;

	@Autowired
	private SalesLineService salesLineService;

	@Autowired
	private SalesLineRepository salesLineRepository;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private CustomerRepository customerRepository;

	@Autowired
	private PaymentMethodRepository paymentMethodRepository;

	@Autowired
	private CashierSessionRepository cashierSessionRepository;

	@Autowired
	private GeneralSetupRepository generalSetupRepository;

	@Autowired
	private CashierSessionService cashierSessionService;

	@Autowired
	private com.digithink.pos.service.ReturnVoucherService returnVoucherService;

	@Autowired
	private com.digithink.pos.erp.service.SessionExportService sessionExportService;

	@Autowired
	private PricingService pricingService;

	@Autowired
	private StockService stockService;

	@Autowired
	private StockMovementService stockMovementService;

	@Autowired
	private LoyaltyService loyaltyService;

	@Autowired
	private LoyaltyMemberRepository loyaltyMemberRepository;

	@Autowired
	private PromotionRepository promotionRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private PaymentChangeLogRepository paymentChangeLogRepository;

	@Override
	protected _BaseRepository<SalesHeader, Long> getRepository() {
		return salesHeaderRepository;
	}

	/**
	 * Get current cashier session for a user (helper method)
	 */
	public Optional<CashierSession> getCurrentCashierSession(UserAccount user) {
		return cashierSessionService.getCurrentOpenSession(user);
	}

	/**
	 * Process complete sale transactionally (header + lines + payment + ticket)
	 */
	@Transactional(rollbackFor = Exception.class)
	public SalesHeader processCompleteSale(ProcessSaleRequestDTO request, UserAccount currentUser) throws Exception {
		log.info("Processing complete sale for user: " + currentUser.getUsername());

		// Get current cashier session
		CashierSession currentSession = cashierSessionRepository
				.findByCashierAndStatus(currentUser, com.digithink.pos.model.enumeration.SessionStatus.OPENED)
				.orElseThrow(() -> new IllegalStateException("No open cashier session found"));

		// Generate sales number
		String salesNumber = generateSalesNumber();

		// Create sales header
		SalesHeader salesHeader = new SalesHeader();
		salesHeader.setSalesNumber(salesNumber);
		salesHeader.setSalesDate(LocalDateTime.now());
		salesHeader.setCreatedByUser(currentUser);
		salesHeader.setCashierSession(currentSession);
		salesHeader.setStatus(TransactionStatus.COMPLETED);
		salesHeader.setSubtotal(request.getSubtotal());
		salesHeader.setTaxAmount(request.getTaxAmount());
		salesHeader.setDiscountAmount(request.getDiscountAmount() != null ? request.getDiscountAmount() : 0.0);
		salesHeader.setDiscountPercentage(request.getDiscountPercentage());
		salesHeader.setTotalAmount(request.getTotalAmount());
		salesHeader.setPaidAmount(request.getPaidAmount());
		salesHeader.setChangeAmount(request.getChangeAmount());
		salesHeader.setNotes(request.getNotes());
		salesHeader.setCompletedDate(LocalDateTime.now());

		// Discount source tracking (header level)
		if (request.getDiscountSource() != null) {
			salesHeader.setDiscountSource(request.getDiscountSource());
			if ("PROMOTION".equals(request.getDiscountSource()) && request.getPromotionId() != null) {
				promotionRepository.findById(request.getPromotionId())
						.ifPresent(salesHeader::setPromotion);
			}
		}

		// Set customer - use provided customer or passenger customer
		Customer customer = null;
		if (request.getCustomerId() != null) {
			customer = customerRepository.findById(request.getCustomerId()).orElse(null);
		}

		// If no customer provided, use passenger customer from GeneralSetup
		if (customer == null) {
			String passengerCustomerId = generalSetupRepository.findByCode("PASSENGER_CUSTOMER").get().getValeur();

			if (passengerCustomerId != null) {
				customer = customerRepository.findByCustomerCode(passengerCustomerId)
						.orElseThrow(() -> new IllegalStateException("Deafult customer not found"));
				if (customer != null) {
					log.info("Using passenger customer: " + customer.getCustomerCode() + " - " + customer.getName());
				} else {
					log.warn("Passenger customer ID found in GeneralSetup but customer not found: "
							+ passengerCustomerId);
				}
			} else {
				log.warn("PASSENGER_CUSTOMER not found in GeneralSetup");
			}
		}

		// Set customer (will be passenger customer if none provided)
		if (customer != null) {
			salesHeader.setCustomer(customer);
		} else {
			log.error("No customer assigned to sales header - customer was null and passenger customer not available");
		}

		// Save sales header
		salesHeader = save(salesHeader);
		log.info("Sales header created: " + salesHeader.getId());

		// Create sales lines
		List<SalesLine> salesLines = new ArrayList<>();
		for (ProcessSaleRequestDTO.SaleLineDTO lineDTO : request.getLines()) {
			Item item = itemRepository.findById(lineDTO.getItemId())
					.orElseThrow(() -> new IllegalArgumentException("Item not found: " + lineDTO.getItemId()));

			SalesLine salesLine = new SalesLine();
			salesLine.setSalesHeader(salesHeader);
			salesLine.setItem(item);
			salesLine.setQuantity(lineDTO.getQuantity());
			salesLine.setUnitPrice(lineDTO.getUnitPrice());
			salesLine.setLineTotal(lineDTO.getLineTotal());

			// Set discount fields
			if (lineDTO.getDiscountPercentage() != null) {
				salesLine.setDiscountPercentage(lineDTO.getDiscountPercentage());
			}
			if (lineDTO.getDiscountAmount() != null) {
				salesLine.setDiscountAmount(lineDTO.getDiscountAmount());
			}

			// Discount source tracking (line level)
			if (lineDTO.getDiscountSource() != null) {
				salesLine.setDiscountSource(lineDTO.getDiscountSource());
				if ("PROMOTION".equals(lineDTO.getDiscountSource()) && lineDTO.getPromotionId() != null) {
					promotionRepository.findById(lineDTO.getPromotionId())
							.ifPresent(salesLine::setPromotion);
				}
			}

			// Set VAT fields - use values from DTO if provided, otherwise calculate
			Integer vatPercent = lineDTO.getVatPercent() != null ? lineDTO.getVatPercent() : item.getDefaultVAT();
			salesLine.setVatPercent(vatPercent);

			if (lineDTO.getVatAmount() != null) {
				salesLine.setVatAmount(lineDTO.getVatAmount());
			} else {
				salesLine.setVatAmount(calculateVat(lineDTO.getLineTotal(), vatPercent));
			}

			if (lineDTO.getUnitPriceIncludingVat() != null) {
				salesLine.setUnitPriceIncludingVat(lineDTO.getUnitPriceIncludingVat());
			} else {
				salesLine.setUnitPriceIncludingVat(calculateUnitPriceIncludingVat(lineDTO.getUnitPrice(), vatPercent));
			}

			if (lineDTO.getLineTotalIncludingVat() != null) {
				salesLine.setLineTotalIncludingVat(lineDTO.getLineTotalIncludingVat());
			} else {
				salesLine.setLineTotalIncludingVat(
						lineDTO.getLineTotal() + (salesLine.getVatAmount() != null ? salesLine.getVatAmount() : 0.0));
			}

			salesLine = salesLineService.save(salesLine);
			salesLines.add(salesLine);
			log.info("Sales line created: " + salesLine.getId());

			// FREE_QUANTITY promotion: create a second line for the free items (qty=freeQuantity, total=0)
			if (lineDTO.getFreeQuantity() != null && lineDTO.getFreeQuantity() > 0) {
				SalesLine freeLine = new SalesLine();
				freeLine.setSalesHeader(salesHeader);
				freeLine.setItem(item);
				freeLine.setQuantity(lineDTO.getFreeQuantity());
				freeLine.setUnitPrice(lineDTO.getUnitPrice());
				freeLine.setLineTotal(0.0);
				freeLine.setDiscountPercentage(100.0);
				freeLine.setDiscountAmount(lineDTO.getUnitPrice() != null ? lineDTO.getUnitPrice() * lineDTO.getFreeQuantity() : 0.0);
				freeLine.setDiscountSource("PROMOTION");
				freeLine.setVatPercent(lineDTO.getVatPercent() != null ? lineDTO.getVatPercent() : item.getDefaultVAT());
				freeLine.setVatAmount(0.0);
				freeLine.setUnitPriceIncludingVat(lineDTO.getUnitPriceIncludingVat());
				freeLine.setLineTotalIncludingVat(0.0);
				if (lineDTO.getPromotionId() != null) {
					promotionRepository.findById(lineDTO.getPromotionId())
							.ifPresent(freeLine::setPromotion);
				}
				freeLine = salesLineService.save(freeLine);
				salesLines.add(freeLine);
				log.info("Free line created for FREE_QUANTITY promotion: item={}, freeQty={}", item.getId(), lineDTO.getFreeQuantity());
			}
		}

		// Tax stamp (timbre fiscal) - add one line per receipt when enabled (e.g. Tunisia 100 millimes)
		Optional<GeneralSetup> enableTaxStampOpt = generalSetupRepository.findByCode("ENABLE_TAX_STAMP");
		if (enableTaxStampOpt.isPresent() && "true".equalsIgnoreCase(enableTaxStampOpt.get().getValeur())) {
			Optional<Item> taxStampItemOpt = itemRepository.findByItemCode("TAX_STAMP");
			if (taxStampItemOpt.isPresent()) {
				String valueStr = generalSetupRepository.findByCode("TAX_STAMP_VALUE_MILLIMES")
						.map(GeneralSetup::getValeur).orElse("100");
				int millimes = 100;
				try {
					if (valueStr != null && !valueStr.trim().isEmpty()) {
						millimes = Integer.parseInt(valueStr.trim());
					}
				} catch (NumberFormatException e) {
					log.warn("Invalid TAX_STAMP_VALUE_MILLIMES: {}, using 100", valueStr);
				}
				double stampAmount = millimes / 1000.0; // millimes to TND
				Item taxStampItem = taxStampItemOpt.get();
				SalesLine taxStampLine = new SalesLine();
				taxStampLine.setSalesHeader(salesHeader);
				taxStampLine.setItem(taxStampItem);
				taxStampLine.setQuantity(1);
				taxStampLine.setUnitPrice(stampAmount);
				taxStampLine.setLineTotal(stampAmount);
				taxStampLine.setVatPercent(0);
				taxStampLine.setVatAmount(0.0);
				taxStampLine.setUnitPriceIncludingVat(stampAmount);
				taxStampLine.setLineTotalIncludingVat(stampAmount);
				taxStampLine = salesLineService.save(taxStampLine);
				salesLines.add(taxStampLine);
				// Header totals are set from request; frontend includes tax stamp when enabled
				log.info("Tax stamp line added: {} TND", stampAmount);
			} else {
				log.warn("Tax stamp enabled but TAX_STAMP item not found");
			}
		}

		// Attach loyalty member if provided
		LoyaltyMember loyaltyMember = null;
		if (request.getLoyaltyMemberId() != null) {
			loyaltyMember = loyaltyMemberRepository.findById(request.getLoyaltyMemberId()).orElse(null);
			if (loyaltyMember != null) {
				salesHeader.setLoyaltyMember(loyaltyMember);
			}
		}

		// Create payments and update header paid/change (shared with completePendingSale)
		List<Payment> payments = createAndSavePaymentsForSale(salesHeader, request, currentUser);
		salesHeader = save(salesHeader);

		// Process loyalty points (earn and/or redeem) after sale is committed
		if (loyaltyMember != null) {
			try {
				// Redeem points first (if requested)
				int pointsToRedeem = request.getLoyaltyPointsToRedeem() != null ? request.getLoyaltyPointsToRedeem() : 0;
				if (pointsToRedeem > 0) {
					double deduction = loyaltyService.redeemPoints(loyaltyMember.getId(), pointsToRedeem, salesHeader, currentSession);
					salesHeader.setLoyaltyPointsRedeemed(pointsToRedeem);
					salesHeader.setLoyaltyDeductionAmount(deduction);
				}

				// Earn points on the sale total (always, after redemption)
				int earned = loyaltyService.earnPoints(loyaltyMember.getId(), salesHeader, currentSession);
				if (earned > 0) {
					salesHeader.setLoyaltyPointsEarned(earned);
				}

				salesHeader = save(salesHeader);
			} catch (Exception e) {
				log.error("Error processing loyalty points for sale {}: {}", salesNumber, e.getMessage(), e);
				// Do not fail the sale if loyalty points processing fails
			}
		}

		// Update stock (standalone only; shared helper)
		decrementStockForSalesLines(salesLines, salesHeader);

		triggerSessionExportForPayments(payments, salesHeader);

		// Note: Printing is now handled by the frontend (each POS terminal)
		// This allows multiple POS terminals to print independently
		log.info("Sale completed successfully: " + salesNumber + ". Printing handled by frontend.");

		return salesHeader;
	}

	/**
	 * Save a pending sale (without payments) - customer can continue later
	 */
	@Transactional(rollbackFor = Exception.class)
	public SalesHeader savePendingSale(ProcessSaleRequestDTO request, UserAccount currentUser) throws Exception {
		log.info("Saving pending sale for user: " + currentUser.getUsername());

		// Get current cashier session
		CashierSession currentSession = cashierSessionRepository
				.findByCashierAndStatus(currentUser, com.digithink.pos.model.enumeration.SessionStatus.OPENED)
				.orElseThrow(() -> new IllegalStateException("No open cashier session found"));

		// Generate sales number
		String salesNumber = generateSalesNumber();

		// Create sales header with PENDING status
		SalesHeader salesHeader = new SalesHeader();
		salesHeader.setSalesNumber(salesNumber);
		salesHeader.setSalesDate(LocalDateTime.now());
		salesHeader.setCreatedByUser(currentUser);
		salesHeader.setCashierSession(currentSession);
		salesHeader.setStatus(TransactionStatus.PENDING);
		salesHeader.setSubtotal(request.getSubtotal());
		salesHeader.setTaxAmount(request.getTaxAmount());
		salesHeader.setDiscountAmount(request.getDiscountAmount() != null ? request.getDiscountAmount() : 0.0);
		salesHeader.setDiscountPercentage(request.getDiscountPercentage());
		salesHeader.setTotalAmount(request.getTotalAmount());
		salesHeader.setPaidAmount(0.0); // No payment yet
		salesHeader.setChangeAmount(0.0);
		salesHeader.setNotes(request.getNotes());
		salesHeader.setTableNumber(request.getTableNumber());
		// completedDate is null for pending sales

		// Set customer - use provided customer or passenger customer
		Customer customer = null;
		if (request.getCustomerId() != null) {
			customer = customerRepository.findById(request.getCustomerId()).orElse(null);
		}

		// If no customer provided, use passenger customer from GeneralSetup
		if (customer == null) {
			String passengerCustomerId = generalSetupRepository.findByCode("PASSENGER_CUSTOMER").get().getValeur();

			if (passengerCustomerId != null) {
				customer = customerRepository.findByCustomerCode(passengerCustomerId)
						.orElseThrow(() -> new IllegalStateException("Deafult customer not found"));
				if (customer != null) {
					log.info("Using passenger customer: " + customer.getCustomerCode() + " - " + customer.getName());
				} else {
					log.warn("Passenger customer ID found in GeneralSetup but customer not found: "
							+ passengerCustomerId);
				}
			} else {
				log.warn("PASSENGER_CUSTOMER not found in GeneralSetup");
			}
		}

		// Set customer (will be passenger customer if none provided)
		if (customer != null) {
			salesHeader.setCustomer(customer);
		} else {
			log.error(
					"No customer assigned to pending sales header - customer was null and passenger customer not available");
		}

		// Save sales header
		salesHeader = save(salesHeader);
		log.info("Pending sales header created: " + salesHeader.getId());

		// Create sales lines
		List<SalesLine> salesLines = new ArrayList<>();
		for (ProcessSaleRequestDTO.SaleLineDTO lineDTO : request.getLines()) {
			Item item = itemRepository.findById(lineDTO.getItemId())
					.orElseThrow(() -> new IllegalArgumentException("Item not found: " + lineDTO.getItemId()));

			SalesLine salesLine = new SalesLine();
			salesLine.setSalesHeader(salesHeader);
			salesLine.setItem(item);
			salesLine.setQuantity(lineDTO.getQuantity());

			// Apply pricing from PricingService (SalesPrice/SalesDiscount)
			applyPricingToSalesLine(salesLine, item, customer, lineDTO);

			salesLine = salesLineService.save(salesLine);
			salesLines.add(salesLine);
			log.info("Pending sales line created: " + salesLine.getId());
		}

		// Note: No payments are created for pending sales
		// Payments will be added when the sale is completed
		log.info("Pending sale saved successfully: " + salesNumber);

		return salesHeader;
	}

	/**
	 * Update an existing pending sale's lines and totals (used when returning to table grid).
	 * Replaces all existing lines with the new ones from the request.
	 */
	@Transactional(rollbackFor = Exception.class)
	public SalesHeader updatePendingSale(Long salesHeaderId, ProcessSaleRequestDTO request, UserAccount currentUser)
			throws Exception {
		log.info("Updating pending sale: " + salesHeaderId + " for user: " + currentUser.getUsername());

		SalesHeader salesHeader = findById(salesHeaderId)
				.orElseThrow(() -> new IllegalArgumentException("Pending sale not found: " + salesHeaderId));

		if (salesHeader.getStatus() != TransactionStatus.PENDING) {
			throw new IllegalStateException(
					"Sale is not in PENDING status. Current status: " + salesHeader.getStatus());
		}

		// Update header totals
		salesHeader.setSubtotal(request.getSubtotal());
		salesHeader.setTaxAmount(request.getTaxAmount());
		salesHeader.setDiscountAmount(request.getDiscountAmount() != null ? request.getDiscountAmount() : 0.0);
		salesHeader.setDiscountPercentage(request.getDiscountPercentage());
		salesHeader.setTotalAmount(request.getTotalAmount());
		salesHeader.setNotes(request.getNotes());
		if (request.getTableNumber() != null) {
			salesHeader.setTableNumber(request.getTableNumber());
		}
		if (request.getCustomerId() != null) {
			customerRepository.findById(request.getCustomerId()).ifPresent(salesHeader::setCustomer);
		}

		salesHeader = save(salesHeader);

		// Replace all existing lines with the new ones
		salesLineRepository.deleteBySalesHeader(salesHeader);

		Customer customer = salesHeader.getCustomer();
		for (ProcessSaleRequestDTO.SaleLineDTO lineDTO : request.getLines()) {
			Item item = itemRepository.findById(lineDTO.getItemId())
					.orElseThrow(() -> new IllegalArgumentException("Item not found: " + lineDTO.getItemId()));

			SalesLine salesLine = new SalesLine();
			salesLine.setSalesHeader(salesHeader);
			salesLine.setItem(item);
			salesLine.setQuantity(lineDTO.getQuantity());
			applyPricingToSalesLine(salesLine, item, customer, lineDTO);
			salesLineService.save(salesLine);
		}

		log.info("Pending sale updated successfully: " + salesHeader.getSalesNumber());
		return salesHeader;
	}

	/**
	 * Complete a pending sale by adding payments
	 */
	@Transactional(rollbackFor = Exception.class)
	public SalesHeader completePendingSale(Long salesHeaderId, ProcessSaleRequestDTO request, UserAccount currentUser)
			throws Exception {
		log.info("Completing pending sale: " + salesHeaderId + " for user: " + currentUser.getUsername());

		// Get the pending sale
		SalesHeader salesHeader = findById(salesHeaderId)
				.orElseThrow(() -> new IllegalArgumentException("Pending sale not found: " + salesHeaderId));

		// Verify it's actually pending
		if (salesHeader.getStatus() != TransactionStatus.PENDING) {
			throw new IllegalStateException(
					"Sale is not in PENDING status. Current status: " + salesHeader.getStatus());
		}

		// Verify session is still open
		CashierSession currentSession = cashierSessionRepository
				.findByCashierAndStatus(currentUser, com.digithink.pos.model.enumeration.SessionStatus.OPENED)
				.orElseThrow(() -> new IllegalStateException("No open cashier session found"));

		if (!salesHeader.getCashierSession().getId().equals(currentSession.getId())) {
			throw new IllegalStateException("Pending sale does not belong to current session");
		}

		// Update totals (in case they changed)
		salesHeader.setSubtotal(request.getSubtotal());
		salesHeader.setTaxAmount(request.getTaxAmount());
		salesHeader.setDiscountAmount(request.getDiscountAmount() != null ? request.getDiscountAmount() : 0.0);
		salesHeader.setDiscountPercentage(request.getDiscountPercentage());
		salesHeader.setTotalAmount(request.getTotalAmount());
		if (request.getNotes() != null) {
			salesHeader.setNotes(request.getNotes());
		}

		// Discount source tracking (header level)
		if (request.getDiscountSource() != null) {
			salesHeader.setDiscountSource(request.getDiscountSource());
			if ("PROMOTION".equals(request.getDiscountSource()) && request.getPromotionId() != null) {
				promotionRepository.findById(request.getPromotionId())
						.ifPresent(salesHeader::setPromotion);
			}
		}

		// Delete existing sales lines (in case items were added/removed/modified)
		salesLineRepository.deleteBySalesHeader(salesHeader);
		log.info("Deleted all old sales lines for pending sale: " + salesHeader.getId());

		// Get customer from existing sales header
		Customer customer = salesHeader.getCustomer();

		// Create new sales lines based on current cart (may have changed)
		List<SalesLine> salesLines = new ArrayList<>();
		for (ProcessSaleRequestDTO.SaleLineDTO lineDTO : request.getLines()) {
			Item item = itemRepository.findById(lineDTO.getItemId())
					.orElseThrow(() -> new IllegalArgumentException("Item not found: " + lineDTO.getItemId()));

			SalesLine salesLine = new SalesLine();
			salesLine.setSalesHeader(salesHeader);
			salesLine.setItem(item);
			salesLine.setQuantity(lineDTO.getQuantity());

			// Apply pricing from PricingService (SalesPrice/SalesDiscount)
			applyPricingToSalesLine(salesLine, item, customer, lineDTO);

			if (lineDTO.getLineTotalIncludingVat() != null) {
				salesLine.setLineTotalIncludingVat(lineDTO.getLineTotalIncludingVat());
			} else {
				salesLine.setLineTotalIncludingVat(
						lineDTO.getLineTotal() + (salesLine.getVatAmount() != null ? salesLine.getVatAmount() : 0.0));
			}

			// Discount source tracking (line level)
			if (lineDTO.getDiscountSource() != null) {
				salesLine.setDiscountSource(lineDTO.getDiscountSource());
				if ("PROMOTION".equals(lineDTO.getDiscountSource()) && lineDTO.getPromotionId() != null) {
					promotionRepository.findById(lineDTO.getPromotionId())
							.ifPresent(salesLine::setPromotion);
				}
			}

			salesLine = salesLineService.save(salesLine);
			salesLines.add(salesLine);
			log.info("Created new sales line: " + salesLine.getId() + " for pending sale completion");

			// Create free line for FREE_QUANTITY promotions
			if (lineDTO.getFreeQuantity() != null && lineDTO.getFreeQuantity() > 0) {
				SalesLine freeLine = new SalesLine();
				freeLine.setSalesHeader(salesHeader);
				freeLine.setItem(item);
				freeLine.setQuantity(lineDTO.getFreeQuantity());
				freeLine.setUnitPrice(lineDTO.getUnitPrice());
				freeLine.setLineTotal(0.0);
				freeLine.setDiscountPercentage(100.0);
				freeLine.setDiscountAmount(lineDTO.getUnitPrice() != null ? lineDTO.getUnitPrice() * lineDTO.getFreeQuantity() : 0.0);
				freeLine.setDiscountSource("PROMOTION");
				freeLine.setVatPercent(lineDTO.getVatPercent() != null ? lineDTO.getVatPercent() : item.getDefaultVAT());
				freeLine.setVatAmount(0.0);
				freeLine.setUnitPriceIncludingVat(lineDTO.getUnitPriceIncludingVat());
				freeLine.setLineTotalIncludingVat(0.0);
				if (lineDTO.getPromotionId() != null) {
					promotionRepository.findById(lineDTO.getPromotionId())
							.ifPresent(freeLine::setPromotion);
				}
				freeLine = salesLineService.save(freeLine);
				salesLines.add(freeLine);
				log.info("Created free line (qty={}) for pending sale completion, item={}", lineDTO.getFreeQuantity(), item.getId());
			}
		}

		// Create payments and update header paid/change (shared with processCompleteSale)
		List<Payment> payments = createAndSavePaymentsForSale(salesHeader, request, currentUser);

		// Update stock (standalone only; shared helper)
		decrementStockForSalesLines(salesLines, salesHeader);

		// Mark header completed and persist
		salesHeader.setStatus(TransactionStatus.COMPLETED);
		salesHeader.setCompletedDate(LocalDateTime.now());
		salesHeader = save(salesHeader);

		log.info("Pending sale completed successfully: " + salesHeader.getSalesNumber());

		triggerSessionExportForPayments(payments, salesHeader);

		return salesHeader;
	}

	/**
	 * Get regular pending sales for current session (excludes table-linked tickets).
	 * Used for the pending tickets panel in ItemSelection — table tickets are managed separately.
	 */
	public List<SalesHeader> getPendingSalesForSession(CashierSession session) {
		return salesHeaderRepository.findByCashierSessionAndStatusAndTableNumberIsNull(session, TransactionStatus.PENDING);
	}

	/**
	 * Get table-linked pending tickets for current session (tableNumber IS NOT NULL).
	 * Used by the table selection grid to show occupied tables and their totals.
	 */
	public List<SalesHeader> getTableTicketsForSession(CashierSession session) {
		return salesHeaderRepository.findByCashierSessionAndStatusAndTableNumberIsNotNull(session, TransactionStatus.PENDING);
	}

	/**
	 * Split-bill: pay for a subset of lines from a pending table ticket.
	 * 1. Creates a new COMPLETED SalesHeader with only the selected lines.
	 * 2. Removes those lines from the original pending ticket and recalculates its totals.
	 * 3. If the original ticket has no remaining lines, cancels it (table becomes free).
	 * Returns the new completed SalesHeader for receipt printing.
	 */
	@Transactional(rollbackFor = Exception.class)
	public SalesHeader splitAndPay(Long originalId, SplitBillRequestDTO request, UserAccount currentUser)
			throws Exception {

		// 1. Load and validate the original pending ticket
		SalesHeader original = findById(originalId)
				.orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + originalId));
		if (original.getStatus() != TransactionStatus.PENDING) {
			throw new IllegalStateException("Only PENDING tickets can be split");
		}
		if (request.getSelectedLineIds() == null || request.getSelectedLineIds().isEmpty()) {
			throw new IllegalArgumentException("At least one line must be selected for split payment");
		}

		List<SalesLine> allLines = salesLineRepository.findBySalesHeader(original);
		List<SalesLine> selectedLines = allLines.stream()
				.filter(l -> request.getSelectedLineIds().contains(l.getId()))
				.collect(java.util.stream.Collectors.toList());
		if (selectedLines.isEmpty()) {
			throw new IllegalArgumentException("None of the selected line IDs found on this ticket");
		}

		// 2. Resolve customer
		Customer customer = original.getCustomer();
		if (request.getCustomerId() != null) {
			customer = customerRepository.findById(request.getCustomerId()).orElse(customer);
		}

		// 3. Create the new completed SalesHeader for the split portion
		String salesNumber = generateSalesNumber();
		SalesHeader splitHeader = new SalesHeader();
		splitHeader.setSalesNumber(salesNumber);
		splitHeader.setSalesDate(LocalDateTime.now());
		splitHeader.setCreatedByUser(currentUser);
		splitHeader.setCashierSession(original.getCashierSession());
		splitHeader.setStatus(TransactionStatus.COMPLETED);
		splitHeader.setCustomer(customer);
		splitHeader.setSubtotal(request.getSubtotal());
		splitHeader.setTaxAmount(request.getTaxAmount());
		splitHeader.setDiscountAmount(0.0);
		splitHeader.setTotalAmount(request.getTotalAmount());
		splitHeader.setPaidAmount(request.getPaidAmount());
		boolean splitHasCash = request.getPayments() != null && request.getPayments().stream().anyMatch(p -> {
			PaymentMethod pm = paymentMethodRepository.findById(p.getPaymentMethodId()).orElse(null);
			return pm != null && pm.getType() == PaymentMethodType.CLIENT_ESPECES;
		});
		double splitChange = request.getChangeAmount() != null ? request.getChangeAmount() : 0.0;
		splitHeader.setChangeAmount((splitChange > 0 && splitHasCash) ? splitChange : 0.0);
		splitHeader.setCompletedDate(LocalDateTime.now());
		splitHeader.setNotes("Split from " + original.getSalesNumber());
		splitHeader.setTableNumber(original.getTableNumber());
		splitHeader = save(splitHeader);

		// 4. Copy selected lines to the new header
		for (SalesLine origLine : selectedLines) {
			SalesLine newLine = new SalesLine();
			newLine.setSalesHeader(splitHeader);
			newLine.setItem(origLine.getItem());
			newLine.setQuantity(origLine.getQuantity());
			newLine.setUnitPrice(origLine.getUnitPrice());
			newLine.setLineTotal(origLine.getLineTotal());
			newLine.setDiscountPercentage(origLine.getDiscountPercentage());
			newLine.setDiscountAmount(origLine.getDiscountAmount());
			newLine.setVatPercent(origLine.getVatPercent());
			newLine.setVatAmount(origLine.getVatAmount());
			newLine.setUnitPriceIncludingVat(origLine.getUnitPriceIncludingVat());
			newLine.setLineTotalIncludingVat(origLine.getLineTotalIncludingVat());
			salesLineService.save(newLine);
		}

		// 5. Create payments for the split header
		if (request.getPayments() != null) {
			for (SplitBillRequestDTO.PaymentDTO payDTO : request.getPayments()) {
				PaymentMethod method = paymentMethodRepository.findById(payDTO.getPaymentMethodId())
						.orElseThrow(() -> new IllegalArgumentException("Payment method not found: " + payDTO.getPaymentMethodId()));
				Payment payment = new Payment();
				payment.setSalesHeader(splitHeader);
				payment.setPaymentMethod(method);
				payment.setTotalAmount(payDTO.getAmount());
				payment.setTitleNumber(payDTO.getTitleNumber());
				payment.setDueDate(payDTO.getDueDate());
				payment.setDrawerName(payDTO.getDrawerName());
				payment.setIssuingBank(payDTO.getIssuingBank());
				paymentService.save(payment);
			}
		}

		// 6. Remove selected lines from original and recalculate totals
		java.util.Set<Long> selectedIds = new java.util.HashSet<>(request.getSelectedLineIds());
		List<SalesLine> remainingLines = allLines.stream()
				.filter(l -> !selectedIds.contains(l.getId()))
				.collect(java.util.stream.Collectors.toList());

		for (SalesLine toRemove : selectedLines) {
			salesLineRepository.delete(toRemove);
		}

		if (remainingLines.isEmpty()) {
			// Table is now empty — cancel the original ticket
			original.setStatus(TransactionStatus.CANCELLED);
			save(original);
			log.info("Split-bill: original ticket {} cancelled (all lines paid)", original.getSalesNumber());
		} else {
			// Recalculate totals from remaining lines
			double newSubtotal = remainingLines.stream().mapToDouble(l -> l.getLineTotal() != null ? l.getLineTotal() : 0.0).sum();
			double newTax = remainingLines.stream().mapToDouble(l -> l.getVatAmount() != null ? l.getVatAmount() : 0.0).sum();
			double newTotal = remainingLines.stream().mapToDouble(l -> l.getLineTotalIncludingVat() != null ? l.getLineTotalIncludingVat() : 0.0).sum();
			original.setSubtotal(newSubtotal);
			original.setTaxAmount(newTax);
			original.setTotalAmount(newTotal);
			original.setDiscountAmount(0.0);
			save(original);
			log.info("Split-bill: original ticket {} updated, {} lines remaining", original.getSalesNumber(), remainingLines.size());
		}

		log.info("Split-bill completed: new ticket {}, original {}", splitHeader.getSalesNumber(), original.getSalesNumber());
		return splitHeader;
	}

	/**
	 * Transfer a pending table ticket to a different table number.
	 * Target table must not already have a pending ticket.
	 */
	@Transactional(rollbackFor = Exception.class)
	public void transferTable(Long salesHeaderId, Integer targetTableNumber) throws Exception {
		SalesHeader salesHeader = findById(salesHeaderId)
				.orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + salesHeaderId));
		if (salesHeader.getStatus() != TransactionStatus.PENDING) {
			throw new IllegalStateException("Only PENDING tickets can be transferred");
		}
		// Check target table is free
		boolean targetOccupied = salesHeaderRepository
				.findByCashierSessionAndStatusAndTableNumberIsNotNull(salesHeader.getCashierSession(), TransactionStatus.PENDING)
				.stream()
				.anyMatch(h -> targetTableNumber.equals(h.getTableNumber()));
		if (targetOccupied) {
			throw new IllegalStateException("Target table " + targetTableNumber + " is already occupied");
		}
		salesHeader.setTableNumber(targetTableNumber);
		save(salesHeader);
		log.info("Table ticket {} transferred to table {}", salesHeaderId, targetTableNumber);
	}

	/**
	 * Single place for creating and saving payments for a sale. Updates the header's paidAmount and changeAmount.
	 * Used by both processCompleteSale and completePendingSale.
	 */
	private List<Payment> createAndSavePaymentsForSale(SalesHeader salesHeader, ProcessSaleRequestDTO request,
			UserAccount currentUser) throws Exception {
		if (request.getPayments() == null || request.getPayments().isEmpty()) {
			throw new IllegalArgumentException("At least one payment method is required");
		}
		validateCashPlafond(request.getPayments());

		List<Payment> payments = new ArrayList<>();
		Double totalPaid = 0.0;

		for (ProcessSaleRequestDTO.PaymentDTO paymentDTO : request.getPayments()) {
			if (paymentDTO.getPaymentMethodId() == null) {
				throw new IllegalArgumentException("Payment method ID is required for all payments");
			}
			if (paymentDTO.getAmount() == null || paymentDTO.getAmount() <= 0) {
				throw new IllegalArgumentException("Payment amount must be greater than 0");
			}

			PaymentMethod paymentMethod = paymentMethodRepository.findById(paymentDTO.getPaymentMethodId()).orElseThrow(
					() -> new IllegalArgumentException("Payment method not found: " + paymentDTO.getPaymentMethodId()));

			if (paymentMethod.getType() == PaymentMethodType.RETURN_VOUCHER) {
				if (paymentDTO.getReference() == null || paymentDTO.getReference().trim().isEmpty()) {
					throw new IllegalArgumentException("Voucher number is required for return voucher payment");
				}
				ReturnVoucher voucher = returnVoucherService.findByVoucherNumber(paymentDTO.getReference()).orElseThrow(
						() -> new IllegalArgumentException("Return voucher not found: " + paymentDTO.getReference()));
				if (!returnVoucherService.isVoucherValid(voucher)) {
					throw new IllegalStateException("Return voucher is not valid (expired or fully used)");
				}
				double remainingAmount = returnVoucherService.getRemainingAmount(voucher);
				if (paymentDTO.getAmount() > remainingAmount) {
					throw new IllegalArgumentException("Payment amount (" + paymentDTO.getAmount()
							+ ") exceeds remaining voucher amount (" + remainingAmount + ")");
				}
				returnVoucherService.useVoucherAmount(paymentDTO.getReference(), paymentDTO.getAmount());
				paymentDTO.setReference(paymentDTO.getReference());
			}

			validateAdditionalPaymentFields(paymentMethod, paymentDTO);

			Payment payment = new Payment();
			payment.setSalesHeader(salesHeader);
			payment.setPaymentMethod(paymentMethod);
			payment.setCreatedByUser(currentUser);
			payment.setStatus(TransactionStatus.COMPLETED);
			payment.setTotalAmount(paymentDTO.getAmount());
			payment.setPaymentDate(LocalDateTime.now());
			payment.setPaymentReference(paymentDTO.getReference());
			payment.setNotes(paymentDTO.getNotes());
			populateAdditionalPaymentFields(payment, paymentDTO);

			payment = paymentService.save(payment);
			payments.add(payment);
			totalPaid += paymentDTO.getAmount();
			log.info("Payment created: {} - Method: {}, Amount: {}", payment.getId(), paymentMethod.getName(),
					paymentDTO.getAmount());
		}

		salesHeader.setPaidAmount(totalPaid);
		boolean hasCashPayment = payments.stream()
				.anyMatch(p -> p.getPaymentMethod() != null
						&& p.getPaymentMethod().getType() == PaymentMethodType.CLIENT_ESPECES);
		double change = totalPaid - request.getTotalAmount();
		salesHeader.setChangeAmount((change > 0 && hasCashPayment) ? change : 0.0);
		return payments;
	}

	/**
	 * Change the payment method of a single payment on a COMPLETED ticket, WITHOUT
	 * changing any amount. Allowed only while the ticket's cashier session has not yet
	 * been synchronized with NAV (OPENED or CLOSED — never TERMINATED). The session is
	 * pessimistically locked for the whole operation, the ERP payment-export rows are
	 * rebuilt from the current payments, and (for CLOSED sessions) the frozen realCash
	 * is recomputed. Every change is recorded in {@link PaymentChangeLog}.
	 */
	@Transactional(rollbackFor = Exception.class)
	public Payment changePaymentMethod(Long salesHeaderId, ChangePaymentMethodRequestDTO request,
			UserAccount currentUser) throws Exception {
		if (request == null || request.getPaymentId() == null || request.getNewPaymentMethodId() == null) {
			throw new IllegalArgumentException("paymentId and newPaymentMethodId are required");
		}

		// Feature flag (GeneralSetup) — disabled by default.
		String featureEnabled = generalSetupRepository.findByCode("ENABLE_PAYMENT_METHOD_CHANGE")
				.map(GeneralSetup::getValeur).orElse("false");
		if (!"true".equalsIgnoreCase(featureEnabled)) {
			throw new IllegalStateException("Payment method change is disabled");
		}

		SalesHeader ticket = salesHeaderRepository.findById(salesHeaderId)
				.orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + salesHeaderId));

		CashierSession session = ticket.getCashierSession();
		if (session == null) {
			throw new IllegalStateException("Ticket has no cashier session");
		}
		// Lock the session for the whole operation (serializes against the async export
		// creator and concurrent edits). Use the locked instance from here on.
		session = cashierSessionRepository.findByIdForUpdate(session.getId())
				.orElseThrow(() -> new IllegalStateException("Session not found"));

		// --- Guards ---
		if (ticket.getStatus() != TransactionStatus.COMPLETED) {
			throw new IllegalStateException("Only completed tickets can be modified");
		}
		if (session.getStatus() == SessionStatus.TERMINATED) {
			throw new IllegalStateException("Session is terminated; payment method can no longer be changed");
		}
		// NOTE: we intentionally gate on the SESSION (payment-export) sync state, NOT on
		// SalesHeader.synchronizationStatus. Payment method only reaches NAV via the session
		// PaymentHeader/PaymentLine track; the ticket/sales export carries no payment method.
		// So a ticket whose sales lines are already in NAV can still have its payment method
		// corrected, as long as the session's payments have not been exported yet.
		if (session.getSynchronizationStatus() != SynchronizationStatus.NOT_SYNCHED) {
			throw new IllegalStateException("Session already synchronized with NAV; payment method cannot be changed");
		}
		if (Boolean.TRUE.equals(ticket.getInvoiced())) {
			throw new IllegalStateException("Ticket is invoiced; payment method cannot be changed");
		}

		Payment payment = paymentService.findById(request.getPaymentId())
				.orElseThrow(() -> new IllegalArgumentException("Payment not found: " + request.getPaymentId()));
		if (payment.getSalesHeader() == null || !payment.getSalesHeader().getId().equals(ticket.getId())) {
			throw new IllegalArgumentException("Payment does not belong to this ticket");
		}
		if (Boolean.TRUE.equals(payment.getSynched())) {
			throw new IllegalStateException("Payment already synchronized with NAV");
		}

		PaymentMethod oldMethod = payment.getPaymentMethod();
		if (oldMethod != null && oldMethod.getType() == PaymentMethodType.RETURN_VOUCHER) {
			throw new IllegalStateException("Return voucher payments cannot be changed");
		}

		PaymentMethod newMethod = paymentMethodRepository.findById(request.getNewPaymentMethodId())
				.orElseThrow(() -> new IllegalArgumentException(
						"Payment method not found: " + request.getNewPaymentMethodId()));
		if (!Boolean.TRUE.equals(newMethod.getActive())) {
			throw new IllegalArgumentException("Selected payment method is not active");
		}
		if (newMethod.getType() == PaymentMethodType.RETURN_VOUCHER) {
			throw new IllegalArgumentException("Cannot change a payment to a return voucher");
		}
		if (oldMethod != null && oldMethod.getId().equals(newMethod.getId())) {
			throw new IllegalArgumentException("New payment method is the same as the current one");
		}

		// Validate the new method's required fields (title number, due date, drawer, bank).
		ProcessSaleRequestDTO.PaymentDTO fieldDto = new ProcessSaleRequestDTO.PaymentDTO();
		fieldDto.setPaymentMethodId(newMethod.getId());
		fieldDto.setAmount(payment.getTotalAmount());
		fieldDto.setTitleNumber(request.getTitleNumber());
		fieldDto.setDueDate(request.getDueDate());
		fieldDto.setDrawerName(request.getDrawerName());
		fieldDto.setIssuingBank(request.getIssuingBank());
		validateAdditionalPaymentFields(newMethod, fieldDto);

		// If switching TO cash, re-validate the ticket's total cash against PLAFOND_ESPECE.
		if (newMethod.getType() == PaymentMethodType.CLIENT_ESPECES) {
			List<ProcessSaleRequestDTO.PaymentDTO> ticketCashView = new ArrayList<>();
			for (Payment p : paymentRepository.findBySalesHeader(ticket)) {
				ProcessSaleRequestDTO.PaymentDTO d = new ProcessSaleRequestDTO.PaymentDTO();
				boolean isTarget = p.getId().equals(payment.getId());
				d.setPaymentMethodId(isTarget ? newMethod.getId()
						: (p.getPaymentMethod() != null ? p.getPaymentMethod().getId() : null));
				d.setAmount(p.getTotalAmount());
				ticketCashView.add(d);
			}
			validateCashPlafond(ticketCashView);
		}

		Double changeAmountOld = ticket.getChangeAmount();
		Double realCashOld = session.getStatus() == SessionStatus.CLOSED ? session.getRealCash() : null;

		// --- Apply the change (UPDATE in place; amount untouched) ---
		payment.setPaymentMethod(newMethod);
		payment.setTitleNumber(Boolean.TRUE.equals(newMethod.getRequireTitleNumber()) ? request.getTitleNumber() : null);
		payment.setDueDate(Boolean.TRUE.equals(newMethod.getRequireDueDate()) ? request.getDueDate() : null);
		payment.setDrawerName(Boolean.TRUE.equals(newMethod.getRequireDrawerName()) ? request.getDrawerName() : null);
		payment.setIssuingBank(Boolean.TRUE.equals(newMethod.getRequireIssuingBank()) ? request.getIssuingBank() : null);
		if (currentUser != null) {
			payment.setUpdatedBy(currentUser.getUsername());
		}
		paymentService.save(payment);

		// Recompute the ticket's cash change from its current payments, then persist.
		recomputeChangeAmount(ticket);
		save(ticket);

		// Rebuild the session's (all NOT_SYNCHED) ERP payment-export rows from current payments.
		sessionExportService.rebuildSessionPaymentRows(session);

		// For a CLOSED session the expected cash is frozen — recompute & persist it.
		Double realCashNew = null;
		if (session.getStatus() == SessionStatus.CLOSED) {
			realCashNew = cashierSessionService.recalculateRealCashForClosedSession(session);
		}

		// --- Audit ---
		PaymentChangeLog audit = new PaymentChangeLog();
		audit.setSalesHeader(ticket);
		audit.setPayment(payment);
		audit.setOldPaymentMethod(oldMethod);
		audit.setNewPaymentMethod(newMethod);
		audit.setAmount(payment.getTotalAmount());
		audit.setChangeAmountOld(changeAmountOld);
		audit.setChangeAmountNew(ticket.getChangeAmount());
		audit.setRealCashOld(realCashOld);
		audit.setRealCashNew(realCashNew);
		audit.setChangedByUser(currentUser);
		audit.setSessionStatus(session.getStatus() != null ? session.getStatus().name() : null);
		audit.setReason(request.getReason());
		if (currentUser != null) {
			audit.setCreatedBy(currentUser.getUsername());
		}
		paymentChangeLogRepository.save(audit);

		log.info("Payment method changed: ticket={}, payment={}, {} -> {}, by={}, session={}", ticket.getSalesNumber(),
				payment.getId(), oldMethod != null ? oldMethod.getCode() : "?", newMethod.getCode(),
				currentUser != null ? currentUser.getUsername() : "?", session.getSessionNumber());

		return payment;
	}

	/**
	 * Recompute a ticket's cash change from its current payments: change is only given
	 * when at least one CLIENT_ESPECES payment is present (mirrors createAndSavePaymentsForSale).
	 */
	private void recomputeChangeAmount(SalesHeader ticket) {
		List<Payment> ticketPayments = paymentRepository.findBySalesHeader(ticket);
		double totalPaid = ticketPayments.stream()
				.mapToDouble(p -> p.getTotalAmount() != null ? p.getTotalAmount() : 0.0).sum();
		boolean hasCashPayment = ticketPayments.stream().anyMatch(p -> p.getPaymentMethod() != null
				&& p.getPaymentMethod().getType() == PaymentMethodType.CLIENT_ESPECES);
		double total = ticket.getTotalAmount() != null ? ticket.getTotalAmount() : 0.0;
		double change = totalPaid - total;
		ticket.setChangeAmount((change > 0 && hasCashPayment) ? change : 0.0);
	}

	/**
	 * Decrement stock for all sale lines (standalone only). Single place used by processCompleteSale and completePendingSale.
	 */
	private void decrementStockForSalesLines(List<SalesLine> salesLines, SalesHeader salesHeader) {
		for (SalesLine line : salesLines) {
			if (line.getItem() != null && line.getQuantity() != null && line.getQuantity() > 0) {
				if ("TAX_STAMP".equals(line.getItem().getItemCode())) continue;
				stockService.decrementForSale(line.getItem().getId(), line.getQuantity());
				stockMovementService.recordSale(
						line.getItem().getId(), line.getQuantity(),
						line.getUnitPrice(), line.getVatPercent(), line.getUnitPriceIncludingVat(),
						salesHeader.getId(), salesHeader.getCashierSession());
			}
		}
	}

	/**
	 * Trigger async creation of payment headers/lines for session export. Non-fatal on failure.
	 */
	private void triggerSessionExportForPayments(List<Payment> payments, SalesHeader salesHeader) {
		try {
			sessionExportService.createPaymentHeadersAndLinesAsync(payments, salesHeader);
			log.info("Triggered async creation of payment headers/lines for {} payments", payments.size());
		} catch (Exception ex) {
			log.error("Failed to trigger async creation of payment headers/lines: {}", ex.getMessage(), ex);
		}
	}

	private void validateAdditionalPaymentFields(PaymentMethod paymentMethod,
			ProcessSaleRequestDTO.PaymentDTO paymentDTO) {
		boolean requireTitle = Boolean.TRUE.equals(paymentMethod.getRequireTitleNumber());
		boolean requireDueDate = Boolean.TRUE.equals(paymentMethod.getRequireDueDate());
		boolean requireDrawer = Boolean.TRUE.equals(paymentMethod.getRequireDrawerName());
		boolean requireBank = Boolean.TRUE.equals(paymentMethod.getRequireIssuingBank());

		if (requireTitle && isBlank(paymentDTO.getTitleNumber())) {
			throw new IllegalArgumentException(
					"Title number is required for payment method: " + paymentMethod.getName());
		}

		// Validate title number length if configured in GeneralSetup
		if (requireTitle && !isBlank(paymentDTO.getTitleNumber())) {
			Integer requiredLength = getTitleNumberLengthForPaymentType(paymentMethod.getType());
			if (requiredLength != null) {
				String titleNumber = paymentDTO.getTitleNumber().trim();
				if (titleNumber.length() != requiredLength) {
					throw new IllegalArgumentException(
							"Title number must be exactly " + requiredLength + " characters for payment method: "
									+ paymentMethod.getName() + ". Provided: " + titleNumber.length() + " characters.");
				}
			}
		}

		if (requireDueDate && paymentDTO.getDueDate() == null) {
			throw new IllegalArgumentException("Due date is required for payment method: " + paymentMethod.getName());
		}
		if (requireDrawer && isBlank(paymentDTO.getDrawerName())) {
			throw new IllegalArgumentException(
					"Drawer name is required for payment method: " + paymentMethod.getName());
		}
		if (requireBank && isBlank(paymentDTO.getIssuingBank())) {
			throw new IllegalArgumentException(
					"Issuing bank is required for payment method: " + paymentMethod.getName());
		}
	}

	private void populateAdditionalPaymentFields(Payment payment, ProcessSaleRequestDTO.PaymentDTO paymentDTO) {
		payment.setTitleNumber(paymentDTO.getTitleNumber());
		payment.setDueDate(paymentDTO.getDueDate());
		payment.setDrawerName(paymentDTO.getDrawerName());
		payment.setIssuingBank(paymentDTO.getIssuingBank());
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	/**
	 * Get the required title number length for a payment method type from
	 * GeneralSetup. Returns null if not configured (backward compatible).
	 * 
	 * Configuration pattern: PAYMENT_METHOD_{TYPE}_TITLE_NUMBER_LENGTH Example:
	 * PAYMENT_METHOD_CLIENT_CHEQUE_TITLE_NUMBER_LENGTH = "7"
	 * 
	 * @param paymentMethodType The payment method type
	 * @return The required length as Integer, or null if not configured
	 */
	private Integer getTitleNumberLengthForPaymentType(PaymentMethodType paymentMethodType) {
		if (paymentMethodType == null) {
			return null;
		}

		String configCode = "PAYMENT_METHOD_" + paymentMethodType.name() + "_TITLE_NUMBER_LENGTH";
		java.util.Optional<GeneralSetup> setup = generalSetupRepository.findByCode(configCode);

		if (setup.isPresent() && setup.get().getValeur() != null) {
			try {
				return Integer.parseInt(setup.get().getValeur().trim());
			} catch (NumberFormatException e) {
				log.warn("Invalid title number length configuration for {}: {}", configCode, setup.get().getValeur());
				return null;
			}
		}

		return null;
	}

	/**
	 * Validate total cash (espèce) amount against Plafond espèce from GeneralSetup.
	 * When PLAFOND_ESPECE is empty or null, no limit is applied. When set (e.g. 1000), total cash per sale must not exceed that value (TND).
	 */
	private void validateCashPlafond(List<ProcessSaleRequestDTO.PaymentDTO> paymentDTOs) {
		double totalCash = 0.0;
		for (ProcessSaleRequestDTO.PaymentDTO dto : paymentDTOs) {
			if (dto.getPaymentMethodId() == null || dto.getAmount() == null || dto.getAmount() <= 0) {
				continue;
			}
			PaymentMethod pm = paymentMethodRepository.findById(dto.getPaymentMethodId()).orElse(null);
			if (pm != null && pm.getType() == PaymentMethodType.CLIENT_ESPECES) {
				totalCash += dto.getAmount();
			}
		}
		java.util.Optional<GeneralSetup> plafondOpt = generalSetupRepository.findByCode("PLAFOND_ESPECE");
		if (!plafondOpt.isPresent()) {
			return;
		}
		String valeur = plafondOpt.get().getValeur();
		if (valeur == null || valeur.trim().isEmpty()) {
			return;
		}
		try {
			double limit = Double.parseDouble(valeur.trim());
			if (limit <= 0) {
				return;
			}
			if (totalCash > limit) {
				throw new IllegalArgumentException(
						"Plafond espèce: le montant total en espèces (" + totalCash + " TND) dépasse la limite autorisée (" + limit + " TND).");
			}
		} catch (NumberFormatException e) {
			log.warn("Invalid PLAFOND_ESPECE value in GeneralSetup: {}", valeur);
		}
	}

	/**
	 * Count pending sales for session
	 */
	public long countPendingSalesForSession(CashierSession session) {
		return salesHeaderRepository.findByCashierSessionAndStatus(session, TransactionStatus.PENDING).size();
	}

	/**
	 * Cancel/Delete a pending sale
	 */
	@Transactional(rollbackFor = Exception.class)
	public void cancelPendingSale(Long salesHeaderId, UserAccount currentUser) throws Exception {
		log.info("Cancelling pending sale: " + salesHeaderId + " for user: " + currentUser.getUsername());

		// Get the pending sale
		SalesHeader salesHeader = findById(salesHeaderId)
				.orElseThrow(() -> new IllegalArgumentException("Pending sale not found: " + salesHeaderId));

		// Verify it's actually pending
		if (salesHeader.getStatus() != TransactionStatus.PENDING) {
			throw new IllegalStateException(
					"Sale is not in PENDING status. Current status: " + salesHeader.getStatus());
		}

		// Verify session is still open and belongs to current user
		CashierSession currentSession = cashierSessionRepository
				.findByCashierAndStatus(currentUser, com.digithink.pos.model.enumeration.SessionStatus.OPENED)
				.orElseThrow(() -> new IllegalStateException("No open cashier session found"));

		if (!salesHeader.getCashierSession().getId().equals(currentSession.getId())) {
			throw new IllegalStateException("Pending sale does not belong to current session");
		}

		// Change status to CANCELLED
		salesHeader.setStatus(TransactionStatus.CANCELLED);
		save(salesHeader);

		log.info("Pending sale cancelled successfully: " + salesHeader.getSalesNumber());
	}

	/**
	 * Generate unique sales number with location prefix and count by day
	 */
	private String generateSalesNumber() {
		// Get default location from GeneralSetup
		String locationCode = generalSetupRepository.findByCode("DEFAULT_LOCATION").map(gs -> gs.getValeur())
				.orElse("LOC001");

		// Count sales for today only
		LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
		long count = salesHeaderRepository.countBySalesDateGreaterThanEqual(todayStart);

		// Format: LOC001251102043 (locationCode + YY + MM + DD + sequence)
		String dateStr = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
		return locationCode + dateStr + String.format("%03d", count + 1);
	}

	/**
	 * Get tickets history with filters
	 */
	public org.springframework.data.domain.Page<SalesHeader> getTicketsHistory(
			String dateFromStr, String dateToStr, String statusStr,
			String syncStatusStr, String paymentMethodIdStr, String searchStr,
			String minPriceStr, String maxPriceStr, String familyIdStr, String subFamilyIdStr,
			String cashierIdStr, String sessionNumberStr, int page, int size) {
		// Parse date from — supports datetime (2026-05-16T08:00) and date-only (2026-05-16)
		java.time.LocalDateTime dateFrom = null;
		if (dateFromStr != null && !dateFromStr.trim().isEmpty()) {
			try {
				dateFrom = java.time.LocalDateTime.parse(dateFromStr.trim(),
						java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
			} catch (Exception e1) {
				try {
					dateFrom = java.time.LocalDate.parse(dateFromStr.trim()).atStartOfDay();
				} catch (Exception e2) {
					log.warn("Invalid dateFrom format: " + dateFromStr);
				}
			}
		}

		// Parse date to — supports datetime (2026-05-16T20:00) and date-only (2026-05-16)
		java.time.LocalDateTime dateTo = null;
		if (dateToStr != null && !dateToStr.trim().isEmpty()) {
			try {
				dateTo = java.time.LocalDateTime.parse(dateToStr.trim(),
						java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
			} catch (Exception e1) {
				try {
					dateTo = java.time.LocalDate.parse(dateToStr.trim()).atTime(23, 59, 59);
				} catch (Exception e2) {
					log.warn("Invalid dateTo format: " + dateToStr);
				}
			}
		}

		// Parse status
		TransactionStatus status = null;
		if (statusStr != null && !statusStr.trim().isEmpty() && !statusStr.equalsIgnoreCase("all")) {
			try {
				status = TransactionStatus.valueOf(statusStr.toUpperCase());
			} catch (Exception e) {
				log.warn("Invalid status: " + statusStr);
			}
		}

		// Parse sync status
		com.digithink.pos.model.enumeration.SynchronizationStatus syncStatus = null;
		if (syncStatusStr != null && !syncStatusStr.trim().isEmpty() && !syncStatusStr.equalsIgnoreCase("all")) {
			try {
				syncStatus = com.digithink.pos.model.enumeration.SynchronizationStatus
						.valueOf(syncStatusStr.toUpperCase());
			} catch (Exception e) {
				log.warn("Invalid syncStatus: " + syncStatusStr);
			}
		}

		// Parse payment method ID
		Long paymentMethodId = null;
		if (paymentMethodIdStr != null && !paymentMethodIdStr.trim().isEmpty()
				&& !paymentMethodIdStr.equalsIgnoreCase("all")) {
			try {
				paymentMethodId = Long.parseLong(paymentMethodIdStr);
			} catch (Exception e) {
				log.warn("Invalid paymentMethodId format: " + paymentMethodIdStr);
			}
		}

		// Parse min/max price
		Double minPrice = null;
		if (minPriceStr != null && !minPriceStr.trim().isEmpty()) {
			try { minPrice = Double.parseDouble(minPriceStr.trim()); } catch (Exception e) { log.warn("Invalid minPrice: " + minPriceStr); }
		}
		Double maxPrice = null;
		if (maxPriceStr != null && !maxPriceStr.trim().isEmpty()) {
			try { maxPrice = Double.parseDouble(maxPriceStr.trim()); } catch (Exception e) { log.warn("Invalid maxPrice: " + maxPriceStr); }
		}

		// Parse family / subfamily
		Long familyId = null;
		if (familyIdStr != null && !familyIdStr.trim().isEmpty() && !familyIdStr.equalsIgnoreCase("all")) {
			try { familyId = Long.parseLong(familyIdStr.trim()); } catch (Exception e) { log.warn("Invalid familyId: " + familyIdStr); }
		}
		Long subFamilyId = null;
		if (subFamilyIdStr != null && !subFamilyIdStr.trim().isEmpty() && !subFamilyIdStr.equalsIgnoreCase("all")) {
			try { subFamilyId = Long.parseLong(subFamilyIdStr.trim()); } catch (Exception e) { log.warn("Invalid subFamilyId: " + subFamilyIdStr); }
		}

		// Capture variables for lambda (effectively final)
		final java.time.LocalDateTime finalDateFrom = dateFrom;
		final java.time.LocalDateTime finalDateTo = dateTo;
		final TransactionStatus finalStatus = status;
		final com.digithink.pos.model.enumeration.SynchronizationStatus finalSyncStatus = syncStatus;
		final Long finalPaymentMethodId = paymentMethodId;
		final String finalSearchStr = searchStr;
		final Double finalMinPrice = minPrice;
		final Double finalMaxPrice = maxPrice;
		final Long finalFamilyId = familyId;
		final Long finalSubFamilyId = subFamilyId;

		Long cashierId = null;
		if (cashierIdStr != null && !cashierIdStr.trim().isEmpty() && !cashierIdStr.equalsIgnoreCase("all")) {
			try { cashierId = Long.parseLong(cashierIdStr.trim()); } catch (Exception e) { log.warn("Invalid cashierId: " + cashierIdStr); }
		}
		final Long finalCashierId = cashierId;
		final String finalSessionNumber = sessionNumberStr != null && !sessionNumberStr.trim().isEmpty() ? sessionNumberStr.trim() : null;

		// Build specification for filtering
		org.springframework.data.jpa.domain.Specification<SalesHeader> spec = (root, query, criteriaBuilder) -> {
			java.util.List<javax.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();

			// Date range filter
			if (finalDateFrom != null && finalDateTo != null) {
				predicates.add(criteriaBuilder.between(root.get("salesDate"), finalDateFrom, finalDateTo));
			} else if (finalDateFrom != null) {
				predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("salesDate"), finalDateFrom));
			} else if (finalDateTo != null) {
				predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("salesDate"), finalDateTo));
			}

			// Status filter
			if (finalStatus != null) {
				predicates.add(criteriaBuilder.equal(root.get("status"), finalStatus));
			}

			// Sync status filter
			if (finalSyncStatus != null) {
				predicates.add(criteriaBuilder.equal(root.get("synchronizationStatus"), finalSyncStatus));
			}

			// Payment method filter (filter by tickets that have payments with this payment
			// method)
			if (finalPaymentMethodId != null) {
				// Use subquery to find tickets with payments matching the payment method
				javax.persistence.criteria.Subquery<Long> paymentSubquery = query.subquery(Long.class);
				javax.persistence.criteria.Root<com.digithink.pos.model.Payment> paymentRoot = paymentSubquery
						.from(com.digithink.pos.model.Payment.class);
				paymentSubquery.select(paymentRoot.get("salesHeader").get("id"));
				paymentSubquery.where(criteriaBuilder.and(
						criteriaBuilder.equal(paymentRoot.get("paymentMethod").get("id"), finalPaymentMethodId),
						criteriaBuilder.equal(paymentRoot.get("salesHeader").get("id"), root.get("id"))));
				predicates.add(criteriaBuilder.exists(paymentSubquery));
			}

			// Search filter (by sales number or customer name)
			if (finalSearchStr != null && !finalSearchStr.trim().isEmpty()) {
				String searchPattern = "%" + finalSearchStr.trim().toLowerCase() + "%";
				javax.persistence.criteria.Predicate salesNumberPredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.get("salesNumber")), searchPattern);
				javax.persistence.criteria.Predicate customerNamePredicate = criteriaBuilder
						.like(criteriaBuilder.lower(root.join("customer", javax.persistence.criteria.JoinType.LEFT).get("name")), searchPattern);
				predicates.add(criteriaBuilder.or(salesNumberPredicate, customerNamePredicate));
			}

			// Price range filter
			if (finalMinPrice != null) {
				predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("totalAmount"), finalMinPrice));
			}
			if (finalMaxPrice != null) {
				predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("totalAmount"), finalMaxPrice));
			}

			// Cashier filter
			if (finalCashierId != null) {
				predicates.add(criteriaBuilder.equal(
						root.get("cashierSession").get("cashier").get("id"), finalCashierId));
			}

			// Session number filter
			if (finalSessionNumber != null) {
				predicates.add(criteriaBuilder.equal(
						root.get("cashierSession").get("sessionNumber"), finalSessionNumber));
			}

			// Family filter — ticket must have at least one line with item in this family
			if (finalFamilyId != null) {
				javax.persistence.criteria.Subquery<Long> familySubquery = query.subquery(Long.class);
				javax.persistence.criteria.Root<com.digithink.pos.model.SalesLine> lineRoot = familySubquery.from(com.digithink.pos.model.SalesLine.class);
				familySubquery.select(lineRoot.get("salesHeader").get("id"));
				familySubquery.where(criteriaBuilder.and(
						criteriaBuilder.equal(lineRoot.get("salesHeader").get("id"), root.get("id")),
						criteriaBuilder.equal(lineRoot.get("item").get("itemFamily").get("id"), finalFamilyId)));
				predicates.add(criteriaBuilder.exists(familySubquery));
			}

			// Subfamily filter
			if (finalSubFamilyId != null) {
				javax.persistence.criteria.Subquery<Long> subFamilySubquery = query.subquery(Long.class);
				javax.persistence.criteria.Root<com.digithink.pos.model.SalesLine> lineRoot2 = subFamilySubquery.from(com.digithink.pos.model.SalesLine.class);
				subFamilySubquery.select(lineRoot2.get("salesHeader").get("id"));
				subFamilySubquery.where(criteriaBuilder.and(
						criteriaBuilder.equal(lineRoot2.get("salesHeader").get("id"), root.get("id")),
						criteriaBuilder.equal(lineRoot2.get("item").get("itemSubFamily").get("id"), finalSubFamilyId)));
				predicates.add(criteriaBuilder.exists(subFamilySubquery));
			}

			return criteriaBuilder.and(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
		};

		org.springframework.data.domain.PageRequest pageRequest = org.springframework.data.domain.PageRequest.of(
				page, size, org.springframework.data.domain.Sort.by(
						org.springframework.data.domain.Sort.Direction.DESC, "salesDate"));
		return salesHeaderRepository.findAll(spec, pageRequest);
	}

	/**
	 * Calculate VAT amount from line total and VAT percentage
	 */
	private Double calculateVat(Double lineTotal, Integer vatPercentage) {
		if (lineTotal == null || vatPercentage == null || vatPercentage == 0) {
			return 0.0;
		}
		// VAT calculation: lineTotal * (vatPercentage / 100)
		return lineTotal * (vatPercentage / 100.0);
	}

	/**
	 * Calculate unit price including VAT
	 */
	private Double calculateUnitPriceIncludingVat(Double unitPrice, Integer vatPercentage) {
		if (unitPrice == null) {
			return 0.0;
		}
		if (vatPercentage == null || vatPercentage == 0) {
			return unitPrice;
		}
		// Unit price including VAT: unitPrice * (1 + vatPercentage / 100)
		return unitPrice * (1.0 + (vatPercentage / 100.0));
	}

	/**
	 * Apply pricing from PricingService to a sales line
	 * Calculates price and discount based on SalesPrice and SalesDiscount tables
	 */
	private void applyPricingToSalesLine(SalesLine salesLine, Item item, Customer customer,
			ProcessSaleRequestDTO.SaleLineDTO lineDTO) {
		// Calculate price using PricingService (if enabled, will use SalesPrice/SalesDiscount)
		PricingResult pricingResult = pricingService.calculateItemPrice(item, customer, lineDTO.getQuantity(), null);
		Double calculatedUnitPrice = pricingResult.getUnitPrice();
		Boolean priceIncludesVat = pricingResult.getPriceIncludesVat();
		Double discountPercentage = pricingResult.getDiscountPercentage();

		// Convert price from TTC to HT if needed
		Double unitPriceHT = calculatedUnitPrice;
		if (Boolean.TRUE.equals(priceIncludesVat) && item.getDefaultVAT() != null && item.getDefaultVAT() > 0) {
			// Price is TTC, convert to HT
			unitPriceHT = calculatedUnitPrice / (1.0 + (item.getDefaultVAT() / 100.0));
		}

		// Calculate line total from calculated price
		Double lineTotalHT = unitPriceHT * lineDTO.getQuantity();

		// Set price and line total
		salesLine.setUnitPrice(unitPriceHT);
		salesLine.setLineTotal(lineTotalHT);

		// Set discount fields - prioritize SalesDiscount, then use DTO discount
		if (discountPercentage != null) {
			salesLine.setDiscountPercentage(discountPercentage);
			// Calculate discount amount from percentage
			Double discountAmount = lineTotalHT * (discountPercentage / 100.0);
			salesLine.setDiscountAmount(discountAmount);
			lineTotalHT = lineTotalHT - discountAmount;
			salesLine.setLineTotal(lineTotalHT);
		} else {
			// Use discount from DTO if no SalesDiscount found
			if (lineDTO.getDiscountPercentage() != null) {
				salesLine.setDiscountPercentage(lineDTO.getDiscountPercentage());
			}
			if (lineDTO.getDiscountAmount() != null) {
				salesLine.setDiscountAmount(lineDTO.getDiscountAmount());
				lineTotalHT = lineTotalHT - lineDTO.getDiscountAmount();
				salesLine.setLineTotal(lineTotalHT);
			}
		}

		// Set VAT fields - use values from DTO if provided, otherwise calculate
		Integer vatPercent = lineDTO.getVatPercent() != null ? lineDTO.getVatPercent() : item.getDefaultVAT();
		salesLine.setVatPercent(vatPercent);

		if (lineDTO.getVatAmount() != null) {
			salesLine.setVatAmount(lineDTO.getVatAmount());
		} else {
			salesLine.setVatAmount(calculateVat(lineTotalHT, vatPercent));
		}

		if (lineDTO.getUnitPriceIncludingVat() != null) {
			salesLine.setUnitPriceIncludingVat(lineDTO.getUnitPriceIncludingVat());
		} else {
			salesLine.setUnitPriceIncludingVat(calculateUnitPriceIncludingVat(unitPriceHT, vatPercent));
		}

		if (lineDTO.getLineTotalIncludingVat() != null) {
			salesLine.setLineTotalIncludingVat(lineDTO.getLineTotalIncludingVat());
		} else {
			salesLine.setLineTotalIncludingVat(
					lineTotalHT + (salesLine.getVatAmount() != null ? salesLine.getVatAmount() : 0.0));
		}
	}

	/**
	 * Prepare invoice for a completed ticket: set invoiced=true, fiscal registration,
	 * and optional invoice customer name.
	 * If the ticket was TOTALLY_SYNCHED, set status to PARTIALLY_SYNCHED so the sync job
	 * will push POS_Invoice, Fiscal_Registration and invoice customer name to ERP when
	 * available (offline-safe).
	 */
	@Transactional
	public SalesHeader prepareInvoice(Long ticketId, String fiscalRegistration, String invoiceCustomerName) {
		if (fiscalRegistration == null || fiscalRegistration.trim().isEmpty()) {
			throw new IllegalArgumentException("Fiscal Registration is mandatory for preparing an invoice");
		}
		SalesHeader ticket = salesHeaderRepository.findById(ticketId)
				.orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));
		if (ticket.getStatus() != TransactionStatus.COMPLETED) {
			throw new IllegalArgumentException("Only completed tickets can be prepared for invoice");
		}
		if (Boolean.TRUE.equals(ticket.getInvoiced())) {
			throw new IllegalArgumentException("Ticket is already marked as invoiced");
		}
		ticket.setInvoiced(true);
		ticket.setFiscalRegistration(fiscalRegistration.trim());
		if (invoiceCustomerName != null && !invoiceCustomerName.trim().isEmpty()) {
			ticket.setInvoiceCustomerName(invoiceCustomerName.trim());
		}
		if (ticket.getSynchronizationStatus() == SynchronizationStatus.TOTALLY_SYNCHED) {
			ticket.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
		}
		return salesHeaderRepository.save(ticket);
	}
}
