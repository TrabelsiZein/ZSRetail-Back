# Communication (3.3.0)

**Status**: step 1 (the switch and the two tables), step 2 (the store finds its events and writes `comm_event` rows),
step 3 (rules and templates make the messages, a test provider "sends" them; channels in the properties). No endpoint,
no screen, no consent, no head office side, no real provider yet.

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

## Next steps (to be agreed one at a time)

Step 4 the admin pages (rules, templates, messages), step 5 the member's consent and language (they travel with the
shared loyalty register; templates then need a key on code and language; a rule then needs a purpose, service or
marketing), step 6 the head office side (the events of its stores whose loyalty it owns), the campaign (a
`campaign_id` on the message), step 8 the real providers.
