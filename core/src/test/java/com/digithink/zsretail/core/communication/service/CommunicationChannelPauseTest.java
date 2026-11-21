package com.digithink.zsretail.core.communication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationChannelState;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider.SendResult;
import com.digithink.zsretail.core.communication.provider.TestCommunicationProvider;

/**
 * 3.3.0, communication step 8a part 2: a channel problem pauses the channel, not the messages (one problem per cycle,
 * the attempts unused, growing waits 1, 5, 15, then 60 min, the other channels going on, the messages expiring at their
 * own time, a message or a test accepted lifting the pause, a test never making the wait longer); one batch per provider
 * and cycle; the speed measured and the New campaign window's sending time.
 */
class CommunicationChannelPauseTest {

	private static final String REFUSED = "SMTP login refused (mail.magasin.tn): 535 5.7.8 Authentication failed";

	private final CampaignTestBed bed = new CampaignTestBed();

	/** The email channel's provider: down answers a channel problem; counts the calls and the batches. */
	private static final class Mail implements CommunicationProvider {
		boolean down = true;
		/** Step 8b: the next answers, before the down rule (null: none left). */
		final java.util.Deque<SendResult> next = new java.util.ArrayDeque<>();
		int calls;
		int batches;
		int closed;
		final List<String> given = new ArrayList<>();

		@Override
		public String getName() {
			return "mail";
		}

		@Override
		public SendResult send(CommunicationMessage message) {
			calls++;
			given.add(message.getAddress());
			if (!next.isEmpty()) {
				return next.poll();
			}
			return down ? SendResult.channelProblem(REFUSED) : SendResult.accepted("M-" + calls);
		}

		@Override
		public Batch openBatch() {
			batches++;
			return new Batch() {
				@Override
				public SendResult send(CommunicationMessage message) {
					return Mail.this.send(message);
				}

				@Override
				public void close() {
					closed++;
				}
			};
		}
	}

	private final Mail mail = new Mail();

	@BeforeEach
	void emailOn() {
		bed.emailEnabled = "true";
		bed.properties.put("communication.email.provider", "mail");
		bed.extraProviders.add(mail);
	}

	private CommunicationMessage email(String card, String address) {
		bed.member(card, "Sana", "2000000" + (bed.memberTable.size() + 1), true).setEmail(address);
		CommunicationMessage message = new CommunicationMessage();
		message.setEventId(99L);
		message.setChannel(CommunicationTypes.EMAIL);
		message.setProvider("mail");
		message.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
		message.setRecipientRef(card);
		message.setAddress(address);
		message.setSubject("Bienvenue");
		message.setBody("Bonjour");
		message.setDedupKey("99:WELCOME_EMAIL:" + card);
		return bed.messages().save(message);
	}

	private String cycle(CommunicationProvider... others) {
		List<CommunicationProvider> providers = new ArrayList<>(Arrays.asList(others));
		providers.add(mail);
		return bed.sender(providers.toArray(new CommunicationProvider[0])).run(Instant.now().plusSeconds(20));
	}

	private CommunicationChannelState state() {
		return bed.channelStateTable.get(CommunicationTypes.EMAIL);
	}

	private CommunicationChannels channels() {
		return new CommunicationChannels(bed.settings(), Arrays.asList(new TestCommunicationProvider(), mail),
				bed.messages(), bed.sender(mail), () -> "Happyness", () -> "Licence SARL",
				Optional.of(() -> (createdBy, card) -> "Magasin Centre"), () -> bed.now)
				.withChannelStates(bed.channelStates());
	}

