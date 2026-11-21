# Communication module: setup guide (3.3.0)

For the person who installs the Communication module at a customer: a store or a head office. The module sends
messages to loyalty members by email, SMS and WhatsApp: a welcome when a member is created, a thanks after a ticket,
and campaigns launched by hand. How it works inside: `communication.md` (same folder).

Examples in this guide use `example.com` addresses, `+216XXXXXXXX` phones and `CHANGE_ME` secrets: never write a real
key, password, token or phone number in a document, a ticket or a screenshot.

## 0. Before you start

- **Where the lines go**: in the installation's own file (the "outside file", see `../deployment-modes.md`), never in
  the files inside the WAR. Every line is optional: a line you do not write keeps its default (the tables below).
- **Restart after a change**: the lines are read when the application starts.
- **Secrets** (the SMTP password, the SMS key, the WhatsApp token) go only in that file. They are never shown on screen
  (only "défini" / "non défini") nor written in the logs.
- **Which installation sends**: the one that owns the loyalty members.
  - A store whose loyalty is its own (`ownership.loyalty=LOCAL`): the store sends. Set everything at the store.
  - A store whose loyalty is the head office's: the store only finds its events and passes them up; the head office
    sends. At the store write only `communication.enabled=true`; set the channels at the head office. Channel lines or a
    test-recipient line written at such a store are not used (the store's start log still lists them): for the tests,
    the test-recipient mode must be set **at the head office**.
  - A head office with the module on: sends for its stores' members and its own.
- **Rights** (given to the ADMIN role at each start): `read:admin-communication` (see the page),
  `write:admin-communication` (change rules and templates, send again, send a test), `write:admin-communication-campaigns`
  (launch and cancel campaigns). Give them to other roles on the Roles page if needed.
- **Members**: a member receives messages only if "Accepte de recevoir des messages" is ticked on the member and the
  member has a valid phone (8 digits, or + and the country code) or email.

## 1. The switches

| Line | Default | What it does |
|---|---|---|
| `communication.enabled` | `false` | The module. Off: no Communication page, no menu entry, no job, nothing is sent; selling and loyalty work as before. On: the page appears (menu Clients on a store, Customers & loyalty on a head office) for users with the right, and the jobs run. Only `true` or `false`: another value stops the start. |
| `communication.cycle-seconds` | `60` | Seconds between two rounds of work. While messages wait, the next round comes after 5 s. A whole number of at least 1, else the start stops. |
| `communication.email.enabled` | `false` | The email channel. |
| `communication.sms.enabled` | `false` | The SMS channel. |
| `communication.whatsapp.enabled` | `false` | The WhatsApp channel. |
| `communication.email.provider` | `test` | `smtp` for real emails. |
| `communication.sms.provider` | `test` | `http` for real SMS. |
| `communication.whatsapp.provider` | `test` | `meta` for real WhatsApp messages. An unknown provider name stops the start. |
| `communication.phone-country-code` | `216` | Put before a member's 8-digit phone. 1 to 3 digits, else the start stops. |
| `communication.test-recipient.phone` | empty | Test-recipient mode for SMS and WhatsApp: filled, every SMS and WhatsApp message goes to this phone instead of the member's. |
| `communication.test-recipient.email` | empty | Test-recipient mode for email: same, for emails. |

What a channel switch changes:
- **On, with a provider that can send**: the channel shows "Activé" on the Canaux tab and its badge in the page's
  header; its templates and rules can be switched on; campaigns can use it.
- **On, but its provider lines are incomplete** (no SMTP host, no SMS address, no WhatsApp token...): the channel is
  treated as off, the Canaux tab says why, and the start writes one WARN line:
  `Communication: sms is on with the provider http, but communication.sms.http.url is empty: the channel is off`.
  The application still starts.
- **Off**: the card shows "Désactivé"; its templates and rules cannot be switched on; no campaign offers it; an event
  whose rule is on that channel makes no message (the event says "canal désactivé").
- **Careful with the provider `test`**: it is the default. With it, nothing leaves, and the message is still recorded as
  "Envoyé". The Canaux tab says "Fournisseur de test : rien ne part". Always write the provider line of a real channel.

**Switching the module off does not forget**: when it is switched on again, the tickets and the new members of the
time it was off still get their messages if they are within their rule's validity (24 hours by default). To stop for a
while without a burst at the restart, switch the rules off (Règles) rather than the module.

The start writes one line with the state of the channels, for example
`Communication: channels whatsapp on (meta), sms on (http), email off, phone country code 216`, and one more line when the
test-recipient mode is on.

## 2. Email (SMTP)

| Line | Default | Meaning |
|---|---|---|
| `communication.email.smtp.host` | empty | The SMTP server. Empty: the email channel stays off. |
| `communication.email.smtp.port` | `587` | The server's port. |
| `communication.email.smtp.security` | `starttls` | `starttls` (usually port 587), `ssl` (usually port 465) or `none` (a local relay only). |
| `communication.email.smtp.username` | empty | The account's login. Empty: no login. |
| `communication.email.smtp.password` | empty | The account's password (or application password). Outside file only. |
| `communication.email.smtp.timeout-seconds` | `15` | Connection and answer timeout, 1 to 300. The answer after an email's end waits at least 60 s (servers check the email first). |
| `communication.email.from-address` | empty | The sender's address. Empty: the channel stays off. |
| `communication.email.from-name` | empty | The sender's name, e.g. the store's name. |

Complete example:

```properties
communication.enabled=true
communication.email.enabled=true
communication.email.provider=smtp
communication.email.smtp.host=smtp.example.com
communication.email.smtp.port=587
communication.email.smtp.security=starttls
communication.email.smtp.username=fidelite@example.com
communication.email.smtp.password=CHANGE_ME
communication.email.from-address=fidelite@example.com
communication.email.from-name=Magasin Exemple
```

Emails are plain text, in UTF-8 (Arabic is fine). The subject comes from the template.

## 3. SMS (HTTP gateway)

One HTTP call per SMS. Any gateway is plugged in by lines, no code: where to call, how to send the fields, and how to
read the answer.

| Line | Default | Meaning |
|---|---|---|
| `communication.sms.http.url` | empty | The gateway's address (http or https), placeholders allowed. Empty: the SMS channel stays off. |
| `communication.sms.http.method` | `GET` | `GET` or `POST`. |
| `communication.sms.http.body` | `query` | How the fields travel: `query` (in the address; the only way for GET), `form`, `json`. |
| `communication.sms.http.json-template` | empty | `json` only: the body with its placeholders. Empty: one JSON object of the fields. |
| `communication.sms.http.field.<name>` | none | One line per field, e.g. `communication.sms.http.field.to={phone}`. |
| `communication.sms.http.header.<Name>` | none | One line per header, e.g. `communication.sms.http.header.Authorization=Bearer {key}`. |
| `communication.sms.http.key` | empty | The gateway's key, used as `{key}`. Outside file only. |
| `communication.sms.http.username` | empty | `{username}`, and `{basic}` with the password. |
| `communication.sms.http.password` | empty | `{password}`, `{basic}`. Outside file only. |
| `communication.sms.http.sender` | empty | `{sender}`: the sender name the gateway allows. |
| `communication.sms.http.phone-format` | `with-plus` | How `{phone}` is written: `with-plus` +216XXXXXXXX, `without-plus` 216XXXXXXXX, `with-00` 00216XXXXXXXX, `local` XXXXXXXX. |
| `communication.sms.http.unicode-values` | `true,false` | What `{unicode}` becomes: first when the text needs Unicode (Arabic, some accents), second otherwise, e.g. `1,0`. |
| `communication.sms.http.connect-timeout-seconds` | `10` | 1 to 300. |
| `communication.sms.http.read-timeout-seconds` | `30` | 1 to 300. Past it, the SMS counts as "no answer" (section 7). |
| `communication.sms.http.accepted-status` | `200-299` | The HTTP statuses that mean accepted. |
| `communication.sms.http.accepted-field` | empty | A JSON field of the answer (e.g. `status`) that must hold... |
| `communication.sms.http.accepted-value` | empty | ...one of these values (e.g. `OK`). |
| `communication.sms.http.accepted-text` | empty | Or a text the answer must hold (a plain-text gateway, e.g. `OK`). |
| `communication.sms.http.id-field` | empty | The JSON field with the gateway's id of the SMS. |
| `communication.sms.http.id-regex` | empty | Or a regular expression with one group (write each backslash twice). |
| `communication.sms.http.error-field` | empty | The JSON field with the gateway's error text. Empty: the start of the answer. |
| `communication.sms.http.channel-error-texts` | empty | Texts that, in a refusal, mean the whole channel cannot send (e.g. `credit,balance`): the channel pauses instead of failing each SMS. |
| `communication.sms.http.retry-no-answer` | `false` | `true` only if the gateway refuses a second SMS with the same `{messageId}`: an SMS with no answer is then tried again. Needs a field carrying `{messageId}`. |

Placeholders (in the address, fields, headers and JSON template): `{phone}` `{text}` `{sender}` `{messageId}` (our
reference of the SMS, the same when it is sent again) `{unicode}` `{key}` `{username}` `{password}` `{basic}`. An
unknown placeholder turns the channel off with a WARN line. Write `{phone}`, never `${phone}`: a `$` before a brace is
read by Spring as one of its own settings, and the start stops.

Complete example, a JSON gateway with the key in a header:

```properties
communication.enabled=true
communication.sms.enabled=true
communication.sms.provider=http
communication.sms.http.url=https://sms.example.com/v1/sms
communication.sms.http.method=POST
communication.sms.http.body=json
communication.sms.http.json-template={"to":"{phone}","from":"{sender}","text":"{text}","unicode":{unicode},"clientRef":"{messageId}"}
communication.sms.http.header.Authorization=Bearer {key}
communication.sms.http.key=CHANGE_ME
communication.sms.http.sender=MAGASIN
communication.sms.http.accepted-field=status
communication.sms.http.accepted-value=OK
communication.sms.http.id-field=messageId
communication.sms.http.error-field=error
communication.sms.http.channel-error-texts=credit,balance
```

A second example (a GET gateway with a text answer) is in `core/src/main/resources/application.properties`.

## 4. WhatsApp (Meta's WhatsApp Cloud API)

WhatsApp sends only templates approved by Meta, never free text. Each WhatsApp template of ZS Retail points to a
template approved at Meta, by its name and language.

| Line | Default | Meaning |
|---|---|---|
| `communication.whatsapp.meta.url` | `https://graph.facebook.com` | Meta's address (a reseller offering the same API: its own). |
| `communication.whatsapp.meta.api-version` | `v26.0` | Meta's API version (the newest on Meta's versions page, 2026-11-21). |
| `communication.whatsapp.meta.phone-number-id` | empty | The sender number's id at Meta (digits, not the phone number). Empty: the channel stays off. |
| `communication.whatsapp.meta.token` | empty | The access token. Outside file only. Empty: the channel stays off. |
| `communication.whatsapp.meta.token-header` | `Authorization` | Only for a reseller that wants another header. |
| `communication.whatsapp.meta.token-prefix` | `Bearer` | Only for a reseller (empty: the token alone). |
| `communication.whatsapp.meta.connect-timeout-seconds` | `10` | 1 to 300. |
| `communication.whatsapp.meta.read-timeout-seconds` | `30` | 1 to 300. Past it, "no answer" (section 7). |
| `communication.whatsapp.meta.channel-codes` | empty | Meta error codes to treat as the channel's problem (lists and ranges, e.g. `131060,132100-132199`). |
| `communication.whatsapp.meta.retry-codes` | empty | ...as "this message, try again later". |
| `communication.whatsapp.meta.message-codes` | empty | ...as "this message failed". |
| `communication.whatsapp.meta.template-codes` | empty | ...as "the template is refused". |

