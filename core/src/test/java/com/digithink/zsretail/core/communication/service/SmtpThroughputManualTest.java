package com.digithink.zsretail.core.communication.service;

import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.provider.SmtpCommunicationProvider;
import com.digithink.zsretail.core.support.CatchSmtpServer;

/**
 * 3.3.0, communication step 8a, a measure, not a check (run by hand: -Dcomm.smtp.measure=true): how many emails one
 * cycle of the sender (its 20 s) gives to the provider smtp, against the mail server of the tests on 127.0.0.1, the
 * messages' table in memory (the database's two writes per message not counted). Prints the numbers. Step 8a part 2:
 * one connection for the cycle, and the rate per hour the New campaign window would show from this measure.
 */
@EnabledIfSystemProperty(named = "comm.smtp.measure", matches = "true")
class SmtpThroughputManualTest {

	@Test
	@DisplayName("Emails sent in one cycle of 20 s through smtp to the local mail server")
	void measure() throws Exception {
		int waiting = Integer.getInteger("comm.smtp.messages", 5000);
		try (CatchSmtpServer server = new CatchSmtpServer(0, null)) {
			CampaignTestBed bed = new CampaignTestBed();
			bed.emailEnabled = "true";
			bed.properties.put("communication.email.provider", "smtp");
			bed.properties.put(SmtpCommunicationProvider.HOST_KEY, "127.0.0.1");
			bed.properties.put(SmtpCommunicationProvider.PORT_KEY, String.valueOf(server.getPort()));
			bed.properties.put(SmtpCommunicationProvider.SECURITY_KEY, "none");
			bed.properties.put(SmtpCommunicationProvider.FROM_ADDRESS_KEY, "fidelite@magasin.tn");
			MockEnvironment env = new MockEnvironment();
			bed.properties.forEach(env::setProperty);
			SmtpCommunicationProvider smtp = new SmtpCommunicationProvider(env);
			bed.extraProviders.add(smtp);
			bed.now = LocalDateTime.now();
			for (int i = 0; i < waiting; i++) {
				CommunicationMessage message = new CommunicationMessage();
				message.setChannel(CommunicationTypes.EMAIL);
				message.setProvider("smtp");
				message.setRecipientType(CommunicationTypes.LOYALTY_MEMBER);
				message.setAddress("member" + i + "@example.tn");
				message.setSubject("Nos offres de la semaine");
				message.setBody("Bonjour, profitez de nos offres de la semaine chez Magasin Centre ! Votre solde : 120"
						+ " points.");
				message.setDedupKey("CAMPAIGN:1:member" + i + "@example.tn");
				bed.messages().save(message);
			}
			long started = System.nanoTime();
			bed.sender(smtp).run(Instant.now().plusSeconds(20));
			double seconds = (System.nanoTime() - started) / 1e9;
			long sent = bed.messageTable.values().stream().filter(m -> m.getStatus() == CommunicationMessageStatus.SENT)
					.count();
			System.out.printf("SMTP measure: %d of %d sent in %.1f s (%.1f per second); mails caught: %d on %d"
					+ " connection(s); the New campaign window would say about %s per hour%n", sent, waiting, seconds,
					sent / seconds, server.mails().size(), server.connections(),
					bed.channelStates().perHour(CommunicationTypes.EMAIL, "smtp"));
		}
	}
}