	@Test
	@DisplayName("One problem per cycle: the channel paused, every message still waiting with its attempts unused; the"
			+ " waits grow 1, 5, 15, 60 min and stay at 60; the next try only after the wait; fixed, all leave and the"
			+ " pause ends")
	void pausesTheChannel() {
		CommunicationMessage first = email("LYL-1", "a@exemple.tn");
		CommunicationMessage second = email("LYL-2", "b@exemple.tn");
		CommunicationMessage third = email("LYL-3", "c@exemple.tn");
		assertEquals("channel email paused", cycle());
		assertEquals(1, mail.calls, "one refused login, not one per message");
		for (CommunicationMessage m : Arrays.asList(first, second, third)) {
			assertEquals(CommunicationMessageStatus.PENDING, m.getStatus());
			assertEquals(0, m.getAttempts(), "its attempts unused");
			assertNull(m.getNextAttemptAt());
			assertNull(m.getLastError());
		}
		CommunicationChannelState paused = state();
		assertEquals(bed.now, paused.getPausedSince());
		assertEquals(bed.now.plusMinutes(1), paused.getPausedUntil());
		assertEquals(1, paused.getProblems());
		assertEquals(REFUSED, paused.getPauseReason());

		assertNull(cycle(), "paused: nothing tried, nothing to say");
		assertEquals(1, mail.calls);
		bed.now = bed.now.plusSeconds(61);
		cycle();
		assertEquals(2, mail.calls);
		assertEquals(bed.now.plusMinutes(5), state().getPausedUntil());
		assertEquals(2, state().getProblems());
		assertEquals(bed.now.minusSeconds(61), state().getPausedSince(), "since the first problem");
		bed.now = bed.now.plusMinutes(4);
		cycle();
		assertEquals(2, mail.calls, "the wait of 5 min not over");

		assertEquals(Duration.ofMinutes(15), CommunicationChannelStates.pause(3));
		assertEquals(Duration.ofMinutes(60), CommunicationChannelStates.pause(4));
		assertEquals(Duration.ofMinutes(60), CommunicationChannelStates.pause(9));

		mail.down = false;
		bed.now = bed.now.plusMinutes(2);
		assertEquals("3 sent", cycle());
		assertEquals(5, mail.calls);
		assertFalse(state().isPaused(), "a message accepted ends the pause");
		assertNull(state().getPausedUntil());
		assertEquals(0, state().getProblems());
		for (CommunicationMessage m : Arrays.asList(first, second, third)) {
			assertEquals(CommunicationMessageStatus.SENT, m.getStatus());
			assertEquals(1, m.getAttempts());
		}
	}

	@Test
	@DisplayName("A paused channel stops only itself: the SMS of the same cycle leave; one batch per provider and cycle,"
			+ " closed at its end")
	void otherChannelsGoOn() {
		email("LYL-1", "a@exemple.tn");
		email("LYL-2", "b@exemple.tn");
		bed.member("LYL-9", "Karim", "20000009", true);
		CommunicationMessage sms = bed.automatic("LYL-9", "20000009");
		cycle(new TestCommunicationProvider());
		assertEquals(CommunicationMessageStatus.SENT, sms.getStatus());
		assertEquals(1, mail.calls);
		assertEquals(1, mail.batches);
		assertEquals(1, mail.closed);

		mail.down = false;
		bed.now = bed.now.plusMinutes(2);
		cycle();
		assertEquals(3, mail.calls);
		assertEquals(2, mail.batches, "one batch for the cycle's two emails");
		assertEquals(2, mail.closed);
	}

	@Test
	@DisplayName("A test accepted on the Canaux tab ends the pause at once: the waiting messages leave at the next cycle")
	void testLiftsThePause() {
		CommunicationMessage waiting = email("LYL-1", "a@exemple.tn");
		cycle();
		assertTrue(state().isPaused());
		mail.down = false;
		Map<String, Object> test = channels().testSend("EMAIL", "essai@exemple.tn", "admin");
		assertEquals(CommunicationMessageStatus.SENT, test.get("status"));
		assertEquals(true, test.get("pauseLifted"));
		assertNull(test.get("pause"));
		assertFalse(state().isPaused());
		assertEquals("1 sent", cycle(), "the wait of 1 min not over, but the pause lifted");
		assertEquals(CommunicationMessageStatus.SENT, waiting.getStatus());
	}

	@Test
	@DisplayName("A test that meets the problem again: FAILED with the reason, tried once; the pause keeps its wait (a"
			+ " click never makes it longer); the channel shows it")
	void testKeepsTheWait() {
		email("LYL-1", "a@exemple.tn");
		cycle();
		Map<String, Object> test = channels().testSend("EMAIL", "essai@exemple.tn", "admin");
		assertEquals(CommunicationMessageStatus.FAILED, test.get("status"));
		assertEquals(REFUSED, test.get("error"));
		assertEquals(false, test.get("pauseLifted"));
		assertNotNull(test.get("pause"));
		CommunicationMessage row = bed.messageTable.get((Long) test.get("id"));
		assertEquals(1, row.getAttempts(), "a test was tried");
		assertNull(row.getNextAttemptAt());
		assertEquals(1, state().getProblems());
		assertEquals(bed.now.plusMinutes(1), state().getPausedUntil());

		@SuppressWarnings("unchecked")
		Map<String, Object> email = ((List<Map<String, Object>>) channels().view().get("channels")).get(2);
		@SuppressWarnings("unchecked")
		Map<String, Object> pause = (Map<String, Object>) email.get("pause");
		assertEquals(bed.now, pause.get("since"));
		assertEquals(bed.now.plusMinutes(1), pause.get("until"));
		assertEquals(REFUSED, pause.get("reason"));
	}

