package com.digithink.pos.erp.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.pos.erp.dto.ErpOperationResult;
import com.digithink.pos.erp.dto.ErpPaymentHeaderDTO;
import com.digithink.pos.erp.dto.ErpPaymentLineDTO;
import com.digithink.pos.erp.dto.ErpSessionDTO;
import com.digithink.pos.erp.model.PaymentHeader;
import com.digithink.pos.erp.model.PaymentLine;
import com.digithink.pos.erp.repository.PaymentHeaderRepository;
import com.digithink.pos.erp.repository.PaymentLineRepository;
import com.digithink.pos.model.CashierSession;
import com.digithink.pos.model.Payment;
import com.digithink.pos.model.ReturnHeader;
import com.digithink.pos.model.SalesHeader;
import com.digithink.pos.model.enumeration.PaymentMethodType;
import com.digithink.pos.model.enumeration.SessionStatus;
import com.digithink.pos.model.enumeration.SynchronizationStatus;
import com.digithink.pos.model.enumeration.TransactionStatus;
import com.digithink.pos.repository.CashierSessionRepository;
import com.digithink.pos.repository.PaymentRepository;
import com.digithink.pos.repository.ReturnHeaderRepository;
import com.digithink.pos.repository.SalesHeaderRepository;
import com.digithink.pos.service.GeneralSetupService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SessionExportService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SessionExportService.class);

	private final ErpSynchronizationManager synchronizationManager;
	private final CashierSessionRepository cashierSessionRepository;
	private final GeneralSetupService generalSetupService;
	private final SalesHeaderRepository salesHeaderRepository;
	private final ReturnHeaderRepository returnHeaderRepository;
	private final PaymentRepository paymentRepository;
