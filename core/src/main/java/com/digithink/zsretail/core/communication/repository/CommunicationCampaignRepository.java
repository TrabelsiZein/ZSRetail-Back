package com.digithink.zsretail.core.communication.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.communication.enumeration.CommunicationCampaignStatus;
import com.digithink.zsretail.core.communication.model.CommunicationCampaign;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.3.0, step 7: the campaigns (comm_campaign). */
@Repository
public interface CommunicationCampaignRepository extends _BaseRepository<CommunicationCampaign, Long> {

	Optional<CommunicationCampaign> findByCode(String code);

	/** The campaigns of a status, oldest first (the maker takes the RUNNING ones). */
	List<CommunicationCampaign> findByStatusOrderById(CommunicationCampaignStatus status);

	/** The latest campaign of a template launched from a time (the warning "already launched today"). */
	Optional<CommunicationCampaign> findFirstByTemplateCodeAndLaunchedAtGreaterThanEqualOrderByIdDesc(String templateCode,
			LocalDateTime from);

	/** The latest campaigns, newest first (the Messages tab's campaign filter). */
	List<CommunicationCampaign> findTop100ByOrderByIdDesc();
}
