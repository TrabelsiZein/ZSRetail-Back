package com.digithink.zsretail.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Computes a stable machine fingerprint on startup and keeps it cached in memory.
 *
 * Windows strategy:
 * - MachineGuid from registry
 * - System drive volume serial ({@code %SystemDrive%}, default {@code C:})
 *
 * installationId = SHA-256(machineGuid + "|" + volumeSerial), upper-hex.
 */
@Service
public class MachineFingerprintService {

	private static final Logger log = LoggerFactory.getLogger(MachineFingerprintService.class);

	/** Volume serial as printed by {@code vol} (four hex digits, dash, four hex digits). Locale-independent. */
	private static final Pattern VOLUME_SERIAL_PATTERN = Pattern.compile("\\b([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})\\b");

	private static volatile String installationId;

	@PostConstruct
	public void init() {
		installationId = computeInstallationId();
		log.info("Machine installation ID initialized: {}", mask(installationId));
	}

	public String getInstallationId() {
		if (installationId == null || installationId.isBlank()) {
			installationId = computeInstallationId();
		}
		return installationId;
	}

	private String computeInstallationId() {
		try {
			String machineGuid = readWindowsMachineGuid();
			String volumeSerial = readWindowsSystemVolumeSerial();
			String combined = normalize(machineGuid) + "|" + normalize(volumeSerial);
			return sha256Hex(combined).toUpperCase(Locale.ROOT);
		} catch (Exception e) {
			log.error("Failed to compute machine fingerprint: {}", e.getMessage());
			throw new IllegalStateException("Unable to compute machine installation ID", e);
		}
	}

	private String readWindowsMachineGuid() throws Exception {
		Process process = new ProcessBuilder("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
				.redirectErrorStream(true)
				.start();
		String output = readProcessOutput(process);
		process.waitFor();
		for (String line : output.split("\\R")) {
			if (line.toLowerCase(Locale.ROOT).contains("machineguid")) {
				String[] parts = line.trim().split("\\s+");
				return parts[parts.length - 1].trim();
			}
		}
		throw new IllegalStateException("MachineGuid not found");
	}

	private String readWindowsSystemVolumeSerial() throws Exception {
		String drive = systemDriveSpec();
		Process process = new ProcessBuilder("cmd", "/c", "vol " + drive)
				.redirectErrorStream(true)
				.start();
		String output = readProcessOutputCmd(process);
		int exit = process.waitFor();
		String fromVol = extractVolumeSerialFromVolOutput(output);
		if (fromVol != null) {
			return fromVol;
		}
		if (exit != 0) {
			log.warn("vol {} exited with status {}", drive, exit);
		}
		String fromWmi = readVolumeSerialViaWmi(drive);
		if (fromWmi != null) {
			return fromWmi;
		}
		throw new IllegalStateException("Volume serial number not found");
	}

	/** e.g. {@code C:} from {@code SystemDrive}, for {@code vol} / WMI. */
	private String systemDriveSpec() {
		String d = System.getenv("SystemDrive");
		if (d == null || d.isBlank()) {
			return "C:";
		}
		d = d.trim();
		if (!d.endsWith(":")) {
			d = d + ":";
		}
		return d;
	}

	/**
	 * Parses {@code vol} output without relying on English wording (localized Windows
	 * uses other phrases; Tomcat-as-service still runs {@code vol} the same way).
	 */
	private String extractVolumeSerialFromVolOutput(String output) {
		if (output == null || output.isBlank()) {
			return null;
		}
		Matcher m = VOLUME_SERIAL_PATTERN.matcher(output);
		String last = null;
		while (m.find()) {
			last = m.group(1);
		}
		return last;
	}

	/**
	 * Same serial format as {@code vol}, via WMI — works when parsing fails or output encoding is wrong.
	 */
	private String readVolumeSerialViaWmi(String driveSpec) {
		String deviceId = driveSpec.trim().toUpperCase(Locale.ROOT);
		if (!deviceId.endsWith(":")) {
			deviceId = deviceId + ":";
		}
		try {
			String ps = String.format(Locale.ROOT,
					"(Get-CimInstance Win32_LogicalDisk -Filter \"DeviceID='%s'\").VolumeSerialNumber",
					deviceId.replace("\"", "`\""));
			Process process = new ProcessBuilder(
					"powershell.exe",
					"-NoProfile",
					"-NonInteractive",
					"-Command",
					ps)
					.redirectErrorStream(true)
					.start();
			String output = readProcessOutputCmd(process);
			process.waitFor();
			String trimmed = output.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			long raw = Long.parseLong(trimmed.replaceAll("\\s+", ""));
			long serial = raw & 0xFFFFFFFFL;
			return String.format(Locale.ROOT, "%04X-%04X", (serial >> 16) & 0xFFFFL, serial & 0xFFFFL);
		} catch (Exception e) {
			log.warn("WMI volume serial fallback failed: {}", e.getMessage());
			return null;
		}
	}

	private String readProcessOutput(Process process) throws Exception {
		try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			return br.lines().collect(Collectors.joining("\n"));
		}
	}

	/** {@code cmd.exe} uses the process ANSI/OEM code page; UTF-8 mis-decodes localized {@code vol} text. */
	private String readProcessOutputCmd(Process process) throws Exception {
		Charset cs = Charset.defaultCharset();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), cs))) {
			return br.lines().collect(Collectors.joining("\n"));
		}
	}

	private String normalize(String input) {
		return input == null ? "" : input.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
	}

	private String sha256Hex(String value) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
		StringBuilder sb = new StringBuilder(hash.length * 2);
		for (byte b : hash) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	private String mask(String id) {
		if (id == null || id.length() < 12) return "N/A";
		return id.substring(0, 6) + "..." + id.substring(id.length() - 6);
	}
}