The four code lines are only for a code Meta adds later; the built-in sorting is in section 7.

Complete example:

```properties
communication.enabled=true
communication.whatsapp.enabled=true
communication.whatsapp.provider=meta
communication.whatsapp.meta.phone-number-id=123456789012345
communication.whatsapp.meta.token=CHANGE_ME
```

Then, in **Communication, Modèles**, for each WhatsApp template:
- **Nom du modèle chez Meta**: the exact name approved at Meta (lowercase letters, digits and `_`).
- **Langue chez Meta**: the language code of the approved template (`fr`, `ar`, `en`, `en_US`...). Empty: the
  template's own language.
- **Valeurs chez Meta**: "Numérotées" if Meta's text uses {{1}}, {{2}}...; "Nommées" if it uses {{first_name}}...
- **Texte**: the same text as approved at Meta, with our fields where Meta has its values. The window lists the values
  Meta will receive, in order: the first field in the text is {{1}}, the next new one {{2}}, and so on. The member
  receives Meta's approved text; our text is what the screens show.

A WhatsApp template without its name at Meta cannot be used by a rule or a campaign. Two examples are installed,
switched off: `MEMBER_CARD_WHATSAPP_FR` (name at Meta `zs_carte_fidelite`) and `TICKET_THANKS_WHATSAPP_FR`
(`zs_merci_achat`), with their rules. Before switching them on, put in Modèles the names, language and text of the
customer's own approved templates.