	@Test
	@DisplayName("No pause yet, a test meets a channel problem: the channel paused (the first wait)")
	void testStartsAPause() {
		Map<String, Object> test = channels().testSend("EMAIL", "essai@exemple.tn", "admin");
		assertEquals(CommunicationMessageStatus.FAILED, test.get("status"));
		assertTrue(state().isPaused());
		assertEquals(1, state().getProblems());
	}

	@Test
	@DisplayName("While the channel waits, its messages expire at their own time (EXPIRED, the reason says why), never"
			+ " given to the provider")
	void expiresWhilePaused() {
		CommunicationMessage soon = email("LYL-1", "a@exemple.tn");
		soon.setExpiresAt(bed.now.plusSeconds(30));
		CommunicationMessage later = email("LYL-2", "b@exemple.tn");
		later.setExpiresAt(bed.now.plusHours(24));
		cycle();
		bed.now = bed.now.plusSeconds(45);
		assertEquals("1 expired", cycle());
		assertEquals(1, mail.calls);
		assertEquals(CommunicationMessageStatus.EXPIRED, soon.getStatus());
		assertEquals(CommunicationChannelStates.EXPIRED_WHILE_PAUSED, soon.getLastError());
		assertEquals(CommunicationMessageStatus.PENDING, later.getStatus());
	}

	@Test
	@DisplayName("A restart keeps the pause: a new sender reads it and does not try before the wait is over")
	void restartKeepsThePause() {
		email("LYL-1", "a@exemple.tn");
		cycle();
		assertNull(cycle(), "a new sender (built each cycle here) reads the pause");
		assertEquals(1, mail.calls);
	}

	@Test
	@DisplayName("The speed: each cycle's messages and time, about the last 1,000, per provider; per hour = the speed"
			+ " times the sender's share of an hour (20 s of sending, then 5 s of wait, step 8b); none for the provider test, another"
			+ " provider or a measure older than 30 days")
	void speed() {
		mail.down = false;
		email("LYL-1", "a@exemple.tn");
		email("LYL-2", "b@exemple.tn");
		cycle();
		assertEquals(2, state().getSpeedMessages());
		assertEquals("mail", state().getSpeedProvider());
		assertEquals(bed.now, state().getSpeedAt());

		CommunicationChannelStates states = bed.channelStates();
		bed.channelStateTable.clear();
		states.measured(CommunicationTypes.EMAIL, "mail", 100, 50_000); // 2 a second
		assertEquals(Integer.valueOf(5760), states.perHour(CommunicationTypes.EMAIL, "mail"), "2/s x 3600 x 20/25");
		assertNull(states.perHour(CommunicationTypes.EMAIL, "smtp"), "measured on another provider");
		assertNull(states.perHour(CommunicationTypes.SMS, "test"), "the provider test sends nothing");
		states.measured(CommunicationTypes.EMAIL, "mail", 1400, 700_000);
		assertEquals(1000, state().getSpeedMessages(), "about the last 1,000");
		assertEquals(500_000, state().getSpeedMillis(), "the same speed kept");
		states.measured(CommunicationTypes.EMAIL, "smtp", 10, 1_000);
		assertEquals(10, state().getSpeedMessages(), "a new provider starts a new measure");
		bed.now = bed.now.plusDays(31);
		assertNull(states.perHour(CommunicationTypes.EMAIL, "smtp"), "older than 30 days");
	}

