package com.digithink.zsretail.service.franchise;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.dto.franchise.FranchiseInvoiceDTO;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.PurchaseHeader;
import com.digithink.zsretail.model.PurchaseLine;
import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.PurchaseHeaderRepository;
import com.digithink.zsretail.repository.PurchaseLineRepository;
import com.digithink.zsretail.repository.VendorRepository;
import com.digithink.zsretail.service.GeneralSetupService;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Franchise client: pulls pending supply invoices from the admin and auto-creates local purchase receptions.
 * Only active when franchise.customer=true.
 */
@Service
@RequiredArgsConstructor
@Log4j2
@ConditionalOnProperty(name = "franchise.customer", havingValue = "true")
public class FranchiseSupplyReceptionService {

	private static final String VENDOR_CODE = "FRANCHISE_ADMIN";
	private static final String API_KEY_HEADER = "X-Franchise-Api-Key";
	private static final String LAST_RECEPTION_KEY = "FRANCHISE_LAST_SUPPLY_RECEPTION_SYNC";
	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	private final ItemRepository itemRepository;
	private final PurchaseHeaderRepository purchaseHeaderRepository;
	private final PurchaseLineRepository purchaseLineRepository;
	private final VendorRepository vendorRepository;
	private final GeneralSetupService generalSetupService;
	private final RestTemplate restTemplate;

	@Value("${franchise.remote.url}")
	private String remoteUrl;

	@Value("${franchise.api-key}")
	private String apiKey;

