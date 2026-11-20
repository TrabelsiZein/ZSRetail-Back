# Communication (3.3.0)

**Status**: step 1 (the switch and the two tables), step 2 (the store finds its events and writes `comm_event` rows),
step 3 (rules and templates make the messages, a test provider "sends" them; channels in the properties), step 4 (the
Communication page: messages, rules, templates), step 5 (the member's consent; rules created from the page), step 6
(the head office shape: a store whose loyalty is the head office's pushes its events up, the head office makes and
sends the messages). No campaign, no member language, no real provider yet.

Messages to loyalty members by WhatsApp, SMS and email: two automatic events in version 1 ("loyalty member created",
"ticket validated for a member") and a simple campaign (one template to every member with consent). The till never
waits for a provider: the events are found later by a query, as the sales copies are (`head-office.md`, "Sales
copies"); nothing is hooked into the selling services.

## The design (agreed after the read-only report of 2026-11-20)

- **Two queues.** `comm_event` holds what happened, `comm_message` holds what to send. An event makes zero or more
  messages; a campaign message has no event.
- **Events are found where they happen**: at the store for tickets and enrolments at the store, at the head office
  for a member enrolled on its pages.
- **Rules, templates, consent and sending live with the owner of loyalty** (`ownership.loyalty`). On a store with
  loyalty `LOCAL` (EMTOP), the store does everything. On a store whose loyalty is the head office's (ZEN, ParaFendri,
  Happyness when so configured), the store only sends its events up, and the head office makes and sends the messages.
- **Messages are sent by a test channel first**, then the real providers.

## Step 1: the switch

`communication.enabled`, a store and a head office alike. Core's `application.properties` says `false`; an
installation turns it on with `true` in its outside file. Absent or empty is off too. Any other value stops the
startup (`NodeOwnership.isCommunicationSet`, run by `NodeOwnership.resolve`). The question for the beans of the
next steps is `Installation.COMMUNICATION` (`@ConditionalOnInstallation`). Off, the module does nothing and the
application behaves as 3.2.0. On, the startup summary adds `communication on`, and step 1 has nothing else to do.
Details: `../deployment-modes.md`, section 2. Test: `CommunicationKeyTest`; the key is in the record of
`ConfigurationKeysProofTest`.

## Step 1: the tables

Created by `db/3.3.0/update.sql` in the form Hibernate gives them (and by Hibernate on a database without them). They
exist on a store and on a head office. Entities and repositories are in core, under
`core/communication/{model,repository,enumeration}`. Every text column of the module is `nvarchar` (Arabic); the
audit columns that every entity has (`created_by`, `updated_by`, from `_BaseEntity`) stay `varchar(255)` as
Hibernate makes them. The types, channels and providers are plain texts, not Java enums: a row received from a
store of another version must stay readable (an unknown enum value breaks every read of the table, as
`erp_sync_job` showed). Only the statuses, which never travel, are enums.

### `comm_event` (`CommunicationEvent`): what happened

| Column | Type | Meaning |
|---|---|---|
| `id`, `active`, `created_at`, `created_by`, `updated_at`, `updated_by` | `_BaseEntity` | `created_at`: when the row was written here (found, or received from a store) |
| `event_type` | nvarchar(50), not null | `MEMBER_CREATED`, `TICKET_VALIDATED` |
| `source_type` | nvarchar(50), not null | `LOYALTY_MEMBER`, `SALES_HEADER` |
| `source_id` | bigint | the source's id in the database of `origin_store_code` |
| `dedup_key` | nvarchar(200), not null, unique `uk_comm_event_dedup` | once per event, e.g. `TICKET_VALIDATED:STORE-B:12345` (the store code keeps it unique at the head office) |
| `recipient_type` | nvarchar(30) | `LOYALTY_MEMBER` (later the store's users); null when no one |
| `recipient_ref` | nvarchar(100) | the card number (the same at the store and at the head office) |
| `payload` | nvarchar(max) | the values at that moment, JSON (ticket number, amount, points earned, balance...) |
| `origin_store_code` | nvarchar(50) | the store where it happened (`DEFAULT_LOCATION`); at a head office, the store that sent it |
| `occurred_at` | datetime2, not null | clock of the installation where it happened |
| `status` | nvarchar(10), not null | `PENDING`, `PROCESSED` (messages made here, or accepted by the head office), `SKIPPED` (nothing to send), `ERROR` (retried, reason in `last_error`) |
| `attempts` | int, not null | handlings or pushes that got an answer |
| `last_error` | nvarchar(1000) | the last reason |
| `processed_at` | datetime2 | when it left `PENDING` / `ERROR` |

Index `ix_comm_event_queue (status, created_at)`.

### `comm_message` (`CommunicationMessage`): what to send

| Column | Type | Meaning |
|---|---|---|
| `id`, `active`, `created_at`, `created_by`, `updated_at`, `updated_by` | `_BaseEntity` | |
| `event_id` | bigint, index `ix_comm_message_event` | the `comm_event` that made it; null for a campaign (no foreign key: the two tables can be purged on their own) |
| `channel` | nvarchar(20), not null | `WHATSAPP`, `SMS`, `EMAIL` |
| `provider` | nvarchar(30) | who sends it, `TEST` first |
| `recipient_type` | nvarchar(30), not null | `LOYALTY_MEMBER` |
| `recipient_ref` | nvarchar(100) | the card number: consent is read again at send time |
| `address` | nvarchar(255), not null | copied when made: a phone as `+21612345678`, or an email |
| `subject` | nvarchar(255) | email only |
| `body` | nvarchar(max) | the text, made when the message is made |
| `template_code` | nvarchar(50) | the template used |
| `language_code` | nvarchar(5) | `ar`, `fr`, `en` |
| `status` | nvarchar(10), not null | `PENDING`, `SENT` (accepted by the provider), `FAILED` (for good), `EXPIRED` (not sent before `expires_at`), `SKIPPED` |
| `attempts` | int, not null | |
| `next_attempt_at` | datetime2 | not sent before (null: at once) |
| `expires_at` | datetime2 | never sent after |
| `provider_message_id` | nvarchar(255) | the provider's id |
| `last_error` | nvarchar(1000) | |
| `dedup_key` | nvarchar(200), not null, unique `uk_comm_message_dedup` | once per message |
| `sent_at` | datetime2 | when the provider accepted it |

Index `ix_comm_message_queue (status, next_attempt_at)`.

Index `ix_comm_event_source (source_type, source_id)` (step 2): does this ticket, this member have its event.

### `comm_state` (`CommunicationState`, step 2): the module's state

One row per `code` (nvarchar(50), unique `uk_comm_state_code`), with `time_value` (datetime2) and `id_value` (bigint).
`ACTIVE_SINCE`: the instant of the first start with the module on, written by the application (no configuration
line), never moved: turning the module off and on again keeps it, and nothing older is ever picked.
`TICKET_CURSOR`: the ticket finder's cursor (updated_at and id of the last ticket read).

## Step 2: the scheduler and the store's two finders

**The scheduler** (`core/communication/scheduler/CommunicationScheduler`), built like the head office link's
`LinkJobScheduler`: one thread `comm-1`, its pool not a bean; the first cycle 30 s after the start, then
`communication.cycle-seconds` after the end of the previous one (absent or empty: 60; otherwise a whole number of at
least 1, anything else stops the start). A cycle runs every `CommunicationTask` bean in its `@Order`; a task stops
starting new work 20 s after the cycle began. A task that throws is logged (WARN, once per cycle) and the next task
and the next cycle go on. At its start it writes `ACTIVE_SINCE` when the row is absent. Log: one INFO line at start
(`Communication: on since ..., a cycle every 60 s on comm-1: TICKET_EVENTS, MEMBER_EVENTS`), one INFO line per task
that wrote something. The scheduler, `CommunicationStates`, the source and the finders exist only with
`Installation.COMMUNICATION`: off, no bean and no thread (`InstallationBeansProofTest`: none in any configuration,
5 beans in the shape "store, communication on", 2 in "head office, communication on").

Nothing is hooked into the selling services or `LoyaltyService`: the finders read the tables
(`store/communication/JpaStoreEventSource`, JPQL, read only), as the sales copies do.

**Tickets: `TICKET_VALIDATED`** (`TicketEventFinder`, order 1, code `TICKET_EVENTS`).
- Picked: `sales_header` rows with status `COMPLETED`, a loyalty member, `completed_date` from `ACTIVE_SINCE`; read by
  a cursor on (`updated_at`, id) starting at `ACTIVE_SINCE`, the rows changed less than 30 s ago read at a later cycle
  (`updated_at` is written before the sale's transaction commits), 200 per page.
- A parked ticket completed later has an old id but a new `updated_at`: it is read. A ticket with a member and 0 points
  gives its event (the ticket is watched, not `loyalty_transaction`).
- Skipped: `PENDING` (parked, not paid: read when it is completed), `CANCELLED` (a parked ticket dropped, never
  paid), `REFUNDED` (no 3.3.0 code sets it; a ticket refunded later keeps the event made when it was completed),
  tickets without a member (split and pay makes such tickets), tickets completed before `ACTIVE_SINCE` even when
  changed after it. Returns are `return_header` rows, not watched.
- Once per ticket: a ticket read again (the ERP export, the head office push or an invoice moving its `updated_at`)
  already has its event, found by `source_type` + `source_id` (so a later change of the store code cannot make a
  second one); the unique dedup key `TICKET_VALIDATED:<store code>:<ticket id>` keeps it unique at the head office.
- `recipient_type` `LOYALTY_MEMBER`, `recipient_ref` the card, `occurred_at` the completion, `origin_store_code`
  `DEFAULT_LOCATION`. Payload: `ticketNumber`, `ticketDate` (completion), `total`, `pointsEarned`, `pointsSpent`,
  `pointsDeduction` (the money the points paid), `balanceAfter` (the sale's last `loyalty_transaction.balance_after`;
  a sale without a loyalty row, the member's balance when the event is written), `cardNumber`, `firstName`,
  `lastName`, `storeCode`, `storeName` (the location of `DEFAULT_LOCATION`), `companyName` (Company information). No
  phone or email: the address is read when the message is made.

**Members: `MEMBER_CREATED`** (`MemberEventFinder`, order 2, code `MEMBER_EVENTS`).
- Picked: active members created from `ACTIVE_SINCE` without an event yet (by `source_type` + `source_id`, no cursor:
  the table is small), by id, 200 per page. Loyalty `LOCAL`: the members made here (origin not `HEAD_OFFICE`, so a card
  of the network register left from before is not). Loyalty owned by the head office
  (`Installation.LOYALTY_FROM_HEAD_OFFICE`): the members with a `hol_loyalty_member_copy` row (enrolled here) whose
  outcome is not `MERGED`; never a copy received from the head office (no such row), never a deactivated duplicate.
- The trap: the head office decides a merge (same phone) when the `LOYALTY_PUSH` job sends the member (every 10 min),
  after this finder (every minute). A card merged later keeps its event; the head office, which will handle the
  events of such a store, drops `MEMBER_CREATED` of an alias card (later step).
- Dedup key `MEMBER_CREATED:<card>`. Payload: `cardNumber`, `firstName`, `lastName`, `pointsBalance`, `enrolledAt`,
  `storeCode`, `storeName`, `companyName`.

Both finders write nothing while `DEFAULT_LOCATION` is empty (one WARN line); nothing is lost, the rows are found once
it is set. The events stay `PENDING`.

Tests: `CommunicationEventFindersTest` (the two finders on in-memory lists read with the queries' rules),
`StoreMemberEventsQueryTest` (the member queries on H2), `CommunicationSchedulerTest`, `InstallationBeansProofTest`.
The ticket query (it needs the whole sale graph) is proven by the run on the PC.

**Proof on the PC (2026-11-20, `pos_franchise_customer`, store FKAIROUAN, loyalty LOCAL)**: `communication.enabled=true`
in the local file `configs/local/test_happyness_store.properties`; at the start `ACTIVE_SINCE` 16:42:15. In Chrome: one
member enrolled at the till (LYL-000001), one sale with it (FKAIROUAN261120001, 65 TND, 65 points), one sale without a
member (FKAIROUAN261120002). After the cycles: 2 `comm_event` rows, `MEMBER_CREATED:LYL-000001` and
`TICKET_VALIDATED:FKAIROUAN:490` (`balanceAfter` 65 from the sale's EARNED row), both `PENDING`; none for the sale
without a member, none for the 489 tickets of before; `TICKET_CURSOR` on ticket 490; no ERROR line. `storeName` is
null there: that store has no `location` row (its catalogue and supply are the head office's), a point for the
message step.

## Step 3: from the event to a message sent by the test provider

**The property lines** (all in core's `application.properties`; `communication.enabled` is the master: off, none of
them is read; `CommunicationSettings`, read once at the start, one INFO line `Communication: channels whatsapp off,
sms on (test), email off, phone country code 216`):

| Key | Values | Absent or empty |
|---|---|---|
| `communication.whatsapp.enabled`, `communication.sms.enabled`, `communication.email.enabled` | `true` / `false` (any case); another value is read as off with one WARN line, never a refused start | `false` |
| `communication.<channel>.provider` | `test` (the only one in step 3); an unknown name stops the start: `Invalid value 'x' for property communication.sms.provider: the providers are test (absent or empty: test)` | `test` |
| `communication.phone-country-code` | 1 to 3 digits, a `+` accepted; anything else stops the start | `216` |

**Where it runs**: `Installation.COMMUNICATION_SENDS_HERE`, the module on, on a store whose loyalty is not the head
office's (a store alone, or loyalty `LOCAL`). On a store whose loyalty is the head office's the finders still write
the events, but there is no processor, no sender and no seeding: the events stay `PENDING` for the head office step.
A head office does not send yet. (`InstallationBeansProofTest`, shape "store, loyalty from the head office,
communication on".)

### `comm_template` (`CommunicationTemplate`) and `comm_rule` (`CommunicationRule`)

| `comm_template` column | Type | Meaning |
|---|---|---|
| `code` | nvarchar(50), not null, unique `uk_comm_template_code` | e.g. `TICKET_THANKS_SMS_FR` |
| `name` | nvarchar(100), not null | for the staff |
| `channel` | nvarchar(20), not null | `WHATSAPP`, `SMS`, `EMAIL` |
| `language_code` | nvarchar(5), not null | `fr`, `ar`, `en` |
| `subject` | nvarchar(255) | email only |
| `body` | nvarchar(max), not null | the text with `{fields}` |
| `provider_template_name` | nvarchar(100) | WhatsApp later: the template approved at the provider |
| `active` (`_BaseEntity`) | bit | on or off |

| `comm_rule` column | Type | Meaning |
|---|---|---|
| `code` | nvarchar(50), not null, unique `uk_comm_rule_code` | e.g. `TICKET_THANKS_SMS` |
| `event_type` | nvarchar(50), not null | `MEMBER_CREATED`, `TICKET_VALIDATED` |
| `recipient_type` | nvarchar(30), not null | `LOYALTY_MEMBER` |
| `channel` | nvarchar(20), not null | must be the template's |
| `template_code` | nvarchar(50), not null | `comm_template.code` |
| `lifetime_hours` | int, not null | a message is never sent more than this after its **event happened** (24) |
| `active` (`_BaseEntity`) | bit | on or off |

**Seeded at the start** (`CommunicationSeed`, where it sends), only when the code is missing, **inactive**; a row that
exists is never touched:
- `MEMBER_CARD_SMS_FR` / rule `MEMBER_CARD_SMS` (`MEMBER_CREATED`): `Bienvenue {firstName} ! Votre carte de fidélité
  {companyName} : {cardNumber}. Présentez-la à chaque achat pour cumuler des points.`
- `TICKET_THANKS_SMS_FR` / rule `TICKET_THANKS_SMS` (`TICKET_VALIDATED`): `Merci {firstName} pour votre achat chez
  {companyName} ({total} TND) : +{pointsEarned} points, solde {balanceAfter} points (carte {cardNumber}).`

Both use only the basic SMS alphabet (no ç, ê, ô: one of them makes the whole SMS Unicode, 70 characters a part) and
stay in one part (160) with long usual values (`CommunicationSettingsTest.seededTexts`).

### The processor (`CommunicationEventProcessor`, order 10, code `MESSAGES_MADE`)

Each `PENDING` event (and `ERROR` with fewer than 5 attempts), by id, each in its own transaction, until the cycle's
bound:
- No active rule of its type: `SKIPPED`, `no active rule for TICKET_VALIDATED`. The member (by the card,
  `recipient_ref`) not found or inactive: `SKIPPED`.
- Each active rule of its type: its channel off, its template missing or inactive or of another channel, no address,
  or the event older than the rule's lifetime: no message, the reason (`TICKET_THANKS_SMS: channel sms off`). Else one
  `PENDING` message: the address read from the member now (SMS and WhatsApp `+` country code + the 8 digits, only when
  the phone is 8 digits; email only when it looks like one), the text from the template and the payload, the
  template's code and language, the channel's provider, `recipient_ref` the card, `expires_at` the event's
  `occurred_at` plus the lifetime, dedup key `<event id>:<rule code>` (a second pass makes nothing).
- `PROCESSED` when it has at least one message (the reasons of the other rules kept in `last_error`), else `SKIPPED`
  with the reasons. A technical failure: `ERROR`, one more attempt, the cause; tried again at the next cycles, at most
  5 times; the other events go on.
- Rules being inactive by default, with the module and a channel on every event is `SKIPPED` until a rule is turned
  on; turning a rule on later never sends the events of before.

**The fields** (`CommunicationTemplates`): `firstName`, `lastName`, `cardNumber`, `ticketNumber`, `total` (3
decimals), `pointsEarned`, `pointsSpent`, `balanceAfter`, `storeName`, `companyName`. A field without a value gives an
empty text, never `null`; a field not in the list is left out (empty) and logged once per template.

### The sender (`CommunicationSender`, order 20, code `MESSAGES_SENT`) and the providers

The `PENDING` messages due (`next_attempt_at` empty or passed), by id, 50 a page, one at a time, until the cycle's
bound:
- Past `expires_at`: `EXPIRED`, not sent. Its channel off now: `SKIPPED` (switching a channel off also stops the
  messages already waiting). Its provider unknown: `FAILED`.
- `CommunicationProvider.send`: accepted, `SENT`, `sent_at`, `provider_message_id`; a temporary failure (or a throw):
  one more attempt, `next_attempt_at` 1, 5, 15 then 60 minutes later, `FAILED` at the 5th; a permanent failure:
  `FAILED` at once. `last_error` says why.
- **The provider `test`** (`TestCommunicationProvider`): sends nothing, one INFO line (the address masked,
  `+2169****002`), accepted as `TEST-<message id>-<8 characters>`.
- Trap for the real providers: if the application stops after a provider accepted a message and before `SENT` is
  saved, the message is sent again at the next start (at least once). Step 8: give the provider our dedup key as its
  idempotency key where it has one, or add a SENDING status.

**Proof on the PC (2026-11-20, `pos_franchise_customer`)**: `communication.sms.enabled=true` in the local file, the
script run again (the two tables), `LOYALTY_ENABLED` on for the test; the start wrote the 4 rows inactive, turned on by
SQL before the first cycle. First cycle: the 2 `PENDING` events of step 2 became 2 messages `SENT` by `test`
(`TEST-1-...`, `TEST-2-...`, `+21699000002`, 117 and 105 characters, the accents stored as Unicode). A new sale with
the member (FKAIROUAN261120003, 17:56:31): its message `SENT` at 17:57:16. Then `communication.sms.enabled=false` and a
restart: the next sale with the member (FKAIROUAN261120004) gave an event `SKIPPED`, `TICKET_THANKS_SMS: channel sms
off`, no message. No ERROR line.

Tests: `CommunicationMessagesTest` (processor and sender on in-memory tables, a fake provider),
`CommunicationSettingsTest` (the lines, where it runs, the seeded texts, the fields), `InstallationBeansProofTest`,
`ConfigurationKeysProofTest` (keys 77).

## Step 4: the Communication page

Where the store sends (`Installation.COMMUNICATION_SENDS_HERE`; `ApplicationModeService.isCommunicationSentHere`):
`core/communication/controller/CommunicationAPI` and `core/communication/service/CommunicationAdmin`; the store answers
the ticket number search (`store/communication/StoreTicketSearch`, the interface `CommunicationTicketSearch` in core).
Elsewhere no endpoint (404) and no page.

**`GET /config`** gains `communication`: `{enabled, sendsHere, channels}` (`channels`: the channels switched on, e.g.
`["SMS"]`; empty when off). The front shows the page when `enabled && sendsHere` (never a head office).

**Rights**: `read:admin-communication` (every read, and the preview), `write:admin-communication` (every change); 403
otherwise. Given to ADMIN at every start where the store sends (`ZZDataInitializer.COMMUNICATION_ADMIN_PERMISSIONS`, as
the RFID pages); other roles are ticked on the Roles page (group "Communication", shown only there).

**Endpoints** (`/communication`; a refusal is `{error, code, params}`, 400 or 404 for a row not found, the front shows
`serverErrors.<code>`):

| Call | What |
|---|---|
| `GET /messages?status=&channel=&dateFrom=&dateTo=&q=&page=&size=` | messages newest first, `{content, totalElements, totalPages, number, size}`; `status` one or several joined by commas (`FAILED,EXPIRED`); dates on the day the message was made; `q` the card, the address or a ticket number (the store's tickets whose number contains it, through the event's `source_id`); each row with the member's name now |
| `GET /messages/{id}` | one message, its event (values read from the payload) |
| `POST /messages/{id}/resend` | a FAILED or EXPIRED message: `PENDING`, attempts 0, no next attempt, a new lifetime from now (its rule's, the code after the `:` of its dedup key; 24 h when gone), the channel's provider now, **the address read again from the member** (a phone corrected on the card is used; a member gone keeps the message's address; a member without a valid one now: refused); `last_error` keeps the history: `resent 2026-11-21 10:00 by admin (FAILED after 5 attempt(s)); before: ...`. Refused while its channel is off |
| `GET /events?status=SKIPPED\|ERROR&dateFrom=&dateTo=&q=` | the events that made no message (`SKIPPED`) or failed (`ERROR`), with the reason |
| `GET /counters` | `sentToday` (made today, SENT), `waiting` (PENDING), `failed` (FAILED or EXPIRED, any day: sending one again takes it out), `skippedToday` (events of today SKIPPED); each the count of a list filter |
| `GET /rules`, `GET /rules/{id}`, `PUT /rules/{id}` `{channel, templateCode, lifetimeHours}`, `PUT /rules/{id}/active` `{active}` | the seeded rules; no create, no delete (switched off) |
| `GET /templates`, `GET /templates/{id}`, `POST /templates`, `PUT /templates/{id}`, `PUT /templates/{id}/active` `{active}` | no delete (switched off: the codes stay in the messages) |
| `POST /templates/preview` `{channel, body}` | the body filled with sample values (the company's name is the installation's): `{text, length, encoding, parts, unknownFields}`; SMS: GSM 7-bit 160 / 153 a part (an extension character counts two), Unicode 70 / 67 (`SmsParts`) |
| `GET /templates/fields` | the fields in order with their sample values (the buttons) |

**The checks** (`CommunicationAdmin`, codes in `CommunicationException`):
- A rule: its template exists and is on the rule's channel; an active rule also needs its channel on and its template
  active (an inactive rule may point to a template still off: switch the template on first, then the rule); lifetime 1
  to 720 hours. On: its channel on and its template active. Off: always.
- A template: code `A-Z 0-9 _`, 2 to 50, unique (any case), fixed once created; name required (100); language fr, ar
  or en; email: a subject; WhatsApp: the provider's template name (kept for step 8); body required (2000), an unknown
  `{field}` refused with its name (`COMM_TEMPLATE_UNKNOWN_FIELD`). Created on a channel switched on, created on. Its
  channel changes only to a channel on and only when no rule uses it. Off refused while an active rule uses it; on
  only with its channel on.
- A rule or template on a channel switched off in the properties stays listed, marked, and cannot be switched on.

**The page** (front `views/admin/communication/`, route `admin-communication`, menu Clients › Communication, the
search bar; `COMMUNICATION_ROUTES`): the banner with the channels on; three tabs in General setup's look, on top.
- Messages: the four counters (a click shows their rows), the switch "Messages | Événements ignorés", the filters, the
  list (date, channel, member and card, address, template, status, attempts), the wide detail window (text, history,
  event and its values, dates, provider id) with "Renvoyer" (write right, FAILED or EXPIRED, its channel on). The known
  skip reasons are shown in the screen's language (`services/communication.js` `reasonText`), any other as written.
- Règles: event in clear words, channel, template, lifetime, on / off switch; a wide edit window (only the channels on,
  the templates of the channel).
- Modèles: code, name (and the rule that uses it), channel, language, on / off, start of the text; a wide window with a
  button per field (inserted at the cursor), the live preview from the backend, its length and SMS parts.
- The read right alone: everything read only (the eye, no switch, no button).

Tests: `CommunicationAdminTest` (resend, the rules' and templates' checks, preview, SMS parts), `AppConfigAPITest`
(`communication`), `ZZDataInitializerRolesTest` (the two rights), `InstallationBeansProofTest` (store, communication on:
13 beans). Front: `tools/store-menu-check.mjs` (the page shown only with the module sending here and the read right,
the search bar), `tools/i18n-keys-check.mjs`. The lists (JPA criteria) are proven by the run on the PC.

**Proof on the PC (2026-11-20, `pos_franchise_customer`, store FKAIROUAN)**, admin logged in again (the browser held
the rights of a login made before ADMIN got the two keys):
1. Menu Clients › Communication and the three tabs in fr, ar (right to left) and en.
2. Messages: the 3 SENT messages and the SKIPPED event of step 3 ("TICKET_THANKS_SMS : canal SMS désactivé"), the
   counters 3 / 0 / 0 / 1; the search "120003" found the message of FKAIROUAN261120003; its detail window.
3. Modèles: the thanks text edited with the field button "Solde" inserted at the cursor (preview 106 characters, 1 SMS,
   GSM 7-bit), saved; a new template `MEMBER_CARD_SMS_AR` (Arabic, 94 characters, 2 SMS Unicode); `{prenom}` shown in
   red and refused by the API (400 `COMM_TEMPLATE_UNKNOWN_FIELD`, `{prenom}`); switching off the thanks template while
   its rule is on refused.
4. Règles: the welcome rule off (database `active` 0, `updated_by` admin), then on.
5. `LOYALTY_ENABLED` on, a sale with LYL-000001 (FKAIROUAN261120005, 21:29:09): message 4 SENT at 21:29:44 with the
   edited text ("Merci TEST ! Votre achat chez Happyness : 48.900 TND, +48 points. Nouveau solde : 209 points. A
   bientot !"), in the list.
6. A role with `read:admin-communication` only: the page read only, a change by hand 403; a role without it: no menu
   line, the address typed by hand sent to "not authorized", the API 403.
7. `communication.enabled=false` and a restart: `/config` off, no menu line, the address sent to the dashboard; the
   line back to true.
No ERROR in the back-end log, no console error.

## Step 5: the member's consent, rules created

**The field** (`loyalty_member`, the member table of a store and of a head office; `db/3.3.0/update.sql`, section 6):
`message_consent` (bit, not null, default 0: every existing member has none), `message_consent_at` (the last change),
`message_consent_by` (who made it: a user, `STORE:RS01/sami` through the head office). A member field like the phone:
it exists with the module on or off. The store's copy table `hol_loyalty_member_copy` holds no member content (the push
reads the member), the head office builds each copy down from the member: no other table has it.

**One rule** (`core/model/MessageConsent`): `apply` (a change asked: null asks nothing, the same value keeps its date, a
new one gets now and the user), `copy` (the owner's value as it is; null, from an older version, changes nothing),
`mergeYes` (a merge by phone: a yes of the new enrolment is kept on the surviving card with its date when it is newer
than the card's last change; a no, or a yes older than a withdrawal recorded at the head office, changes nothing).

**Where it changes**: the till's enrolment window and the members page (create, edit; the same Vue page on a store and a
head office), with the right to edit a member, through the same paths as the other fields:
- loyalty `LOCAL` (and the head office's own page): `LoyaltyService.createMember`, `updateMember`
  (`CreateLoyaltyMemberRequestDTO.messageConsent`, null when the tick is not shown);
- loyalty owned by the head office: the enrolment is made here and pushed (`LoyaltyMemberCopyDTO` carries
  `messageConsent`, `messageConsentAt`, `messageConsentBy`); the head office creates the member with it
  (`HoLoyaltyReceiver.receiveMember`) or, on a merge, `mergeYes` on the card that holds the phone (then a copy down);
  an edit goes through `LoyaltyMemberEditDTO.messageConsent` / `messageConsentBy` (`StoreLoyaltyNetwork.edit`,
  `HoLoyaltyReceiver.applyEdit`; a deactivation or a link to a customer sends none: unchanged); the copies down and the
  head office's answers apply it with `copy` (`LoyaltyCopyWriter.saveMember`, part of the change detection).
- Version tolerance: both DTOs ignore unknown fields (an older receiver drops the consent); every receiver applies it
  only when present (an older sender sends none: nothing changes, nothing fails). With an older head office a consent
  given at a newer store stays on that store only.

**Screens** (only when `GET /config` says `communication.enabled`, also on a store that does not send here: its head
office will): the tick "Accepte de recevoir des messages (SMS, WhatsApp, e-mail)", unticked by default, in the till's
enrolment window and the members page's create and edit windows (the last change shown in edit and in the detail);
the members list: a Messages column, a filter (`GET /loyalty/members?consent=yes|no`) and the count of members with
consent (`consentCount`). The form sends the consent only when its tick is shown.

**The module**: the processor skips an event of a member without consent (`SKIPPED`, `no consent`, translated on the
page); the sender reads the consent again just before sending (`SKIPPED`, `no consent when it was to be sent`; also a
member gone: `member X not found when it was to be sent`); "send again" is refused (`COMM_RESEND_NO_CONSENT`).

**Rules created** (`POST /communication/rules` {code, eventType, channel, templateCode, lifetimeHours, active}; the
Règles tab's "Nouvelle règle"): code `A-Z 0-9 _` unique, a known event (`MEMBER_CREATED`, `TICKET_VALIDATED`), a channel
switched on, an active template of that channel, lifetime 1 to 720 h, on (default) or off. One rule per event +
channel + template (`COMM_RULE_DUPLICATE`, also when a rule is changed onto another's template). Never deleted. Trap:
two rules of one event and channel with two templates (French and Arabic) both send, so a member gets two messages
until members have a language.

Tests: `MessageConsentTest` (the rule; a store with loyalty `LOCAL`), `SharedLoyaltyConsentTest` (network-tests, the
harness of `SharedLoyaltyRoundTripTest`: up at enrolment, down, through the edit, missing values, merge yes / no /
withdrawn after), `CommunicationMessagesTest` (no consent, withdrawn before sending, member gone),
`CommunicationAdminTest` (send again refused, rules created). The loyalty tests of before are not changed
(`LoyaltyCardNumbersTest`, `LoyaltyEarningTiersTest`, `LoyaltyMemberFunctionTest`, `LoyaltyMemberPhoneTest`,
`HoLoyaltyRegisterTest`, `LoyaltyAPINetworkTest`, `SharedLoyaltyRoundTripTest`).

**Proof on the PC (2026-11-20, `pos_franchise_customer`, loyalty `LOCAL`)**: the script run twice (the three columns,
the one member without consent). `LOYALTY_ENABLED` on for the test.
1. LYL-000001 without consent, a sale (FKAIROUAN261120006): event 6 `SKIPPED` "no consent", no message.
2. The tick on the members page (Accepte, "Dernier changement 20/11/2026 22:08 par admin"), a sale (…007): message 5
   `SENT` with the thanks text.
3. At the till: TEST CONSENT OUI (99000003) with the tick: LYL-000002, its welcome message 6 `SENT`; TEST CONSENT NON
   (99000004) without: LYL-000003, event 9 `SKIPPED` "no consent".
4. The members list: the Messages column, "2 membres acceptent les messages", the filter yes (2) / no (1).
5. A rule MEMBER_CARD_SMS_BIS (welcome, SMS, MEMBER_CARD_SMS_FR) refused: duplicate of MEMBER_CARD_SMS; the rule
   MEMBER_CARD_SMS_AR (welcome, SMS, MEMBER_CARD_SMS_AR) accepted, saved off (two welcome SMS otherwise).
6. `communication.enabled=false` and a restart: no tick (till, create window), no column, no filter, no count; the line
   back to true. No ERROR in the back-end log, no console error.

## Step 6: the head office shape

**Who sends**: `NodeOwnership.isCommunicationSentHere` is now the module on where the loyalty is owned: a store alone, a
store with loyalty `LOCAL`, and a head office (same processor, sender, templates, rules and channel switches). A store
whose loyalty is the head office's pushes its events up (`Installation.COMMUNICATION_PUSHED_UP`): it makes no message
and has no Communication page nor its rights.

**The store's push** (`store/holink/service/CommunicationPushService`, link job `COMMUNICATION_PUSH`, every 60 s, editable
from the Jobs page; no property). The event rows are the queue, marked by their own status (no new table: the status
never travels, the copy `CommunicationEventCopyDTO` carries none): `PENDING` to send; `PROCESSED` taken by the head office
(stored, or already there: a duplicate is harmless, `uk_comm_event_dedup`); `ERROR` refused (attempts, `last_error`,
sent again at most 5 times); `SKIPPED` ended. The head office takes no events (its module off: `{taken: false}`; an older
head office without the endpoint: 404): the push pauses 1 h (one INFO line, one note in the exchange log), nothing
counted; the scheduled runs say "paused" (SUCCESS), and **Run now** on the Jobs page lifts the pause and asks at once
(`LinkJob.runNow`, e.g. once the head office's module is back on). An event never taken ends quietly 720 h after it happened (`never taken by the head office within 720 h`): the
longest lifetime a rule may have (the store does not know the head office's rules). An unreachable head office: as the
other link jobs.

**The head office** (`POST /ho/communication/events`, `HeadOfficeCommunicationAPI`, every head office of 3.3.0, under
the `/ho/**` chain): `HoCommunicationReceiver` stores each event once (by its dedup key), `origin_store_code` the calling
store, `occurred_at` the store's (the lifetime counts from it), `PENDING`; one that happened before the head office's
`ACTIVE_SINCE` is stored `SKIPPED` (`happened before the communication module was on at the head office`), so nothing
older is ever messaged and the store never sends it again. Members (`HoCommunicationMembers`, address and consent read
from the head office's member at processing and again before sending):
- Order of arrival: a card the head office does not know yet leaves its event `PENDING` (`member X not at the head
  office yet: tried again`, no attempt counted) while the longest lifetime of the active rules of its type has not
  passed since it happened; then `SKIPPED` (`member X never reached the head office within N h`). A store alone still
  skips at once.
- Merged cards (`ho_loyalty_alias`): `MEMBER_CREATED` of an alias card is `SKIPPED` (`card X merged into Y: no welcome
  for a merged card`); `TICKET_VALIDATED` goes to the surviving member (his card on the message, his phone, his consent).
- One welcome per member: a store's enrolment has its store's `MEMBER_CREATED` only (the receiver of members writes no
  event, whatever its outcome CREATED, EXISTS or MERGED); a member created on the head office's own pages has the head
  office's (`HoMemberEventFinder`: active members created from `ACTIVE_SINCE` whose author is not `STORE:<code>`, without
  an event of their card); the unique key `MEMBER_CREATED:<card>` is the same on both sides. Tickets: stores only.

**The head office's page**: route `admin-headoffice-communication` (twin of `admin-communication`, menu Clients, only
with `communicationShown`), rights `read:admin-communication` and `write:admin-communication` (the store's keys) given to
ADMIN at start where it sends, listed on its Roles page (`meta.writePermission`). Messages and events left out show the
store (code and name; "Siège" for the head office's own) and have a store filter (`store=<code>`, `__HO__` for its own);
the counters follow it (`GET /communication/counters?store=`); `GET /communication/stores`.

Tests: `SharedCommunicationRoundTripTest` (network-tests: duplicate push, event before member, alias card for both
events, activation instant, module off then an older head office, "Run now" lifting the pause, the quiet end), with
`support/InMemoryCommunication`. The rule "sends here" changed on purpose: `CommunicationSettingsTest.sendsHere`,
`AppConfigAPITest.communication` and `ZZDataInitializerRolesTest.communicationPageGivenToAdmin` now expect a head office
with the module on to send and its ADMIN to get the page (a head office always owns its loyalty: the start refuses
`ownership.loyalty=HEAD_OFFICE` there, and loyalty has no ERP owner); `InstallationBeansProofTest` records 130 beans.

Screen test (2026-11-20, the ZEN pair `configs/local/test_zen_store` + `test_zen_ho`, databases `zen_store` and
`zen_headoffice` brought from 3.1.1 to 3.3.0 by the scripts of 3.1.2, 3.2.0 and 3.3.0): the 7 points passed, 0 back-end
ERROR, 0 console error. Run the WARs exploded (`WarLauncher`, as `devenv/start.ps1`): a WAR run packed with `java -jar`
reads each first class lookup through its nested jars, and the first requests can take 30 to 45 s (a push timed out
once at 10 s; its duplicate was answered "already at the head office").

## Next steps (to be agreed one at a time)

The member's language (it travels with the
shared loyalty register; templates then need a key on code and language; a rule then needs a purpose, service or
marketing), step 6 the head office side (the events of its stores whose loyalty it owns), the campaign (a
`campaign_id` on the message), step 8 the real providers.
