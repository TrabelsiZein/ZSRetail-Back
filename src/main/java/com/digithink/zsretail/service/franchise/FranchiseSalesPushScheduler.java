package com.digithink.zsretail.service.franchise;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.dto.franchise.FranchiseSalesPayload;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.repository.SalesHeaderRepository;
import com.digithink.zsretail.repository.SalesLineRepository;
import com.digithink.zsretail.service.GeneralSetupService;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Franchise client: scheduled job that pushes completed, unsent sales to the franchise admin.
 * Reuses the existing synchronizationStatus field on SalesHeader (safe because franchise clients
 * are always standalone=true with no ERP sync).
 * Only active when franchise.customer=true.
 */
@Component
@RequiredArgsConstructor
@Log4j2
@ConditionalOnProperty(name = "franchise.customer", havingValue = "true")
public class FranchiseSalesPushScheduler {

	private static final String API_KEY_HEADER = "X-Franchise-Api-Key";

	private final SalesHeaderRepository salesHeaderRepository;
	private final SalesLineRepository salesLineRepository;
	private final GeneralSetupService generalSetupService;
	private final RestTemplate restTemplate;

	@Value("${franchise.remote.url}")
	private String remoteUrl;

	@Value("${franchise.api-key}")
	private String apiKey;

	/**
	 * Pushes unsent completed sales to the franchise admin on the configured cron schedule.
	 * Sales are marked NOT_SYNCHED by default. After successful push: TOTALLY_SYNCHED.
	 * On error: PARTIALLY_SYNCHED (will retry on next run).
	 */
	@Scheduled(cron = "${franchise.sync.sales.cron:0 */15 * * * *}")
	@Transactional
	public void pushSales() {
		List<SalesHeader> unsentSales = salesHeaderRepository
				.findByStatusAndSynchronizationStatusNot(
						TransactionStatus.COMPLETED,
						SynchronizationStatus.TOTALLY_SYNCHED);

		if (unsentSales.isEmpty()) {
			return;
		}

		log.info("Franchise sales push: {} unsent sales to push", unsentSales.size());

		String locationCode = generalSetupService.findValueByCode("DEFAULT_LOCATION");
		String url = remoteUrl + "/franchise/sales";
		HttpHeaders headers = buildHeaders();
		headers.set("Content-Type", "application/json");

		int pushed = 0;
		int failed = 0;

		for (SalesHeader sale : unsentSales) {
			try {
				FranchiseSalesPayload payload = buildPayload(sale, locationCode);
				restTemplate.exchange(url, HttpMethod.POST,
						new HttpEntity<>(payload, headers), Void.class);

				sale.setSynchronizationStatus(SynchronizationStatus.TOTALLY_SYNCHED);
				salesHeaderRepository.save(sale);
				pushed++;
			} catch (Exception e) {
				log.warn("Franchise sales push: failed to push sale {}: {}", sale.getSalesNumber(), e.getMessage());
				sale.setSynchronizationStatus(SynchronizationStatus.PARTIALLY_SYNCHED);
				salesHeaderRepository.save(sale);
				failed++;
			}
		}

		log.info("Franchise sales push complete: pushed={}, failed={}", pushed, failed);
	}

	private FranchiseSalesPayload buildPayload(SalesHeader sale, String locationCode) {
		List<SalesLine> lines = salesLineRepository.findBySalesHeader(sale);

		List<FranchiseSalesPayload.LinePayload> linePayloads = lines.stream().map(l -> {
			String itemCode = l.getItem() != null ? l.getItem().getItemCode() : null;
			String itemName = l.getItem() != null ? l.getItem().getName() : null;
			return new FranchiseSalesPayload.LinePayload(
					itemCode,
					itemName,
					l.getQuantity(),
					l.getUnitPrice(),
					l.getDiscountAmount(),
					l.getLineTotalIncludingVat()
			);
		}).collect(Collectors.toList());

		String customerName = null;
		if (sale.getCustomer() != null) {
			customerName = sale.getCustomer().getName();
		}

		String cashierName = null;
		if (sale.getCreatedByUser() != null) {
			cashierName = sale.getCreatedByUser().getFullName();
		}

		// Compute HT/TVA from lines
		double totalHT = lines.stream().mapToDouble(l -> l.getLineTotal() != null ? l.getLineTotal() : 0.0).sum();
		double totalTVA = lines.stream().mapToDouble(l -> l.getVatAmount() != null ? l.getVatAmount() : 0.0).sum();
		double totalTTC = sale.getTotalAmount() != null ? sale.getTotalAmount() : 0.0;

		return new FranchiseSalesPayload(
				locationCode,
				sale.getSalesNumber(),
				sale.getSalesDate(),
				customerName,
				cashierName,
				totalHT,
				totalTVA,
				totalTTC,
				linePayloads
		);
	}

	private HttpHeaders buildHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(API_KEY_HEADER, apiKey);
		return headers;
	}
}
