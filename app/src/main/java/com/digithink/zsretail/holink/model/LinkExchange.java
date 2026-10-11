package com.digithink.zsretail.holink.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.Table;

import com.digithink.zsretail.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * One exchange with the head office (task 2.6), for the exchange log of the Head office link page. Written only for an
 * exchange that sent something or failed (a push cycle with nothing to send writes none; the heartbeat writes one only
 * when its state changes). Purged after headoffice.log-retention-days.
 */
@Entity
@Table(name = "hol_exchange_log", indexes = @Index(name = "ix_hol_exchange_log_date", columnList = "exchange_date"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class LinkExchange extends _BaseEntity {

	public static final int ERROR_LENGTH = 1000;

	/** Start of the exchange, store clock. */
	@Column(name = "exchange_date", nullable = false)
	private LocalDateTime exchangeDate;

	/** Code of the job, e.g. SALES_PUSH. */
	@Column(nullable = false, length = 40)
	private String job;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 4)
	private ExchangeDirection direction;

	/** Records sent (or received); 0 for the heartbeat. */
	@Column(name = "record_count", nullable = false)
	private int recordCount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private LinkJobResult result;

	/** First problem of the exchange; null when it fully succeeded. */
	@Column(length = ERROR_LENGTH)
	private String error;

	@Column(name = "duration_ms")
	private Long durationMs;
}
