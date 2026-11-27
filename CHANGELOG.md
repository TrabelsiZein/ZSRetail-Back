# Changelog — ZSRetail-Back

One section per product version, newest first. Every commit that changes behaviour, a DB script or a config key adds
its line here, under the version of its branch. The screens are in `ZSRetail-Front/CHANGELOG.md`. The French notes shown
in the application (`APP_RELEASE_NOTES`) are written in `db/<version>/update.sql` from this file when a version is closed.

## 3.5.0 — in progress (branch `release/3.5.0`, opened from `release/3.4.0` on 2026-11-26)

Topic: ticket templates ("Modèles de tickets"), `docs/roadmap/ticket-templates.md`; steps 1 to 3 done
(`docs/modules/ticket-templates.md`).

### Before deployment
- A database at 3.4.0: `db/3.5.0/update.sql`, then the 3.5.0 WAR (`AppVersionGuard`). The script stops, changing
  nothing, while APP_VERSION is not 3.4.0 or 3.5.0; it may run again. Same script on a store and a head office.
- Run the script **before** the first start of the 3.5.0 WAR: it creates `ticket_template` and
  `pos_machine.paper_width` in their own form (Hibernate's `ddl-auto=update` would otherwise create them first).

### Features
- Ticket templates, step 1: the store setting `TICKET_RENDERER` (General Setup, "Caisse et clôture", `OLD` / `NEW`,
  `OLD` by default: the print code of before, untouched), created at start-up on a store, sent by `GET /config` as
  `ticketRenderer` (`NEW` in any case, else `OLD`; null on a head office). Its label in the event log (`AdminLabels`).
  `docs/modules/general-setup.md`, "Ticket printing".
- Ticket templates, step 2 (`docs/modules/ticket-templates.md`): the templates of the documents (core:
  `TicketTemplate`, `TicketBlocks` the block catalogue with its locks, `TicketTemplateService`), the sale ticket's only
  for now. `GET /ticket-templates` (any logged user), `PUT` / `DELETE /ticket-templates/{type}` (save, reset; the right
  `write:admin-ticket-templates`), store only (`TicketTemplateAPI`). A save is checked: a locked block switched off, the
  line's VAT off without "TVA par taux", an unknown code, a width other than 58 / 80, an unknown size are refused with
  their code. No row = the default, today's ticket, its linked switches read from Company Information and General setup
  as the old code reads them (never changed).
- The rights `read:admin-ticket-templates` and `write:admin-ticket-templates` (store), given to ADMIN on a new store
  and at every start of a store; their labels in `AdminLabels`.
- Event log: `TICKET_TEMPLATE_SAVED` and `TICKET_TEMPLATE_RESET` (Notable, subject `TICKET_TEMPLATE`), the values
  before → after, with the French names of the page (`TicketBlockLabels`).
- The VAT by rate of a ticket in the sale responses (`vatLines`: `process-sale`, `complete-pending`, `split-and-pay`,
  `details`), the tickets X / Z's spread; a difference of a millime per line with the till's VAT goes to the largest
  rate (`SessionReportService.ticketVatLines`).
- POS machines: the printer's paper width (`paperWidth`, 58 / 80 / empty = the template's), checked
  (`POS_MACHINE_PAPER_WIDTH`), sent by `GET /pos-machine/my-machine`.
- Ticket templates, step 2b part 1 (format 2): the template's layout (paper width, text size, spacing AIRY / NORMAL /
  COMPACT, 0 to 6 blank lines before the cut, separators LINE / DASHES / NONE) and element options (the logo's width,
  maximum height and position, the store name's and the TOTAL's size, the item name wrapped or cut) in the catalogue
  (`TicketBlocks.Option`), checked on save (`TICKET_TEMPLATE_OPTION`, `TICKET_TEMPLATE_UNKNOWN_OPTION`); new item
  elements: item code, its barcode's number (off), the promotion's name in the discount row (on). Every default is
  today's ticket. A format 1 layout (`compact`) reads as COMPACT. The event log describes the layout and the options.
- Ticket templates, step 2b part 2: the template owns everything the sale ticket prints. New section TOP_TEXT (its
  text: 3 lines, empty); texts of the thank-you (2 lines) and of the footer (6 lines), 48 characters a line, null =
  Company Information's as today, checked on save (`TICKET_TEMPLATE_TEXT`); the contact in four elements (WEBSITE,
  EMAIL, PHONE, FAX); the customer code's option `passenger` SHOW / HIDE (`GET /ticket-templates` sends
  `passengerCode`); the tombola section's switch linked to `TOMBOLA_ENABLED` (no lock: a saved template decides). The
  old settings are never changed; the old printing keeps reading them. Docs: `docs/modules/ticket-templates.md`.
- Ticket templates, step 3 part 1: the return voucher (`RETURN_VOUCHER`) and the cash refund slip (`CASH_REFUND`) have
  their template: one row each, the same layout and options as the sale ticket, their own blocks in `TicketBlocks`
  (the voucher: its number badge, original ticket, validity date, payments, barcode, its message and its validity
  reminder; the refund: its number badge, original ticket, its message), their locked blocks refused off by the save
  (the voucher's number, original ticket, date, validity date, barcode; the refund's number, original ticket, date;
  both the store name, the lines, their VAT, Total TVA, the amount, MF; DUPLICATA on the voucher). `TicketBlockLabels`:
  the documents and blocks named as the page names them ("Titre du document" for every document's title).
- Ticket templates, step 3 part 2: the warranty ticket (`WARRANTY`) and the tombola slip (`TOMBOLA_SLIP`) have their
  template. The warranty: the same layout and options as the other customer tickets, its own blocks (its number badge,
  the warranty's number, the sale's ticket, the item, its start and end dates, the date; the sold line with the item
  code, the unit price and the line's VAT rate off; Total TVA and "Article sous garantie"; its barcode; its message and
  its end date reminder), locked: the store's name, DUPLICATA, the warranty's number, ticket, item, dates, the item line,
  MF. The tombola slip: one section `SLIP` (locked; on its own ticket, title, the sale's number and the member's name
  locked, barcode, phone), no layout (a layout key refused: `TICKET_TEMPLATE_UNKNOWN_OPTION`). The return voucher's
  "Remise totale" off by default (today's voucher never printed it). `TicketBlockLabels`: the new blocks, and a block's
  name on one document when it prints something else there (`fr(type, code)`: "Numéro de bon", "Montant du bon",
  "Numéro de retour", "Remboursé en espèces", "N° de garantie", "Article sous garantie"); the event log uses it, and
  describes a template without layout without its layout lines. The description of `TICKET_RENDERER` (a new database)
  names no list of documents.
- Ticket templates, step 4: the internal tickets have their template: `SESSION_REPORT` (the session ticket X and Z),
  `STORE_REPORT` (the store ticket X and Z), `CLOSING_DECLARED`, `CASH_MOVEMENT_SLIP`, `CLOSING_WITHDRAWAL_SLIP`, all
  available (`TicketDocumentType.isInternal`, `internal: true` in `GET /ticket-templates`). A layout only
  (`TicketBlocks.INTERNAL_LAYOUT`: paper 58 / 80, size, spacing, `hideZeroRows` false), no blocks: a save that sends
  some is refused (`TICKET_TEMPLATE_NO_BLOCKS`), blank lines and separators are unknown options. The event log names
  them (`TicketBlockLabels`) and says "Lignes à zéro masquées / imprimées". Tests: `TicketTemplateInternalAPITest`,
  `TicketTemplateServiceTest`.

- Ticket templates, step 5 (back end): a head office manages the ticket templates of the stores whose switch "Le siège
  gère les modèles de tickets" is on (`ho_store.manages_ticket_templates`, the Stores page, per store, off by default;
  not an `ownership.<x>` line), sent with the heartbeat (`ticketTemplatesManaged`). A new domain of the copies down only,
  `DataDomain.TICKET_TEMPLATES` (no property key; the ownership code loops over `DataDomain.ownershipDomains()`). Head
  office: `TicketTemplateAPI` moved to core, the built-in defaults there, "Garantie" always listed, a save or a reset
  sent to every store (`TicketTemplateDownProvider`, a null text sent as the built-in one), ADMIN given the two rights.
  Store: the switch saved in `hol_link_right` (`TicketTemplatesManaged`); off → on the store's own templates copied to
  `ticket_template_saved` and replaced, on → off the templates received kept and editable, an event each
  (`TICKET_TEMPLATES_MANAGED_ON` / `_OFF`); `TicketTemplateDownHandler` applies the copies (origin `HEAD_OFFICE`) while
  managed; a save or a reset answers 403 `TICKET_TEMPLATE_MANAGED`, `GET /ticket-templates` says `managedByHeadOffice`;
  a save always sets the origin LOCAL. The copies down puller and its job are on every linked store (`DownHandler.active()`
  skips the templates while not managed); still no bean without a head office.
- Ticket templates, step 5 (front's part): the head office's ADMIN receives `read:admin-headoffice-ticket-templates` (the
  page "Modèles de tickets" of its Settings menu, `HEAD_OFFICE_ADMIN_PERMISSIONS`) and the store's
  `write:admin-ticket-templates` (the API's save and reset, on both installations) in place of the store's two rights.
  `TicketTemplatesRoundTripTest` (network-tests): the head office saves, the store receives it and its page gives it to
  the till; a reset, the built-in default; switch off, the rows kept and editable; on again, the store's own put aside
  and replaced.

### Fixes
- Ticket templates step 5: a store linked to a head office did not start ("No default constructor found" for
  `TicketTemplatesManaged`: two constructors, none `@Autowired`, Spring looked for a default one). The constructor
  Spring uses is now marked. `BeanConstructorsGuardTest` (network-tests): every component of every project has a
  constructor Spring can choose (its own `AutowiredAnnotationBeanPostProcessor` rule), so such a class fails the suite.
- Warranties off (`warranty.enabled` not true): the warranty ticket's template too. `GET /ticket-templates` leaves the
  document `WARRANTY` out; `PUT` and `DELETE /ticket-templates/WARRANTY` answer 404 `{"error"}` as `/warranty`
  (`WarrantyAPI.OFF_MESSAGE`). A saved warranty template stays in `ticket_template` and comes back with the line.
- Warranties: `GET /warranty`, `GET /warranty/{id}` and `POST /warranty` send `ticketDiscountFactor` (not stored): the
  share of the sold line paid after the sale's ticket discount, the returns screen's rule
  (`WarrantyService.ticketDiscountFactor`: the ticket's %, a ticket amount as the ticket's total over its lines' TTC, a
  ticket paid partly with points its discount only, `loyaltyHeaderFactor`); null without a ticket discount. The
  warranty ticket's "Remise ticket" (`TicketBlocks.WARRANTY` TOTALS gains `TICKET_DISCOUNT`, on, printed only when
  there is one).
- Returns: `GET /return-header` (the list) and `GET /return-header/{id}/details` send the return's cashier
  (`createdByUser`: id, username, full name; the user who made the return, else its session's cashier, else null), so
  the voucher's reprint prints it: the returns stored it, the endpoints did not send it (the old reprint printed
  "Caissier : System", the new one no row).

### Config
- `rfid.enabled`, `warranty.enabled`, `eventlog.enabled`: a blank value (the line with nothing after the `=`) is
  `false`, as a missing line; it stopped the startup. `true` and `false` (trimmed, any case) unchanged; any other value
  still stops the startup with its message (`NodeOwnership.moduleSwitch`).
- `storeorders.enabled`: the same rule (`moduleSwitch`): blank = `false` (on a store, alone or linked, and on a head
  office), as a missing line; it stopped the startup. `true` still only on a store whose goods come from its head office;
  any other value still stops the startup.

### DB script
- Step 5: `ho_store.manages_ticket_templates` (head office, bit, nullable: every store keeps its own templates) and the
  table `ticket_template_saved` (store: the store's own templates kept aside when its head office starts managing them,
  written, never read), each guarded.
- `db/3.5.0/update.sql`: the version check and `APP_VERSION` 3.5.0; `TICKET_RENDERER` named in a comment only (the row
  is created at start-up, as every General Setup setting).
- Step 2: the table `ticket_template` (store and head office; unique `document_type`) and the column
  `pos_machine.paper_width` (store, nullable), each guarded; no data written.
- Step 2b: `ticket_template` in format 2: `layout` (JSON) in place of `paper_width`, `font_size`, `compact`,
  `hide_zero_rows`; `format_version` default 2. A database that ran the draft of step 2 is converted in place (its rows'
  layout built from the old columns, then the columns dropped); guarded, it may run again.

## 3.4.0 — released 2026-11-26 (branch `release/3.4.0`, tag `v3.4.0`, opened from `release/3.3.0` on 2026-11-23)

### Before deployment
- A database at 3.3.0: `db/3.4.0/update.sql`, then the 3.4.0 WAR (`AppVersionGuard`). The script stops, changing
  nothing, while APP_VERSION is not 3.3.0 or 3.4.0; it may run again. Same script on a store and a head office.

### Features
- Event log ("Journal des événements"), step 1 (`docs/modules/event-log.md`): the switch, the tables, the catalogue
  of events and the helper `EventLog`. Nothing calls it yet except the start (`EVENTLOG_STARTED`, Notable since step
  2, at each start with the module on). Off, the application behaves as 3.3.0.
- Event log, step 2: the server's till events, one call at the end of each gesture (`TillEvents`): `SALE_VALIDATED`
  (a sale, a pending ticket completed, the part of a table ticket paid apart), `PENDING_SAVED` (saved, saved again),
  `PENDING_DELETED`, `PAYMENT_CHANGED`, `RETURN_DONE`, `CASH_MOVEMENT`, `SESSION_OPENED` and `SESSION_OPENING_GAP`,
  `SESSION_CLOSED` or `SESSION_CLOSED_WITH_DIFFERENCE` (whatever `ENABLE_CASH_DISCREPANCY_CHECK` says), `SESSION_VERIFIED`.
  Each has a French text for a store manager. The user, the badge's owner (the grants `TillRightsGuard` used in the
  request), the session and the POS machine are filled by themselves (`StoreEventContext`). Nothing changes for the
  gestures; off, nothing is made.
- Event log, step 3: the badge and the logins (`AccessEvents`, core). `LOGIN_OK`; `LOGIN_REFUSED`, naming the user
  only when the login typed is an existing user's, else "Identifiant inconnu" and the address (the text typed is never
  written); `LOGOUT` through the new `POST /logout` (204, writes only the event); `TILL_RIGHT_USED` for a badge pass
  (never a pass on the role's own right); `TILL_RIGHT_REFUSED` for a refused scan, a request refused 403
  `TILL_RIGHT_REQUIRED` (sale, pending, return, close, cash movement) and a refused `POST /badge/pass`, the details
  saying which case and why. `SESSION_OPENED_PREVIOUS_UNVERIFIED` (Notable): a session opened while its drawer's
  previous one is not verified. A badge's code is never written; `badge_scan_log` is written as before.
- Event log, step 4: `POST /event-log/batch` (a store), the till's events in groups (`TillEventBatch`). Only the codes
  the till may send (`EventCode.FROM_TILL`: the cart, the discount gestures, a pending ticket taken back, the reprints,
  the tickets X / Z and the session tickets, `EVENTLOG_EVENTS_DROPPED`); any other, a money event above all, is refused
  and answered so the till drops it. A UUID sent again is answered as stored. The user (by id or login), the session
  and the machine the till says are kept; what the server cannot confirm (another user's session, an unknown user,
  session or machine, an unreadable till time) is marked in the new column `doubt` and in the details, never refused.
  The sender, the address and the server's clock are the server's. One counter update per importance reserves the
  group's numbers, one JDBC batch inserts it. 404 when off, 400 above 100 events, 503 when not stored.
- Event log, step 5: two codes the till may send, `CART_LEFT_AT_LOGOUT` and `CART_LOST_ON_RELOAD` (both Notable); the
  gestures themselves are the front's (`ZSRetail-Front` CHANGELOG).
- Event log, step 5b: `PROMO_CODE_ADDED`, `PROMO_CODE_REMOVED` and `CART_CUSTOMER_CHANGED` (Notable), codes the till may
  send.
- Event log, fixes after the test round: the till's "Retour à l'administration" password check (login request marked
  `login-purpose: TILL_ADMIN_RETURN`, allowed in the CORS headers) writes `ADMIN_RETURN_VERIFIED` (Step), never a
  `LOGIN_OK`; its wrong password is a `LOGIN_REFUSED` that says so, still counted. `SESSION_OPENED` says what the drawer's
  last clôture left (no gap event, the 3.2.0 écart unchanged). A refused login has no session. `EVENTLOG_STARTED` in
  French.
