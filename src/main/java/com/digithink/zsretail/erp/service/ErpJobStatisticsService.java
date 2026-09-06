package com.digithink.zsretail.erp.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.digithink.zsretail.erp.dto.ErpJobStatisticsDTO;
import com.digithink.zsretail.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.erp.enumeration.ErpSyncOperation;
import com.digithink.zsretail.erp.model.ErpCommunication;
import com.digithink.zsretail.erp.repository.ErpCommunicationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ErpJobStatisticsService {

	private final ErpCommunicationRepository communicationRepository;

	public ErpJobStatisticsDTO getStatistics(ErpSyncJobType jobType, LocalDateTime fromDate, LocalDateTime toDate) {
		List<ErpSyncOperation> operations = mapJobTypeToOperations(jobType);
		
		List<ErpCommunication> allCommunications;
		if (fromDate != null && toDate != null) {
			allCommunications = communicationRepository.findByOperationInAndStartedAtBetweenOrderByStartedAtDesc(
					operations, fromDate, toDate);
		} else {
			allCommunications = communicationRepository.findByOperationInOrderByStartedAtDesc(operations);
		}

		ErpJobStatisticsDTO stats = new ErpJobStatisticsDTO();
		
		// Overall statistics
		stats.setTotalExecutions((long) allCommunications.size());
		stats.setSuccessfulExecutions(countByStatus(allCommunications, ErpCommunicationStatus.SUCCESS));
		stats.setFailedExecutions(countByStatus(allCommunications, ErpCommunicationStatus.ERROR));
		stats.setWarningExecutions(countByStatus(allCommunications, ErpCommunicationStatus.WARNING));
		stats.setSuccessRate(calculateSuccessRate(allCommunications));
		stats.setAverageDurationMs(calculateAverageDuration(allCommunications));
		
		// Last execution
		if (!allCommunications.isEmpty()) {
			ErpCommunication last = allCommunications.get(0);
			stats.setLastExecutionAt(last.getStartedAt());
			stats.setLastExecutionStatus(last.getStatus().name());
		}

		// Last 7 days
		LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
		List<ErpCommunication> last7Days = allCommunications.stream()
				.filter(comm -> comm.getStartedAt() != null && comm.getStartedAt().isAfter(sevenDaysAgo))
				.collect(Collectors.toList());
		stats.setLast7Days(calculatePeriodStats(last7Days));

		// Last 30 days
		LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
		List<ErpCommunication> last30Days = allCommunications.stream()
				.filter(comm -> comm.getStartedAt() != null && comm.getStartedAt().isAfter(thirtyDaysAgo))
				.collect(Collectors.toList());
		stats.setLast30Days(calculatePeriodStats(last30Days));

		// Recent executions (last 10)
		stats.setRecentExecutions(allCommunications.stream()
				.limit(10)
				.map(this::mapToHistoryItem)
				.collect(Collectors.toList()));

		return stats;
	}

	private List<ErpSyncOperation> mapJobTypeToOperations(ErpSyncJobType jobType) {
		switch (jobType) {
		case IMPORT_ITEM_FAMILIES:
			return Arrays.asList(ErpSyncOperation.IMPORT_ITEM_FAMILIES);
		case IMPORT_ITEM_SUBFAMILIES:
			return Arrays.asList(ErpSyncOperation.IMPORT_ITEM_SUBFAMILIES);
		case IMPORT_ITEMS:
			return Arrays.asList(ErpSyncOperation.IMPORT_ITEMS);
		case IMPORT_ITEM_BARCODES:
			return Arrays.asList(ErpSyncOperation.IMPORT_ITEM_BARCODES);
		case IMPORT_LOCATIONS:
			return Arrays.asList(ErpSyncOperation.IMPORT_LOCATIONS);
		case IMPORT_CUSTOMERS:
			return Arrays.asList(ErpSyncOperation.IMPORT_CUSTOMERS);
		case IMPORT_SALES_PRICES_AND_DISCOUNTS:
			return Arrays.asList(ErpSyncOperation.IMPORT_SALES_PRICES, ErpSyncOperation.IMPORT_SALES_DISCOUNTS);
		case SYNC_ERP_DELETIONS:
			return Arrays.asList(ErpSyncOperation.SYNC_ERP_DELETIONS);
		case EXPORT_CUSTOMERS:
			return Arrays.asList(ErpSyncOperation.EXPORT_CUSTOMER);
		case EXPORT_TICKETS:
			// EXPORT_TICKETS job generates multiple operations
			return Arrays.asList(ErpSyncOperation.EXPORT_TICKET, ErpSyncOperation.EXPORT_TICKET_LINE,
					ErpSyncOperation.UPDATE_TICKET);
		case EXPORT_RETURNS:
			return Arrays.asList(ErpSyncOperation.EXPORT_RETURN, ErpSyncOperation.EXPORT_RETURN_LINE,
					ErpSyncOperation.UPDATE_RETURN);
		default:
			return new ArrayList<>();
		}
	}

	private long countByStatus(List<ErpCommunication> communications, ErpCommunicationStatus status) {
		return communications.stream().filter(comm -> comm.getStatus() == status).count();
	}

	private Double calculateSuccessRate(List<ErpCommunication> communications) {
		if (communications.isEmpty()) {
			return null;
		}
		long successCount = countByStatus(communications, ErpCommunicationStatus.SUCCESS);
		return (successCount * 100.0) / communications.size();
	}

	private Double calculateAverageDuration(List<ErpCommunication> communications) {
		if (communications.isEmpty()) {
			return null;
		}
		List<ErpCommunication> withDuration = communications.stream()
				.filter(comm -> comm.getDurationMs() != null)
				.collect(Collectors.toList());
		
		if (withDuration.isEmpty()) {
			return null;
		}

		double total = withDuration.stream().mapToLong(ErpCommunication::getDurationMs).sum();
		return total / withDuration.size();
	}

	private ErpJobStatisticsDTO.StatisticsPeriod calculatePeriodStats(List<ErpCommunication> communications) {
		ErpJobStatisticsDTO.StatisticsPeriod period = new ErpJobStatisticsDTO.StatisticsPeriod();
		period.setTotalExecutions((long) communications.size());
		period.setSuccessfulExecutions(countByStatus(communications, ErpCommunicationStatus.SUCCESS));
		period.setFailedExecutions(countByStatus(communications, ErpCommunicationStatus.ERROR));
		period.setSuccessRate(calculateSuccessRate(communications));
		period.setAverageDurationMs(calculateAverageDuration(communications));
		return period;
	}

	private ErpJobStatisticsDTO.ExecutionHistoryItem mapToHistoryItem(ErpCommunication comm) {
		ErpJobStatisticsDTO.ExecutionHistoryItem item = new ErpJobStatisticsDTO.ExecutionHistoryItem();
		item.setId(comm.getId());
		item.setStartedAt(comm.getStartedAt());
		item.setCompletedAt(comm.getCompletedAt());
		item.setDurationMs(comm.getDurationMs());
		item.setStatus(comm.getStatus() != null ? comm.getStatus().name() : null);
		item.setErrorMessage(comm.getErrorMessage());
		return item;
	}
}

