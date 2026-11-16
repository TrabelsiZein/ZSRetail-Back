package com.digithink.zsretail.store.cashdifference;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.service.JournalExcel;

import lombok.extern.log4j.Log4j2;

/**
 * 3.2.0, the differences, step B: the page "Écarts de caisse" (docs/modules/cash-sessions.md, "Cash differences"), a
 * store only (this project; never on a head office). Read only:
 * <ul>
 * <li>GET /cash-differences?from&amp;to&amp;posMachineId&amp;cashierId&amp;showPending&amp;page&amp;size (showPending, default
 * false: a session only waiting for its clôture is not listed): one page of the lines and
 * the summary of the whole period (CashDifferencesDTO); from / to default to the current month.</li>
 * <li>GET /cash-differences/excel, the same filters: the file of every line and the summary.</li>
 * </ul>
 * The right of the Sessions page and the Journal (read:admin-sessions-history) opens it, else 403; without "Voir
 * montants session" (read:view-session-amounts) the fermeture's difference of a session not finally closed is not sent.
 * 400 for a date that does not parse or a period ending before it starts.
 */
@RestController
@RequestMapping("cash-differences")
@Log4j2
public class CashDifferencesAPI {

	public static final String READ_PERMISSION = "read:admin-sessions-history";
	public static final String AMOUNTS_PERMISSION = "read:view-session-amounts";

	@Autowired
	private CashDifferencesService service;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@GetMapping
	public ResponseEntity<?> read(@RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) Long posMachineId,
			@RequestParam(required = false) Long cashierId,
			@RequestParam(defaultValue = "false") boolean showPending, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		if (!currentUserProvider.currentUserHasPermission(READ_PERMISSION)) {
			return forbidden();
		}
		CashDifferencesService.Filter filter = filter(from, to, posMachineId, cashierId, showPending);
		if (filter == null) {
			return badPeriod(from, to);
		}
		return ResponseEntity.ok(service.page(filter, amountsVisible(), page, size));
	}

	@GetMapping("/excel")
	public ResponseEntity<?> excel(@RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) Long posMachineId,
			@RequestParam(required = false) Long cashierId,
			@RequestParam(defaultValue = "false") boolean showPending) {
		if (!currentUserProvider.currentUserHasPermission(READ_PERMISSION)) {
			return forbidden();
		}
		CashDifferencesService.Filter filter = filter(from, to, posMachineId, cashierId, showPending);
		if (filter == null) {
			return badPeriod(from, to);
		}
		try {
			byte[] file = CashDifferencesExcel.of(service.all(filter, amountsVisible()));
			return ResponseEntity.ok()
					.header(HttpHeaders.CONTENT_DISPOSITION,
							"attachment; filename=\"" + CashDifferencesExcel.fileName(filter.from, filter.to) + "\"")
					.contentType(MediaType.parseMediaType(JournalExcel.CONTENT_TYPE)).body(file);
		} catch (Exception e) {
			log.error("CashDifferencesAPI::excel:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(Collections.singletonMap("error", e.getMessage()));
		}
	}

	private boolean amountsVisible() {
		return currentUserProvider.currentUserHasPermission(AMOUNTS_PERMISSION);
	}

	/** The period (blank: the current month's first or last day); null when a date does not parse or to is before from. */
	static CashDifferencesService.Filter filter(String from, String to, Long posMachineId, Long cashierId) {
		return filter(from, to, posMachineId, cashierId, false);
	}

	/** The same, with the switch "Afficher les sessions en attente de clôture" (showPending, default false). */
	static CashDifferencesService.Filter filter(String from, String to, Long posMachineId, Long cashierId,
			boolean showPending) {
		YearMonth month = YearMonth.now();
		LocalDate start;
		LocalDate end;
		try {
			start = from == null || from.trim().isEmpty() ? month.atDay(1) : LocalDate.parse(from.trim());
			end = to == null || to.trim().isEmpty() ? month.atEndOfMonth() : LocalDate.parse(to.trim());
		} catch (Exception e) {
			return null;
		}
		return end.isBefore(start) ? null : new CashDifferencesService.Filter(start, end, posMachineId, cashierId, showPending);
	}

	private static ResponseEntity<?> forbidden() {
		return ResponseEntity.status(403).body(Collections.singletonMap("error", "Permission required: " + READ_PERMISSION));
	}

	private static ResponseEntity<?> badPeriod(String from, String to) {
		return ResponseEntity.badRequest().body(Collections.singletonMap("error",
				"from / to: '" + from + "' to '" + to + "' is not a period (dates YYYY-MM-DD, to not before from)"));
	}
}
