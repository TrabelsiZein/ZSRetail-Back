package com.digithink.zsretail.core.communication.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.3.0: the messages of the communication module (comm_message). */
@Repository
public interface CommunicationMessageRepository extends _BaseRepository<CommunicationMessage, Long> {

	/** Step 7: the queue of {@link #findDue}: the automatic messages (an event's, no campaign). */
	int AUTOMATIC = 0;

	/** Step 7: the queue of {@link #findDue}: the campaign messages. */
	int CAMPAIGN = 1;

	Optional<CommunicationMessage> findByDedupKey(String dedupKey);

	/**
	 * Step 3: the messages due after {@code afterId}, by id: PENDING, with no next attempt or one not after {@code now}
	 * (index ix_comm_message_queue). Step 7: of one queue, {@link #AUTOMATIC} or {@link #CAMPAIGN}: the sender empties
	 * the automatic one first, so a campaign never delays a welcome or a thanks.
	 */
	@Query("select m from CommunicationMessage m where m.status = :pending"
			+ " and (m.nextAttemptAt is null or m.nextAttemptAt <= :now) and m.id > :afterId"
			+ " and ((:queue = 0 and m.campaignId is null) or (:queue = 1 and m.campaignId is not null)) order by m.id")
	List<CommunicationMessage> findDue(@Param("pending") CommunicationMessageStatus pending,
			@Param("now") LocalDateTime now, @Param("afterId") long afterId, Pageable page, @Param("queue") int queue);

	/** Step 7: the dedup keys of a batch that exist already (one query for a batch of a campaign). */
	@Query("select m.dedupKey from CommunicationMessage m where m.dedupKey in :keys")
	List<String> findExistingDedupKeys(@Param("keys") Collection<String> keys);

	/** Step 7: [campaignId, status, count] of the campaigns given (the counters of the list). */
	@Query("select m.campaignId, m.status, count(m) from CommunicationMessage m where m.campaignId in :ids"
			+ " group by m.campaignId, m.status")
	List<Object[]> countByCampaignAndStatus(@Param("ids") Collection<Long> ids);

	/** Step 7: the messages of a campaign in a status (DONE once none is PENDING). */
	long countByCampaignIdAndStatus(Long campaignId, CommunicationMessageStatus status);

	/**
	 * Step 8a, at the start: the messages left SENDING (the application stopped between the provider's call and its
	 * answer) FAILED with the reason, never sent again by themselves. Returns how many.
	 */
	@Modifying
	@Query("update CommunicationMessage m set m.status = :failed, m.lastError = :reason, m.updatedAt = :now"
			+ " where m.status = :sending")
	int markInterrupted(@Param("sending") CommunicationMessageStatus sending,
			@Param("failed") CommunicationMessageStatus failed, @Param("reason") String reason,
			@Param("now") LocalDateTime now);

	/** Step 8a: the latest message of a channel given to its provider (the Canaux tab's last result). */
	Optional<CommunicationMessage> findFirstByChannelAndAttemptsGreaterThanOrderByUpdatedAtDesc(String channel,
			int attempts);

	/** Step 7: a campaign cancelled: its waiting messages SKIPPED with the reason, in one statement. */
	@Modifying
	@Query("update CommunicationMessage m set m.status = :skipped, m.lastError = :reason, m.updatedAt = :now,"
			+ " m.updatedBy = :user where m.campaignId = :campaignId and m.status = :pending")
	int skipWaiting(@Param("campaignId") Long campaignId, @Param("pending") CommunicationMessageStatus pending,
			@Param("skipped") CommunicationMessageStatus skipped, @Param("reason") String reason,
			@Param("now") LocalDateTime now, @Param("user") String user);
}