## 5. What to get from each provider before starting

**Email**
- The SMTP server's name, its port and its security (STARTTLS or SSL).
- An account (login and password; some providers need an "application password").
- The sender address, allowed to send through that server (ask the provider; the domain's SPF and DKIM records avoid the
  spam folder).
- A test mailbox you can read.

**SMS**
- The gateway's address, the method (GET or POST) and how the fields are sent (in the address, a form, JSON).
- The key or the login, and where it goes (in the address, a header, the body).
- The sender name, accepted by the gateway (often registered in advance).
- The phone format the gateway expects (with +, without +, 00, local).
- How the gateway says "accepted" and "refused": the HTTP status, a field and its value, or a text; where it puts the
  SMS's id and the error text. Ask for an example of each answer.
- How it sends Arabic (a Unicode flag and its values).
- The words it uses when the credit is used up (for `channel-error-texts`).
- Whether it refuses a second SMS with the same client reference (only then `retry-no-answer=true`).
- Some credit for the tests.

**WhatsApp**
- The customer's Meta business account, with a WhatsApp Business account.
- The sender number, added and verified in that WhatsApp Business account (or, for a first test, Meta's test number).
- That number's **phone number id** (shown in the Meta app's WhatsApp, API Setup page).
- A **permanent access token**: a system user's token from the business settings, with the WhatsApp messaging
  permission. The token shown on the API Setup page is temporary (it expires within a day): do not use it at a customer.
