# Communication (3.3.0)

**Status**: step 1 of the module, the switch and the two tables. No job, no finder, no endpoint, no screen, nothing sent.

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

## Next steps (to be agreed one at a time)

The finder of the two events at the store, the test channel and its sender job, the push up to the head office when
it owns loyalty, the member's consent and language (they travel with the shared loyalty register), templates and
rules, the campaign page.
