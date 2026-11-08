package com.digithink.zsretail.core.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.digithink.zsretail.core.config.MachineFileEnvironmentPostProcessor;
import com.digithink.zsretail.core.config.ZsRetailApplication;
import com.digithink.zsretail.core.model.LicenseRecord;
import com.digithink.zsretail.core.repository.LicenseRecordRepository;
import com.digithink.zsretail.core.service.MachineFingerprintService;

/**
 * Boot 4 migration, step 1.2: one application really started, the way ZsRetailApplication.run starts it (its type as a
 * profile and as default properties, its configuration files, every bean, the startup T-SQL), on an embedded Tomcat on a
 * free port and on {@link MigTestDatabase} only, and called over HTTP so a request goes through the real filter chains and,
 * for an error, the real ERROR dispatch.
 * <p>
 * No ERP connector, no head office link, no jobs: nothing is called outside this machine. The arguments win over every
 * other source (the outside file a -Dzsretail.machine-file would name is refused here, before anything starts).
 */
public final class MigTestApplication implements AutoCloseable {

	private final Class<? extends ZsRetailApplication> application;
	private final String type;
	private final MigTestDatabase database;
	private final String[] more;
	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

	private ConfigurableApplicationContext context;
	private String base;

	private MigTestApplication(Class<? extends ZsRetailApplication> application, String type, MigTestDatabase database,
			String... more) {
		this.application = application;
		this.type = type;
		this.database = database;
		this.more = more;
	}

	/**
	 * Started on a new empty database. Skips or fails when the server is unreachable ({@link MigTestDatabase}).
	 *
	 * @param more further arguments, e.g. --rfid.enabled=true
	 */
	public static MigTestApplication start(Class<? extends ZsRetailApplication> application, String type, String... more) {
		String machineFile = System.getProperty(MachineFileEnvironmentPostProcessor.MACHINE_FILE_KEY);
		if (machineFile != null) {
			throw new IllegalStateException("The startup tests run on their own database, not on the outside file " + machineFile);
		}
		MigTestDatabase database = MigTestDatabase.fromSettings();
		database.recreate();
		MigTestApplication started = new MigTestApplication(application, type, database, more);
		try {
			started.run();
		} catch (RuntimeException | Error e) {
			database.drop();
			throw e;
		}
		return started;
	}

	private void run() {
		SpringApplication spring = new SpringApplication(application);
		spring.setAdditionalProfiles(type);
		spring.setDefaultProperties(ZsRetailApplication.defaults(type));
		spring.setBannerMode(Banner.Mode.OFF);
		List<String> arguments = new ArrayList<>(Arrays.asList(
				"--spring.datasource.url=" + database.url(),
				"--spring.datasource.username=" + database.username(),
				"--spring.datasource.password=" + database.password(),
				"--server.port=0",
				"--erp.connector=none",
				"--erp.sync.enabled=false",
				"--headoffice.url=",
				"--logging.level.root=WARN"));
		arguments.addAll(Arrays.asList(more));
		context = spring.run(arguments.toArray(new String[0]));
		base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port")
				+ context.getEnvironment().getProperty("server.servlet.context-path");
	}

	/** Stopped and started again on the same database, as an installation does at every restart. */
	public void restart() {
		context.close();
		run();
	}

	public ConfigurableApplicationContext context() {
		return context;
	}

	public <T> T bean(Class<T> beanClass) {
		return context.getBean(beanClass);
	}

	/** The application stopped, then the database dropped. */
	@Override
	public void close() {
		try {
			context.close();
		} finally {
			database.drop();
		}
	}

	// ─── The licence: a fresh database has none, so every path but the bypassed ones answers 402 ─────────────────────

	/** The row LicenseService.getStatus() reads as VALID: bound to this machine, a year left. */
	public void withLicense() {
		withoutLicense();
		LicenseRecord record = new LicenseRecord();
		record.setCompanyName("MIGTEST");
		record.setInstallationId(bean(MachineFingerprintService.class).getInstallationId());
		record.setIssuedAt(LocalDate.now());
		record.setExpiresAt(LocalDate.now().plusYears(1));
		record.setRawJson("{}");
		record.setUploadedAt(LocalDateTime.now());
		record.setUploadedBy("migtest");
		record.setCurrentLicense(true);
		bean(LicenseRecordRepository.class).save(record);
	}

	public void withoutLicense() {
		bean(LicenseRecordRepository.class).deleteAll();
	}

	// ─── HTTP ───────────────────────────────────────────────────────────────────────────────────────────────────────

	/** A response as a caller gets it. */
	public static final class Answer {
		public final int status;
		public final String body;
		private final HttpResponse<String> response;

		Answer(HttpResponse<String> response) {
			this.response = response;
			this.status = response.statusCode();
			this.body = response.body();
		}

		public String header(String name) {
			return response.headers().firstValue(name).orElse(null);
		}
	}

	/** A call inside the context path; body null: none; headers: name, value, name, value... */
	public Answer call(String method, String path, String body, String... headers) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).method(method,
				body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		if (body != null) {
			request.header("Content-Type", "application/json");
		}
		if (headers.length > 0) {
			request.headers(headers);
		}
		try {
			return new Answer(http.send(request.build(), HttpResponse.BodyHandlers.ofString()));
		} catch (IOException e) {
			throw new IllegalStateException(method + " " + path + ": " + e, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(method + " " + path + " interrupted", e);
		}
	}

	/** POST /login, as the front does: the credentials in the headers username and password. */
	public Answer login(String username, String password) {
		return call("POST", "/login", null, "username", username, "password", password);
	}

	/** The Authorization header of a real login. */
	public String[] bearerOf(String username, String password) {
		Answer login = login(username, password);
		String token = login.header("Authorization");
		if (login.status != 200 || token == null) {
			throw new IllegalStateException("Login of " + username + " answered " + login.status + ": " + login.body);
		}
		return new String[] { "Authorization", token };
	}
}