	/**
	 * Fetches pending supply invoices from the admin and creates local purchase receptions.
	 * Stops and returns an error if any invoice contains items not yet synced locally.
	 */
	@Transactional(rollbackFor = Exception.class)
	public ReceptionResult receiveSupplies() {
		String locationCode = generalSetupService.findValueByCode("DEFAULT_LOCATION");
		if (locationCode == null || locationCode.isBlank()) {
			return ReceptionResult.error("DEFAULT_LOCATION is not configured in General Setup");
		}

		String url = remoteUrl + "/franchise/invoices?locationCode=" + locationCode;
		FranchiseInvoiceDTO[] invoices;
		try {
			HttpHeaders headers = buildHeaders();
			ResponseEntity<FranchiseInvoiceDTO[]> response =
					restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), FranchiseInvoiceDTO[].class);
			invoices = response.getBody();
		} catch (Exception e) {
			log.error("Franchise supply reception: unable to reach admin", e);
			return ReceptionResult.error("Unable to reach franchise admin: " + e.getMessage());
		}

		if (invoices == null || invoices.length == 0) {
			updateLastReceptionDate();
			return ReceptionResult.success(0);
		}

		Vendor franchiseVendor = vendorRepository.findByVendorCode(VENDOR_CODE)
				.orElse(null);
		if (franchiseVendor == null) {
			return ReceptionResult.error("FRANCHISE_ADMIN vendor not found. Please restart the application.");
		}

		int received = 0;

		for (FranchiseInvoiceDTO invoice : invoices) {
			// Check all items exist locally before processing
			List<String> missingItemCodes = findMissingItems(invoice);
			if (!missingItemCodes.isEmpty()) {
				return ReceptionResult.missingItems(
						"Some items in invoice " + invoice.getInvoiceNumber() + " are not synced locally. "
								+ "Please sync items first.",
						missingItemCodes);
			}

			// Create local purchase reception
			createLocalPurchase(invoice, franchiseVendor);

			// Acknowledge to admin
			try {
				acknowledgeInvoice(invoice.getId());
			} catch (Exception e) {
				log.warn("Failed to acknowledge invoice {} on admin: {}", invoice.getInvoiceNumber(), e.getMessage());
			}

			received++;
		}

		updateLastReceptionDate();
		log.info("Franchise supply reception complete: {} invoices received for location '{}'", received, locationCode);
		return ReceptionResult.success(received);
	}

	private List<String> findMissingItems(FranchiseInvoiceDTO invoice) {
		if (invoice.getLines() == null) return List.of();
		return invoice.getLines().stream()
				.filter(l -> l.getItemCode() != null)
				.filter(l -> itemRepository.findByItemCode(l.getItemCode()).isEmpty())
				.map(FranchiseInvoiceDTO.FranchiseInvoiceLineDTO::getItemCode)
				.collect(Collectors.toList());
	}

	private void createLocalPurchase(FranchiseInvoiceDTO invoice, Vendor vendor) {
		String purchaseNumber = "FR-" + invoice.getInvoiceNumber();

		if (purchaseHeaderRepository.findByPurchaseNumber(purchaseNumber).isPresent()) {
			log.debug("Purchase for franchise invoice {} already exists, skipping", invoice.getInvoiceNumber());
			return;
		}

		PurchaseHeader purchase = new PurchaseHeader();
		purchase.setPurchaseNumber(purchaseNumber);
		purchase.setPurchaseDate(LocalDateTime.now());
		purchase.setVendor(vendor);
		purchase.setStatus(TransactionStatus.COMPLETED);
		purchase.setNotes("Auto-created from franchise supply invoice: " + invoice.getInvoiceNumber());
		purchase.setCreatedBy("FranchiseReception");
		purchase.setUpdatedBy("FranchiseReception");

		double subtotal = 0.0;
		double tax = 0.0;

		List<PurchaseLine> lines = new ArrayList<>();

		if (invoice.getLines() != null) {
			for (FranchiseInvoiceDTO.FranchiseInvoiceLineDTO lineDto : invoice.getLines()) {
				if (lineDto.getItemCode() == null) continue;

				Item item = itemRepository.findByItemCode(lineDto.getItemCode()).orElse(null);
				if (item == null) continue;

				PurchaseLine line = new PurchaseLine();
				line.setPurchaseHeader(purchase);
				line.setItem(item);
				line.setQuantity(lineDto.getQuantity() != null ? lineDto.getQuantity() : 0);
				line.setUnitPrice(lineDto.getUnitPrice() != null ? lineDto.getUnitPrice() : 0.0);
				line.setVatPercent(lineDto.getVatPercent());
				line.setVatAmount(lineDto.getTaxAmount());

				double lineTotal = line.getQuantity() * line.getUnitPrice();
				line.setLineTotal(lineTotal);
				line.setLineTotalIncludingVat(lineDto.getTotalAmount());

				subtotal += lineTotal;
				if (lineDto.getTaxAmount() != null) tax += lineDto.getTaxAmount();

				lines.add(line);

				// Update item's purchase price (existing "réception achat" behaviour)
				item.setLastDirectCost(line.getUnitPrice());
				item.setLastDirectNetCost(line.getUnitPrice());
				item.setUpdatedBy("FranchiseReception");
				itemRepository.save(item);

				// Update stock
				if (line.getQuantity() > 0) {
					itemRepository.addToStockQuantity(item.getId(), line.getQuantity());
				}
			}
		}

		purchase.setSubtotal(subtotal);
		purchase.setTaxAmount(tax);
		purchase.setTotalAmount(subtotal + tax);
		purchase = purchaseHeaderRepository.save(purchase);

		// Save each line explicitly (PurchaseHeader has no CascadeType.ALL on purchaseLines)
		final PurchaseHeader savedPurchase = purchase;
		for (PurchaseLine l : lines) {
			l.setPurchaseHeader(savedPurchase);
			purchaseLineRepository.save(l);
		}

		log.info("Franchise supply received: purchase {} created from invoice {}",
				purchaseNumber, invoice.getInvoiceNumber());
	}

	private void acknowledgeInvoice(Long invoiceId) {
		String url = remoteUrl + "/franchise/invoices/" + invoiceId + "/acknowledge";
		HttpHeaders headers = buildHeaders();
		restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(headers), Void.class);
	}

	private void updateLastReceptionDate() {
		generalSetupService.updateValue(LAST_RECEPTION_KEY, LocalDateTime.now().format(ISO_FORMATTER));
	}

	private HttpHeaders buildHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(API_KEY_HEADER, apiKey);
		return headers;
	}

	public static class ReceptionResult {
		public final boolean success;
		public final int receivedCount;
		public final String errorMessage;
		public final List<String> missingItemCodes;

		private ReceptionResult(boolean success, int receivedCount, String errorMessage, List<String> missingItemCodes) {
			this.success = success;
			this.receivedCount = receivedCount;
			this.errorMessage = errorMessage;
			this.missingItemCodes = missingItemCodes;
		}

		public static ReceptionResult success(int count) {
			return new ReceptionResult(true, count, null, List.of());
		}

		public static ReceptionResult error(String message) {
			return new ReceptionResult(false, 0, message, List.of());
		}

		public static ReceptionResult missingItems(String message, List<String> codes) {
			return new ReceptionResult(false, 0, message, codes);
		}
	}
}
