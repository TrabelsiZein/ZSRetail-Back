package com.digithink.zsretail.core.communication.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider.Outcome;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider.SendResult;
import com.digithink.zsretail.core.communication.service.CommunicationSettings;
import com.digithink.zsretail.core.support.CatchSmtpServer;

/**
 * 3.3.0, communication step 8a: the provider smtp against a mail server inside the test (CatchSmtpServer, 127.0.0.1, no
 * network): plain text in UTF-8 (Arabic readable), the subject, the sender; an invalid address never sent; 5xx FAILED at
 * once, 4xx tried again; no host or no sender: cannot send, the channel off. Step 8a part 2: the channel's problems (no
 * server, a timeout, a refused login, the sender refused, 421 on a new connection, a quota); one connection per batch;
 * the EHLO name and the certificate check.
 */
class SmtpCommunicationProviderTest {

	private CatchSmtpServer server;

	@BeforeEach
	void start() throws IOException {
		server = new CatchSmtpServer(0, null);
	}

	@AfterEach
	void stop() throws IOException {
		server.close();
	}

	private MockEnvironment env(int port) {
		return new MockEnvironment().withProperty(SmtpCommunicationProvider.HOST_KEY, "127.0.0.1")
				.withProperty(SmtpCommunicationProvider.PORT_KEY, String.valueOf(port))
				.withProperty(SmtpCommunicationProvider.SECURITY_KEY, "none")
				.withProperty(SmtpCommunicationProvider.TIMEOUT_KEY, "2")
				.withProperty(SmtpCommunicationProvider.FROM_ADDRESS_KEY, "fidelite@magasin.tn")
				.withProperty(SmtpCommunicationProvider.FROM_NAME_KEY, "Magasin Centre");
	}

	private SmtpCommunicationProvider provider() {
		return new SmtpCommunicationProvider(env(server.getPort()));
	}

	private static CommunicationMessage email(String to, String subject, String body) {
		CommunicationMessage message = new CommunicationMessage();
		message.setId(7L);
		message.setChannel(CommunicationTypes.EMAIL);
		message.setAddress(to);
		message.setSubject(subject);
		message.setBody(body);
		return message;
	}

	@Test
	@DisplayName("Sent: plain text in UTF-8, Arabic readable, the subject, the sender's name and address, the Message-ID on"
			+ " the sender's domain")
	void sent() throws Exception {
		String arabic = "مرحبا سناء، شكرا على شرائك. رصيدك: 120 نقطة.";
		SendResult result = provider().send(email("sana@example.tn", "شكرا - Merci", arabic));
		assertEquals(Outcome.ACCEPTED, result.getOutcome(), result.getError());
		assertTrue(result.getProviderMessageId().startsWith("<"), result.getProviderMessageId());
		// The Message-ID names the sender's domain, never this computer
		assertTrue(result.getProviderMessageId().endsWith("@magasin.tn>"), result.getProviderMessageId());
		assertEquals(1, server.mails().size());
		CatchSmtpServer.Mail mail = server.mails().get(0);
		assertEquals(Collections.singletonList("sana@example.tn"), mail.getTo());
		assertEquals("fidelite@magasin.tn", mail.getFrom());
		MimeMessage parsed = mail.parsed();
		assertEquals("شكرا - Merci", parsed.getSubject());
		assertEquals(arabic, String.valueOf(parsed.getContent()).trim());
		assertTrue(parsed.getContentType().toLowerCase().contains("text/plain"), parsed.getContentType());
		assertTrue(parsed.getContentType().toLowerCase().contains("utf-8"), parsed.getContentType());
		InternetAddress from = (InternetAddress) parsed.getFrom()[0];
		assertEquals("Magasin Centre", from.getPersonal());
	}

	@Test
	@DisplayName("Step 8c: the server's answer after the final '.' waited about 60 s (here 3 s with a 2 s timeout): the"
			+ " mail accepted, not uncertain; the connection's own timeout stays short")
	void slowAnswerAfterData() {
		server.slowAnswerAfterData(3000);
		SmtpCommunicationProvider provider = provider();
		assertEquals(60, provider.describe().get("answerAfterDataSeconds"));
		long started = System.currentTimeMillis();
		SendResult result = provider.send(email("sana@example.tn", "Merci", "Texte"));
		assertEquals(Outcome.ACCEPTED, result.getOutcome(), result.getError());
		assertTrue(System.currentTimeMillis() - started >= 3000, "the answer was waited for");
		assertEquals(1, server.mails().size());
	}

	@Test
	@DisplayName("An address that is not an email: never sent, FAILED at once with the reason")
	void invalidAddress() {
		SendResult result = provider().send(email("sana@", "Bonjour", "Texte"));
		assertEquals(Outcome.PERMANENT_FAILURE, result.getOutcome());
		assertEquals("invalid email address 'sana@': not sent", result.getError());
		assertTrue(server.mails().isEmpty());
	}

