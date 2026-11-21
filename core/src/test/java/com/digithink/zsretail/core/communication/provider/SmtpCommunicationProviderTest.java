package com.digithink.zsretail.core.communication.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Collections;

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
 * once, 4xx / no server / a timeout / a refused login tried again; no host or no sender: cannot send, the channel off.
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
	@DisplayName("Temporary: the server busy (451), no server, a silent server (timeout), a refused login")
	void temporary() throws IOException {
		server.busy(true);
		SendResult busy = provider().send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.TEMPORARY_FAILURE, busy.getOutcome());
		assertTrue(busy.getError().startsWith("SMTP 451: "), busy.getError());

		int closed;
		try (ServerSocket free = new ServerSocket(0)) {
			closed = free.getLocalPort();
		}
		SendResult down = new SmtpCommunicationProvider(env(closed)).send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.TEMPORARY_FAILURE, down.getOutcome());
		assertTrue(down.getError().startsWith("SMTP server unreachable"), down.getError());

		server.busy(false).silent(true);
		long started = System.currentTimeMillis();
		SendResult silent = provider().send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.TEMPORARY_FAILURE, silent.getOutcome());
		assertTrue(silent.getError().startsWith("SMTP timeout"), silent.getError());
		assertTrue(System.currentTimeMillis() - started < 10_000, "the read timeout (2 s) holds");

		server.silent(false).login("magasin", "bon-mot-de-passe");
		SendResult login = new SmtpCommunicationProvider(env(server.getPort())
				.withProperty(SmtpCommunicationProvider.USERNAME_KEY, "magasin")
				.withProperty(SmtpCommunicationProvider.PASSWORD_KEY, "mauvais"))
				.send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.TEMPORARY_FAILURE, login.getOutcome());
		assertTrue(login.getError().startsWith("SMTP login refused (127.0.0.1)"), login.getError());
		SendResult good = new SmtpCommunicationProvider(env(server.getPort())
				.withProperty(SmtpCommunicationProvider.USERNAME_KEY, "magasin")
				.withProperty(SmtpCommunicationProvider.PASSWORD_KEY, "bon-mot-de-passe"))
				.send(email("sana@example.tn", "Bonjour", "Texte"));
		assertEquals(Outcome.ACCEPTED, good.getOutcome(), good.getError());
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