private final PaymentHeaderRepository paymentHeaderRepository;
	private final PaymentLineRepository paymentLineRepository;

	/**
	 * Export sessions to Dynamics NAV
	 */
	@Transactional
	public void exportSessions() {
		LOGGER.info("Starting session export to ERP");

		// Find all CLOSED or TERMINATED sessions that are not totally synched
		List<CashierSession> sessionsToSync = cashierSessionRepository.findByStatusInAndSynchronizationStatusNot(
				Arrays.asList(SessionStatus.TERMINATED), SynchronizationStatus.TOTALLY_SYNCHED);

		if (sessionsToSync.isEmpty()) {
			LOGGER.info("No sessions to export");
			return;
		}

		LOGGER.info("Found {} sessions to export", sessionsToSync.size());

		int successCount = 0;
		int errorCount = 0;

		for (CashierSession session : sessionsToSync) {
			try {
				exportSession(session);
				successCount++;
			} catch (Exception ex) {
				LOGGER.error("Failed to export session {}: {}", session.getSessionNumber(), ex.getMessage(), ex);
				errorCount++;
			}
		}

		LOGGER.info("Session export completed. Success: {}, Errors: {}", successCount, errorCount);
	}

	/**
	 * Export a single session to ERP
	 */
	@Transactional
	public void exportSession(CashierSession session) {
		LOGGER.info("Exporting session: {}", session.getSessionNumber());

		// Check if session is already partially synched - if so, skip session sync and
		// go directly to payments
		if (session.getSynchronizationStatus() == SynchronizationStatus.PARTIALLY_SYNCHED) {
			LOGGER.info(
					"Session {} is already partially synched (already saved in ERP). Skipping session sync and proceeding to payment sync.",
					session.getSessionNumber());
			// Go directly to payment sync
			exportSessionPayments(session);
			return;
		}

		// Convert to ErpSessionDTO
		ErpSessionDTO sessionDTO = toErpSessionDTO(session);

		// Push session to ERP
		ErpOperationResult result = synchronizationManager.pushSession(sessionDTO);

		if (result.isSuccess()) {
			// NAV returns just a number (1) for success, not a document number
			// Use session number as external reference (or the returned value if available)
			String externalRef = result.getExternalReference() != null ? result.getExternalReference()
					: session.getSessionNumber();
			session.setErpNo(externalRef);
			session.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
			cashierSessionRepository.save(session);

			LOGGER.info("Session {} exported to ERP successfully. External reference: {}", session.getSessionNumber(),
					externalRef);

			// Now export payments for this session
			exportSessionPayments(session);
		} else {
			String errorMsg = result.getMessage() != null ? result.getMessage() : "Failed to export session to ERP";
			LOGGER.error("Failed to export session {}: {}", session.getSessionNumber(), errorMsg);
			throw new RuntimeException(errorMsg);
		}
	}

	/**
	 * Export session payments to ERP. PaymentHeader and PaymentLine records are
	 * already created by the async method after each ticket creation, so this
	 * method just syncs the existing records to ERP.
	 */
	@Transactional
	private void exportSessionPayments(CashierSession session) {
		LOGGER.info("Exporting payments for session: {}", session.getSessionNumber());

		// Get existing PaymentHeader records for this session
		// These should already exist from the async method that runs after ticket
		// creation
		List<PaymentHeader> existingHeaders = paymentHeaderRepository.findByCashierSession(session);

		if (existingHeaders.isEmpty()) {
			LOGGER.info("No payment headers found for session {} - nothing to sync", session.getSessionNumber());
			session.setSynchronizationStatus(SynchronizationStatus.TOTALLY_SYNCHED);
			cashierSessionRepository.save(session);
			return;
		}

		LOGGER.info("Found {} payment headers to sync for session {}", existingHeaders.size(),
				session.getSessionNumber());

		// Export payment headers
		for (PaymentHeader paymentHeader : existingHeaders) {
			// Skip if header is TOTALLY_SYNCHED - both header and lines are already synced
			if (paymentHeader.getSynchronizationStatus() == SynchronizationStatus.TOTALLY_SYNCHED) {
				LOGGER.info("Payment header {} is totally synched. Skipping header and line sync.",
						paymentHeader.getId());
				continue;
			}

			// Skip header sync if PARTIALLY_SYNCHED (header already synced, only lines need
			// checking)
			if (paymentHeader.getSynchronizationStatus() == SynchronizationStatus.PARTIALLY_SYNCHED) {
				LOGGER.info(
						"Payment header {} is partially synched (document number: {}). Skipping header sync, will check lines.",
						paymentHeader.getId(), paymentHeader.getErpNo());
				continue;
			}

			// Sync header if NOT_SYNCHED
			if (paymentHeader.getSynchronizationStatus() == SynchronizationStatus.NOT_SYNCHED) {
				try {
					ErpPaymentHeaderDTO headerDTO = new ErpPaymentHeaderDTO();
					headerDTO.setPaymentClass(paymentHeader.getPaymentClass());
					headerDTO.setPostDate(paymentHeader.getPostDate());

					ErpOperationResult result = synchronizationManager.pushPaymentHeader(headerDTO);

					if (result.isSuccess() && result.getExternalReference() != null) {
						paymentHeader.setErpNo(result.getExternalReference());
						paymentHeader.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
						paymentHeaderRepository.save(paymentHeader);
						LOGGER.info(
								"Payment header {} exported to ERP with document number: {}. Status set to PARTIALLY_SYNCHED.",
								paymentHeader.getId(), result.getExternalReference());
					} else {
						LOGGER.error("Failed to export payment header {}: {}", paymentHeader.getId(),
								result.getMessage());
						// Continue with next header
					}
				} catch (Exception ex) {
					LOGGER.error("Failed to export payment header {}: {}", paymentHeader.getId(), ex.getMessage(), ex);
					// Continue with next header
				}
			}
		}

		// Export payment lines for synched headers (skip TOTALLY_SYNCHED headers)
		for (PaymentHeader paymentHeader : existingHeaders) {
			// Skip if header is TOTALLY_SYNCHED
			if (paymentHeader.getSynchronizationStatus() == SynchronizationStatus.TOTALLY_SYNCHED) {
				continue;
			}

			// Process lines for PARTIALLY_SYNCHED headers
			if (paymentHeader.getSynchronizationStatus() == SynchronizationStatus.PARTIALLY_SYNCHED) {
				List<PaymentLine> paymentLines = paymentLineRepository.findByPaymentHeader(paymentHeader);
				List<PaymentLine> unsynchedLines = paymentLines.stream()
						.filter(line -> !Boolean.TRUE.equals(line.getSynched())).collect(Collectors.toList());

				// Sync unsynched lines
				for (PaymentLine paymentLine : unsynchedLines) {
					try {
						ErpPaymentLineDTO lineDTO = new ErpPaymentLineDTO();
						lineDTO.setDocNo(paymentHeader.getErpNo());
						lineDTO.setCustNo(paymentLine.getCustNo());
						lineDTO.setAmount(BigDecimal.valueOf(paymentLine.getAmount()));
						lineDTO.setFenceNo(paymentLine.getFenceNo());
						lineDTO.setTicketNo(paymentLine.getTicketNo());
						lineDTO.setTitleNo(paymentLine.getTitleNumber());
						lineDTO.setDueDate(paymentLine.getDueDate());
						lineDTO.setDrawerName(paymentLine.getDrawerName());

						ErpOperationResult result = synchronizationManager.pushPaymentLine(paymentHeader.getErpNo(),
								lineDTO);

						if (result.isSuccess()) {
							paymentLine.setSynched(true);
							paymentLineRepository.save(paymentLine);

							// Mark all grouped payments synced; fall back to legacy single ref
						List<Payment> linkedPayments = paymentLine.getPayments();
						if (!linkedPayments.isEmpty()) {
							for (Payment p : linkedPayments) {
								p.setSynched(true);
								paymentRepository.save(p);
							}
						} else if (paymentLine.getPayment() != null) {
							paymentLine.getPayment().setSynched(true);
							paymentRepository.save(paymentLine.getPayment());
						}

							LOGGER.info("Payment line {} exported to ERP", paymentLine.getId());
						} else {
							LOGGER.error("Failed to export payment line {}: {}", paymentLine.getId(),
									result.getMessage());
							// Continue with next line
						}
					} catch (Exception ex) {
						LOGGER.error("Failed to export payment line {}: {}", paymentLine.getId(), ex.getMessage(), ex);
						// Continue with next line
					}
				}

				// After syncing lines, check if all lines are synched
				// If yes, update header status to TOTALLY_SYNCHED
				List<PaymentLine> allLines = paymentLineRepository.findByPaymentHeader(paymentHeader);
				boolean allLinesSynched = !allLines.isEmpty() && allLines.stream().allMatch(PaymentLine::getSynched);

				if (allLinesSynched
						&& paymentHeader.getSynchronizationStatus() != SynchronizationStatus.TOTALLY_SYNCHED) {
					paymentHeader.setSynchronizationStatus(SynchronizationStatus.TOTALLY_SYNCHED);
					paymentHeaderRepository.save(paymentHeader);
					LOGGER.info("Payment header {} is now TOTALLY_SYNCHED (all lines synced)", paymentHeader.getId());
				}
			}
		}

		// Check if all payment headers are TOTALLY_SYNCHED
		List<PaymentHeader> allHeaders = paymentHeaderRepository.findByCashierSession(session);
		boolean allHeadersTotallySynched = !allHeaders.isEmpty() && allHeaders.stream()
				.allMatch(header -> header.getSynchronizationStatus() == SynchronizationStatus.TOTALLY_SYNCHED);

		// If all payment headers are totally synced, mark session as totally synched
		if (allHeadersTotallySynched) {
			session.setSynchronizationStatus(SynchronizationStatus.TOTALLY_SYNCHED);
			cashierSessionRepository.save(session);
			LOGGER.info("Session {} fully synchronized (all payment headers are TOTALLY_SYNCHED)",
					session.getSessionNumber());
		} else {
			// Check if at least one header is synched (partially or totally)
			boolean hasSynchedHeaders = allHeaders.stream()
					.anyMatch(header -> header.getSynchronizationStatus() == SynchronizationStatus.PARTIALLY_SYNCHED
							|| header.getSynchronizationStatus() == SynchronizationStatus.TOTALLY_SYNCHED);

			if (hasSynchedHeaders) {
				session.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
				cashierSessionRepository.save(session);
			}
			LOGGER.info("Session {} partially synchronized - some payment headers not yet totally synced",
					session.getSessionNumber());
		}
	}

	/**
	 * Async post-sale entry point: creates PaymentHeader/PaymentLine records for new
	 * payments after ticket creation. Non-fatal on failure (logs only). Delegates to
	 * {@link #createPaymentHeadersAndLines}.
	 */
	@Async("asyncExecutor")
	@Transactional
	public void createPaymentHeadersAndLinesAsync(List<Payment> payments, SalesHeader salesHeader) {
		try {
			createPaymentHeadersAndLines(payments, salesHeader);
		} catch (Exception ex) {
			LOGGER.error("Error creating payment headers and lines asynchronously: {}", ex.getMessage(), ex);
		}
	}

	/**
	 * Core creation logic shared by the async post-sale path and the rebuild path.
	 * Throws on failure so callers can roll back. The caller MUST hold a write lock
	 * on the cashier session (acquired here via {@link #lockSession}).
	 */
	private void createPaymentHeadersAndLines(List<Payment> payments, SalesHeader salesHeader) {
		if (payments == null || payments.isEmpty()) {
			LOGGER.debug("No payments provided to createPaymentHeadersAndLines");
			return;
		}

		// Get session from first payment
		CashierSession session = payments.get(0).getSalesHeader().getCashierSession();
		if (session == null) {
			LOGGER.warn("Cannot create payment headers/lines: session is null");
			return;
		}
		lockSession(session.getId());

			// Filter out RETURN_VOUCHER payments and optionally CLIENT_CHEQUE payments
			boolean skipCheque = "true".equalsIgnoreCase(generalSetupService.findValueByCode("ERP_SKIP_CHEQUE_PAYMENTS"));
			List<Payment> paymentsToProcess = payments.stream()
					.filter(payment -> payment.getPaymentMethod().getType() != PaymentMethodType.RETURN_VOUCHER)
					.filter(payment -> !skipCheque || payment.getPaymentMethod().getType() != PaymentMethodType.CLIENT_CHEQUE)
					.collect(Collectors.toList());

			if (paymentsToProcess.isEmpty()) {
				LOGGER.debug("No payments to process after filtering payments");
				return;
			}

			// Group payments by payment class
			Map<String, List<Payment>> paymentsByClass = paymentsToProcess.stream()
					.collect(Collectors.groupingBy(p -> p.getPaymentMethod().getCode()));

			LOGGER.info("Creating payment headers and lines asynchronously for {} payment classes in session {}",
					paymentsByClass.size(), session.getSessionNumber());

			// Process each payment class
			for (Map.Entry<String, List<Payment>> entry : paymentsByClass.entrySet()) {
				String paymentClass = entry.getKey();
				List<Payment> paymentGroup = entry.getValue();

				if (paymentGroup.isEmpty()) {
					continue;
				}

				// Check if PaymentHeader already exists for this payment class and session
				List<PaymentHeader> existingHeaders = paymentHeaderRepository.findByCashierSession(session);
				PaymentHeader paymentHeader = existingHeaders.stream()
						.filter(header -> paymentClass.equals(header.getPaymentClass())).findFirst().orElse(null);

				// Use first payment's date as postDate
				LocalDate postDate = paymentGroup.get(0).getPaymentDate().toLocalDate();

				// Determine payment type from first payment
				PaymentMethodType paymentType = paymentGroup.get(0).getPaymentMethod().getType();

				// Create PaymentHeader if it doesn't exist
				if (paymentHeader == null) {
					paymentHeader = new PaymentHeader();
					paymentHeader.setCashierSession(session);
					paymentHeader.setPaymentClass(paymentClass);
					paymentHeader.setPostDate(postDate);
					paymentHeader.setSynchronizationStatus(SynchronizationStatus.NOT_SYNCHED);
					paymentHeader = paymentHeaderRepository.save(paymentHeader);
					LOGGER.info("Created new payment header {} for payment class {}", paymentHeader.getId(),
							paymentClass);
				} else {
					// Update postDate if needed (use the latest date)
					if (postDate.isAfter(paymentHeader.getPostDate())) {
						paymentHeader.setPostDate(postDate);
						paymentHeaderRepository.save(paymentHeader);
					}
				}

				// Get existing payment lines for this header
				List<PaymentLine> existingLines = paymentLineRepository.findByPaymentHeader(paymentHeader);
				List<Long> existingPaymentIds = existingLines.stream().filter(line -> line.getPayment() != null)
						.map(line -> line.getPayment().getId()).collect(Collectors.toList());

				// Create PaymentLines for payments that don't have lines yet
				if (paymentType == PaymentMethodType.CLIENT_ESPECES) {
					// CLIENT_ESPECES: one line per customer, summed per customer
					List<Payment> newCashPayments = paymentGroup.stream()
							.filter(p -> !existingPaymentIds.contains(p.getId()))
							.collect(Collectors.toList());

					// Group new payments by customer code
					Map<String, List<Payment>> byCustomer = newCashPayments.stream()
							.filter(p -> p.getSalesHeader().getCustomer() != null)
							.collect(Collectors.groupingBy(
									p -> p.getSalesHeader().getCustomer().getCustomerCode()));

					for (Map.Entry<String, List<Payment>> custEntry : byCustomer.entrySet()) {
						String custNo = custEntry.getKey();
						List<Payment> custPayments = custEntry.getValue();

						double newAmount = custPayments.stream()
								.mapToDouble(p -> p.getTotalAmount() != null ? p.getTotalAmount() : 0.0).sum();
						double change = salesHeader.getChangeAmount() != null ? salesHeader.getChangeAmount() : 0.0;
						double adjustedAmount = (newAmount - change) > 0 ? (newAmount - change) : newAmount;

						// Look for an existing line for this customer in this header
						PaymentLine existingLine = existingLines.stream()
								.filter(line -> custNo.equals(line.getCustNo()))
								.findFirst().orElse(null);

						if (existingLine == null) {
							PaymentLine paymentLine = new PaymentLine();
							paymentLine.setPaymentHeader(paymentHeader);
							paymentLine.setPayment(custPayments.get(0));
							paymentLine.setCustNo(custNo);
							paymentLine.setAmount(adjustedAmount);
							paymentLine.setFenceNo(session.getSessionNumber());
							paymentLine.setTicketNo(custPayments.get(0).getSalesHeader().getSalesNumber());
							paymentLine.setTitleNumber(null);
							paymentLine.setDueDate(null);
							paymentLine.setDrawerName(null);
							paymentLine.setSynched(false);
							paymentLine.setPayments(new ArrayList<>(custPayments));
							paymentLineRepository.save(paymentLine);
							LOGGER.info("Created CLIENT_ESPECES line for customer {} amount {}", custNo, adjustedAmount);
						} else {
							existingLine.setAmount(existingLine.getAmount() + adjustedAmount);
							existingLine.setTicketNo(custPayments.get(custPayments.size() - 1).getSalesHeader().getSalesNumber());
							List<Long> alreadyLinked = existingLine.getPayments().stream()
									.map(Payment::getId).collect(Collectors.toList());
							custPayments.stream()
									.filter(p -> !alreadyLinked.contains(p.getId()))
									.forEach(existingLine.getPayments()::add);
							paymentLineRepository.save(existingLine);
							LOGGER.info("Updated CLIENT_ESPECES line for customer {} new total {}", custNo, existingLine.getAmount());
						}
					}

				} else {
					// Other payment classes: create one line per payment that doesn't have a line
					// yet
					for (Payment payment : paymentGroup) {
						// Skip if this payment already has a line
						if (existingPaymentIds.contains(payment.getId())) {
							continue;
						}

						SalesHeader ticket = payment.getSalesHeader();
						String custNo = ticket.getCustomer() != null ? ticket.getCustomer().getCustomerCode() : null;
						String fenceNo = session.getSessionNumber();
						String ticketNo = ticket.getSalesNumber();

						if (custNo != null) {
							PaymentLine paymentLine = new PaymentLine();
							paymentLine.setPaymentHeader(paymentHeader);
							paymentLine.setPayment(payment);
							paymentLine.setCustNo(custNo);
							paymentLine.setAmount(payment.getTotalAmount() != null ? payment.getTotalAmount() : 0.0);
							paymentLine.setFenceNo(fenceNo);
							paymentLine.setTicketNo(ticketNo);
							paymentLine.setTitleNumber(payment.getTitleNumber());
							paymentLine.setDueDate(payment.getDueDate());
							paymentLine.setDrawerName(payment.getDrawerName());
							paymentLine.setSynched(false);
							paymentLineRepository.save(paymentLine);
							LOGGER.info("Created payment line for payment {} (amount: {})", payment.getId(),
									payment.getTotalAmount());
						}
					}
				}
			}

		LOGGER.info("Successfully created payment headers and lines for session {}",
				session.getSessionNumber());
	}

	/**
	 * Acquire a pessimistic write lock on the session row to serialize all
	 * PaymentHeader/PaymentLine mutations for that session.
	 */
	private void lockSession(Long sessionId) {
		if (sessionId != null) {
			cashierSessionRepository.findByIdForUpdate(sessionId);
		}
	}

	/**
	 * Rebuild ALL PaymentHeader/PaymentLine rows for a session from the current
	 * Payment rows. Allowed only when nothing in the session has been synced to NAV
	 * (every header NOT_SYNCHED). Used after an admin changes a ticket's payment
	 * method, so the eventual NAV export reflects the new method exactly.
	 */
	@Transactional
	public void rebuildSessionPaymentRows(CashierSession session) {
		if (session == null) {
			return;
		}
		lockSession(session.getId());

		List<PaymentHeader> headers = paymentHeaderRepository.findByCashierSession(session);
		boolean anySynced = headers.stream()
				.anyMatch(h -> h.getSynchronizationStatus() != SynchronizationStatus.NOT_SYNCHED);
		if (anySynced) {
			throw new IllegalStateException("Cannot rebuild payment rows: session " + session.getSessionNumber()
					+ " already has payment headers synchronized with NAV");
		}

		// Capture which sales currently HAVE export rows, BEFORE deleting. The rebuild must
		// reproduce EXACTLY this set — only the sales the normal post-sale creator processed.
		// In particular split-bill tickets (splitAndPay) never get export rows, so they must
		// NOT be synthesized here or the NAV payment export would be inflated.
		Set<Long> exportedSaleIds = new HashSet<>();
		for (PaymentHeader header : headers) {
			List<PaymentLine> lines = paymentLineRepository.findByPaymentHeader(header);
			for (PaymentLine line : lines) {
				for (Payment p : line.getPayments()) {
					if (p.getSalesHeader() != null) {
						exportedSaleIds.add(p.getSalesHeader().getId());
					}
				}
				if (line.getPayment() != null && line.getPayment().getSalesHeader() != null) {
					exportedSaleIds.add(line.getPayment().getSalesHeader().getId());
				}
			}
			if (!lines.isEmpty()) {
				paymentLineRepository.deleteAll(lines);
			}
		}
		paymentLineRepository.flush();
		if (!headers.isEmpty()) {
			paymentHeaderRepository.deleteAll(headers);
			paymentHeaderRepository.flush();
		}

		// Regenerate from current payments, sale by sale (reuses the exact creation logic),
		// restricted to the sales that previously had export rows.
		List<SalesHeader> sales = salesHeaderRepository.findByCashierSession(session).stream()
				.filter(s -> s.getStatus() == TransactionStatus.COMPLETED)
				.filter(s -> exportedSaleIds.contains(s.getId()))
				.collect(Collectors.toList());
		for (SalesHeader sale : sales) {
			List<Payment> salePayments = paymentRepository.findBySalesHeader(sale);
			if (!salePayments.isEmpty()) {
				createPaymentHeadersAndLines(salePayments, sale);
			}
		}
		LOGGER.info("Rebuilt payment headers/lines for session {} ({} completed sales)",
				session.getSessionNumber(), sales.size());
	}

	/**
	 * Convert CashierSession to ErpSessionDTO
	 */
	private ErpSessionDTO toErpSessionDTO(CashierSession session) {
		ErpSessionDTO dto = new ErpSessionDTO();
		dto.setSessionNumber(session.getSessionNumber());

		// Set location from GeneralSetup (for NAV export)
		String locationCode = generalSetupService.findValueByCode("DEFAULT_LOCATION");
		if (locationCode != null) {
			dto.setLocationCode(locationCode);
		}

		// Calculate ticket count (excluding PENDING and CANCELLED)
		List<SalesHeader> allSales = salesHeaderRepository.findByCashierSession(session);
		List<SalesHeader> sales = allSales.stream().filter(sale -> sale.getStatus() != TransactionStatus.PENDING
				&& sale.getStatus() != TransactionStatus.CANCELLED).toList();
		dto.setTicketCount(sales.size());
		dto.setClosingAmount(sales.stream().mapToDouble(s -> s.getTotalAmount()).sum());

		// Calculate return count (simple returns only - cash refunds)
		List<ReturnHeader> returns = returnHeaderRepository.findByCashierSession(session);
//		List<ReturnHeader> simpleReturns = returns.stream()
//				.filter(ret -> ret.getReturnType() == ReturnType.SIMPLE_RETURN).toList();
		dto.setReturnCount(returns.size());

		// Calculate voucher redemptions (payments of type RETURN_VOUCHER)
		List<Payment> voucherPayments = sales.stream()
				.flatMap(sale -> paymentRepository.findBySalesHeader(sale).stream())
				.filter(payment -> payment.getPaymentMethod() != null
						&& payment.getPaymentMethod().getType() == PaymentMethodType.RETURN_VOUCHER)
				.toList();

		dto.setReturnCashedCount(voucherPayments.size());

		Double returnCashedAmount = voucherPayments.stream()
				.mapToDouble(p -> p.getTotalAmount() != null ? p.getTotalAmount() : 0.0).sum();
		dto.setReturnCashedAmount(returnCashedAmount);

		return dto;

	}

}