	@Test
	@DisplayName("The server refuses the address (550): FAILED at once with the server's reason")
	void refused() {
		server.refuse("nobody@example.tn");
		SendResult result = provider().send(email("nobody@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.PERMANENT_FAILURE, result.getOutcome());
		assertTrue(result.getError().startsWith("SMTP 550: "), result.getError());
		assertTrue(result.getError().contains("Recipient address rejected"), result.getError());
	}

	@Test
	@DisplayName("The email's own temporary failures: a recipient busy (451), the connection lost before its data (a"
			+ " plain temporary failure); step 8b: the connection cut after its final \".\", no answer: uncertain, never"
			+ " tried again by itself")
	void temporary() {
		server.busy(true);
		SendResult busy = provider().send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.TEMPORARY_FAILURE, busy.getOutcome());
		assertTrue(busy.getError().startsWith("SMTP 451: "), busy.getError());

		server.busy(false).hangUpAtRecipient(true);
		SendResult before = provider().send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.TEMPORARY_FAILURE, before.getOutcome(), "cut before the data: never taken");
		assertTrue(before.getError().startsWith("SMTP connection lost: "), before.getError());

		server.hangUpAtRecipient(false).hangUp(true);
		SendResult after = provider().send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.UNCERTAIN, after.getOutcome(), "cut after the final dot: may have been taken");
		assertTrue(after.getError().startsWith("SMTP: no answer after the email was sent"), after.getError());
		assertTrue(server.mails().isEmpty());
	}

	@Test
	@DisplayName("Step 8a part 2, the channel's problems (every next email would meet them): no server, a silent server"
			+ " (timeout), a refused login, the sender refused (553, or 530 without a login), 421 on a new connection, a"
			+ " daily quota (550 5.4.5)")
	void channelProblems() throws IOException {
		int closed;
		try (ServerSocket free = new ServerSocket(0)) {
			closed = free.getLocalPort();
		}
		SendResult down = new SmtpCommunicationProvider(env(closed)).send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.CHANNEL_PROBLEM, down.getOutcome());
		assertTrue(down.getError().startsWith("SMTP server unreachable"), down.getError());

		server.silent(true);
		long started = System.currentTimeMillis();
		SendResult silent = provider().send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.CHANNEL_PROBLEM, silent.getOutcome());
		assertTrue(silent.getError().startsWith("SMTP timeout"), silent.getError());
		assertTrue(System.currentTimeMillis() - started < 10_000, "the read timeout (2 s) holds");

		server.silent(false).login("magasin", "bon-mot-de-passe");
		SendResult login = new SmtpCommunicationProvider(env(server.getPort())
				.withProperty(SmtpCommunicationProvider.USERNAME_KEY, "magasin")
				.withProperty(SmtpCommunicationProvider.PASSWORD_KEY, "mauvais"))
				.send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.CHANNEL_PROBLEM, login.getOutcome());
		assertTrue(login.getError().startsWith("SMTP login refused (127.0.0.1)"), login.getError());
		assertEquals(1, server.refusedLogins());
		SendResult noLogin = provider().send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.CHANNEL_PROBLEM, noLogin.getOutcome(), "530 at MAIL FROM: the sender's problem");
		assertTrue(noLogin.getError().startsWith("SMTP sender refused (530)"), noLogin.getError());
		SendResult good = new SmtpCommunicationProvider(env(server.getPort())
				.withProperty(SmtpCommunicationProvider.USERNAME_KEY, "magasin")
				.withProperty(SmtpCommunicationProvider.PASSWORD_KEY, "bon-mot-de-passe"))
				.send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.ACCEPTED, good.getOutcome(), good.getError());
		server.login(null, null);

		server.refuseSender("553 5.7.1 <fidelite@magasin.tn>: Sender address rejected: not owned by user");
		SendResult sender = provider().send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.CHANNEL_PROBLEM, sender.getOutcome());
		assertTrue(sender.getError().startsWith("SMTP sender refused (553)"), sender.getError());
		server.refuseSender("421 4.7.0 Try again later, closing connection");
		SendResult busyServer = provider().send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.CHANNEL_PROBLEM, busyServer.getOutcome());
		assertTrue(busyServer.getError().startsWith("SMTP 421: "), busyServer.getError());
		server.refuseSender(null).quota(true);
		SendResult quota = provider().send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.CHANNEL_PROBLEM, quota.getOutcome());
		assertTrue(quota.getError().startsWith("SMTP 550: ") && quota.getError().contains("5.4.5"), quota.getError());
		assertTrue(server.mails().size() == 1, "only the good one");
	}

	@Test
	@DisplayName("Step 8a part 2: a batch sends on one connection; a refused address keeps it; closed by the server"
			+ " (421 after emails accepted) the email waits and the next one opens a new connection")
	void batch() {
		SmtpCommunicationProvider smtp = provider();
		server.refuse("nobody@example.tn");
		try (SmtpCommunicationProvider.SmtpBatch batch = smtp.openBatch()) {
			assertEquals(Outcome.ACCEPTED, batch.send(email("a@example.tn", "1", "Un")).getOutcome());
			assertEquals(Outcome.PERMANENT_FAILURE, batch.send(email("nobody@example.tn", "2", "Deux")).getOutcome());
			assertEquals(Outcome.PERMANENT_FAILURE, batch.send(email("pas-un-email", "3", "Trois")).getOutcome());
			assertEquals(Outcome.ACCEPTED, batch.send(email("b@example.tn", "4", "Quatre")).getOutcome());
			assertEquals(1, batch.connections());
		}
		assertEquals(1, server.connections());
		assertEquals(Arrays.asList(1, 1), server.mails().stream().map(CatchSmtpServer.Mail::getConnection)
				.collect(Collectors.toList()));

		server.closeAfter(2);
		try (SmtpCommunicationProvider.SmtpBatch batch = smtp.openBatch()) {
			assertEquals(Outcome.ACCEPTED, batch.send(email("c@example.tn", "5", "Cinq")).getOutcome());
			assertEquals(Outcome.ACCEPTED, batch.send(email("d@example.tn", "6", "Six")).getOutcome());
			SendResult closed = batch.send(email("e@example.tn", "7", "Sept"));
			assertEquals(Outcome.TEMPORARY_FAILURE, closed.getOutcome(), "421 after emails accepted: the email's");
			assertTrue(closed.getError().startsWith("SMTP 421: "), closed.getError());
			assertEquals(Outcome.ACCEPTED, batch.send(email("e@example.tn", "7", "Sept")).getOutcome());
			assertEquals(2, batch.connections());
		}
		assertEquals(3, server.connections());
	}

	@Test
	@DisplayName("Step 8a part 2: the EHLO name is the sender's domain; the server's name checked against its TLS"
			+ " certificate; the connection checked with RSET after a refusal")
	void mailSettings() {
		assertEquals(Outcome.ACCEPTED, provider().send(email("sana@example.tn", "Bonjour", "Texte")).getOutcome());
		assertEquals(Collections.singletonList("magasin.tn"), server.ehloNames());
		java.util.Properties props = provider().sessionProperties();
		assertEquals("magasin.tn", props.getProperty("mail.smtp.localhost"));
		assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"));
		assertEquals("true", props.getProperty("mail.smtp.userset"));
		assertNull(props.getProperty("mail.smtp.ssl.trust"), "the JDK's trusted authorities, nothing trusted blindly");
		assertEquals("magasin.tn", SmtpCommunicationProvider.ehloName("fidelite@magasin.tn"));
		assertTrue(SmtpCommunicationProvider.congestion("550 5.4.5 Daily user sending quota exceeded"));
		assertTrue(SmtpCommunicationProvider.congestion("452-4.4.5 Insufficient disk space"));
		assertFalse(SmtpCommunicationProvider.congestion("550 5.1.1 User unknown"));
	}

	@Test
	@DisplayName("No host or no sender: cannot send, the email channel on with smtp treated as off (one reason), never a"
			+ " refused start; the settings shown never hold the password")
	void unavailable() {
		SmtpCommunicationProvider noHost = new SmtpCommunicationProvider(new MockEnvironment()
				.withProperty(SmtpCommunicationProvider.FROM_ADDRESS_KEY, "fidelite@magasin.tn"));
		assertEquals("communication.email.smtp.host is empty", noHost.unavailableReason());
		SmtpCommunicationProvider noSender = new SmtpCommunicationProvider(new MockEnvironment()
				.withProperty(SmtpCommunicationProvider.HOST_KEY, "mail.magasin.tn"));
		assertEquals("communication.email.from-address is empty", noSender.unavailableReason());
		assertNull(provider().unavailableReason());

		MockEnvironment on = new MockEnvironment().withProperty("communication.email.enabled", "true")
				.withProperty("communication.email.provider", "smtp");
		CommunicationSettings settings = new CommunicationSettings(on, java.util.Arrays.asList("test", "smtp"),
				java.util.Arrays.asList(new TestCommunicationProvider(), noHost));
		assertFalse(settings.isEnabled(CommunicationTypes.EMAIL));
		assertEquals("communication.email.smtp.host is empty", settings.offReason(CommunicationTypes.EMAIL));

		SmtpCommunicationProvider secret = new SmtpCommunicationProvider(env(server.getPort())
				.withProperty(SmtpCommunicationProvider.USERNAME_KEY, "magasin")
				.withProperty(SmtpCommunicationProvider.PASSWORD_KEY, "tres-secret"));
		assertEquals("set", secret.describe().get("password"));
		assertFalse(secret.describe().toString().contains("tres-secret"));
		assertEquals("not set", provider().describe().get("password"));
		assertEquals("Magasin Centre <fidelite@magasin.tn>", provider().sender());
	}
}
