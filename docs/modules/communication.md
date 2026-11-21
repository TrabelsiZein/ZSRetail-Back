# Communication (3.3.0)

**Status**: step 1 (the switch and the two tables), step 2 (the store finds its events and writes `comm_event` rows),
step 3 (rules and templates make the messages, a test provider "sends" them; channels in the properties), step 4 (the
Communication page: messages, rules, templates), step 5 (the member's consent; rules created from the page), step 6
(the head office shape: a store whose loyalty is the head office's pushes its events up, the head office makes and
sends the messages), step 7 (the campaigns: one template to every member who accepts messages; the empty-field guard of
every message), step 8a (the email channel for real through SMTP, the test-recipient mode, no double send, the Canaux
tab, the right to launch a campaign). SMS and WhatsApp still on the test provider; no member language, no member filter
on a campaign.

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
| `provider` | nvarchar(30) | who sends it: `test`, step 8a `smtp` (email) |
| `recipient_type` | nvarchar(30), not null | `LOYALTY_MEMBER`; step 8a `TEST`, a test of the Canaux tab |
| `recipient_ref` | nvarchar(100) | the card number: consent is read again at send time |
| `address` | nvarchar(255), not null | copied when made: a phone as `+21612345678`, or an email |
| `subject` | nvarchar(255) | email only |
| `body` | nvarchar(max) | the text, made when the message is made |
| `template_code` | nvarchar(50) | the template used |
| `language_code` | nvarchar(5) | `ar`, `fr`, `en` |
| `status` | nvarchar(10), not null | `PENDING`, step 8a `SENDING` (written before the provider is called), `SENT` (accepted by the provider), `FAILED` (for good), `EXPIRED` (not sent before `expires_at`), `SKIPPED` |
| `attempts` | int, not null | |
| `next_attempt_at` | datetime2 | not sent before (null: at once) |
| `expires_at` | datetime2 | never sent after |
| `provider_message_id` | nvarchar(255) | the provider's id |
| `last_error` | nvarchar(1000) | |
| `dedup_key` | nvarchar(200), not null, unique `uk_comm_message_dedup` | once per message |
| `sent_at` | datetime2 | when the provider accepted it |
| `redirected_to` | nvarchar(255) | step 8a: the test address the provider was given instead of `address` (the test-recipient mode); null: the member's |

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
- Trap for the real providers (closed by step 8a): if the application stopped after a provider accepted a message and
  before `SENT` was saved, the message was sent again at the next start. Now `SENDING` is saved before the provider is
  called, and a message found `SENDING` at the start is `FAILED` "interrupted", sent again by hand only ("Step 8a").

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

## Step 7: the campaigns

A campaign sends one template, once, to every member who accepts messages: a promotion, a piece of news. Only where
the messages are made (`COMMUNICATION_SENDS_HERE`: a store alone, a store that owns its loyalty, a head office); a
store whose loyalty is the head office's has no tab and no API (404). It needs the module on; it does not depend on
the activation instant. Rights: the read right of the page; launch and cancel: `write:admin-communication` until step
8a, since then their own right `write:admin-communication-campaigns` ("Step 8a").

**Tables** (`db/3.3.0/update.sql`, sections 7 and 8, safe to run twice): `comm_campaign` (`CommunicationCampaign`):
`code` (CMP- and the launch instant, unique), `name`, `template_code`, `channel`, a copy of the template's
`language_code`, `subject` and `body` when it was launched (a template changed or switched off later changes nothing),
`status` RUNNING / DONE / CANCELLED (no draft: the window counts and previews before anything is written), `lifetime_hours`,
`targeted` (the count at the launch), `made`, `left_out`, `last_member_id` (the cursor of the making), `launched_at`,
`launched_by`, `made_at`, `ended_at`, `cancelled_by`. `comm_message.campaign_id` (null for an automatic message) and its
index `ix_comm_message_campaign (campaign_id, status)`.

**Who receives it** (`CampaignAudience`): one query, `LoyaltyMemberRepository.findCampaignCandidates` (active members
with the consent, not a copy of a head office's register (origin HEAD_OFFICE), by id), then for each member in id
order the same checks: a card merged into another member (a head office's `ho_loyalty_alias`) is never a recipient;
no address for the channel (8 digits for SMS and WhatsApp, an email: the rule of every message) left out; an address
met before (two members, one phone) left out, the first member by id keeps it; a field of the template without a
value left out. The window's count and the maker's messages read the same query and the same checks
(`CampaignAudienceQueryTest` runs the query on H2). The members without consent are counted by
`countActiveWithoutConsent`. A head office reaches the members of every store (no store filter in version 1).

**Fields** a campaign fills: `firstName`, `lastName`, `cardNumber`, `pointsBalance` (the member's balance now; a new
field, also in the welcome's payload), `companyName` (Company information), `storeName` (a store: its own name; a head
office: the store where the member enrolled, its author `STORE:<code>` or its card `LYL-<code>-...`, none for its own
members). A template with a sale's field (`ticketNumber`, `total`, `pointsEarned`, `pointsSpent`, `balanceAfter`) is not
offered and is refused (`COMM_CAMPAIGN_TEMPLATE_FIELDS`).

**Launch** (`POST /communication/campaigns`): refused with a coded reason: no name, a lifetime outside 1 to 720 h, a
template unknown, of another channel, switched off or with a sale's field, its channel off (`COMM_CHANNEL_OFF`),
nobody to receive it (`COMM_CAMPAIGN_NO_RECIPIENT`); the same template already launched today: 409
`COMM_CAMPAIGN_SAME_DAY` until the request says `confirmSameDay` (the window's tick). Launched: the row RUNNING with the
count of now, nothing else in the request.

**The making** (`CommunicationCampaignMaker`, task `CAMPAIGN_MESSAGES`, order 15: after the automatic messages are
made, before the sender), at most 8 s of each cycle: batches of 200 members by id, each in its own transaction with the
campaign's cursor, so a restart goes on after the last batch written. One message per campaign and address (dedup key
`CAMPAIGN:<id>:<address>`, unique): a batch made twice writes no double. Each message: its campaign, no event, the
address and the text with the member's values, `expires_at` = the launch plus the campaign's lifetime (24 h by
default, set in the window, 1 to 720 h). Every member read: `made_at`; the campaign DONE once none of its messages waits.
The speed of the making with a large member table is not measured yet (tested with up to 450 members in memory and 6 on
a database).

**The sender** (`CommunicationSender`): before step 7 it took the due messages by id, 50 at a time, until the cycle's
deadline. Now two queues in each cycle: the automatic messages first (`findDue(..., AUTOMATIC)`, no campaign), then
the campaigns' in the time left (`CAMPAIGN`): a campaign of thousands never delays a welcome or a thanks, which wait at
most one cycle. A cancelled campaign's message is not sent (its status read at most every 2 s). Before sending any
message to a member: the member gone, made inactive, without consent, or its address no longer the message's (a phone
corrected since): SKIPPED with the reason (step 5 read the consent only).

**Cancel** (`POST /communication/campaigns/{id}/cancel`, while RUNNING): CANCELLED, its waiting messages SKIPPED in one
statement ("campaign cancelled by <user> at <time>"), the ones sent stay; nothing more made.

**The empty-field guard** (every message, automatic and campaign; `CommunicationTemplates.withFallbacks`): the
company's name empty takes the store's; the store's name empty takes the company's; both empty, the licence holder's
name (the current licence). The member's fields missing in a payload are taken from the member. A field still empty:
the message is not made (an automatic one: the rule's reason `no value for {field}`; a campaign: the member left out,
counted). The previews show a field without a value as typed (`{companyName}`) and name it; the template window says
when Company information is empty.

**Page**: a fourth tab, Campagnes: the list (name, template, channel, date, who, targeted, sent, failed, skipped,
status; refreshed every 10 s while one runs), "Nouvelle campagne" (a wide window: name, channel, template (the others
listed with why they are not offered), lifetime; who receives it and who is left out and why; the preview with the
first member who would receive it; the warning of the same day and its tick; a confirmation), one campaign (its
counters, its text, its dates, Cancel while running, its messages: the Messages tab filtered on it). The Messages tab:
a Campaign column, a campaign filter (the counters follow it; the events left out belong to no campaign). A campaign's
message is the head office's own in the store filter. The seeded example: `PROMO_SMS_FR`, inactive, no rule.

**API** (read: the read right; launch and cancel: the write right): `GET /communication/campaigns?page=&size=`, `GET
/communication/campaigns/names`, `GET /communication/campaigns/templates`, `POST /communication/campaigns/audience`
{templateCode, channel}, `GET /communication/campaigns/{id}`, `POST /communication/campaigns`, `POST
/communication/campaigns/{id}/cancel`; `campaign=<id>` on `/messages` and `/counters`.

Tests: `CommunicationCampaignsTest` (launch, making, sending, DONE; one phone; restart and a batch made twice; cancel;
refusals; the same day; automatic first; read again before sending; the guard, automatic and campaign),
`CampaignAudienceQueryTest` (the query on H2), `CommunicationPromoSeedTest`, with `CampaignTestBed`. The rules changed on
purpose: `CommunicationMessagesTest.message` (an empty `{storeName}` now takes the company's name),
`CommunicationAdminTest.preview` (11 fields with `pointsBalance`), `InstallationBeansProofTest` (132 beans).

Screen test (2026-11-21): the Happyness store on `pos_franchise_customer` (`configs/local/test_happyness_store`) and the
ZEN pair (`test_zen_store` + `test_zen_ho`), the WARs exploded; the 9 points passed, 0 back-end ERROR, 0 console error.

## Step 8a: the email channel for real, the Canaux tab, the safety before real providers

SMS and WhatsApp stay on the provider `test`. Every new line is optional, with its default in core's
`application.properties`; a password only in the outside file.

**The provider `smtp`** (`SmtpCommunicationProvider`, `communication.email.provider=smtp`): javax.mail of the mail
starter already in core's pom, its own `Session` per message (never a shared mail bean), with
`mail.smtp.connectiontimeout`, `mail.smtp.timeout` and `mail.smtp.writetimeout` all at
`communication.email.smtp.timeout-seconds`; one connection per message. Plain text, UTF-8 (subject, text, the sender's
name), the subject from the template, the sender `communication.email.from-address` and `from-name`; the message's
`provider_message_id` is the email's Message-ID, on the sender's domain (`<...@magasin.tn>`, the session's `mail.from`;
without it javax.mail wrote this computer's name, `<...@DESKTOP-...>`, seen in the screen test).

| Line | Default | Meaning |
|---|---|---|
| `communication.email.smtp.host` | empty | the mail server; empty with email on and `smtp`: one WARN, the channel off |
| `communication.email.smtp.port` | `587` | 1 to 65535 |
| `communication.email.smtp.security` | `starttls` | `none`, `starttls` (required: a server without it is refused), `ssl` |
| `communication.email.smtp.username` | empty | no login when empty |
| `communication.email.smtp.password` | empty | only in the outside file; never shown, never logged |
| `communication.email.smtp.timeout-seconds` | `15` | connect, read and write, 1 to 300 |
| `communication.email.from-address` | empty | required with `smtp` (an email) |
| `communication.email.from-name` | empty | shown as the sender's name |
| `communication.test-recipient.phone` | empty | the test-recipient mode for SMS and WhatsApp (8 digits or `+` and 8 to 15) |
| `communication.test-recipient.email` | empty | the test-recipient mode for email |

What the server answers:
- A member's email that is not one (typed elsewhere than the members page, which refuses it: an import, a copy): no
  message is made, the event's reason says it (`TICKET_THANKS_EMAIL: email 'sana@' is not valid`; none typed: `no
  email address`). An address that reaches the provider and is not an email: not sent, `FAILED` at once, `invalid email
  address '<a>': not sent`, no retry.
- A temporary failure (no connection, a timeout, a 4xx answer, the connection lost): the waits of every message, 1, 5,
  15 then 60 minutes, `FAILED` at the 5th attempt (`attempt n failed: SMTP 451: ...`, `SMTP server unreachable: ...`,
  `SMTP timeout: ...`).
- A permanent failure (a 5xx answer, the address refused): `FAILED` at once with the server's words (`SMTP 550: ...`).
- A refused login (535) is **temporary**, not permanent: the wrong password is the installation's, not the member's;
  the messages wait and leave once the password is fixed (`SMTP login refused (<host>): ...`).
- Email on, `smtp` and no host (or no sender, a port, a timeout or a security not valid): one WARN at the start
  (`Communication: email is on with the provider smtp, but communication.email.smtp.host is empty: the channel is off`)
  and the channel is off; never a refused start.

**Test-recipient mode** (`CommunicationSettings.testRecipient`): one line per kind of address. Filled, every message of
that kind is given to the provider with this address instead of the member's (`CommunicationMessage.sentTo`, a copy);
the row keeps the member's address in `address` and the test one in `redirected_to`. A line that is not an address: one
WARN and the channels of that kind are off (never a send to the members, never a refused start). One INFO line at the
start when it is on. The Messages tab shows a banner (each channel and its test address) and marks each redirected row;
the Canaux tab shows it per channel.

**No double send**: the sender saves `SENDING` before the provider is called, then `SENT` / `PENDING` / `FAILED` with
its answer. A message found `SENDING` at the start (the application stopped meanwhile, `ApplicationReadyEvent`, before
the first cycle) becomes `FAILED` with `interrupted while it was being sent (the application stopped): it may have left,
check before sending it again` (one WARN with their number); it never leaves by itself, "Renvoyer" sends it again. A
`SENDING` message is never due (`findDue` reads `PENDING` only).

**The Canaux tab** (5th tab, everywhere the page exists, read only; `GET /communication/channels`, read right): per
channel on / off (and why off), its provider, what the provider shows of its settings (`describe()`: host, port,
security, user, timeout; the password only "set" / "not set"), its sender, its test recipient, the last message given
to its provider (status, time, reason). A line when Company information is empty: what the members read instead (a
store: its name, else the licence holder's; a head office: each member's store, its own members the licence holder's).
"Envoyer un test" (write right, channel on; `POST /communication/channels/{channel}/test` {address}): a fixed short
text (French, the time in it; the email subject `ZS Retail : message de test`) sent now through the channel's provider,
the test-recipient mode applying as to any message. Refused: a channel unknown or off, an address that is not one
(`COMM_TEST_ADDRESS`). Kept in the messages (`recipient_type` `TEST`, shown as a test); tried once (a temporary failure
is `FAILED` at once).

**The right to launch** (`write:admin-communication-campaigns`): launch and cancel need it (403 without it, even with
the write right); the Campaigns tab is read only without it. Given to ADMIN at each start where the page exists
(`ZZDataInitializer.COMMUNICATION_ADMIN_PERMISSIONS`), on a store and a head office; listed on both Roles pages.

**Seed**: `MEMBER_CARD_EMAIL_FR` (welcome, subject "Bienvenue chez {companyName}") and `TICKET_THANKS_EMAIL_FR`
(thanks, subject "Merci pour votre achat chez {companyName}") with their rules `MEMBER_CARD_EMAIL` and
`TICKET_THANKS_EMAIL` on the email channel, all inactive, written at the start where the store sends (once each).

**Database** (`db/3.3.0/update.sql`, section 9, safe to run twice): `comm_message.redirected_to` nvarchar(255).

**Speed** (measured 2026-11-21, `SmtpThroughputManualTest`, run by hand with `-Dcomm.smtp.measure=true`): 5,000 emails
given by the sender to `smtp` against the test mail server on 127.0.0.1, one connection per message, no TLS, the
messages' table in memory: all 5,000 in 8.7 s (571 per second, inside one cycle's 20 s). The real database adds two
writes per message (`SENDING`, then the answer) and the maker's inserts, so a campaign of 5,000 through a local server
takes a few cycles (minutes). A hosted mail server is the limit: with TLS and a login per message, 0.3 to 1 s per email
gives 20 to 60 emails per cycle (the sender has at most the cycle's 20 s every 60 s), 1,200 to 3,600 an hour: 5,000 in
1.5 to 4 h; a daily cap below the campaign's size (common on hosted plans) leaves the rest `EXPIRED` after the 24 h
lifetime. **A campaign can expire before it is fully sent.** Proposed rule (not built): the window shows the time the
campaign needs at the channel's measured rate (the last sends) and refuses a launch whose time exceeds the lifetime
(or proposes the lifetime it needs); with it, one SMTP connection kept for a batch of messages.

Tests: `SmtpCommunicationProviderTest` (against `CatchSmtpServer`, a mail server written for the tests in core's test
sources, 127.0.0.1, no network: Arabic in UTF-8, the sender, an invalid address, 550, 451, no server, a silent server
and its timeout, a refused login then a good one, no host / no sender, the password never shown),
`CommunicationSendStatesTest` (SENDING first, interrupted at the start, the test-recipient mode and a bad line, the
Canaux view and the test send tried once, a member's email not valid, the right to launch, the campaign's preview equal
to its message),
`CommunicationEmailSeedTest`. Changed on purpose: `ZZDataInitializerRolesTest` (the right in ADMIN's set),
`InstallationBeansProofTest` (134 beans: the provider and the tab's service), `ConfigurationKeysProofTest` (keys 87).

Screen test (2026-11-21): the Happyness store on `pos_franchise_customer` (`configs/local/test_happyness_store`, the
email lines added for the test and taken out after), the WAR exploded, the tests' `CatchSmtpServer` started alone on
127.0.0.1:2525 with a login (nothing left the PC): the welcome and the thanks by email, an email campaign in Arabic to
2 members, a wrong password (waits 1 then 5 min, then sent once the password was right), a member's email not valid,
the test-recipient mode, the Canaux tab and its test, a user without the right to launch, fr and ar; a message left
SENDING found FAILED "interrupted" at the start. 0 back-end ERROR, 0 console error.

## Next steps (to be agreed one at a time)

The member's language (it travels with the shared loyalty register; templates then need a key on code and language; a
rule then needs a purpose, service or marketing), member filters on a campaign (store, points, last purchase), a
planned date, a promo code per member, step 8b the real SMS and WhatsApp providers, the campaign's expiry rule ("Step
8a", "Speed").