- Event log, step 6: the Journal page's API (`store.eventlog.EventLogPageAPI`, the right `read:admin-event-log`,
  given to ADMIN of every store): `GET /event-log` (the filters, 50 lines a page on the server, newest first by the
  event's own time, the runs of refused logins as one line, the counters), `/excel` (20,000 events at most),
  `/catalogue`, `/missing` (the numbers missing per importance). 404 when the module is off. Amounts to 3 decimals.
  The badge's owner kept on its use and on a refused known badge (`badge_owner_*`).
- Event log, step 7: the badge scans of before copied into the Journal (`update.sql` step 5); `GET /event-log` gains
  `sessionIds` and `newValues` (the "À surveiller" links), `/catalogue` the roles' names; a count kept in the amount
  column (`LOGIN_REFUSED_REPEATED`, `EVENTLOG_EVENTS_DROPPED`) is sent as `count`, never as an amount (page and Excel);
  `CashDifferencesDTO.WatchRow.sessionIds`.
- Event log, step 9: a setting and a right named as the screen names them, the key beside (`AdminLabels`, written
  from the front by `tools/admin-labels-check.mjs --write`); a switch says oui / non.
- Event log, step 9: `ROLE_RENAMED` (Notable, a role's name changed: the events keep it as text); `INVENTORY_DELETED` is Notable (only a draft can be deleted).
- Event log, step 9: the administrator's gestures (`AdminEvents`, one call where each happens, only when a value really
  changed, never a password, a badge code or a secret setting's value): users (created, fields, role, active, password,
  deleted), badges, roles' rights, General setup (`SETTING_CHANGED`), sales prices and discounts, a local item's price
  (`ITEM_PRICE_CHANGED`, new), own prices, promotions, stock and CMP adjusted, inventories, purchases, BL receptions,
  imports (one event a run), payment methods (`PAYMENT_METHOD_SAVED` / `_DELETED`, new), loyalty points adjusted by hand
  (`LOYALTY_POINTS_ADJUSTED`, Alert, new) and programs (`LOYALTY_PROGRAM_SAVED` / `_DELETED`, new).
- Event log, step 8: the settings (`EventLogSettings`: the level, three durations, the night cleanup; checked, each
  change an `EVENTLOG_SETTINGS_CHANGED` Alert with before / after); level Notable: no Step written, none in
  `eventLogTillCodes`, a queued one refused `BELOW_LEVEL`, no hole in the numbers; the cleanup (`GET` / `POST
  /event-log/cleanup`, `delete:admin-event-log` given to ADMIN, the night job at 03:30 when on): never the last 30 days,
  a run of first numbers recorded in `cleaned_to` (never shown as missing), 500 rows a statement, one `EVENTLOG_CLEANUP`
  Alert (the button) or `EVENTLOG_CLEANUP_NIGHTLY` Notable (the night job); the cleanups and the settings' changes never deleted. `GET /event-log` `firstEventAt`; one filter "Badge de"
  (the authoriser or the badge's owner; `authorisedById` is the same filter).

### Enhancements
- Event log: `LOGIN_REFUSED` is Notable; from the 5th refused login in a row on one login or from one address (a run
  ends at a successful login or after 15 minutes without a refusal), `LOGIN_REFUSED_REPEATED` (Alert).
- `GET /config` `eventLogTillCodes`: the codes the till may send and their importance (empty when the event log is off).
- `LicenseFilter` lets `/event-log/batch` and `/logout` pass with the licence expired, as `/login`.
- Event log: a request from a PC that is no registered POS machine (the back office) has no POS machine on its event,
  its address kept (was the session's till).
- Spring's default `LogoutFilter` is off (it answered `/logout` with a redirect; nothing used it): `/logout` is now
  `POST /logout`.
- `POST /sales-header/process-sale`, `save-pending`, `update-pending/{id}`, `complete-pending/{id}`: optional `cartId`
  (the cart's UUID, sent by the till from event log step 5), kept on the events only.
- Event log: `EVENTLOG_STARTED` is Notable, not an Alert (a store restarts every morning).

### Fixes
- Event log settings: a refused value is a coded refusal (`EVENTLOG_LEVEL_INVALID`, `EVENTLOG_DURATION_INVALID`,
  `EVENTLOG_SWITCH_INVALID`, the value and the maximum), so General setup says it in the screen's language.
- `backend.log` no longer writes the login typed at a refused login (it may be a password typed in the wrong field): one
  WARN with the kind of refusal and the address; only a server failure keeps its stack trace. "User not found" no longer
  names the login.
- Users: the badge code and its expiry date typed in the add popup are saved (`POST /user-account` dropped them: only an
  edit or "Générer" gave a badge). Checked as an edit checks them (`UserAccountAPI.badgeRefusal`: 6 to 50 letters,
  digits or hyphens, a code no other user holds); a bad code or an unreadable date is refused with its message, the user
  not created.

### DB script
- `db/3.4.0/update.sql` 1-2: `event_log` (one row per event, never updated; unique `event_uuid`; indexes by date,
  subject, session, user, importance + number) and `event_log_sequence` (the last number of each importance, rows 10,
  20 and 30 seeded at 0). Every database.
- `db/3.4.0/update.sql` 3: `event_log.install_id` (the browser that made a till event) and `event_log.doubt`, index
  `ix_event_log_install` (install_id, machine_sequence_no). Every database; a database where the script ran before
  runs it again (each statement guarded).
- `db/3.4.0/update.sql` step 4 (event log step 6): `event_log.occurred_at` (filled for the rows of before),
  `badge_owner_id` / `_login` / `_name` (the uses of before from `authorised_by`), `group_key`;
  `ix_event_log_occurred` (occurred_at, importance); `ix_event_log_user` moved to (user_id, occurred_at). Re-runnable.
- `db/3.4.0/update.sql` step 5 (event log step 7): each badge scan of `badge_scan_log` (a badge used, a refused scan;
  not a role pass) copied once into `event_log` as today's event, imported, without a number (`event_uuid`
  `badge-scan-<id>`; a scan that has its event is not copied). Re-runnable: a second run copies nothing.
- `db/3.4.0/update.sql` step 6 (event log step 8): `event_log_sequence.cleaned_to` (0). Re-runnable.
- Version closed: the French notes of 3.4.0 in `APP_RELEASE_NOTES` (`db/3.4.0/update.sql`, 4 lines) and of 3.3.0
  (`db/3.3.0/update.sql`, 5 lines), written again on each run. No screen reads them yet (a "Nouveautés" screen is an
  idea for later, `docs/roadmap/open-points.md`).

### Config
- General setup (a store, created at the start): `EVENTLOG_STORE_FROM` (STEP), `EVENTLOG_KEEP_STEP_MONTHS` (3),
  `EVENTLOG_KEEP_NOTABLE_MONTHS` (24), `EVENTLOG_KEEP_ALERT_MONTHS` (24), `EVENTLOG_AUTO_CLEANUP` (false).
- `eventlog.enabled` (a store only): `false` by default and in the store type file, strictly true/false. Absent is
  false; `true` on a head office stops the start. `GET /config` `eventLogEnabled`; startup summary "event log on".
  Commented in `configs/machine-model.properties`; the configuration keys record (keys 123).

## 3.3.0 — released 2026-11-23 (branch `release/3.3.0`, opened from `release/3.2.0` on 2026-11-20)

### Before deployment
- A database at 3.2.0: `db/3.3.0/update.sql`, then the 3.3.0 WAR (`AppVersionGuard`). The script stops, changing
  nothing, while APP_VERSION is not 3.2.0 or 3.3.0; it may run again. Same script on a store and a head office.
- The Warranties page is off by default: a store that registers warranties needs `warranty.enabled=true` in its
  file (the script prints "Warranties exist: add warranty.enabled=true to this store's file." when its `warranty`
  table has rows). An installation already at 3.3.0 needs no script for this change: the new WARs and front suffice.
- "Commandes centrale" is off by default: a store that orders from its head office needs `storeorders.enabled=true` in
  its file (the script prints "Store orders exist: add storeorders.enabled=true to this store's file." when its
  `hol_order` table has rows); its head office keeps `headoffice.storeorders.post`. No script is needed for this either.

### Features
- Communication module, step 1 (`docs/modules/communication.md`): the switch and the two tables, nothing else (no job,
  no endpoint, no screen, nothing sent). Off, the application behaves as 3.2.0.
- Communication module, step 2: with the module on, the store finds its events by query (nothing added to the selling
  services or `LoyaltyService`) and writes `comm_event` rows, left `PENDING`: `TICKET_VALIDATED` for each ticket
  completed with a loyalty member, `MEMBER_CREATED` for each member enrolled at this store, both only after the
  activation instant (the first start with the module on). Its own scheduler (one thread, `comm-1`, a cycle every 60 s).
  Proof on the PC (2026-11-20, `pos_franchise_customer`): see `docs/modules/communication.md`.
- Communication module, step 3: where the store sends (module on, loyalty not the head office's), each PENDING event
  becomes one message per active rule of its type whose channel is on (address read from the member, text from the
  template, never sent more than the rule's lifetime after the event), and the sender hands them to the provider
  `test`, which sends nothing and answers accepted. Two French SMS templates and their rules are written at the start,
  inactive. On a store whose loyalty is the head office's the events stay PENDING.
- Communication module, step 4: the Communication page's API (`/communication/...`, only where the store sends; every
  read needs `read:admin-communication`, every change `write:admin-communication`, given to ADMIN at each start there):
  the messages (filters status, channel, dates, card / address / ticket number; one message with its event; "send
  again" for a FAILED or EXPIRED one), the events left out, four counters, the rules (change, on / off) and the
  templates (create, change, on / off, a preview with the SMS parts, the fields). Refusals coded (`COMM_*`, shown in fr,
  ar, en). Nothing is deleted: a rule or a template is switched off. `GET /config` gains `communication` {enabled,
  sendsHere, channels}.
- Communication module, step 5: the loyalty member's consent to receive messages (yes / no, no by default; the date
  and the author of its last change), a member field with the module on or off (`MessageConsent`). It travels with the
  shared loyalty: up at enrolment, down with the copies, through the head office edit; a missing value (an older store
  or head office) changes nothing; a merge by phone keeps a yes of the new enrolment (with its date, when newer than the
  card's last change) and never lets a no remove a yes. The processor skips a member without consent ("no consent"),
  the sender reads it again just before sending (SKIPPED when withdrawn, or the member gone), "send again" is refused
  (`COMM_RESEND_NO_CONSENT`). `GET /loyalty/members?consent=yes|no` and `consentCount`. Rules created from the page
  (`POST /communication/rules`; one rule per event + channel + template, `COMM_RULE_DUPLICATE`).
- Communication module, step 6, the head office shape: a store whose loyalty is the head office's pushes its events up
  (link job `COMMUNICATION_PUSH`, every 60 s; the event's status is its queue: PROCESSED taken, ERROR refused, SKIPPED
  ended after 720 h; a head office with the module off or without the endpoint pauses the push 1 h, quietly; "Run now"
  on the Jobs page lifts the pause: `LinkJob.runNow()`, by default `run()`). The head
  office stores them once (`POST /ho/communication/events`, origin the store, nothing older than its activation is
  messaged) and makes and sends the messages: a member not arrived yet waits, an alias card's welcome is dropped and its
  tickets go to the surviving member, a member created at the head office gets the head office's welcome. Its
  Communication page (route `admin-headoffice-communication`) shows the store and filters by it.
- Communication module, step 7, the campaigns (where the messages are made: a store alone, a store that owns its
  loyalty, a head office): one template sent once to every active member with the consent and an address for the
  channel, one message per address (`CampaignAudience`, the same query for the window's count and the messages; a head
  office's merged cards never). Launched RUNNING (`POST /communication/campaigns`; refused: a sale's fields, a template
  off, the channel off, nobody to receive it; the same template the same day only with `confirmSameDay`, 409 else),
  the messages made in the background by batches (`CAMPAIGN_MESSAGES`, dedup key per campaign and address, a restart
  goes on after the last batch), DONE when none waits, cancelled while running (its waiting messages SKIPPED). Its own
  lifetime, 24 h by default, set at the launch. The new field `{pointsBalance}`; the example template `PROMO_SMS_FR`
  written inactive at the start.
- Communication module, step 8a, the email channel for real (`docs/modules/communication.md`, "Step 8a"): the
  provider `smtp` (javax.mail, its own session per message with connect / read / write timeouts, plain text in UTF-8,
  the template's subject, the sender's address and name). An address that is not an email: FAILED at once, no retry; a
  temporary failure (no connection, timeout, 4xx, a refused login) waits 1, 5, 15, 60 min; a 5xx or a refused address:
  FAILED at once with the server's reason. Email on with `smtp` and no host or sender: one WARN, the channel off, never
  a refused start. The Message-ID on the sender's domain (never this computer's name). The email examples of the
  welcome and the thanks, with their rules, written inactive at the start. SMS and WhatsApp stay on the provider `test`.
- Communication, step 8a: the test-recipient mode (one optional line per kind of address: every message of that kind
  goes to it, the row keeps the member's address and `redirected_to` the test one); `GET /communication/channels` (each
  channel on / off and why, its provider's settings without any password, its sender, its test recipient, its last
  result; Company information empty and what stands in) and `POST /communication/channels/{channel}/test` {address} (a
  fixed short text sent now, kept as a message of type TEST, tried once; `COMM_TEST_ADDRESS`).
- Communication, step 8a: the right `write:admin-communication-campaigns` launches and cancels a campaign (403 without
  it, even with the write right), given to ADMIN at each start where the page exists, store and head office.
- Communication, step 8a part 2: the New campaign window gives the sending time from the channel's recent real rate
  ("about N per hour, about X for this campaign", the messages already waiting first; none without a recent send or on
  the provider test); longer than the lifetime, a warning and a proposed lifetime taken in one click, never a refusal.
  The Canaux tab shows a channel's pause (since when, why, the next try) and its recent rate; the Messages tab a banner
  per channel paused.
- Communication, step 8b: SMS through an HTTP gateway (`communication.sms.provider=http`), plugged in by filling lines,
  no code: the address, GET or POST, the fields in the address, a form or JSON (a template for any shape), the headers,
  the key or login, the phone's format, the timeouts, how to read the answer; placeholders {phone} {text} {sender}
  {messageId} {unicode} {key} {username} {password} {basic}; two worked examples in core's application.properties. The
  Canaux tab shows its host only, the key "set" / "not set"; "Envoyer un test" sends a test SMS.
- Communication, step 8c: WhatsApp through Meta's WhatsApp Cloud API (`communication.whatsapp.provider=meta`), always a
  template message: the template's name at Meta, its language, our {fields} in the order of their first appearance as
  Meta's values (numbered, or named first_name for {firstName}); the phone with + and the country code. A template gains
  its language and value format at Meta; a WhatsApp template without its name at Meta is refused by a rule and by the
  campaign window. Meta's answers sorted by its error code: the token, the account, the sender, payment, throughput or
  Meta unavailable pause the channel; a recipient who cannot receive fails that message only (two codes tried again
  later); a template refused stops its campaign (new status STOPPED, its waiting messages SKIPPED with the reason) and
  marks the template and its rules, never pausing the channel; no answer follows the uncertain rule. SENT means accepted
  by Meta (Meta's message id kept); no webhook. The Canaux tab shows the provider's settings; "Envoyer un test" for
  WhatsApp sends a chosen template with sample values. The welcome and thanks WhatsApp examples, with their rules,
  written inactive at the start.

### Enhancements
- Communication, step 4: a message sent again keeps its history in `last_error` ("resent ... by ...; before: ..."), and
  a message SENT keeps the `last_error` of its earlier attempts (step 3 cleared it).
- Communication, step 7: the sender sends the automatic messages (welcome, thanks) first in each cycle, the campaigns'
  in the time left; before sending it also leaves out a member made inactive or whose address changed since the message
  was made (step 5 read the consent only). The empty-field guard of every message: the company's name empty takes the
  store's, the store's empty takes the company's, both empty the licence holder's; a field still empty, no message (the
  reason names it); the previews show such a field as typed. An email's subject is filled like its text.
- Communication, step 8a, no double send: the sender saves SENDING before the provider is called; a message found
  SENDING at the start (the application stopped meanwhile) becomes FAILED "interrupted while it was being sent", never
  sent again by itself ("Renvoyer" sends it). The message's detail gives its author (`createdBy`). A member's email
  that is not one: the event's reason says so (`email '<x>' is not valid`), no longer `no email address`.
- Communication, step 8a part 2: a channel's problem pauses the channel, not its messages (the provider contract's
  `CHANNEL_PROBLEM`; for smtp: no server, a connect timeout, TLS, the login refused, the sender refused, 421 on a new
  connection, a quota x.4.5). One try per cycle, then waits of 1, 5, 15 and every 60 min; the messages keep their
  attempts and wait until their own expiry; a message or a test accepted lifts the pause; a restart keeps it. Before, a
  refused login was tried by every message (5 attempts each, then FAILED).
- Communication, step 8a part 2: one SMTP connection per cycle (5,000 emails to the local test server in 1.2 s, 8.7 s
  before); a connection lost before the server accepted stays a plain temporary failure. The server's name is checked
  against its TLS certificate; the EHLO name is the sender's domain, no longer this computer's name.
- Communication, step 8b, every channel: a message that left with no answer back (a read timeout once an SMS request
  was written, no answer after an email's final ".") is FAILED "no answer from the provider, check before sending it
  again" and the channel pauses: never sent again by itself (before: tried again, so possibly twice). The SMS gateway's
  answers: key refused, unreachable, a connect timeout, 5xx, 429 (its Retry-After), a wrong address (404 / 405 / 415),
  a redirect, a refusal naming `channel-error-texts`: the channel pauses; a number refused or another 4xx, a 200 with an
  error inside: that SMS FAILED with the gateway's words. A channel set to a provider that does not send it (smtp for
  SMS, http for email) is off with a WARN.
- Communication, step 8b: while messages are due on a channel not paused, or a campaign is still being made, the next
  cycle starts after 5 s instead of communication.cycle-seconds; the New campaign window's estimate follows (about 3
  times the rate of before).
- Communication, step 8c: an SMS gateway that refuses doubles on {messageId} can have a "no answer" tried again
  (`communication.sms.http.retry-no-answer`) instead of FAILED; the email's answer after its final "." is waited about
  60 s (servers check the mail first), the connection's own timeout unchanged; a provider's asked wait (Retry-After)
  never pauses a channel longer than 60 min (24 h before). The message's detail gives a WhatsApp message's template at
  Meta and its values (`providerParams`).
- Communication, step 8d, Meta's error codes: 131049 (Meta limits marketing messages for this person) is FAILED at once
  with "try again after 24 h" (before: tried again after 1 to 60 min, which only used the attempts); 131030 (a number
  not in the allowed list of Meta's test number) FAILED at once with what to do; 132005 (the text too long once filled
  with this member's values) fails that message only, no longer stopping the campaign nor marking the template; 131066
  (Meta's code from February 2027) FAILED at once. 131056 is still tried again later.
- Communication, step 8e: one message never blocks a channel: a message that meets a channel problem waits behind the
  next try (its attempts unused) so another message checks the channel; met again after other messages passed, it is
  FAILED at once ("refused twice by the provider while other messages passed: check its text and values"), no pause.
  WhatsApp values cleaned before sending (tab and line break to a space, more than 4 spaces to one); a value empty once
  cleaned is never sent to Meta (FAILED with the field's name).
- Communication, step 8e: a message is taken for sending in one statement (SENDING only if still PENDING) and an event
  is read with its row locked: two back ends on one database never send one message or process one event twice.
- Communication, step 8e: nothing of an off time is ever sent: when the module starts on after a start with it off (or
  for the first time), the events start from that moment (one line of comm_state written at a start with the module
  off); a back end stopped and started again with the module on keeps catching up.
- Communication, step 8e: a store whose loyalty is the head office's no longer reads, checks nor logs its channel lines
  (one WARN names those written); no messaging line stops a back end any more: communication.enabled, cycle-seconds,
  an unknown provider name and phone-country-code not valid are a WARN and the module or channel off, 60 s, 216.
- Communication, step 8e: no default provider: a channel switched on with no provider line is off with a WARN; test
  works only when written (a WARN at the start, a notice on the Canaux tab and in the New campaign window).
- Communication, step 8e: a proxy's 502, 504 or 524 with no error of the provider inside follows the no-answer rule
  (never sent again by itself); the provider's own error inside still wins.
- Communication, step 8e: a running campaign whose channel is switched off ends STOPPED with "channel <x> off" (its
  waiting messages SKIPPED), no longer DONE; a message of a stopped or cancelled campaign is never left waiting (SKIPPED
  with the campaign's reason, its expiry read first), and sending it again by hand is refused
  (COMM_RESEND_CAMPAIGN_ENDED): the fast 5 s cycle runs only while a message can leave now.
- Communication, step 8e: one WARN per refused template until it clears (not one per sale); a pause going on with a
  provider's wait of less than a minute logs at INFO, not a WARN every cycle. The 403 of the Communication API carries
  a code (COMM_FORBIDDEN). Removed: GET /communication/rules/{id} and /templates/{id} (never called).
- The Warranties page is optional, off by default (`warranty.enabled`, a store only; `docs/modules/warranties.md`):
  off, every `/warranty` endpoint answers 404 {error} and `GET /config` `warrantyEnabled` is false (the frontend shows
  no menu entry, tile, search entry or Roles line); the `warranty` table and its rows stay. On: as 3.2.0. The startup
  summary says "warranties on". The ADMIN and RESPONSIBLE roles keep their warranty permissions.
- "Commandes centrale" (the store's orders to its head office) is optional, off by default (`storeorders.enabled`, a
  store whose goods come from its head office; `docs/modules/store-orders.md`): off, every `/store-order` endpoint answers
  404 {error} and `GET /config` `storeOrdersEnabled` is false; the tables, the orders and the statuses coming down from
  the head office stay. On: as 3.2.0. The startup summary says "store orders on".
- Store orders: a head office that answers 404 on `/ho/orders` (it takes no store orders: `headoffice.storeorders.post`
  not set there) is now a 409 `{error, code: HEAD_OFFICE_NO_STORE_ORDERS}` naming that line, on the variants and on the
  send (it was a 503 "cannot be reached ... (HTTP 404)"); the order stays a draft.

### Removed
- The page Vendor balance (store and head office), back end: `GET /purchase-header/vendor-balance`,
  `VendorBalanceSummaryDTO`, `PurchaseHeaderRepository.getVendorBalanceSummary`, `PurchaseHeaderService`'s method; the
  permissions `read:vendor-balance` (ADMIN and RESPONSIBLE of a new store, `AppRoleAPI`'s list) and
  `read:admin-headoffice-vendor-balance` (head office ADMIN) are no longer given. ZS Retail handles no payments or
  finance. Vendors, purchases (their paid amount included) and purchase invoices are unchanged. A role row left with one
  of them is harmless (nothing reads it, the Roles page neither shows nor counts it); `update.sql` deletes it.

### Fixes
- Communication, step 8d: the default WhatsApp API version is v26.0, the newest on Meta's versions page; step 8c had
  v27.0, a version Meta has not published.
- A new 3.3.0 database got no `loyalty_member` table: `message_consent`'s definition said `bit default 0 not null` and
  Hibernate adds `not null` to a column not nullable, so SQL Server refused the CREATE TABLE (`not null not null`, a WARN
  only) and the links of `loyalty_member`, `loyalty_transaction` and `sales_header` to it. The definition is now `bit
  default 0` (still not null). A database upgraded by `update.sql` was not touched. New guard
  `ColumnDefinitionsGuardTest`: no column definition of an entity says `not null` where Hibernate adds it.

### DB script
- `db/3.3.0/update.sql`: the tables `comm_event` (what happened) and `comm_message` (what to send), every text column
  nvarchar, with their unique dedup keys and queue indexes; APP_VERSION 3.3.0. Step 2: the table `comm_state` (the
  activation instant, the finders' cursors) and the index `ix_comm_event_source`; run the script again on a database
  that ran its step 1 version. Step 3: the tables `comm_template` and `comm_rule`. Step 5: `loyalty_member` gains
  `message_consent` (bit, not null, default 0: every existing member without consent), `message_consent_at`,
  `message_consent_by`, on a store and on a head office alike. Step 7: the table `comm_campaign` and
  `comm_message.campaign_id` with the index `ix_comm_message_campaign`; run the script again on a database that ran an
  earlier version of it. Step 8a: `comm_message.redirected_to` (nvarchar(255), the test-recipient mode). Step 8a part
  2: the table `comm_channel_state` (a channel's pause and measured speed, one row per channel). Step 8b:
  `comm_message.send_ref` (varchar(40), our reference given to the provider as {messageId}). Step 8c:
  `comm_template.provider_language`, `provider_parameter_format`, `provider_problem`, `provider_problem_at`;
  `comm_campaign.provider_template`, `stop_reason`; `comm_message.provider_params` (nvarchar(max)), all nullable.
  13) the permissions `read:vendor-balance` and `read:admin-headoffice-vendor-balance` deleted from
  `app_role_permission` (every database); 14) a store whose `warranty` table has rows prints "Warranties exist: add
  warranty.enabled=true to this store's file." (nothing changed). Both may run again.
  15) a store whose `hol_order` table has rows prints "Store orders exist: add storeorders.enabled=true to this store's
  file." (nothing changed; may run again).

### Config
- `communication.enabled` (store and head office): `true` turns the module on; absent, empty or `false` (core's
  `application.properties`): off; any other value stops the start. No customer file names it.
- `communication.cycle-seconds` (step 2): seconds between two cycles, absent or empty 60, a whole number of at least
  1 (anything else stops the start). Written in core's `application.properties` since step 3.
- Step 3: `communication.whatsapp.enabled`, `communication.sms.enabled`, `communication.email.enabled` (absent or
  empty false, never a refused start), `communication.<channel>.provider` (absent or empty `test`; an unknown name stops
  the start), `communication.phone-country-code` (absent or empty 216). No customer file names them.
- Step 8a, all optional with their default in core's `application.properties`: `communication.email.smtp.host` (empty),
  `communication.email.smtp.port` (587), `communication.email.smtp.security` (starttls; none, starttls, ssl),
  `communication.email.smtp.username` (empty), `communication.email.smtp.password` (empty; only in the outside file),
  `communication.email.smtp.timeout-seconds` (15), `communication.email.from-address` (empty),
  `communication.email.from-name` (empty), `communication.test-recipient.phone` (empty),
  `communication.test-recipient.email` (empty); `communication.email.provider` accepts `smtp`. A value not valid is a
  WARN and its channel off, never a refused start. No customer file names them.
- Step 8b, all optional with their default in core's `application.properties`: `communication.sms.http.url` (empty),
  `.method` (GET), `.body` (query), `.json-template` (empty), `.key` (empty; only in the outside file), `.username`
  (empty), `.password` (empty; only in the outside file), `.sender` (empty), `.phone-format` (with-plus),
  `.unicode-values` (true,false), `.connect-timeout-seconds` (10), `.read-timeout-seconds` (30), `.accepted-status`
  (200-299), `.accepted-field`, `.accepted-value`, `.accepted-text`, `.id-field`, `.id-regex`, `.error-field`,
  `.channel-error-texts` (all empty); one line per field and per header (`communication.sms.http.field.<name>`,
  `.header.<Name>`, no default); `communication.sms.provider` accepts `http`. No customer file names them.
- Step 8e: `communication.<channel>.provider` has no default any more (empty in core's `application.properties`; it
  was `test`): write `smtp`, `http`, `meta` or `test`. A wrong value of `communication.enabled`, `cycle-seconds`, a
  provider or `phone-country-code` no longer stops the start. A store whose loyalty is the head office's reads only
  `communication.enabled` and `cycle-seconds`.
- Step 8c, all optional with their default in core's `application.properties`: `communication.sms.http.retry-no-answer`
  (false); `communication.whatsapp.meta.url` (https://graph.facebook.com), `.api-version` (v26.0), `.phone-number-id`
  (empty), `.token` (empty; only in the outside file), `.token-header` (Authorization), `.token-prefix` (Bearer),
  `.connect-timeout-seconds` (10), `.read-timeout-seconds` (30), `.channel-codes`, `.retry-codes`, `.message-codes`,
  `.template-codes` (empty: Meta error codes added to a family); `communication.whatsapp.provider` accepts `meta`. No
  customer file names them.
- `warranty.enabled` (a store only): `true` keeps the Warranties page; absent or `false` (the store type file says
  `false`): off. Any other value stops the start, and `true` on a head office too (as `rfid.enabled`). Written `true`
  in the dev, rehearsal and happyness-from-scratch store files (and the local ones); commented in
  `configs/machine-model.properties`. No customer file names it.
- `storeorders.enabled` (a store whose goods come from its head office): `true` keeps "Commandes centrale"; absent or
  `false` (the store type file says `false`): off. Any other value stops the start, and `true` on any other installation
  (a head office included) too. Written `true` in `configs/customers/zen-store.properties` (and the local ZEN store);
  commented in `configs/machine-model.properties`. The Happyness and dev stores have no line.

## 3.2.0 — in progress (branch `release/3.2.0`)

### Before deployment
- A database at 3.1.2: `db/3.2.0/update.sql`, then the 3.2.0 WAR (`AppVersionGuard`). The script stops, changing
  nothing, while APP_VERSION is not 3.1.2 or 3.2.0; it may run again.
- After it, Ticket Restaurant and Chèque Cadeau have no type (the admin page shows "-") and keep their code, name,
  active switch, order, N° titre and its length; the four settings `PAYMENT_METHOD_*_TITLE_NUMBER_LENGTH` are gone
  (each length is on its method). `payment_method` has a filtered index: a statement typed by hand in `sqlcmd` on that
  table needs `sqlcmd -I` (SSMS and the application have the options on).
- Back to 3.1.2 on a converted database (dev databases shared with 3.1.2 fixes): 3.1.2 refuses to start while
  APP_VERSION says 3.2.0 (`AppVersionGuard`, as for any version). With APP_VERSION set back to 3.1.2 by hand it starts
  and sells (tried on a throwaway database): Hibernate fails, quietly, to put back its unique constraint on
  `payment_method.type` (two NULLs); the till and the server no longer check the length of the N° titre of Ticket
  Restaurant and Chèque Cadeau (3.1.2 reads it by type); 3.1.2 recreates the four settings at 7, so a cheque or traite
  length other than 7 is 7 for 3.1.2. Started again, 3.2.0 puts back what it needs (the constraint dropped again, the
  settings deleted); a method without a length then takes the 7 of the recreated setting (a length set is kept).
- Head office and stores: a session copy from a store before 3.2.0 (or sent before this version) holds no scanned-tickets
  values; when it declared a scanned-tickets method, the head office shows "—" for its differences. No session is sent
  again for them: the store sends a session again only when it changes (a CLOSED session verified after the upgrade
  arrives with them). No production session has scanned tickets before 3.2.0.
- Table management: a customer that had `TABLE_MANAGEMENT_ENABLED` true in General setup adds
  `pos.table-management.enabled=true` and its `pos.table-management.table-count` to its installation file (the script
  prints a warning for such a database; none of the current customers, pos_franchise_customer had false / 10).
- The Journal's own right (`read:day-journal`): run `db/3.2.0/update.sql` (step 20) before the 3.2.0 WAR starts, else
  the roles of before get 403 on the Journal until it runs (it gives the right to every role holding
  `read:admin-sessions-history`).
- The roles' writes (Fixes, "`/app-roles`"): ADMIN of a store gets `write:admin-roles` and `delete:admin-roles` at its
  first start if it lacks them; a custom role that creates, changes or deletes roles needs them ticked on the Roles page
  (its `read:admin-roles` alone now only reads the page).

### Features
- "Caisse en direct" (`docs/modules/cash-sessions.md`, "Caisse en direct"): `GET /cashier-session/live` (`store.livecash`),
  read only, 403 without `read:admin-sessions-history`, no amount without "Voir montants session". The day (open
  sessions, the Journal's tickets and sales of today, the cash expected in the open drawers); each active POS machine
  with its open sessions and its latest session when fermée and not clôturée (whatever its date), then each open session
  without one; per open session its cashier, times, opening, tickets, sales, last ticket, cash movements, expected cash and
  "À surveiller" (`CashWatch.perSession`). Grouped queries only, 20 reads per call whatever the number of sessions.
  Nothing stored, no script. Tests: `LiveCashTest` (5).

### Enhancements
- The expected cash of a drawer is one computation, `CashierSessionService.expectedCash` (the closing, the ticket X, the
  session details, Caisse en direct), its sums read in SQL (`ExpectedCashRepository`, `SqlExpectedCashSums`: three
  grouped queries) instead of every ticket and then its payments, one query each; same rules, same result (checked
  against the stored `real_cash` of `pos_franchise_customer`: 45 of 45). Tests: `store.support.InMemoryExpectedCash` in the
  four classes that build the service by hand.
- `/cashier-session` closed further (`docs/modules/cash-sessions.md`, "Amounts hidden and the Journal's right"): the eight
  generic endpoints of `_BaseController` (GET `/`, `/{id}`, `/findByField`, `/{id}/exists`, `/count`, POST, PUT, DELETE)
  answer 403 to anyone and touch nothing (they sent `realCash` and the counts to any logged user and could create,
  change or delete a session; nothing called them). `/history` and `/{id}/details` answer 403 without
  `read:admin-sessions-history` (the till never calls them). Écarts de caisse: without "Voir montants session" the écart
  d'ouverture of an OPENED or CLOSED session is not sent, not summed (`openingGapHidden`, `hiddenOpeningGaps`, "******"
  in the Excel and a line in its summary) and no reason to list the session, as the hidden fermeture difference. Tests:
  `CashierSessionEndpointsTest` (3, MockMvc), `CashDifferencesTest.openingGapHidden`.
- Session amounts on the server and the Journal's own right (`docs/modules/cash-sessions.md`, "Amounts hidden and the
  Journal's right"). Without "Voir montants session" (`read:view-session-amounts`, one rule `SessionAmounts`, also
  used by Écarts de caisse), a session OPENED or CLOSED is sent without the amounts that enter or reveal its expected
  cash: the Sessions list (opening cash, the cashier's count, total sales, returns, differences, écart d'ouverture; an
  amount filter then finds only TERMINATED sessions), the session details (also the amounts per method, cash movements,
  opening float; tickets, returns and payment documents sent empty) and the session ticket X / Z (403, except to the
  session's own cashier: the till's ticket Z at the fermeture). Counts stay, TERMINATED sessions are unchanged, a user
  with the right gets the same answers (`amountsHidden` only sent when true). Before, the front masked part of it and
  the server sent everything. The Journal (`/store-days`, `/day-journal`, their Excel, `/store-report-ticket/x` and
  `/z`) needs `read:day-journal` ("Voir le Journal"), 403 without it, never masked; seeded on ADMIN and RESPONSIBLE,
  listed on the Roles page beside the Sessions' right. Tests: `SessionAmountsTest` (8), `DayJournalRightScriptTest` (2),
  one assertion more in `ZZDataInitializerRolesTest`.
- Écarts de caisse, step C: `watch` / `watchTotal` in `GET /cash-differences` and the Excel's sheet "À surveiller":
  per cashier of the period's sessions (the switch has no effect), tickets, cancelled tickets, returns, discounts and
  their MANUAL part, dépenses, prélèvements, refused authorisations, each from the Journal's / X / Z's definition,
  grouped queries. Without `read:view-session-amounts` the returns, dépenses and prélèvements amounts of a session not
  clôturée are hidden (counts kept). "Par caissier" lists every cashier of the period. Nothing stored, no DB script.
- Écarts de caisse: `showPending` on `GET /cash-differences` and `/excel` (default false): a session only waiting for
  its clôture is no longer listed (a store without clôture saw every session); with another reason it stays, with its
  remark. Without `read:view-session-amounts` a hidden fermeture difference is no reason then. The counts of waiting
  sessions and every sum stay the period's (`docs/modules/cash-sessions.md`, "Cash differences").
- The differences, step B (`docs/modules/cash-sessions.md`, "Cash differences"): `GET /cash-differences` and
  `/cash-differences/excel` (store only, `store.cashdifference`), every session of a period (default the current month;
  POS machine, cashier) with a fermeture or clôture difference, an écart d'ouverture, an opening without the previous
  session clôturée or still waiting for its clôture, paged newest first, and the summary of the whole period per
  cashier (signed totals and the shortages alone). Read only, nothing stored. The right `read:admin-sessions-history`
  checked by the endpoint (403); without `read:view-session-amounts` the fermeture's difference of a session not
  clôturée is not sent nor summed. The numbers come from `SessionFiguresService`, now also the Sessions list's
  (`/cashier-session/history`): one query per kind for the page's sessions instead of 5 to 6 per session; the list's
  numbers do not change (tests: the loader equals the session details, with scanned tickets and cash movements).
- Vocabulary of the two session steps (labels only, `docs/modules/cash-sessions.md`, "Vocabulary"): the cashier
  closes the session ("Fermer" / "Fermée", CLOSED), the responsible does the final closing ("Clôturer" / "Clôturée",
  TERMINATED; English "Final closing" / "Finally closed"). The server's English texts of `PREVIOUS_SESSION_NOT_VERIFIED`,
  `CLOSING_WITHDRAWAL_REQUIRED`, the "closed before its final closing" and "finally closed" refusals; the Journal Excel's
  statuses "Ouverte / Fermée / Clôturée" and the column "Fermeture" of the store ticket sheet; the description of the
  setting `VERIFY_PREVIOUS_SESSION_BEFORE_OPENING` for a new database (an existing row keeps its text: the initializer
  only inserts). No key, enum, database value, permission key or endpoint changes.
- The differences, step A (`docs/modules/cash-sessions.md`, "The previous session not verified"): the previous
  session of the drawer (the opening proposal's rule) closed and not verified. Store setting
  `VERIFY_PREVIOUS_SESSION_BEFORE_OPENING` (off): on, `openSession` refuses with 409 `PREVIOUS_SESSION_NOT_VERIFIED`
  (session, posMachine), after the POS machine's rules; off, nothing refused. Either way the new session keeps the fact
  (`cashier_session_opening_unverified`); `GET /cashier-session/opening-proposal` answers `unverifiedPrevious` and
  `verifyPreviousRequired`; the session's details `openingUnverified`. Tests: `DrawerHandoverTest` (+4).
- The opening amount (`docs/modules/cash-sessions.md`, "The drawer from one session to the next"): the verification
  of a closed session requires `drawerWithdrawal {choice: ALL | NONE | AMOUNT, amount}` ("Retrait du tiroir", 400
  `CLOSING_WITHDRAWAL_*` without or out of range, the session unchanged), on the cash the responsible counted; kept in
  `cashier_session_closing_withdrawal` with a slip number when something was taken; outside the expected cash. The next
  opening on the same drawer (the POS machine or the cashier, by `CASH_DRAWER_OWNER`) gets the amount left of the most
  recent closed session only, when verified with something left: `GET /cashier-session/opening-proposal`, then
  `cashier_session_opening_float` (the float, its source, the écart d'ouverture). Session details, tickets X / Z (session
  and store), the Journal's day (type `PRELEVEMENT_CLOTURE`, not in the in / out totals; `openingFloats`, its Excel) and
  the sessions list (`openingGap`) show them. Tests: `DrawerHandoverTest` (8).
- Payment methods, step 1 (model and conversion; `docs/backend.md`, "Payment methods 3.2.0"):
  `PaymentMethod.type` nullable, one row per type kept by the filtered unique index `ux_payment_method_type` (not a JPA
  constraint any more); `PaymentMethodType` without `TICKET_RESTAURANT` and `CHEQUE_CADEAU` (8 values); new
  `PaymentMethod.behaviour` (`PaymentMethodBehaviour`: `AMOUNT`, every method; `SCANNED_TICKETS` comes with step 3) and
  `PaymentMethod.titleNumberLength`, which the sale and the payment change check instead of the setting of the type
  (`SalesHeaderService.validateAdditionalPaymentFields`; null or 0: any length). `PaymentMethodsUpgrade` runs at each
  start, before any JPA read of the table, the same six statements as the script. A new database is seeded with the
  eight fixed methods only (no Ticket Restaurant or Chèque Cadeau), Client Chèque and Client Traite with the length 7 of
  before; the seed still runs on an empty table only, so a converted row is never recreated or retyped. Tests:
  `PaymentMethodsUpgradeTest`, `ZZDataInitializerPaymentMethodsTest`, `SalesTitleNumberLengthTest`; the statements run
  on a throwaway database (a 3.1.2 one with both rows and payments on them, a second run, a new one, a 3.1.2 start in
  between).
- Payment methods, step 2 (the admin adds and edits methods; `docs/backend.md`, "Payment methods 3.2.0, step 2"):
  POST / PUT / DELETE `/payment-method` need `write:admin-payment-methods` (403), the reads stay open. A created method
  has no type and the behaviour `AMOUNT`; an update never changes the type or the behaviour, and the switches only on a
  method without type (the N° titre length on every method); the code is unique; refusals answer 400 (deletion 409) with
  `{error, reason}`. A fixed method, or one used by a payment, a payment change or a session count, is not deleted
  (switched off). New `PaymentMethod.askTitleNumber`: the N° titre off / optional / required; its length checked on a
  N° titre given, optional or required, and kept by a payment change. Tests: `PaymentMethodServiceTest`,
  `PaymentMethodAPITest`, `SessionExportMethodWithoutTypeTest`, a method without type in the X/Z, journal and head
  office consolidation tests.
- Payment methods, step 3 (accepted tickets and the barcode parser; `docs/backend.md`, "Payment methods 3.2.0, step 3"):
  entity `AcceptedTicket` (table `accepted_ticket`): issuer, ticket code (the barcode's prefix), deduction %, validity
  in months after the printed year, barcode layout (`BarcodeLayout`), active; only under a method of behaviour
  `SCANNED_TICKETS`; two overlapping codes of one barcode length refused (any method, active or not).
  `TicketBarcodeParser` reads a barcode at a date: issuer, face value, year, serial, valid until, value counted, and a
  status (`OK`, `NOT_DIGITS`, `UNKNOWN_TICKET`, `WRONG_LENGTH`, `OTHER_METHOD`, `LINE_INACTIVE`, `EXPIRED`).
  `/payment-method/{id}/tickets`: list, add, edit, delete, `common?kind=MEAL|GIFT` (the common issuers, inactive),
  `test?barcode=` (nothing stored); `/payment-method/ticket-counts`. The behaviour is chosen when a method is created and
  changes only without type, payment or line; a method with lines is not deleted. A scanned-tickets method cannot pay
  yet (sale, split bill, payment change refused until step 5). New store: two inactive scanned-tickets methods (Ticket
  Restaurant, Chèque Cadeau) with the twelve common issuers, inactive; an existing database gets nothing.
- Payment methods, step 7 (`docs/modules/cash-sessions.md`, "Scanned tickets taken"): the scanned tickets taken,
  read-only: `GET /scanned-tickets` (filters: period, issuer, payment method, cashier; a barcode, whole or its last
  digits, leaves the period out; server paging; the totals of the filtered result), `/scanned-tickets/export` (every
  row, 20,000 at most), `/scanned-tickets/issuers`. New permission `read:admin-scanned-tickets` (403 without it): in
  ADMIN's set, given to ADMIN at every start of a store, RESPONSIBLE on a new database. The split bill's payment gaps are
  confirmed and stay open (`docs/roadmap/open-points.md`). Tests: `ScannedTicketsHistoryServiceTest`,
  `ZZDataInitializerRolesTest`.
- Payment methods, step 6 polish: `GET /cashier-session/current/payment-methods` (the open session's methods, switched
  off ones included, no amount) for the closing window's rows; the head office shows a scanned-tickets row (known by
  its own method's code) with the declared face total and tickets and no difference (sending the expected values is
  for later). Tests: `ScannedTicketsClosingTest`, `ConsolidatedSalesServiceTest`.
- Payment methods, step 6 (`docs/modules/cash-sessions.md`, "Scanned tickets"): a scanned-tickets method is declared at
  closing as a number of tickets and a face total, in the existing count row (quantity = tickets, amount = line total
  = face total; `CashierSessionService.countLineTotal`; no new column), at the cashier's closing and the responsible's
  verification (`count` beside `amount`). Expected = the session's tickets (`ScannedTicketsSummary`,
  `ScannedTicketPaymentRepository.findByCashierSession`). The session's details give the face total, the net and the
  numbers of tickets; the tickets X / Z (session and store) check the method on its face total and number of tickets
  (`ControlLine.scanned`, `expectedCount`, `countedCount`, `countDifference`) and add a block per method
  (`scannedTickets`: tickets, face total, deduction, excess kept, net, per issuer); PAIEMENTS unchanged (the net). The
  declared closing ticket gives the number of tickets (`PaymentMethodAmount.ticketsCount`). The cash check at closing
  still compares the cash only. Tests: `ScannedTicketsClosingTest`.
- Payment methods, step 5 (the till's scan card is the front's; `docs/backend.md`, "Payment methods 3.2.0, step 5"): a
  card on a terminal (Client TPE) asks its slip number, optional (the N° titre, `askTitleNumber`): the seed sets it on a
  new database, `PaymentMethodsUpgrade` (a tenth statement) and `db/3.2.0/update.sql` (step 9) on an existing one, only
  where `ask_title_number` is empty (a fixed method's switches are not the admin's). Tests: `PaymentMethodsUpgradeTest`,
  `ZZDataInitializerPaymentMethodsTest`.
- Payment methods, step 4 (the sale path of scanned tickets; `docs/backend.md`, "Payment methods 3.2.0, step 4"): a sale
  paid with a scanned-tickets method: the till sends the barcodes, the server reads and counts each ticket (counted = face
  value - deduction, applied = what is left to pay at most, the excess kept by the store, tickets before cash, no change
  from tickets): one payment per ticket (title number = the barcode) and a `ScannedTicketPayment` with the copies of the
  sale. Refused, with the whole sale and a French message naming the barcode: unknown, another method, switched off, wrong
  length, not digits, expired, twice in the sale, used by another sale (its number and date), nothing left to pay; two
  tills at once: the database decides. `GET /payment-method/{id}/tickets/check` (the till's check, ALREADY_USED with the
  sale, session and cashier). Split bill and payment change refuse scanned tickets. A line that paid sales is not deleted.
  A return of a ticket paid with scanned tickets is refused in cash (return voucher only). Tests:
  `ScannedTicketSaleTest`, the NAV simulation of 12.000 paid with two Servimax tickets.
- Payment methods, step 3b (`docs/backend.md`, "Payment methods 3.2.0, step 3b"): the cash and return voucher settings
  shown on their method's card (`GET / PUT /payment-method/{id}/settings`, the payment methods' right, the values still in
  `general_setup`). `PaymentMethod.sendToErp` replaces `ERP_SKIP_CHEQUE_PAYMENTS` in the payment export (never the return
  voucher). A refusal carries its details (the ticket overlapped). `GET /payment-method/{id}/behaviour-locked`;
  `ticket-counts` gives total and active. Tests: `SessionExportNavSimulationTest` (NAV sync simulated: an added method
  sent and not sent, a class refused then retried, cheque and voucher, payment changes), `DynamicsNavPaymentLineTest`.
- Head office sessions, scanned tickets: the session copy gains one optional field, `scannedTickets`
  (`SessionScannedCopyDTO`: per scanned-tickets method of the session, `paymentMethodCode`, `ticketCount`,
  `faceTotal`, `netTotal`, the values of the store's session details; a method declared at the closing without a
  ticket: zeros). The head office stores them (`HoSessionScanned`, table `ho_session_scanned`, replaced with the
  session) and applies the store's rule: its sessions list adds face - net to the system side, its details check a
  scanned-tickets row face against face and tickets against tickets (the net beside). A copy without the field (a store
  before 3.2.0, or a session sent before) that declared a scanned-tickets method shows "—" (no difference) in the list
  and the details, never a figure. Tests: `SalesCopyMapperTest.sessionScannedTickets`, `ConsolidatedSalesServiceTest`
  (`scannedTicketsChecked`, `scannedTicketsDeclaredNoDifference`).
- Sessions dashboard removed (`SessionDashboard.vue`, in no menu; `/admin/sessions` and `/responsible/sessions` open the
  Sessions page): `GET /cashier-session/dashboard` and `CashierSessionService.getSessionDashboard` removed (it read
  every session of the store, several queries each). Its permissions `read:admin-sessions`, `read:responsible-sessions`
  and `write:responsible-sessions` (no endpoint, route or menu checked them) are removed: from the roles of a new
  database (`ZZDataInitializer`), from the Roles page's list (`AppRoleAPI`) and from the existing roles (DB script, step
  12); a start never gives them back. The Sessions page and its verification form keep `read:admin-sessions-history`
  and `read:verify-session`. Test: `ZZDataInitializerRolesTest.sessionsDashboardPermissionsGone`.
- POS machines, step 1 of 5 (`docs/modules/pos-machines.md`): the POS machine (table `pos_machine`, 3.1.0) exists on
  every store, with or without RFID. `PosMachine`, `PosMachineRepository`, `PosMachineService`, `PosMachineAPI` moved
  from `store.rfid` to `store.posmachine` and lost `@ConditionalOnInstallation(Installation.RFID)`; new
  `PosMachineException` (INVALID 400, NOT_FOUND 404, the same `{error, reason}` body and messages as before) and
  `PosMachineAddresses` (the address rules; `RfidAddresses` keeps them for RFID with its own exception). The machine's
  reader stays RFID's: the service takes the RFID reader repository when it exists (RFID on: as in 3.1.0); without RFID
  a reader sent is ignored, a new machine has none and a machine saved again keeps the one it has. New
  `GET /pos-machine/my-machine` (any logged user, every store): the address seen and the machine recorded there
  (`MyPosMachine`); `/rfid/my-machine` and every RFID endpoint unchanged. The page's permission keeps its key
  `read:admin-rfid-pos-machines`: it leaves `RFID_ADMIN_PERMISSIONS` for `POS_MACHINES_ADMIN_PERMISSIONS`, in ADMIN's
  set of a new store and given to ADMIN at every start of a store (an RFID store's ADMIN has it already: nothing saved),
  never on a head office; `AppRoleAPI` lists it apart from RFID. Head office untouched; selling unchanged. Tests:
  `PosMachineServiceTest`, `PosMachineAPITest` (with and without RFID, my-machine), `PosMachineAddressesTest`,
  `RfidBeansTest.posMachinesWithoutRfid`, `ZZDataInitializerRolesTest.posMachinesPageGivenToAdmin`;
  `installation-beans.txt`: `PosMachineAPI` and `PosMachineService` no longer decided (`decided 110`, `store, RFID on | 12`).
- POS machines, step 2, part A (`docs/modules/pos-machines.md`, "The session's POS machine"): a session keeps the POS
  machine registered at the address of its opening request (`openSession(cashier, openingCash, remoteAddress)`; none
  from a PC not registered; active or not, it is information), in the store table `cashier_session_pos_machine`
  (`SessionPosMachine`, the session's id as key, the link to the machine; `SessionPosMachineService`): core's
  `CashierSession`, the head office and the session copies unchanged. The sales and returns get it through their
  session; nothing added on them. Shown by `GET /cashier-session/history` (`posMachineName`, filter `posMachineId`),
  `/{id}/details` (`session.posMachineName`), `/{id}/report-ticket` (`posMachineName`), the store ticket's
  `sessions[].posMachineName`, `GET /sales-header/history` (`posMachineName`, filter `posMachineId`, also on
  `/history/export`) and `GET /return-header/history` (`posMachineName`); the names of a page in one query, the filter a
  subquery on the link table. A POS machine with sessions cannot be deleted (400 `POS_MACHINE_HAS_SESSIONS`,
  "deactivate it"). Every refusal of the POS machines page gains `code` and `params` (`PosMachineException`), which the
  front shows in the language of the screen; the English `error` stays. Tests: `CashierSessionPosMachineTest`,
  `PosMachineServiceTest.codes` / `.deleteWithSessions`, `PosMachineAPITest.deleteWithSessions`.
- POS machines, step 2, part B (`docs/modules/pos-machines.md`, "The drawer belongs to"): one store choice,
  `CASH_DRAWER_OWNER` (below). `CASHIER` (the default) refuses nothing, as before. `POS_MACHINE`, fixed rules: a session
  opens only from a registered, active POS machine (409 `POS_MACHINE_NOT_REGISTERED` with the address the server sees,
  `POS_MACHINE_INACTIVE`), one open session per machine (409 `POS_MACHINE_IN_USE` naming the cashier); selling, putting
  on hold and returns only from the session's machine, its current address (409 `OTHER_POS_MACHINE` with the machine's
  name), checked by one store `HandlerInterceptor`, `PosMachineSellingGuard`, on `/sales-header/**` and
  `/return-header/**` before the controller: the sale and return code is not touched; every write endpoint of the two
  controllers is classed restricted or not (`PosMachineSellingGuardTest` fails on one not classed); closing and verifying
  are never restricted (`CashierSessionAPI`, outside the guard); the choice does not change while a session is open
  (`CashDrawerSettingCheck`, 400 `CASH_DRAWER_OWNER_SESSIONS_OPEN`) and a machine with an open session is not deactivated
  (400 `POS_MACHINE_OPEN_SESSION`). New `GET /cashier-session/current/pos-machine` (`TillPosMachine`: the drawer, the
  address, the session's machine, blocked) for the till. Core: `PUT /general-setup/{id}` answers a refusal that carries a
  code (`core.exception.CodedRefusal`) with `{error, code, params}`; other refusals unchanged. A reverse proxy is not
  supported with `POS_MACHINE`. Tests: `PosMachineSellingGuardTest`, `CashDrawerSettingCheckTest`,
  `GeneralSetupCodedRefusalTest`, `CashierSessionPosMachineTest` (rules 1, 2, the till's question),
  `PosMachineServiceTest.deactivateWithOpenSession`.
- POS machines, step 3 (`docs/modules/pos-machines.md`, "The day per POS machine"): with `CASH_DRAWER_OWNER` =
  `POS_MACHINE` the store ticket X / Z (`GET /cashier-session/store-report-ticket/x`, `/z`) carries `posMachines`: the
  sessions grouped per POS machine (by name, the sessions without one last, `posMachineId` null), each group with the
  ticket's blocks over its sessions (sales, returns, net, payments, float, control, scanned tickets), added up from the
  session tickets already built (no query per group); the store's blocks are unchanged. With `CASHIER` the field is
  absent and the answer is the one of before. Both endpoints take `posMachineId`: the same ticket over that machine's
  sessions, `posMachineId` / `posMachineName` in its header (404 for an unknown machine or one without a session that
  day). The day's Excel (`GET /cashier-session/day-journal/excel`) gains a sheet "Postes de caisse" with the same
  subtotals (the X on today, the Z on another day, as the page; `StoreReportService.dayTicketByMachine`); the journal
  sheet is unchanged. Nothing at the head office, on the sale receipt, in the amounts or the closing. Tests:
  `StoreReportServiceTest` (CASHIER unchanged, the groups and their subtotals adding up to the store, the X and Z of one
  machine, the 404s, the Excel sheet).
- POS machines, step 4 (`docs/modules/pos-machines.md`, "The users of a POS machine"): each machine has "Tous les
  utilisateurs" (default, every existing machine) or "Seulement" with its users (`PosMachine.usersRestricted`,
  `allowedUserIds`, table `pos_machine_user`). With `CASH_DRAWER_OWNER` = `POS_MACHINE`, a session opens on a
  "Seulement" machine only for a listed, active user: 409 `POS_MACHINE_USER_NOT_ALLOWED` (name), checked after rule 1 and
  before rule 2, at the opening only (a user removed keeps the open session; closing and verifying unrestricted; the admin
  too). A machine whose listed users all left opens for nobody. With `CASHIER` the list is information only. Saving:
  "Seulement" with no active user is refused (400 `POS_MACHINE_USERS_REQUIRED`, name); a list sent keeps only existing,
  active users; a field not sent keeps the machine's value, so no save (a rename included) turns "Seulement" into "Tous";
  "Tous" keeps no list. No foreign key to `user_account` (core deletes a user and knows no store table). Tests:
  `CashierSessionPosMachineTest` (+7).
- POS machines, step 5, cash movements (`docs/modules/cash-sessions.md`, "Cash movements"): `POST /cash-movement`
  (`store.cashmovement`) records PRELEVEMENT / DEPENSE (note required) / APPORT on the logged user's own open session
  (amount above 0 to the millime; never edited or deleted; numbered `<session>-Mnn`; who did it and who authorised it).
  The sixth till action `CASH_MOVEMENT` (`till:cash-movement`, checked on the request by
  `TillRightsGuard.checkCashMovement`: the role or a badge grant, logged as the others); with the drawer of the POS
  machine, refused from another PC as a sale (`PosMachineSellingGuard`, 409). The expected cash
  (`calculateRealCash`) adds the apports and subtracts the prélèvements and the dépenses; the sessions list's
  differences and the head office's follow. Session ticket X / Z, store ticket X / Z (store, one POS machine, groups):
  the block `cashMovements` (absent without any). The Journal's day: the movements by their own time and their totals
  (`DayJournalDTO.cashMovements`), never journal lines; a day with only a movement has a journal; its Excel a sheet
  "Mouvements de caisse", "Postes de caisse" columns Apports / Prélèvements / Dépenses. Session details:
  `cashMovements`. Head office: the session copy's optional `cashMovements {inTotal, outTotal}`
  (`SessionCashMovementsCopyDTO`, `ho_session.cash_movements_in/out`); a copy without it counts none. Nothing to the
  ERP (the session export sends the payments only). Rights: ADMIN and RESPONSIBLE of a new store (never POS_USER),
  ADMIN at each start; `BadgePermission.STEP_C` keeps the step C seed and its migration at the five. Tests:
  `CashMovementTest` (10) and one each in `DayJournalServiceTest`, `SalesCopyMapperTest`,
  `ConsolidatedSalesServiceTest`, `ZZDataInitializerRolesTest`; `PosMachineSellingGuardTest` classes the endpoint.

### Fixes
- `/app-roles`: POST, PUT and DELETE checked no right, so any logged user (a cashier's token) created, changed or deleted
  a role and could give their own role every permission. POST and PUT now need `write:admin-roles`, DELETE
  `delete:admin-roles`, on a head office `read:admin-headoffice-roles` (its page's one key), else 403 (`AppRoleAPI`,
  the `UserAccountAPI` style); the reads stay open. ADMIN of a store gets the two keys at each start when missing
  (`ZZDataInitializer.API_CHECKED_ADMIN_PERMISSIONS`); no script. Test: `AppRoleApiAccessTest`.
- The opening amount, after the browser test: the store ticket's "Laissé dans le tiroir" is what is in the drawers at
  the end (each drawer's last answer; it added every session up); the Journal's écarts d'ouverture keep only the
  non-zero ones; `/{id}/details` gives the list's differences (the cash movements were missing) and its `openingGap`
  (used by no screen, print or head office copy). Tests in `DrawerHandoverTest` (+3) and `CashMovementTest` (+1).
- Sessions list (store and head office): "Diff." and "Diff. Resp" rounded to the millime (they were rounded to 2
  decimals, so a 0.005 difference read 0.01); `Quantities.roundAmount`. The OTHER_POS_MACHINE message names the cash
  movements too ("selling, putting a sale on hold, returns and cash movements").
- `POST /cashier-session/close` with a body without `cashCountLines` (the old `{"actualCash": 50, "notes": "..."}`, or
  the till's `{notes}` when its closing window has no row) answered 500 "no transaction is in progress" since 1.8.0: that
  path called the closing from inside `CashierSessionService`, outside its transaction, and the closing's row lock needs
  one. One path now: every body builds the same request (counted lines when sent, `actualCash`, notes, badge grant,
  confirmed difference) for `closeSessionWithCashCount`; the old `closeSession(id, actualCash, notes)` is removed. The
  till's body is read as before. Test: `CloseSessionBodyTest` (the body `{notes}`, the old body, the till's body, every
  closing method transactional).
- Sessions list: "Diff." and "Diff. Resp" of a session with scanned tickets compared the declared face total with the
  net sales (a right count showed deduction + excess, e.g. +3.80): the system side now adds the tickets' face - net
  (`CashierSessionService.scannedFaceGap`), as the details do. Test: `ScannedTicketsClosingTest`. The head office: see
  Enhancements, "Head office sessions, scanned tickets".
- Sale note "Payment via N method(s)": the till no longer saves it (an English text on every sale, 0 methods counted for
  scanned tickets). Older sales keep it in the database; the sale details (store, head office, the invoice's ticket
  window) and the Excel "Notes" column show it empty (front `sale-note.js`). No person types a sale's note (the till's
  automatic texts only; the server's "Split from ..." is kept). A parked sale paid without a note no longer keeps its
  automatic "Pending sale - customer will return" (`SalesHeaderService.completePendingSale`; the till's "Payment via"
  covered it before). Test: `SaleDecimalQuantityTest.parkedTicketNote`.
- Languages: the product has French, English and Arabic only; German and Portuguese removed in 3.2.0 (front files; the
  back end had none). Docs updated.
- A ticket paid only with a method not sent to the ERP (a cheque with `ERP_SKIP_CHEQUE_PAYMENTS`, step 3b a method switched
  off), whose payment was then changed in Tickets history to a method that is sent, never reached the ERP: the rebuild
  made rows only for the tickets that had some. It now makes those of the changed ticket too (never of a split ticket).
- Startup order: `ZZDataInitializer` (`@DependsOn`) and `JdbcCatalogueState` (it asks for the guard first) run after
  `AppVersionGuard`. Before, on a
  head office, `HeadOfficeErpJobs` could create the initializer first, so a database of another version was written
  (this version: payment methods converted, settings deleted) before the guard refused the start. Proved on a store
  database at 3.1.2 (refused, untouched but for Hibernate's new nullable columns); `StartupOrderTest`.

### DB script
- `db/3.2.0/update.sql` step 20, every database: `read:day-journal` given to every role that holds
  `read:admin-sessions-history`, where missing (a second run adds nothing). Run twice on `pos_franchise_customer`
  (2026-11-16): ADMIN and RESPONSIBLE got it, then 0 rows.
- `db/3.2.0/update.sql` step 19, every database: five indexes on foreign keys the Journal, the tickets X / Z and the
  page Écarts de caisse join on, created only when missing: `ix_sales_line_sales_header`, `ix_payment_sales_header`,
  `ix_return_line_return_header`, `ix_return_header_session`, `ix_session_cash_count_session` (a store's table). The
  entities declare them too (Hibernate creates them on a new database). Syntax-checked; run twice on
  pos_franchise_customer (2026-11-16).
- `db/3.2.0/update.sql` step 18, a store: `cashier_session_opening_unverified` (PK the session,
  `fk_cashier_session_opening_unverified_session`, `_previous`). Not yet syntax-checked: SQL Server was stopped.
- `db/3.2.0/update.sql` step 17, a store: `cashier_session_closing_withdrawal` (PK the session,
  `fk_cashier_session_closing_withdrawal_session`, `_done_by`, index on `done_at`) and `cashier_session_opening_float`
  (PK the session, `fk_cashier_session_opening_float_session`, `_source`). Parsed with `SET PARSEONLY ON`, not run.
- `db/3.2.0/update.sql` (version 3.2.0): the unique constraint on `payment_method.type` dropped (name read), the column
  nullable, the filtered unique index `ux_payment_method_type`, the columns `behaviour` and `title_number_length`, then
  in one transaction the four lengths copied to their methods, Ticket Restaurant and Chèque Cadeau without type
  (behaviour `AMOUNT`), `behaviour` filled, the four settings deleted; step 2: the column `ask_title_number` (bit,
  nullable); step 3: the table `accepted_ticket` (in Hibernate's form, its foreign key named
  `fk_accepted_ticket_payment_method` as in the entity); APP_VERSION 3.1.2 -> 3.2.0. The script sets QUOTED_IDENTIFIER
  and the other options itself: plain sqlcmd (no `-I`) runs it (done so on pos_franchise_customer, 2026-11-12, again for
  each step). It accepts a database already at 3.2.0, so one script brings a 3.1.2 database and a 3.2.0 test database
  alike. Step 3b: the column `send_to_erp` and its conversion (the return voucher off, the cheque off when
  `ERP_SKIP_CHEQUE_PAYMENTS` was true, the others on), then the setting deleted (checked on a scratch database with the
  setting true, false and absent, twice each). Step 4: the table `scanned_ticket_payment` (a store: it has `quick_quantity`;
  in Hibernate's form, constraints named as in the entity) and the filtered unique index `ux_scanned_ticket_live_barcode`
  (`live_barcode IS NOT NULL`, also created at each start); the race of two tills proved on a scratch database (the second
  insert waits for the first, then fails on the index). Step 10: the two table management settings deleted
  (`TABLE_MANAGEMENT_ENABLED`, `TABLE_MANAGEMENT_TABLE_COUNT`, store and head office; a PRINT when it was on), also at
  each start (`PaymentMethodsUpgrade.TABLE_MANAGEMENT_SETTINGS`). Step 11, a head office (it has `ho_session`): the
  table `ho_session_scanned` (in Hibernate's form, its foreign key `fk_ho_session_scanned_session` as in the entity);
  Hibernate creates it too at the first start. Step 12, store and head office: the rows of `read:admin-sessions`,
  `read:responsible-sessions` and `write:responsible-sessions` deleted from `app_role_permission` (every role; safe to
  run twice). Step 13, a store (it has `quick_quantity`): `rfid_reader` and `pos_machine` created when missing, as
  `db/3.1.0` made them, then the index `ix_pos_machine_reader` where missing. `db/3.1.0` created them only where
  `ho_store` was missing, but a store database of the single WAR era has an empty `ho_store` (`pos_db_prod`, the
  Happyness stores, `pos_franchise_customer`): there the tables came from Hibernate at a start, without the index.
  Step 14, a store (it has `quick_quantity` and `pos_machine`): the table `cashier_session_pos_machine` (the POS machine
  of each session; in Hibernate's form, its foreign keys named as in the entity) and its index
  `ix_cashier_session_pos_machine_machine`, when missing; Hibernate creates the table at a start too (without the index).
  No data changed. Step 15, a store (`quick_quantity` and `pos_machine`): `pos_machine.users_restricted` (bit NOT NULL,
  default 0, "Tous les utilisateurs" for every existing machine; when Hibernate added it first, nullable, its empty rows
  are set to 0, then the default and NOT NULL) and the table `pos_machine_user` (`pos_machine_id`, `user_account_id`,
  primary key on both, `fk_pos_machine_user_machine` to `pos_machine`, no foreign key to `user_account`). Separate
  batches; parsed with `SET PARSEONLY ON`, not run. Step 16 (POS machines step 5): a store, the table `cash_movement`
  (Hibernate's form: `uk_cash_movement_number`, `fk_cash_movement_session`, `fk_cash_movement_done_by`,
  `fk_cash_movement_authorised_by`, indexes `ix_cash_movement_session`, `ix_cash_movement_moved_at`) and
  `till:cash-movement` given to the roles ADMIN and RESPONSIBLE where missing (a role removed from it gets it back only
  if the script runs again); a head office, `ho_session.cash_movements_in` and `cash_movements_out` (float, nullable).
  Parsed with `SET PARSEONLY ON`, not run.
- `db/3.2.0/update.sql`, release notes: the first 3.2.0 note (IMPROVE), the reorganised store menu (the settings pages
  in the user menu at the top right; Paramètres, Siège and ERP redistributed into Utilisateurs, Achats, Synchronisation).
  Rewritten on each run after the existing `DELETE`; the other notes are still written when the version is closed.

### Config
- `VERIFY_PREVIOUS_SESSION_BEFORE_OPENING` (BOOLEAN, store only, created off at the start): the differences, step A.
- Table management leaves General setup (`docs/modules/table-management.md`): the properties
  `pos.table-management.enabled` (absent: false) and `pos.table-management.table-count` (absent or not a positive number:
  10), read by `AppConfigAPI` into the same `/config` fields; the store type file states the defaults, an installation
  file needs no line. `TABLE_MANAGEMENT_ENABLED` and `TABLE_MANAGEMENT_TABLE_COUNT` no longer created, deleted by the
  script and at startup (they showed on every General setup, a head office's too). Tests: `AppConfigAPITest`,
  `PaymentMethodsUpgradeTest`, `HeadOfficeStockKeyTest`; `configuration-keys.txt` gains the two keys (keys 68).
- General setup: `ERP_SKIP_CHEQUE_PAYMENTS` removed (no longer created, converted to `send_to_erp` and deleted).
- General setup: `PAYMENT_METHOD_CLIENT_CHEQUE_TITLE_NUMBER_LENGTH`, `PAYMENT_METHOD_TICKET_RESTAURANT_TITLE_NUMBER_LENGTH`,
  `PAYMENT_METHOD_CHEQUE_CADEAU_TITLE_NUMBER_LENGTH`, `PAYMENT_METHOD_CLIENT_TRAITE_TITLE_NUMBER_LENGTH` removed (no
  longer created, deleted by the script and at startup).
- General setup, a store only: `CASH_DRAWER_OWNER` (SELECT `CASHIER,POS_MACHINE`, `CASHIER`), created at start-up
  (`ZZDataInitializer`, the store block); "Le tiroir-caisse appartient", POS machines step 2 B. Not changed while a
  session is open.
- Product version 3.2.0 (every pom; `app.version` follows it).

## 3.1.2 — in progress (branch `release/3.1.2`)

### Before deployment
- Visual check, running: the per-point block of the item card ("À ce point", Stock points tab, a point's own name and
  family when they differ) on a Happyness-type head office (catalogue from the ERP, points de stock whose rows differ).
- Visual check, running: the clearance range of Item cards (the point's price range, the normal price struck, "N
  soldés") on a ZEN-type head office.
- The job `IMPORT_CATALOGUE` tested on an ERP store test instance before any ERP-store deployment.
- A database at 3.1.1: `db/3.1.2/sales-line-unit-cost.sql`, `db/3.1.2/item-cost-history.sql` and
  `db/3.1.2/bulk-sale.sql`, then `db/3.1.2/update.sql`, before the 3.1.2 WAR (`AppVersionGuard`).

### Features
- Bulk sale ("vente en vrac"), step 1 (`docs/modules/bulk-sale.md`; the till's popup is step 2): `Item.soldInBulk`
  ("vendu en vrac", null = false, never read from JSON: the full `PUT /item/{id}` keeps it) set by the new
  `PUT /item/{id}/bulk-flag` `{"soldInBulk": true}`, built as `package-flag` (every mode, an ERP catalogue included)
  but each store's own decision: a head office item of a store fed by its head office takes it too (no catalogue
  guard). The item copy carries `soldInBulk` (true or false): the store writes it only on an item the copy creates,
  never on an item it has, and never counts it in "unchanged", so no pull rewrites the catalogue or undoes the store's
  choice.
  The head office item card gives `soldInBulk` per variant. The store's quick quantities: entity `QuickQuantity`
  (label, quantity with 3 decimals, optional linked container item, active, display order) and `/quick-quantity`, the
  generic CRUD (400 with the reason for no label, a quantity not above 0, a 4th decimal, an unknown item); an item used
  as a linked item is not deleted (409, `QuickQuantityItemDeletionCheck`). No connector reads the flag; every item
  stays null, so EMTOP, ZEN and ParaFendri behave as before (`ItemAPICatalogueGuardTest`, `CatalogueCopyBulkFlagTest`,
  `QuickQuantityServiceTest`).
- Bulk sale, step 2 (the till, front end): checked that the four sale paths (sale, park, update, complete parked) take
  two lines of one item as two lines, never merged, the stock taking both (`SaleDecimalQuantityTest`
  `twoLinesOfOneItemSold`, `twoLinesOfOneItemParkedUpdatedCompleted`); no server change.
- Initial cost of the items without a CMP: the item import's cost column (`costPrice`) now also gives an existing item
  without a CMP its starting one (a cost above 0, an `EXCEL_IMPORT` history row, old cost null); an item with a CMP
  keeps it, the cell ignored and counted in `ImportResultDTO.costsIgnored`; the CMP read as stored, the row locked.
  A file without the column imports as before (`DataImportCostTest`). `GET /item-catalogue?withoutCost=true`: the
  items without a CMP, services, discount items and packs left out, only with `withCost` (ignored otherwise,
  `StoreItemCatalogueCostTest`). "Ajuster le CMP" from no CMP was already allowed (history row old cost null, tested).
- Statistics, the margin: `GET /admin/analytics/summary` adds `margin`, `marginRate` (the Journal's margin %, one
  decimal; absent with a line without cost or an HT of 0) and `linesWithoutCost`, the Journal's gains over the period's
  days (`StoreDaysService.periodGains`, the rule of the month total), so the margin is the sum of the Journal's days.
  Only where the store keeps its own stock and for `read:admin-margins` (`AnalyticsAPI.withMargins`), else absent and
  the lines not read; absent too for a period without costed line. The other fields unchanged (`JournalMarginsTest`).
- Stock value at a date: `GET /report/stock?date=yyyy-MM-dd` (optional) gives the stock report at the end of a past
  day, same grouping (item, family, sub-family), columns and order: qty(D) = the stock now minus the movements after D
  (one grouped query), CMP(D) = the new cost of the item's last cost change up to D, else the old cost of its first
  change after D, else its CMP now (counted in `itemsAtCurrentCmp`); value = qty(D) x CMP(D). Four queries whatever
  the number of items. 400 for a date after today, and for a past date where the installation keeps no stock of its
  own. No date or today: the report of before. Every active item listed, a stock of 0 included
  (`docs/modules/reporting.md`, "Stock report").
- Stock report rebuilt (back end, step 1 of 2; the page next): one Java path for now and a past date (now one query,
  was two; a past date four, as before; same rows, labels and order). `GET /report/stock` adds the groupings `CATEGORY`
  and `SECTION` (the name of the category / section row, else the code, else "No Category" / "No Section"), the
  optional filters `search` (code or name contains, case ignored), `familyId`, `subFamilyId`, `categoryCode`,
  `sectionCode` and `status` (a list of `OK`, `LOW`, `OUT`, `NEGATIVE`, `WITHOUT_COST`, any of them, on the item's own
  status at the date before the grouping; 400 for another value), and per row `stockValueTtc`, `sellingValue` (qty x
  `Item.unitPrice`, HT, today's price even at a past date), `sellingValueTtc`, `itemsWithoutPrice`, and on an item row
  `costPrice` (the CMP the value used), `costPriceTtc`, `unitPrice`, `unitPriceTtc`. VAT: the item's `defaultVAT`, none
  0 as at the till; values computed per item, the row rounded to 3 decimals. **`belowMinStock=true` now means the
  statuses `LOW`, `OUT`, `NEGATIVE` of each item**: an item at 0 with no minimum is listed (it was not), and a family
  row sums only its items below the minimum (it compared its summed qty with its summed minimum)
  (`docs/modules/reporting.md`, "Stock report").
- Stock report rows: `itemCount`, `itemsLow` and `itemsOut` (`OUT` or `NEGATIVE`) on every row, the items the filters
  kept counted by their own status at the date (an item row: 1 and its own); `familyName` and `subFamilyName` on an item
  row. Read in the same loop, no new query (for the page's items column and totals in every grouping).
- Stock movements report rebuilt (back end): `GET /report/stock-movements` adds the groupings `SUBFAMILY`, `CATEGORY`
  and `SECTION`, the optional filters `search`, `familyId`, `subFamilyId`, `categoryCode`, `sectionCode` (the stock
  report's) and `types` (a list, any of them; `movementType` still read), and per row `itemCount`, and on an item row
  `itemCode` and `familyName` (its label is now the name, was "code - name"). Days `yyyy-MM-dd` and months `yyyy-MM`
  zero-padded, in time order (were "2026-3-5"). `COST_ADJUSTMENT` ("Ajuster le CMP", quantity 0) is no longer counted
  as a movement. 400 for an unknown type and for `dateFrom` after `dateTo`. One query (`docs/modules/reporting.md`,
  "Stock movements report").
- Stock card of an item: `GET /report/stock-movements/item/{itemId}?dateFrom&dateTo`, its stock at the start and the end
  of the period (counted back from the stock now), the period's entries and exits, and its movements in time order with
  the stock after each one (at most 2,000, the latest), each with its document number as printed (ticket, return,
  purchase, BL, inventory count), its note and who made it (the session's cashier for a sale, whose movement says
  "System"). 404 for an unknown item. The movements report's rows carry `itemId` (an item row) and `groupKey` (a group
  row: the family's or sub-family's id, the category's or section's code, the type, the day, the month); families and
  sub-families are grouped by id, a sub-family row carries its family's name.
- Cost history of the items: table `item_cost_history`, one row per change of an item's CMP (date, old and new cost,
  source, document type and number, the entry's quantity and unit cost, the user's name), written in the transaction of
  the change: a local purchase (`LOCAL_PURCHASE`), an ERP invoice received (`INVOICE_RECEPTION`), a BL with its head
  office invoice (`SUPPLY_INVOICE`), "Ajuster le CMP" (`MANUAL`), a new item of the Excel import (`EXCEL_IMPORT`) and
  `POST /item` with a cost (`ITEM_CREATION`); none when the cost does not change, none on a sale, nothing where the
  installation keeps no stock of its own (`CostHistoryService`). The entry's CMP update locks the item's row first
  and reads the old cost as stored (`ItemRepository.lockForCostChange`, `findCostPriceById`). `GET
  /item/{id}/cost-history?size` (20, at most 100), newest first, for the item card: 403 without
  `write:admin-item-cost` or `read:admin-margins`, and where the installation keeps no stock of its own.
- Head office that supplies no store: `headoffice.supply.source=NONE` beside `HEAD_OFFICE` and `ERP` (unchanged). No
  supply bean: BLs, supply prices, supply invoices, what the stores owe, the ERP invoices, `/ho/supply/**` and the
  network stock (new question `HEAD_OFFICE_SUPPLIES_STORES`); every price list refused on a head office whose
  catalogue comes from the ERP. `GET /config` `supplySource` `NONE`. A store with `ownership.supply=HEAD_OFFICE` is
  refused at its heartbeat, 409 `{"error"}` with the reason; the store (also changed) shows it as `REFUSED` with that
  text in its Communications log (`docs/modules/head-office.md`, "Head office that supplies no store").
- ERP connector `parafendri`, head office catalogue: a ParaFendri head office whose catalogue comes from the ERP
  (`ownership.catalogue=ERP`, its points de stock the six points de vente) runs the catalogue engine, job
  `SYNC_CATALOGUE`: `ParafendriCatalogueSource` (the tree, `stockPoints` per point from its checkpoint, `barCodes` by a
  cursor on `lastModifiedDateTime` in epoch milliseconds, ZEN's contract, and by item), the ability
  `HEAD_OFFICE_STOCK_POINTS`. The engine's 7 beans in core now also need the installation
  `HEAD_OFFICE_ERP_CATALOGUE_ONLY` (as the 4 of the head office already did), so a ParaFendri store keeps its job
  `IMPORT_CATALOGUE` and starts as before.
- ERP connector `parafendri`: the locations (`fetchLocations`, every location of the company, code and name, none
  marked as the default) and the point de vente's customer (`fetchCustomers`, one row only: the account of
  `DEFAULT_LOCATION`, its `customerNo` from `stockPoints`, then `customers?$filter=number eq`, the page read whole if
  `number` is not filterable; blocked as inactive), both read whole each run. Install: `IMPORT_LOCATIONS`, the default
  location on the Emplacements page, `IMPORT_CUSTOMERS`, `PASSENGER_CUSTOMER` = the customer number (e.g. 4110001).
- ERP connector `parafendri`, step 7: the invoice of a ticket prepared for one, asked from Business Central
  (`shipAndInvoice`, POST `{ orderNo }`, looked up first by `orderNo` so never twice): its number kept on the ticket
  (`invoiceNumber`), its PDF written to `<app.upload.dir>/erp-invoices/<invoiceNo>.pdf`, the ticket `TOTALLY_SYNCHED`;
  a failure leaves it `PARTIALLY_SYNCHED`, asked again next run. New `ErpConnector.requestInvoice` / `ErpInvoiceResult`
  (covered by `WHOLE_TICKET_EXPORT`), new communications operation `REQUEST_INVOICE`. The fiscal registration and name
  cannot be sent yet (no field in `shipAndInvoice`): in the message, one warning per run. A ticket with its `erpNo` is
  never posted again (before, a ticket prepared for an invoice after its post would have been posted a second time).
  EMTOP and `prepareInvoice` unchanged.
- ERP connector `parafendri`, step 0 (ParaFendri's Business Central 28 on-prem, its custom API
  `api/sat/zsretail/v1.0`, a store on the ERP as EMTOP's): project `connectors/parafendri`, accepted as
  `erp.connector=parafendri`, no ability; the settings, the startup check, an NTLM GET-only client whose login goes to
  the host of `base-url` only, and the connection test `ParafendriRestClient.checkCompany()` (the companies of the API,
  `company-id` among them). Every `ErpConnector` method a stub: no job yet (`docs/modules/erp-sync.md`).
- ERP connector `parafendri`, step 6: the tickets posted to Business Central as sales orders, header and lines in one
  POST (`salesOrders`, deep insert), looked up first by `ticketNo` so never twice (Business Central does not filter
  `ticketNo` today: posted anyway, one warning per export run in the log and the communications log). New
  `ErpAbility.WHOLE_TICKET_EXPORT`: with it `TicketExportService` sends each ticket whole (all or nothing); every
  other connector unchanged. `ErpOperationResult.addWarning` (written once per run). With such a connector
  `EXPORT_RETURNS` and `EXPORT_SESSIONS` are seeded disabled. The mapping, to confirm with ParaFendri's BC contact, in
  `docs/modules/erp-sync.md`.
- ERP connector `parafendri`, step 4: the barcodes (`fetchItemBarcodes`, `barCodes`, company-wide): every row on a
  first run, then from the checkpoint `ERP_SYNC_LAST_ITEM_BARCODE` plus the barcodes of the items the same run's items
  part got, so that an item added to a point de vente later gets its barcode; the row's GUID as the barcode's ERP id (a
  changed barcode replaces the old one); blank barcode or item left out with their reason in the communications log.
- ERP connector `parafendri`, step 3: the items (`fetchItems`, `stockPoints` of the store's `DEFAULT_LOCATION`, from the
  checkpoint `ERP_SYNC_LAST_ITEM`): the price before VAT as received (never rounded), the VAT, the unit, inactive when
  blocked or without a price, the four links checked against the tree, left-out rows with their reason in the
  communications log. `ErpItemDTO.unitOfMeasure` (erp-api, optional): the import writes `item.unit_of_measure` only when
  the ERP gives one (emtop and zen give none: unchanged).
- ERP connector `parafendri`, step 2: the tree `itemCategories` for the job `IMPORT_CATALOGUE` (`fetchItemCategories`,
  `fetchItemFamilies`, `fetchItemSubFamilies`, `fetchItemSections` by `indentation` 0 to 3, `ParafendriMapper` as ZEN's):
  an orphan famille kept without its catégorie (noted), an orphan sous-famille or section, a deeper row and a blank code
  left out with their reason, shown in the communications log; no ability.
- ERP connector `parafendri`, step 1: the company's locations (`readLocations()`, `checkLocation(code)`), and a
  connection check after the start (`ParafendriConnectionCheck`, its own thread): the company and the store's General
  Setup `DEFAULT_LOCATION` checked in Business Central, one INFO line, or one WARNING naming the problem; it never stops
  or delays the start.
- Tickets history, Excel export: `GET /sales-header/history/export` with the list's filters (no page): every ticket,
  newest first, at most 20,000 (`truncated`, `totalTickets`), each with its cashier, session, customer, loyalty card,
  totals, line count and quantity and its payments by method (`paymentMethods`: the methods in display order), and
  their lines (item, family, quantity, prices, discount, VAT, totals; the unit cost only with `read:admin-margins`).
  One query for the tickets, the lines and payments by blocks of 1,000 (`TicketsHistoryExport`); the list's filters
  moved into `SalesHeaderService.ticketsHistorySpec`, the list unchanged (`TicketsHistoryExportTest`).
- Store Articles, Excel export: `GET /item-catalogue/export` with the list's filters and sort (no page): the items of the
  lines the list shows, one row each (a NAV item's variants of the status), at most 60,000 (`truncated`): codes,
  variant, name, barcode, levels, prices before / with VAT, clearance price, stock and minimum (own stock), CMP (the
  list's cost right), maximum discount, unit, origin, status. One query (`ItemCatalogueQuery.items`, the list's joins
  and WHERE, line conditions on window values; 0.22 s for 21,907 ZEN variants); `StoreItemCatalogueAPI.criteria`
  shared by the list and the export (`ItemCatalogueExportQueryTest`).

### Enhancements
- Cost history: the reason typed in "Ajuster le CMP" is kept on its row (`item_cost_history.note`, varchar(500), cut
  there; null for the other sources) and sent with `GET /item/{id}/cost-history`.
- Stock report: items without cost are no longer valued at 0. An item without CMP and with a stock other than 0 has
  `stockValue` null (was 0) and `itemsWithoutCost` 1; a family or sub-family row counts its items without cost
  (`itemsWithoutCost`), its value the sum of the others as before. Today's queries are unchanged, one more query finds
  the items without cost. The same rule at a past date.
- Item cards list (head office), with a point de stock chosen: `GET /admin/headoffice/item-catalogue` takes
  `stockPointStatus` `ACTIVE` (default, the list as before) | `INACTIVE` | `ALL`, the row at that point, and
  `priceMin` / `priceMax` filter the point's row selling price with VAT (the clearance price when it applies) instead
  of the item's own price; without a point nothing changes (`HoItemCatalogueQuery.stockPoint`, head office only; core's
  query and the store's Articles page unchanged). Step 1 of retiring "Items by point de stock".
- Item cards (head office), step 2: with a point chosen, each list line's `priceMin` / `priceMax` are its rows'
  selling prices there with VAT (`pointPrice`, the price sort by them), with `clearanceVariants` and the normal range
  `normalPriceMin` / `normalPriceMax`; core's `ItemCatalogueQuery.Criteria` gains `linePrice` / `linePriceJoin` (unset:
  the SQL is unchanged). The card's point rows carry the row's own name, description, family and sub-family (codes and
  names) and `differences` / `differs` against the item's.
- ERP catalogue import, every connector: the barcodes are imported by windows of 1,000 rows, their items and existing
  barcodes read in four queries per window, written by JDBC batches of 100, the session emptied after each window
  (before: three queries per row, each flushing the whole packet; 13,165 ParaFendri barcodes ran more than 27 minutes).
  Same rules and warnings. New log line `ERP item barcodes import: N rows handed, ...`.
- ERP jobs: the job type `EXPORT_CUSTOMERS` is removed (never implemented: it only logged "not yet implemented"), with
  its operation `EXPORT_CUSTOMER`, `ErpConnector.pushCustomer` (and its five stubs) and
  `ErpSynchronizationManager.pushCustomer`. The rows of that type and operation are deleted at startup
  (`ErpSyncJobTypesUpgrade`, before any read of the tables) and by `db/3.1.2/update.sql`. `IMPORT_CUSTOMERS` unchanged.
- Item costs, the weighted average cost (docs/modules/head-office.md, "Item costs (CMP)"): `Item.costPrice` is the CMP
  of an installation that keeps its own stock (no-op where the ERP keeps it and on a head office with
  `headoffice.stock.enabled=false`), no longer the last net cost; `lastDirectCost` / `lastDirectNetCost` unchanged.
  `ItemRepository.applyEntryCost` (one native row update, `DECIMAL(19,5)`, 3 decimals) through
  `StockService.applyEntryCost`, after the stock of every entry that carries a cost: a local purchase (per line, the
  net price), an ERP invoice received (per item, its paid lines together; a free line's quantity outside the entry, a
  line received at 0 leaves it), a BL and its head office invoice (the invoice stamps the BL lines' costs; applied at
  the invoice for a BL received, else at the reception; a BL saved after its invoice takes them from it). `updateCost`
  renamed `updateLastCosts`, without `costPrice`. `PURCHASE_RECEPTION` and `DELIVERY_IN` movements carry the net unit
  cost (null on a BL received before its invoice). `PUT /item/{id}` keeps the stored CMP; `POST /item/{id}/adjust-cost`
  `{costPrice, reason}` ("Ajuster le CMP") with a `COST_ADJUSTMENT` movement (quantity 0, `CMP <old> → <new> :
  <reason>`, the user), 403 without `write:admin-item-cost` (checked by the API, listed by `GET /app-roles/permissions`,
  given to ADMIN at every start wherever the stock is kept). The Excel import writes the cost of new items only.
- Unit cost frozen on the ticket line, so the margin of a past day never moves with the CMP: `sales_line.unit_cost`
  (`SalesLine.unitCost`, net, 3 decimals) takes the item's CMP, half up, when its ticket is completed (a sale, a held
  ticket paid, a split bill paid), the free line of a FREE_QUANTITY promotion included; null on a held ticket, for the
  tax stamp and for an item without CMP; the same in every configuration (where the ERP keeps the stock the item's
  cost is copied as it is). A pack's components are lines of their own (the till splits the pack), each with its own
  CMP. `return_line.unit_cost` takes its sales line's cost, null included. Written on the insert only
  (`updatable = false`: a later save, the generic `PUT /sales-line/{id}` included, never changes it) and never in
  JSON (`@JsonIgnore`): `GET /sales-header/{id}/details` sends `lineCosts` (line id to cost) only to a role with the new
  right `read:admin-margins` (checked by the API, listed by `GET /app-roles/permissions`, given to ADMIN at every start
  wherever the stock is kept, with `write:admin-item-cost`). Stock movements unchanged (selling prices). The lines
  written before 3.1.2 stay without cost (no estimate).
- Unit cost on the ticket line: an item that carries no cost by nature, a service or a discount item
  (`ItemType.carriesNoCost`), freezes 0 on its sale and return lines, never null ("unknown"); a product and a pack keep
  the rule (the CMP, or null without one), the tax stamp stays null (`SaleUnitCostTest`, `ReturnRefundLoyaltyTest`).
- Journal, the gains (docs/modules/cash-sessions.md, "Journal", "Gains"): `GET /cashier-session/store-days` (each day),
  `/day-journal` (each ticket and return, and the day's totals) and their .xlsx carry `cost`, `margin`,
  `marginPercent` and `linesWithoutCost`, from the unit costs frozen on the lines (`JournalCosts`): the HT of the
  Journal (ticket discount, cart promotions and loyalty points in it) over the costed lines minus their cost, a return
  negative, the stamp left out, a service or a discount item at 0; all null without a costed line; margin % only when
  no line is without cost; a day (and the month total of the .xlsx) counts the lines without cost of all its tickets and
  returns, those without any costed line included. Only where the store keeps its own stock and for `read:admin-margins`
  (`CashierSessionAPI.withMargins`), else not computed and absent. The day list reads the month's lines by one
  projection per kind (`findCostLinesOfFinishedBetween`, `findCostLinesOfReturnsBetween`); the day journal from the
  lines it reads. Nothing existing changes (`JournalMarginsTest`, `JournalCostLinesQueryTest`).
- Store Articles page, the list on every store (docs/admin-pages.md, "Articles"): `GET /item-catalogue` gives each line
  what the old flat list showed (`ItemCatalogueService.storeDetails`, one read for the page, `ItemCatalogueQuery.details`):
  a single item's `origin`, `ownPrice` and the head office's price with VAT (`headOfficePrice`), its price before VAT and
  VAT (`unitPrice`, `vatPercent`), its public price with VAT when it is in clearance below it (`publicPrice`), its
  `minStockLevel` and the ERP's `maximumAuthorizedDiscount`; and, with `withCost` in the answer, the line's weighted
  average cost before VAT (`costMin` / `costMax`, a NAV item's range) only where the store keeps its own stock and for a
  role with `write:admin-item-cost` or `read:admin-margins` (checked by the API). Every new field is left out of the
  JSON when null (the head office's list answers as before). New sort `stock` (the line's stock, none read as 0), on
  the store's and the head office's list (`ItemCatalogueStoreDetailsTest`, `StoreItemCatalogueCostTest`).
- Store item card (docs/admin-pages.md, "Articles"): `GET /item/{id}/stock-movements?size` (20 by default, at most 100),
  an item's last stock movements newest first (`ItemStockMovementDTO`: date, type, direction, quantity, unit price
  before VAT, reference, notes, user); the unit prices and the CMP adjustments only for a role with
  `write:admin-item-cost` or `read:admin-margins`, checked by the API (`ItemStockMovementsTest`).
- Till item tiles, images given by address (ZEN): an item without `Item.imageUrl` is sent with its main URL image of
  `item_image` (its first URL row, the main one first: ZEN's `frontImg`) in `imageUrl`, a full address, on the six POS
  reads of `ItemAPI` (`/item/by-sub-family/{id}`, `/item/pos/by-section/{id}`, `/pos/without-section/{id}`,
  `/pos/without-sub-family/{id}`, `/pos/without-family`, `/pos/search`). An uploaded file stays first; `POS_SHOW_IMAGES`
  false: no image, `item_image` not read; without `item_image` rows nothing changes. Every item whose `imageUrl` the
  answer changes is detached first, so the change is never saved (images off included). One rule with the store order
  catalogue: `core.service.ItemMainImages` (`ItemMainImagesTest`).

### Fixes
- **Security: `/user-account` is behind the permissions of the Users pages.** It checked none: on the running application a
  cashier read every badge code (`GET /user-account`, then a till right from `POST /badge/scan`), generated the
  administrator's badge and read it, and set the administrator's password and logged in with it. Now reads need
  `read:admin-users`, creating, editing, switching off and the badge actions `write:admin-users`, deleting
  `delete:admin-users`, else 403; on a head office `read:admin-headoffice-users`, its page's one key, opens them too.
  `/cashiers` (names, for the history filters) and `/current` (one's own account) stay open to any logged user, and so does
  `POST /badge/scan`: the till needs it with a real badge. No screen lets a normal user change his own password here.
- **Security: the badge code no longer travels in an entity.** Like the password hash below, `UserAccount.badgeCode` is read
  from JSON only and left out of `toString`; the Users pages read it through `UserAccountDTO`
  (`UserAccountApiAccessTest`, `UserAccountSecretsJsonTest`).
- **Security: `GET /user-account/current` no longer answers one's own badge code.** Open to any logged user, it carried the
  caller's code, which no screen reads there (`UserAccountDTO.ownAccount`). The answers behind `read:admin-users` keep the
  code: the Users page shows it, and sends it back on a save (`UserAccountApiAccessTest`).
- **Security: `PUT /company-info` needs the page's permission:** `write:admin-company-information` on a store,
  `read:admin-headoffice-company-information` on a head office, else 403 (`CompanyInformationApiAccessTest`). Any logged user
  could save, and an anonymous call answered 500. The `GET` stays public. A responsible no longer fills in the company name.
- The keys the API now checks reach the ADMIN of an older store database at its start (`API_CHECKED_ADMIN_PERMISSIONS`), so
  no administrator is locked out. Only ADMIN: no other role changes.
- **Security: the password hash no longer reaches the browser.** `UserAccount.password` was written in every answer that
  serves an entity embedding its user: `POST /cashier-session/open`, `GET /cashier-session/current` and the list
  `GET /cashier-session` (open to any logged user) answered with `cashier.password`, a bcrypt hash, and so did tickets,
  returns, payments, purchases and invoices. It is now read from JSON only (`@JsonProperty(access = WRITE_ONLY)`) and left
  out of `toString`; the front never read it, and no relation to a user cascades, so an entity sent back without it
  changes no password (`UserAccountSecretsJsonTest`).
- Journal gains: a day's (and a month's) cost and margin are the sum of its tickets' and returns' figures rounded to
  the millime as shown, not the rounding of the unrounded sums (could differ by a millime, 28.782 vs 28.781); the day
  list and the day journal share the rule (`JournalCosts.day`, `JournalMarginsTest.daySumsTheRowsAsShown`).
- Stock movements, two gaps (for the stock value at a date): the item import (Excel) writes the movement of the stock
  it sets, in the row's transaction (a row in error leaves nothing): a new item's `OPENING_STOCK` (note "Import
  Excel", created by `Import`), an existing item's difference as `ADJUSTMENT_IN` / `ADJUSTMENT_OUT` (note "Import
  Excel"), nothing when it does not change or without a stock column. `POST /item` with a stock writes its
  `OPENING_STOCK` (note "Création de l'article", the user) with the item (`ItemService.create`). Before, both set the
  stock with no movement. Nothing when the supply is the ERP's.
- Store linked to a head office after its database was created (ParaFendri Gremda): its ADMIN role now gets
  `read:admin-holink-status` (the "Head office" menu: Jobs, Communications, Data exchanged) at the next start; before,
  only a role created with `headoffice.url` already set had it, so the menu stayed hidden. Nothing is removed.
- ERP connector `parafendri`, step 6: `amountIncludingVAT` is no longer sent on `salesOrders`, header nor lines
  (read-only in Business Central: the live POST answered HTTP 400 "Control 'amountIncludingVAT' is read-only"); BC
  computes it, the header's is read back from the answer and shown in the log message next to our total TTC.
- Split bill of a table ticket (`POST /sales-header/split-and-pay/{id}`, `SalesHeaderService.splitAndPay`): the lines
  paid apart never left the stock and wrote no SALE movement (the rest, paid later, did): they now go out with their
  movements as every completed sale (`decrementStockForSalesLines`), and keep their discount source and promotion,
  which the copy dropped (docs/modules/table-management.md, `SplitBillStockTest`).
- Local purchase with the same item on two lines: the second line saved the item it had loaded (its last costs), which
  put back the stock of before the first line's increment, so the first line's quantity was lost. The last costs are
  now a bulk update and the item is never saved (`PurchaseHeaderServiceCostTest`, the mechanism in
  `EntryCostQueryTest`).
- Head office invoice at the store: the last costs were written by saving the whole item, which could put back a stock
  read before a sale made at the same time; now a bulk update (`SupplyInvoiceWriter`).

### Removed
- Head office page "Items by point de stock", back end: `GET /admin/headoffice/stock-points/items` and `/items/{rowId}`,
  `HoStockPointItemsService`, `StockPointItemDTO`, the queries `findRows` / `findRowsSearched`; the rest of the Stock
  points API stays. The permission `read:admin-headoffice-stock-point-items` is no longer given to ADMIN (checked first
  on the ZEN, Happyness and ParaFendri test head offices: only ADMIN had it, with the Items permission); a role row
  that has it stays, unread. Item cards' point filter replaces the page. `GET /config` gains `headOfficeStockPoints`
  (the head office keeps points de stock: catalogue only from the ERP and a connector with `HEAD_OFFICE_STOCK_POINTS`).

### DB script (`db/3.1.2/sales-line-unit-cost.sql`, `db/3.1.2/item-cost-history.sql` and `db/3.1.2/bulk-sale.sql`, then `db/3.1.2/update.sql`, store and head office, on a database at 3.1.1)
- `bulk-sale.sql`: column `item.sold_in_bulk` bit NULL (store and head office) and, on a store only (no `ho_store`),
  table `quick_quantity` (`label` varchar(100), `quantity` numeric(18,3), `linked_item_id` FK `item`, `display_order`,
  the base columns), each created when missing (a start creates them too); no data changed. `update.sql` stops while
  the column, or on a store the table, is missing.
- `item-cost-history.sql`: column `item_cost_history.note` varchar(500) NULL, in the table and added when missing (a
  database that ran the script before it; a start adds it too).
- `item-cost-history.sql`: table `item_cost_history` (no foreign key to `item`) and its index `ix_item_cost_history_item`
  `(item_id, changed_at)`, each created when missing (a start creates them too); empty at the start. `update.sql` stops
  while the table is missing.
- `update.sql`: index `ix_stock_movement_created` on `stock_movement (created_at) INCLUDE (item_id, direction,
  quantity)` (re-runnable, store and head office): the movements after a day, summed for the stock value at a date,
  read through it instead of a scan of every movement.
- `sales-line-unit-cost.sql`: columns `sales_line.unit_cost` and `return_line.unit_cost` DECIMAL(19,3) NULL, each added
  when missing (a start adds them too); no data changed, the lines of before stay NULL. `update.sql` stops while they
  are missing.
- `update.sql`: the rows of the removed job type deleted, `erp_sync_job` of type `EXPORT_CUSTOMERS` and
  `erp_communication` of operation `EXPORT_CUSTOMER` (each table guarded; re-runnable; the same statements at startup).
- `update.sql`: `APP_VERSION` 3.1.1 → 3.1.2 (it stops while APP_VERSION is not 3.1.1 or 3.1.2, and while
  `sales-line-unit-cost.sql` has not run); notes written when the version is closed. `db/3.1.1/` is unchanged, as
  released: the `EXPORT_CUSTOMERS` deletion and the unit cost columns, first put there by the branches, live in 3.1.2.
- `update.sql`: index `ix_stock_movement_item` on `stock_movement (item_id, id)` (re-runnable, store and head office):
  an item's last movements (the item card) read through it instead of a scan of every movement.

### Config
- `headoffice.supply.source=NONE` (head office): accepted only with `headoffice.stock.enabled=false` and without
  `headoffice.storeorders.post`, else the startup stops naming both keys.
- `erp.connector=parafendri` and its keys `erp.parafendri.base-url`, `company-id` (a GUID), `domain`, `username`,
  `password` (required but domain), `connect-timeout-seconds` (30), `read-timeout-seconds` (600). New file
  `configs/customers/parafendri-store.properties` (placeholders, `erp.sync.enabled=false`).
- `configs/customers/zen-headoffice.properties`: `headoffice.supply.source=ERP` (was `HEAD_OFFICE`, kept until the live
  test), as the real installation runs it; the records of `ConfigurationKeysProofTest` and `InstallationBeansProofTest`
  follow (that line only).

## 3.1.1 — released 2026-10-29 (branch `release/3.1.1`)

### Features
- Till's right rail (catalogue hidden): `GET /cashier-session/current/ticket-count` `{"ticketCount": n}`, the tickets of
  the current user's open session (completed, refunded since included; 0 without a session), a count and never an
  amount (the closing count stays blind; `CashierSessionService.countSessionTickets`, `SessionTicketCountTest`).
  `GET /loyalty/config` sends the active program's `earningTiers` (sorted), so the till shows the points a ticket will
  earn with the sale's rule (`LoyaltyEarningTiersTest`).
- POS screen, catalogue visible by default: a store General Setup row `POS_CATALOGUE_VISIBLE` (BOOLEAN, `false`, POS
  behaviour, created at start-up on a store only), sent by `GET /config` as `posCatalogueVisible` (last key; `false`
  when off or absent). How the till opens when it has no choice of its own (`docs/modules/general-setup.md`).
- Store Articles page, grouped by NAV item: `GET /item-catalogue` and `GET /item-catalogue/filters` on a store, the head
  office's item catalogue list read at the store (one line per NAV item number, else per item; price range, variants
  count, stock, the classification filters, the price with VAT). The query moved to core (`ItemCatalogueQuery`,
  `ItemCatalogueService`, `ItemCatalogueLineDTO`, `ItemCatalogueFiltersDTO`); the head office keeps its point de stock
  filter and count in `HoItemCatalogueQuery` (an item condition of the criteria) and its endpoint unchanged. With a
  search each line gives `matchedItemIds` (its items found: NAV number, item code, name or a barcode), on both.
- Orders to the head office ("Commandes centrale"), step 1, the store's back end (docs/modules/store-orders.md), on a
  store whose goods come from its head office only (elsewhere the URLs answer 404): `hol_order` / `hol_order_line`, one
  draft at a time numbered `<store code>-<year>-<6 digits>` at its creation, one line per variant (the same item again
  replaces its quantity, 0 removes it), only active ERP items; `GET /store-order/catalogue` (the NAV items grouped
  read, filters search / barcode / catégorie / famille / sous-famille / section, sort by number or name, the first
  variant's price and image), `GET /store-order/variants` (the variants with NAV's live stock at the store's location,
  asked to the head office: 503 without it, no partial answer), the draft (`GET`, `POST /draft/lines`, `DELETE
  /draft/lines/{itemId}`, `PUT /draft/note`), `POST /store-order/{id}/send` (synchronous `POST /ho/orders`, the row
  locked meanwhile: accepted SENT; refused 409, not answered 503, nothing saved), `POST /store-order/{id}/cancel` (a
  draft only), `GET /store-order` (status, date range) and `GET /store-order/{id}`. What the head office does with an
  order comes down with the SUPPLY copies (record `ORD:<number>`, kind `STORE_ORDER`: SENT, POSTED with the NAV
  number, ERROR with the reason). The head office side is step 2.
- Item images: `item_image` (any number per item, `FILE` or `URL`, sort order, main), entity and `GET /item/{id}/images`
  (store and head office), read only; `item.imageUrl` stays the main image everywhere.
- Permissions `read:admin-store-orders`, `write:admin-store-orders` ("Commandes centrale"), listed by
  `GET /app-roles/permissions`, given to ADMIN at every start of a store whose goods come from the head office.
- Orders to the head office, step 2, the head office's back end (docs/modules/store-orders.md), only with
  `headoffice.storeorders.post` (MANUAL or ERP; absent: no bean, 404): `POST /ho/orders` takes a store's order once per
  store and number (the same number again: accepted, the order already here), refused with `accepted: false` and the
  reason without the store's ERP customer number, for another location, without lines or with a quantity not above 0;
  `GET /ho/orders/variant-stock?navItemNo=` (the connector, the store's code as the location; 503 without it);
  `ho_store_order` statuses SENT, POSTED, ERROR, REJECTED. ERP mode: the job `POST_STORE_ORDERS` (ERP jobs page, seeded
  disabled, every 5 minutes) posts the orders SENT and ERROR as sales orders (external document number = the order's,
  the store's location and ERP customer number), success POSTED with NAV's number, failure ERROR retried at each run (no
  limit), one communications log row per posting. Admin `admin/headoffice/store-orders`: list (store, status, dates,
  sort), one order, "mark posted" (MANUAL), "reject" (both: REJECTED, never posted), "retry now" (ERP). Every change the
  store sees records `ORD:<number>` for that store (REJECTED goes down as ERROR with its message).
- erp-api: `ErpConnector.fetchVariantStock` and `postSalesOrder` (defaults: not supported), `ErpAbility.STORE_ORDERS`,
  `ErpStoreOrderPosting`. EMTOP and Happyness unchanged.
- connector-zen (replaced by ZEN's real pages, below): the store orders on the contract sent to ZEN (`ZenStoreOrders`, the
  fields in `ZenOrderFields`): `GET
  variantStock?itemNo=&locationCode=`; the lookup `GET salesOrders?$filter=externalDocumentNo eq '..'` first (an order
  found is reused; a lookup that fails posts nothing), then `POST salesOrders` → `orderNo`. The RestTemplate still
  refuses every method but GET, except a POST to the sales orders page.
- Orders to the head office, step 4 (the head office's screens): `GET /config` gives `storeOrdersPost`, `ERP` or `MANUAL`
  on a head office with `headoffice.storeorders.post`, null otherwise and on every store; permissions
  `read:admin-headoffice-store-orders` and `write:admin-headoffice-store-orders`, given to ADMIN at every start only on
  such a head office (`ZZDataInitializer.HEAD_OFFICE_STORE_ORDER_PERMISSIONS`).
- Store orders, ZEN's real page `commandesVente` (docs/modules/store-orders.md): the lookup `GET
  commandesVente?$filter=referenceZS eq '..'&$expand=lignesCommande` (required); none found: one POST with the
  lines nested (`referenceZS` = the order number, `commandeZS` true, `client`, `dateCommande`;
  `lignesCommande` `article`, `codeVariante`, `mode` when set, `quantiteDemandee`), and when NAV answers a 4xx refusing
  the nested lines, the header then one POST per line on `commandesVente(id)/lignesCommande`; an order found reused, the
  lines it lacks posted. NAV's answer kept (`ErpSalesOrderResult`, the return of `ErpConnector.postSalesOrder`):
  `numero` as the ERP number, its `message` on `ho_store_order.nav_message`, per line `quantite` and `message` on
  `ho_store_order_line.nav_accepted_qty` and `nav_line_message`, sent down with `ORD:` (`navMessage`, `lines`) and
  written on `hol_order` / `hol_order_line`; both order views give them. The communications log message names the path
  taken. The page names are constants of `ZenOrderFields`. Sample
  `connectors/zen/src/test/resources/zen/commandesVente.json`.
- Item images from ZEN's products API (docs/modules/erp-sync.md, "Item images"): the job `SYNC_CATALOGUE` has a last
  part `images`, after the barcodes (`ItemImagesEngine`; skipped when the connector reads none: EMTOP and Happyness
  unchanged). Every run asks the NAV item numbers of the items the items part changed (calls of 100); a full run asks
  every NAV item number, at most once per 24 h (the checkpoint `images.since`, no setting), which also catches a
  picture replaced on ZEN's side. Every variant of a NAV item gets `frontImg` (main, sort 0) and `detailsImg` (sort 1)
  as `item_image` URL rows, an empty answer removes them, FILE rows untouched. A failure is a WARNING of the run, never
  an ERROR. erp-api: `ErpCatalogueSource.readsItemImages`, `itemImages`, `ErpItemImagesDTO`. connector-zen:
  `ZenProductsClient` (`{"skus": [..]}` only, never `updatedAt`, which ZEN answers with the whole catalogue; answer
  under `data`), its own RestTemplate (no NAV login, only a POST to that address), header `X-Api-Key`.
- The item copies down carry the item's URL images (`CatalogueItemCopyDTO.images`: url, main, sort); the store replaces
  its URL rows with them (its FILE rows kept; absent: kept). The store order catalogue's tile takes the first variant's
  main URL image when no variant has an `image_url`.
- Store orders, status DELIVERED: on a head office that reads the ERP's invoices (`headoffice.supply.source=ERP`) and
  takes store orders, an invoice saved by the import whose `orderNo` is a POSTED order's `nav_order_no` makes it
  DELIVERED (`invoice_no` = the invoice number, `delivered_at` = its posting date) in the invoice's own transaction,
  for the store whose ERP customer number is the invoice's; the run counts `ordersDelivered`. The `ORD:` copy carries
  `invoiceNo` and `deliveredAt` and the store writes DELIVERED. erp-api: `ErpSupplyInvoiceDTO.orderNo` (null for the
  connectors that give none: Happyness and EMTOP unchanged); connector-zen: `orderNo` of `salesInvoices`. Store:
  `GET /admin/deliveries/by-number?number=` (a received document by its number, 404 when not here).
- Store orders, colis (ZEN's série): an order holds colis of an ERP item apart from its lines (`hol_order_colis` /
  `ho_store_order_colis`, one row per item: nb_colis, contenance, colis_possibles, NAV's colis accepted and message;
  `total_colis` on both orders, `total_qty` the pieces of the lines only). Store `POST /store-order/draft/colis` (0
  removes; above the colis possibles 409, checked again at the send) and `DELETE /store-order/draft/colis?navItemNo=`;
  `draftColis` in the variants answer; an order of colis alone is sent and taken. The head office posts each as a colis
  line (erp-api `ErpSalesOrderDTO.Line.nbColis`; answer `ErpSalesOrderResult.Line.colis` / `acceptedColis`), keeps
  NAV's answer on the colis row and sends it down (`StoreOrderStatusCopyDTO.colis`). connector-zen: a colis line
  `{article, mode: "colis", nbColis}` (pieces lines keep `erp.zen.order.mode`, vrac), NAV's `colisAcceptes` read on
  colis lines, a colis line missing until NAV has one of the item; details gain "Types de série" (`qteSerieType`).
- Store orders, colis per colour row (NAV refused an item-level colis line: "Le n° article et le code variante sont
  obligatoires sur les lignes"; ZEN's série is per variant): each colis row is of a variant, the first of its colour row
  (`variant_code`, `color_description` on `hol_order_colis` / `ho_store_order_colis`, one row per order, item and
  variant); `POST /store-order/draft/colis` takes `variantCode` (an active variant of the item, 400 otherwise), `DELETE`
  too; `draftColis` and the last order's `colis` by variant code; the head office refuses a colis without a variant;
  connector-zen sends `{article, codeVariante, mode: "colis", nbColis, quantiteDemandee}` (the pieces: nbColis × the
  row's contenance; NAV refused 0 with "La quantité demandée doit être positive"), a colis line missing until NAV has
  one of its variant. The store drops the first build's key `uk_hol_order_colis_item` at each start. A colis row of the first build (no
  variant) is left out of the window's colis (its null key broke the variants answer: the window read "head office
  unreachable") and stops the send with what to do (remove it, enter the colis on a colour row).
- Store orders, colis by item (ZEN's final rules; NAV took `{article, mode: "colis", nbColis}`, CV26000008): a colis is
  the whole article, one série of every colour, replacing the per-colour colis. One colis row per order and item,
  `colis_content` (Σ `qteSerieType`) and `colis_disponibles` (ZEN's `colisDisponibles`) kept on it, its composition
  (each variant's pieces per colis, size and colour) in `hol_order_colis_detail` / `ho_store_order_colis_detail`.
  `POST /store-order/draft/colis {navItemNo, nbColis, colisAvailable?, composition?}` (409 above the colis available,
  at the add and at the send), `DELETE ?navItemNo=`. Pieces of a variant whose `vracAutorise` is false are refused
  (409, at the add and at the send; `hol_order_line.vrac_allowed`). erp-api: `ErpVariantStockDTO` `qtyPerColis`,
  `vracAllowed`, `colisAvailable` (generic, optional); `ErpSalesOrderDTO.Line.colis(item, nbColis, pieces)`;
  `ErpSalesOrderResult.Line` `acceptedQty` and `colisNumber` of a colis line, carried down with the order's status
  (NAV's accepted colis, pieces, numéro colis, message). connector-zen: reads `qteSerieType`, `contenanceSerie`,
  `colisDisponibles`, `colisTheoriques`, `colisConformesSerie`, `colisConformesQte`, `vracAutorise`, `qteVrac`
  (details in that order), posts a colis line `{article, mode: "colis", nbColis}` (no variant, no quantity), reads
  `numeroColis`; the infoArticles sample is 43ZFCHD17 read live. The store drops `uk_hol_order_colis_variant` too at
  each start.
- Store orders, `GET /store-order/last?navItemNo=`: the last order of this store sent with a line of that ERP item (not
  a draft, not cancelled): number, status, date, and its quantity of each variant (the variants window's "Dernière
  commande"); 204 without one.
- Store orders, DELIVERED also when the invoice came first: the import keeps each invoice's `orderNo`
  (`ho_erp_invoice.order_no`); an order that becomes POSTED (the job, "retry now", "mark posted") looks up the invoices
  already imported under its ERP number (native, a seek) and is DELIVERED by the first of its store's customer in the
  same transaction.

### Enhancements
- ERP jobs, every customer: one job `IMPORT_CATALOGUE` ("catalogue import") replaces `IMPORT_ITEM_FAMILIES`,
  `IMPORT_ITEM_SUBFAMILIES`, `IMPORT_ITEMS` and `IMPORT_ITEM_BARCODES`. It runs, in this order, catégories, families,
  sub-families, sections, items, barcodes (`ImportCatalogueJob`), each part with today's read, import and checkpoint, and
  stops at the first failing part (the next run goes on). Barcodes now always come after their items: in one scheduler
  tick the barcodes job used to run before the items job, a new item's barcodes were skipped and their checkpoint moved
  past them. New `ErpConnector.fetchItemCategories` / `fetchItemSections` (default: none; emtop unchanged). The rows of
  the four jobs are converted at startup (`ErpSyncJobTypesUpgrade`, before any read of the table): the cron of
  `IMPORT_ITEMS`, enabled when any of the four was. EMTOP: one 12 h schedule instead of four (`docs/modules/erp-sync.md`).
- Store orders, the lists: a `search` parameter on `GET /store-order` and `GET admin/headoffice/store-orders`, in the
  order number, the ERP (head office) order number and the lines' item code, ERP item number and name (any case, the
  wildcards searched as characters; `ItemBarcodeService.containsPattern`).
- NAV items grouped read (`NavItemService.page`, `ItemRepository.NAV_ITEMS`): optional level filters (catégorie, the
  item's own else its famille's; famille; sous-famille; section), the NAV items of the barcodes matching the search, and
  a sort (number, or name then number, either way). The "NAV items" page sends none of them: same lines, same order.
- Head office copies down: several providers per domain (`CopiesDownFeed`), each answering its own codes: the SUPPLY
  domain carries the supply documents and the store orders' statuses together.
- Store orders, for the store's screens (step 3): a catalogue line gives `mainImageItemId` (the item of its image, read
  at `item-image/item/{id}`); a variant NAV gives no row for has `stock` null (unknown) instead of 0.
- Store orders, the variants' stock on ZEN's real page `infoArticles` (docs/modules/store-orders.md): the head office asks
  the connector with the store's code **and ERP customer number** (`ErpConnector.fetchVariantStock(itemNo, locationCode,
  customerNo)`); a store without an ERP customer number is refused before the ERP is asked, with the reception's reason
  (`/ho/orders/variant-stock` 409; the store relays a 409 with it instead of a 503). connector-zen reads `infoArticles?$filter=
  article eq '..' and client eq '..'` (no location): the quantity is `disponible`, never `stock`; null when a row has none
  (unknown, shown "—"). `VariantStockDTO` / `ErpVariantStockDTO` and the store's variants answer gain the optional
  `details` (ordered label → value; ZEN: Prix unitaire, Remise %, Prix net, Stock, Qté vrac, Vrac autorisé, Contenance
  série, Colis possibles, Calculé le) and `sizeOrder` (ZEN: `ordreTaille`); EMTOP and Happyness leave them empty. Test
  sample `connectors/zen/src/test/resources/zen/infoArticles.json` (ZEN's test NAV, 43ZBPND01 for 4110001).

- Store orders, quantity ≤ available (docs/modules/store-orders.md): `POST /store-order/draft/lines` takes an optional
  `availableQty` (the variants window: the quantity available shown on the cell, null for none) that the line keeps
  (`hol_order_line.available_qty`); a quantity above the availability sent, or else the line's (the cart), is refused
  with 409 and nothing saved; the send refuses a draft with any line above its availability (409). The lines answer
  `availableQty`.

### Fixes
- `GET /sales-header/pending-sales` sends each ticket's `discountSource` (MANUAL or PROMOTION): the till taking a held
  ticket back no longer shows its cart offer a second time as a "Remise" (front CHANGELOG).
- Copies down, head office: a store's pull of LOYALTY, PROMOTIONS or SUPPLY answered HTTP 500 (query timeout after
  15 s) while a catalogue writer was recording (seen during the first ZEN images run): the read of
  `ho_down_sequence` scanned the table (the driver's nvarchar against the varchar column) and waited on the locked
  CATALOGUE row. `HoDownSequenceRepository` reads and increments with native queries, the domain cast to varchar: a
  seek on the domain's own row.
- Copies down, head office: a data import or the item images run recorded its codes one by one (a JPA lookup that
  read every row of the domain per code, about 100 ms each), holding the CATALOGUE sequence row about 40 s per chunk of
  500: the stores' CATALOGUE pulls timed out meanwhile (HTTP 500), and the first images run took 31 minutes for 17,140
  codes. `CatalogueHeadOfficeHooks.afterImport` now records each chunk in bulk: `CopiesDownFeed.recordChanges` for every
  store reserves the numbers in one update and writes the every-store rows with `JdbcDownChangeBatch.writeEveryStore`
  (casts kept); the same rows and numbers as before.
- connector-zen, `commandesVente`: NAV refused every order ("Control 'numeroDocExterne' is read-only"). The order number
  now goes in `referenceZS` (ZEN's field for it) and the lookup filters on `referenceZS eq '..'`; `numeroDocExterne` is
  never sent, and the store code is no longer sent (no writable header field fits it; `client` names the store).
- connector-zen, `commandesVente`: when NAV answers the lookup with "Field 'referenceZS' is not filterable", the lookup
  is skipped and the order posted; the communications log message ends with "duplicate check skipped: referenceZS not
  filterable". Any other lookup error still stops the posting.
- connector-zen, `commandesVente`: every line sends `mode` `vrac` by default (`erp.zen.order.mode` overrides it, set
  empty leaves it out); NAV refused lines without it ("Le mode de la ligne … doit être colis ou vrac").
- Store orders lists: `GET admin/headoffice/store-orders` and the store's `GET /store-order` answered 500 ("Parameter
  value [1] did not match expected type [java.lang.Integer]"): the "every store / every status" flags are `long` and the
  statement compared them with an `int` literal; now `1L`, as the BL reception list. `HoStoreOrderPageQueryTest` and
  `StoreOrderPageQueryTest` run the real statements on H2 with and without each filter.

### DB script (`db/3.1.1/store-orders.sql`, then `db/3.1.1/update.sql`, store and head office)
- `store-orders.sql`: tables `hol_order` (unique `number`, index `ix_hol_order_status`, filtered unique index
  `ux_hol_order_draft`: one DRAFT) and `hol_order_line` (unique `order_id`, `item_id`; FK to `hol_order`) on a store
  only; `item_image` (index `ix_item_image_item`) on both. Re-runnable, no data changed. The filtered index is also made
  at each start when missing (`StoreOrderDraftIndex`: a new database runs no script).
- `update.sql`: the ERP jobs' rows `IMPORT_ITEM_FAMILIES`, `IMPORT_ITEM_SUBFAMILIES`, `IMPORT_ITEMS`,
  `IMPORT_ITEM_BARCODES` converted into one `IMPORT_CATALOGUE` (the cron of `IMPORT_ITEMS`, enabled when any of the four
  was, then the four deleted; one transaction, re-runnable; the same batch the application runs at startup).
- `update.sql`: `APP_VERSION` 3.1.1 (it stops while `store-orders.sql` has not run); notes written when the version is
  closed. The till's index `ix_sales_header_session_status` on `sales_header (cashier_session_id, status)` (re-runnable,
  store and head office): the session's ticket count and its pending tickets read through it instead of a scan of every
  sale.
- `store-orders.sql`: column `hol_order_line.available_qty` numeric(18,3) NULL (added when missing; a start adds it too).
- `store-orders.sql`: columns `hol_order.nav_message` varchar(500), `hol_order_line.nav_accepted_qty` numeric(18,3) and
  `nav_line_message` varchar(500) (store), `ho_store_order.nav_message`, `ho_store_order_line.nav_accepted_qty` and
  `nav_line_message` (head office), all NULL, each added when missing (a start adds them too).
- `store-orders.sql`: columns `invoice_no` varchar(30) and `delivered_at` date, NULL, on `hol_order` (store) and
  `ho_store_order` (head office), each added when missing (a start adds them too): the invoice of a DELIVERED order.
- `store-orders.sql`: tables `hol_order_colis` (store; unique order and item; FK to `hol_order`) and
  `ho_store_order_colis` (head office; FK to `ho_store_order`, index on the order), columns `hol_order.total_colis` and
  `ho_store_order.total_colis` int NULL, each when missing.
- `store-orders.sql`: columns `variant_code` varchar(50) and `color_description` varchar(255) NULL on `hol_order_colis`
  and `ho_store_order_colis` (in the tables and added to those of an earlier run); `uk_hol_order_colis_item` (order,
  item) dropped and `uk_hol_order_colis_variant` (order, item, variant) added, each when needed.
- `store-orders.sql`, colis by item: columns `colis_content`, `colis_disponibles` int, `nav_accepted_qty` numeric(18,3),
  `nav_numero_colis` varchar(50) NULL on `hol_order_colis` and `ho_store_order_colis` (`variant_code`,
  `color_description`, `contenance`, `colis_possibles` stay, unused); the per-colour rows merged into one per order and
  item (colis summed, colour cleared, no composition); `uk_hol_order_colis_variant` dropped, `uk_hol_order_colis_order_item`
  (order, item) added; tables `hol_order_colis_detail` and `ho_store_order_colis_detail` (FK to the colis row, index
  on it); `hol_order_line.vrac_allowed` bit NULL. Each when missing.
- `store-orders.sql`: column `ho_erp_invoice.order_no` varchar(50) NULL and index `ix_ho_erp_invoice_order` (head
  office), each added when missing: the sales order an ERP invoice names. The invoices read before keep none.
- `store-orders.sql`, step 2: tables `ho_store_order` (unique `store_id`, `number`; FK to `ho_store`; index
  `ix_ho_store_order_status`) and `ho_store_order_line` (FK to `ho_store_order`) on a head office only. Re-runnable, no
  data changed.

### Config
- `headoffice.storeorders.post` (head office, optional): `MANUAL` or `ERP` (needs a connector with
  `ErpAbility.STORE_ORDERS`: zen); absent, the head office takes no store order. Set to `ERP` in
  `configs/customers/zen-headoffice.properties`.
- `erp.zen.order.mode` (head office with the connector zen, optional): the `mode` of every line of a sales order on
  `commandesVente`; absent: `vrac` (NAV requires `colis` or `vrac`), set empty: the field is not sent. (The pages are no
  longer settings: `erp.zen.variantstock.path` and `erp.zen.salesorders.path`, added earlier in 3.1.1 for the mock, are
  gone; a line left in a file is ignored.)
- `erp.zen.products.url` and `erp.zen.products.api-key` (head office with the connector zen, optional, both or
  neither): ZEN's products API for the item images. Set in `configs/customers/zen-headoffice.properties` (the key
  `CHANGE_ME`: the real one in the installation's file only).

## 3.1.0 — 2026-10-27 (tag `v3.1.0`)

### Features
- RFID at the store (`rfid.enabled`, docs/modules/rfid.md): readers (Hopeland SDK, a fake reader for tests) and POS
  machines, one read at a time per reader, `GET /rfid/my-machine`; tags table, Tags and Tag assignment pages API, the tag
  button of a reception line; the till reads pieces into the cart (`POST /rfid/pos-read`), the tags go with the payment,
  the pending ticket and the return (`rfid_sale_tag`, `GET /rfid/ticket-tags`); a weak tag is below the reader's minimum
  signal; a pack holds no tag.
- Ticket X / ticket Z of a cashier session (`GET /cashier-session/{id}/report-ticket`) and of the store's day, every
  session (`GET /cashier-session/store-report-ticket/x` and `/z`); the cashier closing prints the ticket Z.
- Journal: the days of a month, a day's journal, Excel exports.
- Payment method "Retenue à la source" (type `RETENUE_SOURCE`), inactive by default.
- Item variants for ZEN (docs/variants-zen-analysis-2026-10-14.md): one item per ERP variant (code
  `itemNo~variantCode`) with its 14 variant fields; catégories and sections (the ERP's tree around families and
  sub-families); clearance and clearance price per point de stock row; an items run of changes only, with a checkpoint
  per point de stock; the head office copies carry catégories, sections and variant fields to the stores; ZEN's
  connector `connectors/zen` (`erp.connector=zen`, catalogue only, head office).
- NAV items page API (step 11a): `GET /nav-item`, `GET /nav-item/variants`, one line per NAV item number, the variant ×
  point de stock price grid at the head office, the stock only where the installation keeps its own.
- Catégories and Sections pages API (step 11b): `GET /item-category` (with the number of families),
  `GET /item-category/families?code`, `GET /item-section` (with the path catégorie › famille › sous-famille); GET only.
- Fiches items, head office page API (step 1, the list): `GET /admin/headoffice/item-catalogue?page&size&search&status`
  on every head office, one line per NAV item number, else one per item; search on NAV number, item code, name and
  barcode; GET only, the other item pages and APIs unchanged.
- Fiches items follows the customer's data: `GET /admin/headoffice/item-catalogue/filters` (flags hasVariants,
  hasFamilies, hasDivision, hasCategory, hasSection, stockPointCount, hasBarcodes and the filter choices); the list
  filters on family, sub-family, division, catégorie, section, point de stock, has variants and price with VAT, sorts by
  code (natural for the codes of digits only), name or family, and gives each line its price range with VAT.
- Fiches items, step 2, the item card: `GET /admin/headoffice/item-catalogue/card?navItemNo|itemId[&search]`, one
  answer with the header, every variant (active or not) with its active barcodes and point de stock rows, the points,
  and the item form's read-only details; the variant whose code or barcode equals the search is highlighted.
- Item cards, step 3: the list's `status=INACTIVE` (the lines without an active variant); the card gives each variant's
  `minStockLevel` and, in its details, `minStockLevel` and `lastDirectCost` (only where the head office keeps its
  stock). Headoffice project only.
- Promotions by catégorie and section (ZEN): scopes `CATEGORY` and `SECTION`, targets `promotion.item_category_id` /
  `item_section_id`. Matched at ticket time on the item's codes (catégorie: the item's own code, else its famille's);
  order item > group > section > sous-famille > famille > catégorie > all items, each new branch only for an item
  with the code (no change where the codes are empty: EMTOP, Happyness). Cross-product buy side too. Save: each new
  scope needs its target, other scopes keep neither; both locked once used. Head office copy by code
  (`itemCategoryCode`, `itemSectionCode`), missing = waiting as a famille; a store of an older build skips such a
  promotion (error). `GET /item-category` and `GET /item-section` entries carry the row `id`.
- Promotions on every variant of a NAV item, step 1 (ZEN): an ITEM promotion targets one item row (`item`) or one NAV
  item number (`navItemNo`: every item carrying it, also variants synced later), an ITEM_GROUP holds item rows and/or
  NAV item numbers (`groupNavItemNos`); `getNavItemNo` (benefit on any variant) is stored but refused for now.
  Matched at ticket time: item on its row > item on its NAV number > its groups (rows and NAV numbers one level,
  priority decides), read only for an item carrying a NAV item number (EMTOP, Happyness: the same reads and answers);
  cross-product buy side too. Save: an ITEM never holds both, a NAV number only on ITEM, NAV numbers only on
  ITEM_GROUP, trimmed; a quantity promotion on a NAV target or with a NAV benefit is refused (400) until the till counts
  the lines of a NAV item together (step 2); the three locked once used. The head office copy and the screens follow
  (steps 1b and 3).
- Promotions on every variant of a NAV item, step 1b: `GET /item/search` items carry `navItemNo` (null without one);
  `GET /nav-item/names?navItemNo=..&navItemNo=..` (read only): `{number: name}` of the NAV item numbers asked (at most
  200, a number no item carries left out), the names of the promotions' NAV targets.
- Promotions on every variant of a NAV item, step 4: `GET /item/lookup?scope&q&page&size` (read only), the target lookup
  of the promotion form: scope ITEM, one list of ERP item lines (every active variant of an ERP item number, its
  lowest id and its number of variants) and item lines, by name and ERP item number, each ERP item line right above
  its own variants; search on code, name, ERP
  item number and barcode; packs and inactive items left out; FAMILY, SUBFAMILY, CATEGORY, SECTION: their active
  rows, with the parent's name. Without ERP item numbers: item lines only, the same statement.
- Promotions on every variant of a NAV item, step 3: the head office copy carries `navItemNo`, `getNavItemNo` and
  `groupNavItemNos` (sorted); the store saves them as they are, never waiting for an item carrying them (a group is
  missing only with none of its rows and no NAV number), and logs one INFO line for a number no item of the store
  carries yet. A copy without them (null / empty) compares equal to an existing promotion: nothing rewritten (EMTOP,
  Happyness). A store of an older build ignores the fields.
- Promotions on every variant of a NAV item, step 2, counted across the cart lines (rule 6a): `POST /price-calculate`
  takes `cartLines` (the cart in order) and answers `acrossLines`; the cart pass (`customerId`) adds `lineResults`, every
  line priced with the cart around it (a cart of one item: its line priced alone). Quantity promotions on NAV targets
  and a NAV free item are accepted; a benefit on both an item and an ERP item number is refused; buy NAV = get NAV is
  a same-item promotion. Cross-product:
  `getNavItemNo`, `freeByItemId`, `entitledByItemId` (cheapest first). Promo code: `navItemNo`, `groupNavItemNos`,
  `getNavItemNo`. Without `cartLines`, or a cart of one line: priced exactly as before.
- Till levels catégorie and section, step 1, API only (ZEN; docs/modules/head-office.md, "Till levels"): read-only POS
  lists `GET /item-category/pos`, `GET /item-family/pos?category={code}` and `?withoutCategory=true`,
  `GET /item-section/pos?subFamily={id}`, `GET /item/pos/by-section/{id}`, the fall-throughs
  `GET /item/pos/without-section/{subFamilyId}` and `GET /item/pos/without-family?category={code}`; a section holds the
  items of its own sous-famille that carry its code. `GET /item/pos/search` adds `categories` and `sections` and a `path`
  on every hit, only where the installation has the level: without catégories and sections (EMTOP, Happyness) every
  new list is empty and the search answer is unchanged.
- Supply invoices of ZEN's NAV, step 9a (the read; docs/modules/head-office.md, "Invoices from the ERP, ZEN"): the
  connector `zen` reads ZEN's posted sales invoices (since moved to ZEN's franchise API, see Enhancements) as Happyness's invoices (by year
  prefix `FVE26` after the head office's highest number, the General Setup "Read ERP invoices after number" as the
  starting point), only the invoices of the stores' ERP customer numbers (every store that has one, active or not:
  erp-api `ErpInvoiceCustomers`, head office `HoErpInvoiceCustomers`; none: nothing read, a warning); item code
  `No~Variant_Code`; a cancelled invoice read past and left out, a corrective one read with a warning; the runs in
  the communications log. Ability `SUPPLY_INVOICES`: `headoffice.supply.source=ERP` is accepted with `zen` (the
  configs keep `HEAD_OFFICE` until the live test, step 9b).

### Enhancements
- Till authorisation, step B: `GET /config` serves `tillActions`, the registry of the five badge actions (`{code, key}`,
  `BadgePermission.key()` = the `till:*` role permission key of step C), empty on a head office; the frontend builds its
  badge lists, labels and filters from it.
- Promotions: a free piece only when scanned (buy N get M), repeated for each group of N + M pieces; the free pieces
  carry RFID tags; a return is whole per item.
- Stock adjustments accept decimal quantities when the store allows them; the till's search runs over the whole
  catalogue.
- `GET /config` gains `cashierClosingTicket`, `navItems`, `itemCategories`, `itemSections` (each read on each call).
- Session report: an undeclared payment method reads null.
- Till authorisation, step C: the till rights belong to the role, five permissions `till:consult-customer-list`,
  `till:make-return`, `till:apply-line-discount`, `till:apply-total-discount`, `till:close-session-with-discrepancy`
  (listed by `GET /app-roles/permissions`, sent with the login's `permissions` as the page permissions); a new store
  database gives the five to every role, a head office none. A badge is a credential only (code, expiry, revoked): it
  opens an action when its owner's role holds it, else `BADGE_NO_ACCESS`. The per-user badge list is no longer read or
  written (`badgePermissions` removed from the user DTO and `PUT /user-account/{id}`).
- Till authorisation, step C: on a store of before, at its first start, once (`TILL_RIGHTS_MIGRATED`), each role gets
  the rights every active user of the role passes today without a badge (an admin: the five; with
  `ALWAYS_SHOW_BADGE_SCAN_POPUP` on: nobody else; else his card's list with a valid badge, the close with a difference
  excepted); the role ADMIN gets the five; rights only added; the role × right matrix logged once. Nothing changes at
  the till on the day of the upgrade.
- Till authorisation, step C: every pass is logged in `badge_scan_log` with `granted_by`: a scan (BADGE) or a pass on
  the role's right (ROLE, no badge code, the owner is the logged user), the latter by `POST /badge/pass {action}` (403
  `TILL_RIGHT_REQUIRED` when the role does not hold it); `GET /badge/scan-history` filters `grantedBy` and its rows give
  it; the statistics count the scans only; a scan logs the scanning user's open session (the till's `sessionId` is no
  longer trusted).
- Till authorisation, step C: closing with a cash difference: the logged user whose role holds
  `till:close-session-with-discrepancy` confirms the difference (`discrepancyConfirmed`) whatever the role's name;
  anyone else brings a badge whose owner's role holds it (2.2.2: an admin by the role enum only, a badge by its list).
- Till authorisation, step D: the server checks the till rights on the requests that do the actions: each manual line
  discount and the manual header discount of `process-sale`, `save-pending`, `update-pending/{id}`,
  `complete-pending/{id}`, the return (`process-return`) and the close with a cash difference. The role's right passes
  (logged ROLE once per request); else the request carries the grant of a badge scan (`tillGrant`, per line, header,
  return, close), used once (logged BADGE with the badge's owner); else 403 `{error: TILL_RIGHT_REQUIRED, action,
  reason, lineIndex}` and nothing of the request is kept. A pending ticket keeps the discounts it held (not larger).
  Promotions, price lists and lines without a source need nothing; the split payment and the head office unchanged.
- Till authorisation, step D: `POST /badge/scan` gives a grant for the actions checked on their request (`grant: {token,
  action, badgeOwnerId, issuedAt, expiresAt}`): one action, the scanning user's open session, single use, good for
  `TILL_GRANT_MAX_MINUTES` (240); only its SHA-256 is stored. Such a scan's success is logged when the grant is used,
  no longer at the scan. `GET /config tillActions` gives `checkedOnRequest`. The close takes `tillGrant` instead of
  `badgeCode` and `badgePermission`.
- Step E: the enum `Role` is retired; a user's role is his AppRole and an administrator the user of the system role
  named ADMIN (`SystemRoles`; the ERP admin pages and the rule giving or removing the ADMIN role use it). The login,
  `UserAccountDTO`, `CreateUserRequestDTO` and `GET /user-account/cashiers` no longer carry `role`; `PUT
  /user-account/{id}` no longer reads it; creating a user needs `appRoleId`. Users of before without an AppRole get the
  role named as their enum value at the start (as before, now by JDBC).
- Till rights, discount sources: every discount of a sale or pending request needs the right (or a grant) unless the
  server verifies its source: PROMOTION with an existing active promotion, SALES_PRICE / SALES_DISCOUNT with the item's
  price list discount for the customer (not a larger percentage). A source missing, unknown or not verified counts as
  MANUAL (403 `NO_GRANT` without the right). `save-pending` and `update-pending/{id}` now store each line's and the
  header's discount source and promotion, so a recalled ticket keeps them; a ticket parked before counts as MANUAL and
  passes on the discounts it held.
- Item variants (step 11c): the return screen's ticket lookup and the return voucher lines give the item's
  `variantDescription` (`ReturnHeaderAPI`), so the till and the tickets show "name — variant" (null for the other
  customers: the name alone, as before). The sale lines already carried it through the item.
- Item variants (step 12a): ZEN's connector reads ZEN's real barcodes page `barCodes` (`itemNo`, `variantCode`,
  `barCode`, `lastModifiedDateTime`, `id`) instead of Happyness's `ItemBarCodePOS`; the barcode cursor is
  `lastModifiedDateTime` in epoch milliseconds (NAV's empty date: 1), a full page completed with every row of its last
  millisecond so that none is skipped and the run ends caught up; a barcode changed in NAV is read again.
- Item variants (ZEN), clearance at the till: a point de stock row with clearance true and a clearance price above 0
  sells at its clearance price (before VAT, the item's VAT); the head office sends it as the item's price to the stores
  of the point (`HoCatalogueService.pointCopy`, one rule `StockPointPrice`), with `clearance` and `publicPrice` for the
  till's display; a clearance change reaches the stores as any row change. The head office pages give
  `clearanceApplies` and `sellingPrice`. EMTOP and Happyness unchanged (no clearance).
- ERP items page at a store: `GET /nav-item/variants` gives each variant's `clearance`, `publicPrice` and `ownPrice` as
  the till has them (null at a head office and for the other customers), so the page strikes the public price as the
  till does.
- Item variants (ZEN): the connector builds the variant description from the size, colour and choice descriptions
  ("Taille Standard / VERT PISTACHE / 1er Choix", the blank ones left out) and no longer reads NAV's
  `variantDescription` (always the item description on ZEN's platform); a change of one of them changes the item. The
  item name stays NAV's item description.
- Fiches items card (`GET /admin/headoffice/item-catalogue/card`): `storeStocks`, the stock each active store last sent
  for the card's item codes (`ho_store_stock`, one query, by store code then item code), and `header.withStoreStocks`;
  empty where no store sends its stock. Parameters and the other fields unchanged.
- Fiches items list (`GET /admin/headoffice/item-catalogue`): `sort=variants` (the line's variant count) and
  `sort=price` (its smallest price with VAT, lines without a price last both ways), each then by code.
- Network stock (`GET /admin/headoffice/stock`): `stock=ALL|IN_STOCK|ZERO|BELOW_ZERO` on the columns shown (the head
  office's stock where it keeps one, and the stores shown; `belowZero=true` still means `BELOW_ZERO`), and the item
  filters `familyId`, `subFamilyId`, `divisionCode`, `categoryCode`, `sectionCode`; native SQL built with only the
  filters set (`HoNetworkStockQuery`).
- Network stock, step 2: `GET /admin/headoffice/stock` sorts on `code` (natural, the default), `name`, `headOffice`,
  `store-<id>` (items the store never sent last) and `total` (the active stores; with 2 stores shown or more), `dir`;
  rows give `itemId`, `navItemNo` and `totalStores`, the answer `withTotal`. `GET .../stock/own`: `stock`
  (IN_STOCK, ZERO, BELOW_ZERO on the row's quantity), `sort` (store, code, name, quantity, storeTime, receivedAt), `dir`;
  default order store code then item code (was store id).
- Classification column (every items page, catégorie › famille › sous-famille › section): the filter choices of
  `GET /admin/headoffice/item-catalogue/filters` give `parentCode` (a family's catégorie, a section's sub-family code)
  for the level filters' narrowing; the rows of `GET /admin/headoffice/stock-points/items` (and `/items/{rowId}`) the
  item's `categoryCode`, `categoryName`, `sectionCode`, `sectionName`; the barcode rows (`GET /item-barcode/list`,
  `/item-barcode/items-without-barcode`) the item's `categoryCode`, `sectionCode`. All nullable: null on a customer
  without catégories and sections (EMTOP, Happyness). No filter, sort or other answer changed.
- Supply invoices of ZEN, on ZEN's franchise API (its NAV team moved them): the connector `zen` reads
  `api/sat/franchise/v1.0/companies(<id>)/salesInvoices` (the zsretail API's company id) with the lines
  `salesInvoiceLines`, instead of NAV's OData V4 page `SalesInvoice`; the camelCase fields (`number`,
  `sellToCustomerNo`, `lineType`, ...); the totals from the header (`amount`, `amountIncludingVAT`, the VAT their
  difference), the lines checked against `amount`; no `$select` on the header, so `cancelled` and `corrective` are read
  once the page gives them (not yet: until then every invoice is read and sent); a `currencyCode` that is not blank
  gives a warning. Item code, line types, costs at the store and the head office flow unchanged.

### Fixes
- ERP jobs no longer stop for good on a NAV call that never answers (Emtop 1.12.1, 2026-10-22: Import Customers waited
  on NAV from 14:18 and no job ran by itself again, only "Run now"): the emtop connector's client has a connect timeout
  (30 s) and a read timeout (600 s of silence, not the length of a read), like the happyness and zen connectors.
- Promotions, group of items (Emtop 1.12.1: a group saved with one item): the back end no longer depends on what the
  screen sends. `groupItems` is read by distinct id (`{ id }`, `{ id, itemCode }` or a full item give the same group)
  and `PromotionService.save` loads those ids from the database; an unknown id or an item without an id is refused.
  `Item` equality is unchanged.
- Quantity promotions count the whole pieces of every cart line of their target (rule 6a, every customer, decided
  2026-10-23): a minimum quantity or a "buy N get M free" on a famille, sous-famille, section, catégorie, group, NAV
  item or all items read each line alone (2 shirts of one famille in two lines never reached "buy 2"); the cross-product
  buy side summed each line's floor (1 + 1 pieces never reached N = 2). Level by level (row, NAV item, group, section,
  sous-famille, famille, catégorie, all items), each level counting only the lines no earlier level took; free pieces
  cheapest first, then cart order; a free promotion giving a free piece takes every line it counts. A cart of one line
  is priced as before. Check EMTOP and Happyness quantity promotions on other scopes than ITEM before deploying.
- `Item` equality, root cause of the group saved with one item (Emtop 1.12.1): two items are equal when they have the
  same id (read through `getId()`, a Hibernate proxy equals its item), an item without an id only to itself, one hash
  for every item; Lombok's equality left the id out, so items with only an id were all equal. The other maps keyed by
  an item hold saved rows (invoice and purchase invoice lines grouped by item, head office supply prices): unchanged.
- Promotions page of a store (`POST /promotion`, `PUT /promotion/{id}`): a promotion refused by the save rules (a group
  without items, a catégorie or section promotion without its target, the NAV targets' rules) answers 400 with the
  reason, as the head office does; it answered 500.
- Fiches items list (`GET /admin/headoffice/item-catalogue`): failed on SQL Server ("duplicated sql alias []", the page
  showed no item): every column of its native queries now has its own alias.
- Badge scan history: the statistics count every row of the period, the passes on the role's right (ROLE) with the
  badge scans (BADGE), as the list shows them (the tiles said 19 for a list of 20).
- Batch 3 (test log 2026-10-17): a scan of a known barcode whose item is inactive answers 404 with `inactive: true`
  (the till says "Inactive item"), and an inactive item is never found by the scan (I-04); points removed on a return are
  rounded down as the points earned, the money returned at the sale's rate (24.657 TND returned removes 24, was 25), a
  tiered program still removing what the money kept loses to a lower tier (I-57); the head office ticket and return
  details, network stock and delivery note lines give the item's `variantDescription` when the head office item has one
  (I-24); the sales history list gives `loyaltyCardNumber` (I-54); `GET /promotion/paginated` takes `active`
  (true / false, none: all).
- Invoices (test log I-71): the invoice header's and lines' amounts (HT, VAT, discount, TTC) are stored to the millime,
  half up, on every save, as the tickets (I-07); the header is the sum of the rounded lines. Unit prices unchanged;
  invoices stored before are not corrected.
- Sales report (test log I-67 re-test): the report answered 500 in every grouping since the points fix (HQL
  `unexpected token: /`: Hibernate 5 takes a single term after `THEN`); the points share is in parentheses.
- Stock report: a negative stock has its own status `NEGATIVE` (was `OUT`, test log I-52). Statistics: a KPI delta is
  null, no comparison, when the previous period is 0 (was 0.00, test log I-51).
- Sales report (test log I-67): the loyalty points spent are taken off as the ticket discount, in every grouping, so the
  report adds up to the tickets' paid total; the points share is in the discount column.
- Invoice made from tickets (test log I-63): a ticket with a ticket discount, a cart promotion or points spent adds one
  "Remise ticket <number>" line (HT, VAT, TTC negative), so the invoice adds up to what was paid; the ticket's lines are
  unchanged and a ticket without a reduction gives the invoice of before.
- Head office BL sent with items without a supply price (test log I-35): the validation's answer lists them
  (`missingSupplyPrices`) and logs one WARN line; the BL is sent as before (their supply invoice, and so the store's cost,
  wait for a price).
- Reports grouped by family (test log I-66): the sales and purchase reports count the items without a family, under
  "—" (they were left out).
- Statistics (test log I-49, I-50): "Top 5 products" counts the items without a family (family "—"); the payment split's
  cash is net of the change given back, as the ticket Z (it counted the cash tendered).
- A return's total and a session's expected cash to the millime (test log I-07): `total_return_amount` and `real_cash`
  stored rounded half up; the closing cash check compares the rounded expected cash.
- Return vouchers to the millime (test log I-07): `voucher_amount` and the amount used are stored rounded half up, and a
  voucher payment debits the voucher with the payment's rounded amount (remaining and "fully used" read on the millimes),
  so the voucher and its payment carry the same value.
- Ticket amounts to the millime (test log I-07): a ticket's amounts, its lines' amounts and its payments are stored
  rounded to 3 decimals, half up, on every save (`roundAmounts` of `SalesHeader`, `SalesLine`, `Payment`; unit prices and
  percentages untouched); paid vs total is checked on the rounded values with the till's tolerance of 0.010 (a sale more
  short is refused, before nothing was checked) and the change is rounded paid − rounded total (no more 0.0002). Tickets
  stored before are not corrected; vouchers and the session's real cash are not covered.
- Ticket HT and VAT after a ticket-wide reduction (test log I-48): a ticket with a ticket discount, a cart promotion or
  loyalty points spent stores the HT and VAT of what was paid, spread over the VAT rates in proportion to the lines'
  TTC as the ticket X / Z spreads them (`SalesHeaderService.applyTicketReductionToTotals`), so HT + VAT + stamp = total
  on the receipt, the ticket and the head office copy (85.252 = 71.640 + 13.612, was 79.600 + 15.124). The lines keep
  their amounts before the reduction; a ticket without one keeps the till's values. The ERP ticket export takes its
  gross from the lines, so NAV receives the same discount % as before. Tickets stored before are not corrected.
- Users (`/user-account`, test log I-21): nobody changes the role of his own account, and only an administrator gives
  or removes the Administrateur role, on an edit or a new user; a refused change answers 403 with the reason in French
  (`RoleChangeRefusedException`). A responsible could make himself Administrateur.
- The closing cash check compares cash with cash.
- The return's ticket lookup refuses a ticket that is not completed.
- The RFID Tags page list on SQL Server (Msg 402); a pending ticket paid with its tags no longer breaks
  `uk_rfid_sale_tag_header_epc`.
- A FREE_QUANTITY discount in the session report.
- ZEN's barcodes (step 12a): the run no longer ends on "rows at or below the cursor". NAV keeps the dates in SQL
  datetime (1/300 s) and rounded the filter's cursor + 1 ms back to the cursor; the filters are now
  `lastModifiedDateTime gt <cursor>` and, to complete a full page, `eq <its last time>`.
- Head office link (store): after a "Run now" the job's next run comes from the end of that run, 5 s later when it has
  more to do (e.g. the catalogue copies down by cycles of 1,000), else its frequency; it stayed at the time the
  schedule had computed before (30 min for the copies), also when the page stopped waiting after 30 s.
- Speed with a large catalogue (ZEN's test head office, 21,903 items): the page "Items by point de stock" without a
  search no longer reads every item's barcodes (its query took minutes; the barcode search is only in the query of a
  search); the store reads the tracking rows of a page of copies down in one query, not one per record (each read
  scanned `hol_down_record`); a page of barcodes is read with its items in one query; a store's pull reads its changes
  in the new index alone (the domain cast to `varchar`). Same answers and rows as before.
- Speed of the copies down, barcode phase (store): each barcode received read the old `item.barcode` field twice, a
  scan of `item` each time (3–5 s per page of 100 on 21,904 items); now one read, on an index.

- Head office link (store), copies down: a slow head office no longer stops the catalogue catch-up for 30 min. The
  pull has its own read timeout (60 s, `headoffice.copies-down.read-timeout-seconds`; heartbeat and pushes keep
  10 s); a read timeout is `TIMEOUT` "head office answered too slowly (read timeout 60 s)", not OFFLINE; a run that
  failed on a timeout or an I/O error runs again 10 s later, up to 3 times ("retry in 10 s (1/3)"), the 4th back to
  the frequency with one WARN line; a failed pull is logged at WARN (was INFO).
- ERP catalogue, items step (speed): a packet of items reads its existing items in one query by ERP id and one by
  code, the families and sub-families once, instead of up to five SELECTs per row (each scanning `item`, which has no
  index on `erp_external_id`, and flushing the packet); the item and point de stock row updates go by JDBC batches of
  100 (that transaction only). One INFO line per items step (rows read, equal, new, changed, written, ms) and per
  packet. Unchanged rows were already never written (the engine compares every field).
- ERP catalogue run: a page read that fails on an I/O error or a timeout (e.g. a VPN drop) is read again once 5 s
  later, with one WARN line, for every connector; a second failure fails the step as before; cursors and
  checkpoints move only on a read that succeeds.
- Security (step S): the `/user` controller is removed (`UseAccountAPI`: generic CRUD on the raw `UserAccount`, password
  hash included, and `PUT /user/{id}/role`, which set the role enum without the I-21 check), and so are the unlogged
  badge lookups `GET /badge/by-badge/{code}`, `POST /badge/check-permission`, `GET /user-account/by-badge/{code}`,
  `GET` and `PUT /user-account/{id}/badge`; no caller in the front.
- Security (step S): the badge scan history reads (`/badge/scan-history`, `/badge/{code}/scan-history`,
  `/badge/scan-statistics`) answer 403 unless the role holds `read:admin-badge-scan-history` (any logged user read them),
  and give flat rows (`BadgeScanLogDTO`, the fields the page reads) instead of the entity, which carried both users'
  `UserAccount` (password hash, badge code): the "Scanned by" and "Badge owner" columns are filled again; no badge code
  in the rows nor in the statistics (`topBadges` removed), which give `failedScans` (the "Failed" card showed 0).
- Security (step S): the badge of a deactivated user is refused: at a scan `BADGE_OWNER_INACTIVE` (logged like the
  other refusals), at the close with a cash difference as an unknown badge. It opened every action before.
- Security (step S): badge codes are unique in the database (filtered index `ux_user_account_badge_code`, created by
  the script, or at the start of a new database by `UserBadgeCodeIndex`); a generated code takes its 4 digits from
  `SecureRandom` (was `java.util.Random`); a new database's responsible gets a generated badge code, logged once at the
  first start, instead of the fixed `RESP-BADGE-001` written in the source.
- connector-zen: stockPoints new fields — HT prices (unitPrice, clearancePriceExclVAT), vatPercent, the three blocked
  flags. `publicPrice` and `clearancePrice` are the prices with VAT and were read as the prices before VAT (a till sold
  19.50 × 1.19); they are now a check only (a pair that does not agree with the price before VAT × VAT is noted). VAT
  from `vatPercent` (absent: `erp.zen.default-vat`); `itemBlocked`, `variantBlocked` or `stockPointBlocked` true makes
  the row inactive, each noted; the guessed `active` and `vat` fields are gone. A row of the old page (`publicPrice`, no
  `unitPrice`) fails the read of its location, nothing applied. The engine compares a clearance price at 5 decimals.
  **Deploy**: on a head office that already read ZEN's rows, reset the points' checkpoints (`items.since:*` keys of
  `erp_catalogue_state`), else the rows NAV does not change again keep the price with VAT (docs/modules/erp-sync.md).
### DB script (`db/3.1.0/update.sql`, store and head office)
- RFID tables on a store: `rfid_reader` (with `min_signal`), `pos_machine`, `rfid_tag`, `rfid_sale_tag`.
- Payment method "Retenue à la source", inactive (kept as it is where EMTOP 1.12.1 has it).
- Promotions by catégorie and section: nullable `promotion.item_category_id` and `item_section_id`, their foreign keys
  (only when the column has none) and indexes, store and head office; re-runnable, no data changed.
- Promotions on every variant of a NAV item: nullable `promotion.nav_item_no` and `get_nav_item_no` (varchar(255), as
  `item.nav_item_no`), index `idx_promotion_nav_item_no`, table `promotion_group_nav_item` (`promotion_id`,
  `nav_item_no`, primary key both, foreign key to `promotion`) with index `idx_promo_group_nav_item_nav`; store and head
  office, re-runnable, no data changed.
- Item variants: 14 nullable columns of `item`, `item_family.category_code`, tables `item_category` and `item_section`,
  `ho_stock_point_item.clearance` and `clearance_price` (head office), index `ix_item_nav_item_no` (plain, not filtered).
- Item variants (ZEN): `item.variant_description` rebuilt from `size_description`, `color_description`,
  `choice_description` (" / ", the blank ones left out, null when none) for the items with a `nav_item_no`;
  re-runnable.
- Clearance at the till (ZEN): nullable `item.clearance` (bit) and `item.public_price` (float), store and head office
  (written on a store only, by the copies down); re-runnable, no data changed.
- Speed, the ERP import: indexes `ix_item_erp_external_id` (`item.erp_external_id`) and
  `ix_item_barcode_erp_external_id` (`item_barcode.erp_external_id`), store and head office; re-runnable.
- Speed: indexes `ix_item_barcode_item` (`item_barcode.item_id`) and `ix_item_barcode` (`item.barcode`), store and
  head office; on a head office
  `ix_ho_down_change_pull` (`domain`, `change_version`, `store_id`, `record_code`) replaces `ix_ho_down_change_version`.
  Re-runnable, no data changed.
- Security (step S): filtered unique index `ux_user_account_badge_code` (`user_account.badge_code`, not null), store
  and head office; skipped with a warning when two users already share a code; re-runnable, no data changed.
- Till authorisation, step C: `badge_scan_log.granted_by` (varchar(10), nullable; the rows of before set to `BADGE`)
  and `scanned_badge_code` nullable, store and head office, re-runnable (also at each start, `TillSchemaUpgrade`). The
  till rights are given to the roles by the first start, not by the script (it must read `ALWAYS_SHOW_BADGE_SCAN_POPUP`
  first). `user_account.badge_permissions` is kept, unused (deprecated, dropped in the next version).
- Till authorisation, step D: table `till_grant` on a store (the grants of the badge scans: token SHA-256, action,
  session, user who scanned, owner, badge code, issued, expires, consumed, by what); re-runnable, no data changed.
- Step E: `user_account.role` nullable (its type kept; also at each start, `TillSchemaUpgrade`), kept unused
  (deprecated, dropped in the next version); the users without an AppRole given the role of their enum's name. Store
  and head office, re-runnable.
- Discount sources of pending tickets: `sales_line` / `sales_header` `.discount_source` and `.promotion_id` made sure
  of (they exist already); nothing filled in, the pending tickets of before keep nulls. Re-runnable.
- ZEN's clearance price before VAT keeps 5 decimals: `ho_stock_point_item.clearance_price` `numeric(18,5)` (head
  office; a column made with 3 decimals widened, its values kept); re-runnable.
- `APP_VERSION` 3.1.0 and its notes.

### Config
- `rfid.enabled` (store, default false).
- `erp.connector=zen` and `erp.zen.*` (`base-url`, `company` or `company-id`, `domain`, `username`, `password`,
  `default-vat`, `packet-size`, `dry-run`, page names); deploy files `configs/customers/zen-headoffice.properties` and
  `zen-store.properties`.
- `erp.zen.default-vat`: the VAT of a stockPoints row without `vatPercent` (the page gives it since 2026-10-24).
- `erp.zen.page.barcodes` defaults to `barCodes` (step 12a; was `ItemBarCodePOS`).
- `erp.zen.invoices.*` (step 9, all optional): `page` (`salesInvoices`), `lines-expand` (`salesInvoiceLines`),
  `api-path` (`api/sat/franchise/v1.0`), `number-prefix` (`FVE`), `page-size` (50, 1 to 1000), `seller-name` (`ZEN`).
  The company as for the other pages (`company` or `company-id`: the invoices API is under the same `companies(<id>)`);
  `configs/customers/zen-headoffice.properties` names ZEN's company `ESP`.
- `headoffice.copies-down.read-timeout-seconds` (store, default 60): the read timeout of the pull of copies down.
- `erp.emtop.connect-timeout-seconds` (default 30) and `erp.emtop.read-timeout-seconds` (default 600): the emtop
  connector's NAV timeouts.
- General setup `CASHIER_CLOSING_TICKET` (`Z`, the default, or `DECLARED`).
- General setup `ALWAYS_SHOW_BADGE_SCAN_POPUP` removed (till authorisation, step C): no longer created; on a store its
  row is read once by the till rights migration, then deleted. `TILL_RIGHTS_MIGRATED` (read only): written once by that
  migration, with its date and the role × right matrix.
- General setup `TILL_GRANT_MAX_MINUTES` (store, default 240): the minutes a badge scan's grant stays good when no
  request used it (till authorisation, step D).

## 2.3.0 — 2026-10-11 (tag `v2.3.0`)

### Enhancements
- No functional change from 2.2.2: the store and the head office are two applications, each its WAR deployed as
  `zsretailws` (code cleanup, projects erp-api, connectors, core, store, headoffice, network-tests).

### DB script
- `APP_VERSION` 2.3.0 and its notes.

### Config
- The outside file names no type: remove `spring.profiles.active` and `node.type`; choose the ERP with
  `erp.connector`; rename `erp.dynamicsnav.*` to `erp.emtop.*` and `erp.navpospages.*` to `erp.happyness.*` (an old line
  stops the start, docs/deploy-note.md).

## 2.2.2 — 2026-10-10

### Features
- Returns with decimal quantities (when allowed), the amount the share of the line sold.
- Till: hide families and sub-families without an item to sell; a "Without sub-family" tile.
- Company information, "Sale ticket" block (address, footer text, thank-you line, "ZS Retail" mention).
- Store setting "Show amounts with 3 decimals on the POS screens".

### Enhancements
- An administrator is never asked for a badge at the till; at closing with a difference, the administrator sees the
  difference and confirms it.

### Fixes
- A fixed-amount promotion larger than the line no longer writes negative amounts; a decimal quantity typed when not
  allowed is refused with a message.

### DB script
- `return_line.quantity` and `ho_return_line.quantity` become DECIMAL(18,3); five nullable columns of the company
  information (the sale ticket's identity); `APP_VERSION` 2.2.2 (needs 2.2.1).

### Config
- General setup `POS_AMOUNTS_3_DECIMALS`, `POS_HIDE_EMPTY_FAMILIES` and `POS_HIDE_EMPTY_SUB_FAMILIES`, created at the
  first start, off.

## 2.2.1 — 2026-10-10

### Features
- Decimal quantities (up to 3 decimals) at the till, in the stock and its movements, the inventory count, the receptions
  and the head office's tickets, stock and ERP invoices.

### Enhancements
- The line amount of a decimal quantity is computed by the server (unit price with VAT × quantity, to the millime);
  pages show decimals untruncated; where decimals are not possible yet, a refusal names the item.

### Fixes
- Tax stamp: one rule for the till and the server.

### DB script
- The quantity columns (stock, sales lines, stock movements, head office copies, inventory counts, ERP invoices, BLs and
  receptions, purchase invoices) become DECIMAL(18,3), one transaction per column; `APP_VERSION` only when all are done.

### Config
- General setup `ALLOW_DECIMAL_QUANTITY`, created at the first start, off (off: as in 2.2.0).

## 2.2.0 — 2026-10-09

### Features
- Head office with the ERP catalogue: several points de stock (one ERP location each), read by the items job; each
  store receives only the items of its point, with their name, family and price there.

### Enhancements
- ERP invoices: a warning names the items not in the store's point de stock.

### DB script
- `ho_store.stock_point_id` and the last read columns of `ho_stock_point`; the tables `ho_stock_point` and
  `ho_stock_point_item` are created by Hibernate at the first start.

### Config
- `erp.navpospages.location-code`, `invoices.years` and `invoices.start-number` are no longer read (points created
  at the head office; the start number in General setup).

## 2.1.0 — 2026-10-08

### Features
- Head office: a new installation type that manages stores and never sells (stores, keys, their sales, returns and
  sessions); promotions and loyalty decided by the head office (one member register for the network); items and selling
  prices decided by the head office (base prices, price lists per store); supply by the head office (purchases, stock,
  BLs, reception at the store, the stock of every store); invoicing of deliveries to franchise stores; ERP invoices read
  from Business Central for a head office with the ERP catalogue.
- Inventory count imported from an Excel file.

### Enhancements
- A manual stock adjustment records its movement; editing an item no longer replaces its stock.

### DB script
- New nullable columns of existing tables; the `ho_*` (head office) and `hol_*` (store linked to one) tables are
  created by Hibernate at the first start; nothing dropped (the franchise columns stay, unused).

### Config
- One outside file per installation (database, port, log, ERP, head office address and key) and a preset (store, store
  ERP, head office, head office ERP, network store, network store ERP); the franchise profiles removed.

## 1.12.1 — 2026-10-12 (tag `v1.12.1`, hotfix of 1.12.0)

### Features
- Payment method "Retenue à la source" (`ENCAISS-RS-CLT`) for EMTOP.

## 1.12.0 — 2026-10-02

### Features
- Promotions: a new scope "All items", for an item no narrower promotion covers.
- Loyalty: earning tiers by ticket amount.

### Fixes
- Pending tickets earn and use loyalty points like a direct sale, with the tax stamp line and the loyalty block.
- Points conversion: the same ceiling at the till and on the server.
- Returns: only the points earned by the items returned are taken back, the points used are given back in proportion.

## Before 1.12.0

1.2.5 to 1.11.0: the changes are described in the release notes of each `db/<version>/update.sql`.
