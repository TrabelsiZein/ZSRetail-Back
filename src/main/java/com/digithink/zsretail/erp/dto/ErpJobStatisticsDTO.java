package com.digithink.zsretail.erp.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ErpJobStatisticsDTO {
	private Long totalExecutions;
	private Long successfulExecutions;
	private Long failedExecutions;
	private Long warningExecutions;
	private Double successRate;
	private Double averageDurationMs;
	private LocalDateTime lastExecutionAt;
	private String lastExecutionStatus;
	private StatisticsPeriod last7Days;
	private StatisticsPeriod last30Days;
	private List<ExecutionHistoryItem> recentExecutions;

	@Getter
	@Setter
	public static class StatisticsPeriod {
		private Long totalExecutions;
		private Long successfulExecutions;
		private Long failedExecutions;
		private Double successRate;
		private Double averageDurationMs;
	}

	@Getter
	@Setter
	public static class ExecutionHistoryItem {
		private Long id;
		private LocalDateTime startedAt;
		private LocalDateTime completedAt;
		private Long durationMs;
		private String status;
		private String errorMessage;
	}
}

