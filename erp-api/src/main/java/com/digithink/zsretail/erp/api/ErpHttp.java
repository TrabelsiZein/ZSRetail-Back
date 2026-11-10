package com.digithink.zsretail.erp.api;

import java.util.List;

import org.apache.hc.client5.http.auth.AuthSchemeFactory;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.auth.NTCredentials;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.auth.BasicSchemeFactory;
import org.apache.hc.client5.http.impl.auth.DigestSchemeFactory;
import org.apache.hc.client5.http.impl.auth.NTLMSchemeFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * Boot 4 migration, step 1.7 (decision 5): the HTTP client of every connector, on HttpClient 5.
 * <p>
 * NTLM is registered here by name: HttpClient 5's default registry has no NTLM (it is deprecated since 5.3, still present in
 * 5.5), and an ERP that asks for it would then answer 401 to every call. Digest and Basic stay, in HttpClient 4's order of
 * preference. The timeouts are on the connection, as the scheduler-hang fix of 2026-10-22 needs: a read that waits longer
 * than the read timeout fails instead of holding the job. 0 or less is no timeout, as before.
 */
@SuppressWarnings("deprecation") // NTLMSchemeFactory, NTCredentials: NTLM is what NAV and BC ask for
public final class ErpHttp {

	private ErpHttp() {
	}

	/** An NTLM login, for any host unless {@link #onlyFor} names one. */
	public static final class Login {

		final String username;
		final String password;
		final String domain;
		final String host;
		final int port;

		private Login(String username, String password, String domain, String host, int port) {
			this.username = username;
			this.password = password;
			this.domain = domain;
			this.host = host;
			this.port = port;
		}

		/** The same login, sent only to this host (and this port; -1 for any). */
		public Login onlyFor(String host, int port) {
			return new Login(username, password, domain, host, port);
		}
	}

	/**
	 * An NTLM login. An empty or null domain sends the user name alone, which ZEN's test NAV wants (given the domain in front
	 * of the user name, it answered 401, 2026-10-27).
	 */
	public static Login ntlm(String username, String password, String domain) {
		return new Login(username, password, domain, null, -1);
	}

	/** Spring's request factory on {@link #client}: what a connector's RestTemplate takes. */
	public static HttpComponentsClientHttpRequestFactory requestFactory(Login login, int connectTimeoutSeconds,
			int readTimeoutSeconds) {
		return new HttpComponentsClientHttpRequestFactory(client(login, connectTimeoutSeconds, readTimeoutSeconds));
	}

	/** The client: the timeouts on its connections, the login when there is one (null: none). */
	public static CloseableHttpClient client(Login login, int connectTimeoutSeconds, int readTimeoutSeconds) {
		Timeout connect = seconds(connectTimeoutSeconds);
		Timeout read = seconds(readTimeoutSeconds);
		HttpClientBuilder builder = HttpClients.custom()
				.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
						.setDefaultConnectionConfig(
								ConnectionConfig.custom().setConnectTimeout(connect).setSocketTimeout(read).build())
						.build())
				// The preference must name NTLM too: since 5.3 the default one leaves it out, so a registered scheme alone
				// still answers 401 (ErpHttpTest)
				.setDefaultRequestConfig(
						RequestConfig.custom().setResponseTimeout(read).setTargetPreferredAuthSchemes(SCHEMES).build())
				.setDefaultAuthSchemeRegistry(authSchemes());
		if (login != null) {
			builder.setDefaultCredentialsProvider(credentials(login));
		}
		return builder.build();
	}

	/** Where the login goes: its host and port, or any. */
	public static CredentialsProvider credentials(Login login) {
		BasicCredentialsProvider credentials = new BasicCredentialsProvider();
		credentials.setCredentials(new AuthScope(login.host, login.port), new NTCredentials(login.username,
				login.password == null ? null : login.password.toCharArray(), null, login.domain));
		return credentials;
	}

	/** NTLM first, then Digest and Basic: HttpClient 4's order, without the Kerberos schemes it tried in vain before NTLM. */
	static final List<String> SCHEMES = List.of(StandardAuthScheme.NTLM, StandardAuthScheme.DIGEST, StandardAuthScheme.BASIC);

	static Lookup<AuthSchemeFactory> authSchemes() {
		return RegistryBuilder.<AuthSchemeFactory>create().register(StandardAuthScheme.NTLM, NTLMSchemeFactory.INSTANCE)
				.register(StandardAuthScheme.DIGEST, DigestSchemeFactory.INSTANCE)
				.register(StandardAuthScheme.BASIC, BasicSchemeFactory.INSTANCE).build();
	}

	private static Timeout seconds(int seconds) {
		return seconds > 0 ? Timeout.ofSeconds(seconds) : Timeout.DISABLED;
	}
}
