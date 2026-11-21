package com.digithink.zsretail.core.communication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.communication.CommunicationException;
import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationCampaignStatus;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationCampaign;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.model.CommunicationState;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider;
import com.digithink.zsretail.core.communication.repository.CommunicationStateRepository;
import com.digithink.zsretail.core.communication.service.CommunicationCampaigns.LaunchForm;

/**
 * 3.3.0, communication step 8e: a message taken once (two back ends on one database), no resend of a stopped or
 * cancelled campaign's message (its expiry first), a campaign whose channel goes off STOPPED, nothing of an off time.
 */
class CommunicationStep8eTest {

	private final CampaignTestBed bed = new CampaignTestBed();

	/** Records the messages given. */
	private static final class Recording implements CommunicationProvider {
		final List<Long> given = new ArrayList<>();

		@Override
		public String getName() {
			return "test";
		}

		@Override
		public SendResult send(CommunicationMessage message) {
			given.add(message.getId());
			return SendResult.accepted("R-" + given.size());
		}
	}

	private final Recording provider = new Recording();

	@BeforeEach
	void members() {
		bed.member("LYL-000001", "Sana", "20000001", true);
		bed.member("LYL-000002", "Ali", "20000002", true);
		bed.template("PROMO_SMS_FR", CommunicationTypes.SMS, "{firstName}, nos offres !", true);
	}

	private List<CommunicationMessage> launched() {
		LaunchForm form = new LaunchForm();
		form.name = "Promo";
		form.templateCode = "PROMO_SMS_FR";
		form.channel = "SMS";
		bed.campaigns().launch(form, "admin");
		bed.maker().run(Instant.now().plusSeconds(20));
		return bed.messagesOf(1L);
	}

	@Test
	@DisplayName("Two back ends on one database: a message another took (SENDING) is never given to the provider here")
	void takenOnce() {
		CommunicationMessage row = launched().get(0);
		CommunicationMessage stale = new CommunicationMessage();
		stale.setId(row.getId());
		stale.setStatus(CommunicationMessageStatus.PENDING);
		stale.setChannel(row.getChannel());
		stale.setProvider("test");
		stale.setAddress(row.getAddress());
		stale.setRecipientType(row.getRecipientType());
		stale.setRecipientRef(row.getRecipientRef());
		stale.setCampaignId(row.getCampaignId());
		stale.setDedupKey(row.getDedupKey());
		row.setStatus(CommunicationMessageStatus.SENDING); // the other back end's claim
		CommunicationSender sender = bed.sender(provider);
		assertNull(sender.handle(stale, null).status);
		assertTrue(provider.given.isEmpty());
		assertEquals(CommunicationMessageStatus.SENDING, row.getStatus(), "left to the back end that took it");

		assertEquals("1 sent", sender.run(Instant.now().plusSeconds(20)), "the other message: taken, sent");
	}

	@Test
	@DisplayName("Sending again by hand a message of a campaign STOPPED or CANCELLED: refused, a new campaign is the way;"
			+ " a message of a cancelled campaign past its expiry: EXPIRED (its expiry first)")
	void noResendOnEndedCampaign() {
		List<CommunicationMessage> made = launched();
		bed.campaigns().cancel(1L, "admin");
		CommunicationMessage failed = made.get(0);
		failed.setStatus(CommunicationMessageStatus.FAILED);
		CommunicationAdmin admin = bed.admin().withCampaigns(bed.campaignRepository());
		CommunicationException cancelled = assertThrows(CommunicationException.class,
				() -> admin.resend(failed.getId(), "admin"));
		assertEquals("COMM_RESEND_CAMPAIGN_ENDED", cancelled.getCode());
		assertEquals("CANCELLED", cancelled.getParams().get("status"));
		bed.campaignTable.get(1L).setStatus(CommunicationCampaignStatus.STOPPED);
		assertEquals("COMM_RESEND_CAMPAIGN_ENDED", assertThrows(CommunicationException.class,
				() -> admin.resend(failed.getId(), "admin")).getCode());
		assertEquals(CommunicationMessageStatus.FAILED, failed.getStatus());

		bed.campaignTable.get(1L).setStatus(CommunicationCampaignStatus.CANCELLED);
		CommunicationMessage old = made.get(1);
		old.setStatus(CommunicationMessageStatus.PENDING);
		old.setExpiresAt(bed.now.minusMinutes(1));
		bed.sender(provider).run(Instant.now().plusSeconds(20));
		assertEquals(CommunicationMessageStatus.EXPIRED, old.getStatus());
	}