- The **approved templates**: for each, its name and its language code, and its text with {{1}}, {{2}}... in the order
  of our fields (or named values). Meta sorts templates into categories; a marketing template can be held back by
  Meta's limit per person (section 7).
- With Meta's test number: the phones of the testers, added to the test number's allowed recipients in the app's
  WhatsApp, API Setup page. A number not added is refused (#131030).

## 6. Real-test checklist

Do the tests with the test-recipient mode on, so that nothing reaches a real member. Restart after each change of the
file.

**Every channel, first**
1. In the outside file: `communication.enabled=true`, the channel's lines (sections 2 to 4), and
   `communication.test-recipient.phone=+216XXXXXXXX` (the tester's phone) and/or
   `communication.test-recipient.email=tester@example.com`.
2. Restart. In the log: the line `Communication: channels ...` shows the channel `on` with its provider, the
   test-recipient line, and no WARN line about the channel.
3. Communication, **Canaux**: the yellow test-recipient banner; the channel "Activé" with its provider and settings
   (secrets as "défini"); no pause.
4. **Envoyer un test** to the tester's address: the result says "Envoyé" with the provider's number, and the message
   arrives. For WhatsApp, choose a template: the test sends it with sample values.
5. If it fails, read the reason in the result (section 7), fix the line, restart, test again.

**Email**
6. Check the email arrived in the inbox, not the spam folder; check the sender's name and the subject; an Arabic
   template reads well.

**SMS**
6. Check the sender name shown on the phone; send an Arabic template (Modèles shows its parts: an Arabic SMS has 70
   characters, or 67 per part beyond); check the credit used.

**WhatsApp**
6. In Modèles, fill the name at Meta, the language and the value format of each template you use; the window lists the
   values in order: check them against Meta's approved text.
7. "Envoyer un test" with each template: the phone shows Meta's text with the sample values in the right places.

**Then, still in test-recipient mode**
8. Switch on one template and its rule (Règles). Create a test member who accepts messages: the welcome arrives at the
   tester's address within a minute or two. Make a sale for that member: the thanks arrives.
9. Launch a small campaign: with the test-recipient mode on, **every** member's message goes to the tester, so choose a
   template and expect one message per member who accepts.
10. Communication, **Messages**: the messages are "Envoyé"; a message's window shows the provider's number.

**Finally**
11. Remove the test-recipient lines, restart: the banner is gone. Send one real message to a colleague who is a member
    and accepts messages. Then switch on the rules the customer wants.

## 7. What you see on screen, and what to do

**Message statuses**: "En attente" (waiting for the next round), "En cours d'envoi", "Envoyé" (accepted by the
provider; what happens next on the phone or in the mailbox is not followed), "Échec", "Expiré" (its validity ended
before it could leave, e.g. while its channel was paused), "Annulé" (left out: no consent any more, the address changed,
the campaign was cancelled or stopped). A failed or expired message can be sent again by hand ("Renvoyer").

**The channel is paused** ("En pause" on the Canaux tab and a banner on Messages): the channel itself cannot send, for
example a password, key or token refused, the server unreachable, the credit used up, Meta's limits for the account.
The messages wait without using their tries. The channel tries again with one message after 1, 5, 15, then every 60
minutes; a restart keeps the pause. **What to do**: read the reason, fix the cause (the line in the file and restart,
the credit, the token), then press "Envoyer un test": if it is accepted, the pause ends and the waiting messages leave.

**"No answer from the provider: check before sending it again"**: the message left, but no answer came back (a timeout,
a cut connection). The provider may have sent it. The message is "Échec" and is never sent again by itself, and the
channel pauses. **What to do**: ask the member or check in the provider's portal; send it again by hand only if it did
not arrive.

**"Interrupted while it was being sent"**: the application stopped during the sending. Same advice: check, then send
again by hand if needed.

**A WhatsApp template refused by Meta** ("refusé par Meta" on the template, "modèle refusé par Meta" on its rules): Meta
does not know this name in this language, or the template is not approved, paused or disabled, or the number of values
does not match. The message failed; the channel is not paused. **What to do**: check the template at Meta (approved,
name, language code, values), correct the name, language or value format in Modèles, save (the mark goes away; it also
goes away after the next message of that template accepted by Meta).

**A campaign "Arrêtée"**: its template was refused by Meta during the sending; its waiting messages were cancelled
("Annulé"), the reason is in the campaign's window. **What to do**: fix the template as above, then launch a new
campaign (the window asks to confirm a second campaign the same day).

**WhatsApp, one message failed** (the channel keeps working):
- #131026: this number cannot receive WhatsApp messages (not on WhatsApp, an old app).
- #131050: the person stopped marketing messages from this business.
- #131049: Meta limits marketing messages for this person: try again after 24 hours, not before.
- #131030: Meta's test number may only send to its allowed recipients: add the number in the Meta app (WhatsApp, API
  Setup).
- #132005: the text is too long once filled with this member's values.
- #131056: too many messages to the same person in a short time: this one is tried again later by itself.

**SMS, one message failed**: the gateway refused this number or this text (its words are in the reason). A refusal that
names the credit (`channel-error-texts`) pauses the channel instead.

**Email, one message failed**: the server refused this address (e.g. 550 user unknown), or the address is not an email.

## 8. What version 1 does not do

- No webhooks: ZS Retail does not receive anything back from the providers.
- No delivery or read status: "Envoyé" means accepted by the provider, nothing more.
- No replies: a member's answer (SMS, WhatsApp or email) is not read by ZS Retail.
- No "STOP": a member who answers STOP is not taken off automatically; untick "Accepte de recevoir des messages" on the
  member by hand.
- No media, no buttons, no header values in WhatsApp templates: body text values only.
- No template management at Meta: create, edit and get templates approved in Meta's WhatsApp Manager; ZS Retail only
  uses them by name.
- No sender name per store, no member language, no member filter on a campaign (all members who accept), no planned
  date for a campaign.