	@Test
	@DisplayName("New campaign window: about N per hour and the hours for the campaign (the waiting ones first); a"
			+ " proposed lifetime with a fifth more, 1 to 720 h; no recent sends: no line")
	void sendingTime() {
		for (int i = 1; i <= 3; i++) {
			bed.member("LYL-C" + i, "Sana", "2000010" + i, true).setEmail("c" + i + "@exemple.tn");
		}
		bed.template("PROMO_EMAIL", CommunicationTypes.EMAIL, "Bonjour {firstName}", true).setSubject("Promo");
		assertNull(bed.campaigns().audience("PROMO_EMAIL", null).get("sending"), "never sent: no line");

		CommunicationChannelStates states = bed.channelStates();
		states.measured(CommunicationTypes.EMAIL, "mail", 1, 1_440_000); // 2 an hour (x 20/25)
		@SuppressWarnings("unchecked")
		Map<String, Object> sending = (Map<String, Object>) bed.campaigns().audience("PROMO_EMAIL", null).get("sending");
		assertEquals(2, sending.get("perHour"));
		assertEquals(0L, sending.get("waiting"));
		assertEquals(1.5, sending.get("hours"));
		assertEquals(2, sending.get("proposedLifetimeHours"), "1.5 h plus a fifth, rounded up");
		assertEquals(false, sending.get("beyondMax"));

		for (int i = 0; i < 2000; i++) {
			CommunicationMessage queued = new CommunicationMessage();
			queued.setChannel(CommunicationTypes.EMAIL);
			queued.setProvider("mail");
			queued.setAddress("q" + i + "@exemple.tn");
			queued.setDedupKey("Q:" + i);
			bed.messages().save(queued);
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> long_ = (Map<String, Object>) bed.campaigns().audience("PROMO_EMAIL", null).get("sending");
		assertEquals(2000L, long_.get("waiting"));
		assertEquals(1001.5, long_.get("hours"));
		assertEquals(720, long_.get("proposedLifetimeHours"));
		assertEquals(true, long_.get("beyondMax"));
	}

	@Test
	@DisplayName("Step 8b, no answer once the message left: FAILED \"no answer from the provider, check before sending it"
			+ " again\", one attempt; the channel paused; never sent again by the cycle; the others leave after the wait")
	void uncertain() {
		mail.down = false;
		CommunicationMessage first = email("LYL-1", "a@exemple.tn");
		CommunicationMessage second = email("LYL-2", "b@exemple.tn");
		mail.next.add(SendResult.uncertain("SMS: no answer within 30 s"));
		assertEquals("1 failed, channel email paused", cycle());
		assertEquals(1, mail.calls, "the cycle stops on the channel");
		assertEquals(CommunicationMessageStatus.FAILED, first.getStatus());
		assertEquals(1, first.getAttempts());
		assertEquals(CommunicationSender.NO_ANSWER + " (SMS: no answer within 30 s)", first.getLastError());
		assertEquals(CommunicationMessageStatus.PENDING, second.getStatus());
		assertEquals(0, second.getAttempts());
		assertEquals("no answer from the provider: SMS: no answer within 30 s", state().getPauseReason());
		assertEquals(bed.now.plusMinutes(1), state().getPausedUntil());
		assertTrue(first.getSendRef() != null && first.getSendRef().matches("[0-9a-f]{32}"), first.getSendRef());

		bed.now = bed.now.plusMinutes(2);
		assertEquals("1 sent", cycle());
		assertEquals(Arrays.asList("a@exemple.tn", "b@exemple.tn"), mail.given, "the first never sent twice");
		assertEquals(CommunicationMessageStatus.FAILED, first.getStatus());
		assertFalse(state().isPaused());
	}

	@Test
	@DisplayName("Step 8b: the wait the provider gives (429 Retry-After) is the pause's wait; a test with no answer pauses"
			+ " the channel too")
	void providerWait() {
		email("LYL-1", "a@exemple.tn");
		mail.next.add(SendResult.channelProblem("SMS too many requests (HTTP 429), wait 90 s: slow down",
				Duration.ofSeconds(90)));
		cycle();
		assertEquals(bed.now.plusSeconds(90), state().getPausedUntil());
		assertEquals(1, state().getProblems());

		bed.channelStateTable.clear();
		mail.next.add(SendResult.uncertain("SMS: no answer within 30 s"));
		Map<String, Object> test = channels().testSend("EMAIL", "essai@exemple.tn", "admin");
		assertEquals(CommunicationMessageStatus.FAILED, test.get("status"));
		assertEquals(CommunicationSender.NO_ANSWER + " (SMS: no answer within 30 s)", test.get("error"));
		assertTrue(state().isPaused());
	}

	@Test
	@DisplayName("Step 8b: after a cycle, messages still due on a channel not paused: more to do (the next cycle sooner);"
			+ " none left, or only on a paused channel: nothing more")
	void moreToDo() {
		mail.down = false;
		email("LYL-1", "a@exemple.tn");
		CommunicationSender sender = bed.sender(mail);
		sender.run(Instant.now().minusSeconds(1)); // the deadline passed: nothing sent
		assertTrue(sender.moreToDo());
		sender.run(Instant.now().plusSeconds(20));
		assertFalse(sender.moreToDo(), "all sent");

		email("LYL-2", "b@exemple.tn");
		mail.down = true;
		CommunicationSender paused = bed.sender(mail);
		paused.run(Instant.now().plusSeconds(20));
		assertFalse(paused.moreToDo(), "waiting on a paused channel only");
	}
}