	@Test
	@DisplayName("A running campaign whose channel is switched off: STOPPED with the reason, its waiting messages SKIPPED,"
			+ " never DONE")
	void channelOffStopsTheCampaign() {
		List<CommunicationMessage> made = launched();
		bed.smsEnabled = "false";
		String done = bed.maker().run(Instant.now().plusSeconds(20));
		CommunicationCampaign campaign = bed.campaignTable.get(1L);
		assertEquals(CommunicationCampaignStatus.STOPPED, campaign.getStatus());
		assertEquals("channel sms off", campaign.getStopReason());
		assertEquals(campaign.getCode() + " stopped (channel sms off)", done);
		for (CommunicationMessage m : made) {
			assertEquals(CommunicationMessageStatus.SKIPPED, m.getStatus());
			assertEquals("campaign stopped: channel sms off", m.getLastError());
		}
	}

	@Test
	@DisplayName("Nothing of an off time: the first start, and a start after an off one, start from that moment; a start"
			+ " after a start with the module on keeps catching up; an installation never on writes nothing when off")
	void offThenOn() {
		Map<String, CommunicationState> table = new LinkedHashMap<>();
		CommunicationStateRepository repo = CampaignTestBed.stub(CommunicationStateRepository.class, (method, args) -> {
			switch (method) {
				case "findByCode":
					return Optional.ofNullable(table.get(args[0]));
				case "save":
					CommunicationState state = (CommunicationState) args[0];
					table.put(state.getCode(), state);
					return state;
				default:
					return CampaignTestBed.UNHANDLED;
			}
		});
		new CommunicationOffMark(() -> false, repo).markOff();
		assertTrue(table.isEmpty(), "never on: nothing written");

		LocalDateTime t1 = LocalDateTime.of(2026, 11, 21, 10, 0);
		assertEquals(t1, new CommunicationStates(repo, () -> t1).startedOn(), "the first start");
		LocalDateTime t2 = t1.plusHours(2);
		assertEquals(t1, new CommunicationStates(repo, () -> t2).startedOn(), "on again after on: catching up");
		assertEquals(t1, new CommunicationStates(repo, () -> t2).activeSince());

		new CommunicationOffMark(() -> false, repo).markOff();
		assertEquals(0L, table.get(CommunicationStates.MODULE_ON).getIdValue());
		new CommunicationOffMark(() -> true, repo).markOff();
		assertEquals(0L, table.get(CommunicationStates.MODULE_ON).getIdValue(), "on: the off mark not touched");
		LocalDateTime t3 = t1.plusDays(3);
		assertEquals(t3, new CommunicationStates(repo, () -> t3).startedOn(), "on after off: from that moment");
		assertEquals(t3, table.get(CommunicationStates.ACTIVE_SINCE).getTimeValue());
		assertEquals(1L, table.get(CommunicationStates.MODULE_ON).getIdValue());

		// an installation of before this step (the activation instant, no mark): from that moment too
		table.remove(CommunicationStates.MODULE_ON);
		LocalDateTime t4 = t3.plusDays(1);
		assertEquals(t4, new CommunicationStates(repo, () -> t4).startedOn());
		assertFalse(table.get(CommunicationStates.MODULE_ON).getIdValue() == 0L);
	}
}
