# Ticket templates ("Modèles de tickets") — 3.5.0

What the till's tickets print, in which order, on which width. Design and steps: `roadmap/ticket-templates.md`. Built so
far: step 1 (the switch and the new renderer, the same ticket), step 2 (the table, the API, the page, the sale ticket's
template), step 2b (the layout, the options, the texts, the tombola, everything the sale ticket prints owned by the
template) and step 3 (part 1: the return voucher, its reprint, the cash refund slip; part 2: the warranty ticket and
its duplicate, the tombola slip's own document) and step 4 (the internal tickets: the session and store tickets X / Z, the
closing ticket, the two slips). The head office sends its templates down at step 5.

## The rule

Everything about the printed sale ticket is configured in the template (table `ticket_template`, page "Modèles de
tickets"). Nothing new in General setup or Company Information: `TICKET_RENDERER` stays the only General setup line.

- **Step 6 part 1: the new printing is the only one.** `GET /config` sends `ticketRenderer` `NEW` on every store,
  whatever the General setup row `TICKET_RENDERER` says, and the getter `appConfig/newTicketRenderer` is always true:
  every document prints through `src/libs/ticket`, which reads its template. The old print code (`ReceiptTemplate.vue`,
  `receiptPrintStyles.js`, `printSessionReport.js`, the closing ticket of `ItemSelection.vue`) stays in the tree, never
  called, until part 2 deletes it.
- **No row = the built-in default**: every option's default is today's value; no element is linked to an old setting
  any more (the address off; the thank-you line, the ZS Retail label and the loyalty rows on; the tombola section on, so
  `TOMBOLA_ENABLED` alone decides). A text left null is the built-in one ("Merci pour votre achat !", no footer text).
- **The upgrade carried the old settings** (`db/3.5.0/update.sql`, "The carry at the upgrade" below): a store whose
  Company Information ticket fields or `TICKET_SHOW_LOYALTY_*` rows differed from the built-in values got its template
  rows, so it prints the same ticket the day after. Proved by `tools/ticket-render-check.mjs` (the old outputs recorded,
  the carried template given) and `TicketTemplateServiceTest#carriedRows` (the script's own JSON read back).
- **A saved template decides everything.** The old settings are never cleared or changed, and read by nothing since step
  6 part 1; the Company Information columns and the three General setup rows (`TICKET_RENDERER`,
  `TICKET_SHOW_LOYALTY_BALANCE`, `_EARNED`) stay one release as source data, then are dropped together.
- The identity (name, MF, address, phone, fax, e-mail, website, logo image) stays in Company Information (the A4
  invoices use it, and a head office never sends one store's identity): the template owns whether and how each prints.
  `invoiceFooterNote` stays the invoices' setting.

## The table `ticket_template` (core; db/3.5.0/update.sql)

One row per document type, on a store and on a head office (the head office's rows are for step 5).

| Column | |
|---|---|
| `id`, `active`, `created_at`, `created_by`, `updated_at`, `updated_by` | `_BaseEntity` |
| `document_type` varchar(40), unique `uk_ticket_template_document_type` | `TicketDocumentType`: `SALE`; `RETURN_VOUCHER`, `CASH_REFUND`, `WARRANTY`, `SESSION_REPORT`, `STORE_REPORT`, `CLOSING_DECLARED`, `CASH_MOVEMENT_SLIP`, `CLOSING_WITHDRAWAL_SLIP` listed as coming |
| `layout` nvarchar(max) | JSON: `{"paperWidth": 58 / 80, "fontSize": SMALL / NORMAL / LARGE, "spacing": AIRY / NORMAL / COMPACT, "feedLines": 0-6, "separator": LINE / DASHES / NONE}`; defaults 80, NORMAL, AIRY, 0, LINE |
| `blocks` nvarchar(max) | JSON: `[{"code", "on", "elements": [{"code", "on", "options": {...}}]}]`, the sections in print order |
| `format_version` int, default 2 | the JSON's format (1: the draft of step 2, its columns `paper_width`, `font_size`, `compact`, `hide_zero_rows`, moved into `layout` by db/3.5.0; a layout `"compact": true` reads as COMPACT) |
| `origin` varchar(20), default `LOCAL` | `HEAD_OFFICE` for a copy received (step 5) |

Levels only, never a CSS value (a printer's renderer, phase 2, reads the same row). "Réinitialiser" deletes the row.
`pos_machine.paper_width` (int, nullable, a store): the machine's printer; null = the template's width.

## The blocks of the sale ticket

Two levels: the **sections** move up and down (the header always first, the tombola slip always last); the
**elements** inside a section are switches, in the section's order, some with **options**. The catalogue is the back
end's (`core.tickettemplate.TicketBlocks`: sections, elements, locks, options with their values and defaults); the
front's default (`src/libs/ticket/sale-template.js`) is the same, proved by `tools/ticket-render-check.mjs`.

| Section (default order) | Section | Elements (options) |
|---|---|---|
| En-tête (HEADER) | first, locked | Logo (`width` 20-100 % of the printable width, null = today's 100 px; `maxHeight` 5-60 mm, null = today's 40 px; `align` LEFT / CENTER / RIGHT) · Nom du magasin 🔒 (`size` SMALL / MEDIUM / LARGE) · Adresse (off) · Titre « REÇU DE VENTE » · DUPLICATA 🔒 (reprints) |
| Texte d'en-tête (TOP_TEXT) | new, empty | Lignes (`text`: 3 lines of 48 characters, "" by default) |
| Informations du ticket (INFO) | locked | Numéro de vente 🔒 · Date et heure 🔒 · Caissier · Code client (`passenger` SHOW / HIDE) |
| Articles (ITEMS) | locked | Lignes 🔒 (`name` WRAP / CUT) · Code article (off) · Code-barres de l'article (off: its number, the item card's) · Prix unitaire (off: « 2 x 45.000 TND ») · Remise de la ligne · Nom de la promotion (in the discount row) · TVA de la ligne (off only with « TVA par taux » on) |
| Totaux (TOTALS) | locked | Remise totale · Total TVA 🔒 · TOTAL 🔒 (`size`) |
| TVA par taux (VAT_BY_RATE) | off | — |
| Paiements (PAYMENTS) | locked (functional) | — |
| Fidélité (LOYALTY) | | Carte et titulaire · Points gagnés · Points utilisés (and the deduction) · Solde de points |
| Code-barres (BARCODE) | the page warns when off (the returns scan it) | — |
| Pied du ticket (FOOTER) | locked (the MF) | Remerciement (`text` 2 lines, null = "Merci pour votre achat !") · Site web · E-mail · Téléphone · Fax · Matricule fiscal 🔒 · Texte de pied (`text` 6 lines, null = none) · Mention ZS Retail |
| Ticket de tombola (TOMBOLA) | last; on by default (step 6) | — (printed only when `TOMBOLA_ENABLED` is on too, with a loyalty member, never on a duplicate) |

Locks: `LEGAL` (store name, number, date, the lines, total VAT, TOTAL, MF, DUPLICATA), `FUNCTIONAL` (payments). The
line's VAT: off only while "TVA par taux" is on (the page puts it back on, the back end refuses both off). Not locked, to
check with the accountant: the address, the unit price.

**The carry at the upgrade** (step 6 part 1, `db/3.5.0/update.sql`): until part 1 some switches were linked to an old
setting while no row existed (the address: Company Information `receiptPrintAddress`, null = no; the thank-you line
`receiptShowThankYou` and the ZS Retail label `receiptShowSoftwareLabel`, null = yes; the loyalty earned and balance
rows `TICKET_SHOW_LOYALTY_EARNED` / `_BALANCE`, absent = yes, anything but true = no), and a null text printed
Company Information's (`receiptThankYouText`, trimmed, else the built-in one; `receiptFooterText`, its trailing
whitespace cut, else `invoiceFooterNote` on one line). The script writes those values, as the old ticket read them, into
the templates:
- A row only for a document whose carried values differ from the built-in ones (a store that never changed them gets
  none): SALE (all of them), RETURN_VOUCHER, CASH_REFUND and WARRANTY (the address, the footer text, the label).
- The row is partial JSON: `blocks` names every element that was linked (and the tombola section, on); `layout` NULL.
  The reader completes it from the catalogue (missing sections at their place, missing elements their default, the
  default layout). Origin `LOCAL`, `created_by` / `updated_by` `upgrade 3.5.0`, format 2.
- Texts: the thank-you on one line (CR, LF, tab as spaces), trimmed, null when empty or the built-in one (compared
  binary); the footer text with its line breaks (CR dropped, the whitespace at its end cut), else the invoice note on one
  line (its line breaks and runs of spaces as one space: `pre-line` printed it so); control characters dropped;
  backslash, quote, LF and tab escaped for JSON.
- Guards: a store database (`quick_quantity`, every store started at 3.1.2 or later, or no `ho_store`, a store
  brought up by the scripts alone; a head office has `ho_store` and never `quick_quantity`; "no `ho_store`" alone is
  not enough, a store database created before the projects were split has every table); only while `APP_VERSION` is
  still 3.4.0 (the script sets 3.5.0 at its end since step 6, so a second run carries nothing, even after a
  "Réinitialiser"); never over a row of that document. No event is written (SQL).
- One difference with the old printing, printed the same: the invoice note as a template text has the class
  `receipt-footer-lines` (`white-space: pre-line`); on one line it prints as before.

**Texts** (step 2b): at most N lines of 48 characters (a line at 80 mm); the page warns above 32 (a line at 58 mm), the
ticket wraps, never cuts. One text per template, printed as typed (no language variants; the ticket's fixed labels stay
French). A null text prints the built-in one (step 6 part 1: the thank-you "Merci pour votre achat !", no footer text;
until then Company Information's). The page starts an empty text from the built-in one. Only a save checks the limits:
a text read longer (one carried at the upgrade) is kept and prints in full; the page shows what would be kept and the
save waits until the admin shortens it.

**Passenger code**: `HIDE` leaves out "Code client" when the sale's customer is the passenger (General setup
`PASSENGER_CUSTOMER`, sent with the templates as `passengerCode`); unknown passenger code: printed as today.

## The layout

- Paper 58 / 80 mm (a POS machine's own width first); text size SMALL 11 px / NORMAL 12 px (today) / LARGE 13 px.
- Spacing: `AIRY` today's ticket; `NORMAL` about half the gaps (8 mm at the end, line height 1.3), the same dividers;
  `COMPACT` the least space (3 mm at the end, line height 1.2), no divider under a section title, two in a row become
  one, thin 1 px rules.
- Blank lines before the cut: 0 to 6, after everything (the tombola slip included).
- Separators: `LINE` today's ━ / ═ characters (a thin solid rule with COMPACT); `DASHES` a line of "-" (a thin dashed
  rule with COMPACT); `NONE` a small space in their place. The tombola slip keeps its own dashed lines.

## The API (store: `store.tickettemplate.TicketTemplateAPI`; service in core)

- `GET /ticket-templates`, any logged user (the till prints with it): `{ documents: [{ type, available, layout (the
  layout's options), sections (catalogue), template (null: none), defaults }], formatVersion, linked, passengerCode }`.
- `PUT /ticket-templates/{type}` and `DELETE /ticket-templates/{type}` (204, also without a row): the right
  `write:admin-ticket-templates`, checked by the API (403 `TICKET_TEMPLATE_FORBIDDEN`).
- Refusals `{ error, code, params }` (`TicketTemplateException`, `serverErrors.<code>` in the front):
  `TICKET_TEMPLATE_TYPE_UNKNOWN` (404), `_NOT_AVAILABLE`, `_OPTION` (a value out of its values or range), `_UNKNOWN_OPTION`,
  `_TEXT` (too many lines, a line too long), `_UNKNOWN_SECTION`, `_UNKNOWN_ELEMENT`, `_DUPLICATE`, `_LOCKED`, `_VAT_NEEDED`.
- A save is normalised (`TicketTemplateService.normalise`, strict): every layout option, every section once (one not sent
  goes after the one before it in the catalogue), the header first, the tombola last, every element and option in
  order (one not sent: its default). A row read back is normalised leniently (an unknown code or option dropped, a wrong
  value its default, a locked block back on), so a row of another version never breaks a print.
- Rights: `read:admin-ticket-templates` (the page, the menu) and `write:admin-ticket-templates`, store only, given to
  ADMIN on a new store and at every start of a store (`ZZDataInitializer.TICKET_TEMPLATE_ADMIN_PERMISSIONS`); Roles
  page, group "Dashboard & Paramètres".
- Event log (when `eventlog.enabled`): `TICKET_TEMPLATE_SAVED` (Notable, the values before → after: Papier, Taille,
  Espacement, Lignes avant la coupe, Séparateurs, Ordre, Masqués, Options; nothing when nothing changed) and
  `TICKET_TEMPLATE_RESET` (Notable, its values of before); subject `TICKET_TEMPLATE`, id the document type. The French
  names: `TicketBlockLabels` (= the page's, checked).

## VAT by rate

The sale responses send `vatLines` `[{ rate, baseHT, vatAmount, totalTTC }]`: `process-sale`, `complete-pending/{id}`,
`split-and-pay/{id}` and `{id}/details` (`SessionReportService.ticketVatLines`). The tickets X / Z's spread (the ticket
discount and the loyalty points over the rates, the fiscal stamp left out). On a ticket without a ticket-wide reduction
the header's VAT is the till's (rounded line by line): a difference of at most a millime per line goes to the largest
rate above 0 % (its HT the other way), so the rates add up to the printed "Total TVA". The block prints rate, HT, VAT,
amounts without "TND" (three columns at 58 mm).

## The page (front: `src/views/admin/ticket-templates/TicketTemplatesPage.vue`)

Route `admin-ticket-templates` (`/admin/ticket-templates`, resource `admin-ticket-templates`), in the user menu (top
right) after "Informations société". The admin pages' banner (Réinitialiser, Enregistrer in it); the documents on the
left (the sale ticket, the return voucher, the cash refund slip, the warranty, the tombola slip; the internal tickets
"Bientôt"); the settings in four tabs (a document shows those it has: the tombola slip "Blocs" only): "Mise en page" (paper, size, spacing,
separators, blank lines; this PC's own width said under the paper), "Logo et tailles" (logo, store name, TOTAL, item
name), "Textes" (top text, thank-you, footer text: their line counts, the 58 mm warning, what would be kept when too
long), "Blocs" (the sections up / down, their switches, a lock and its reason in place of a switch, the passenger
choice under "Code client", where a linked switch comes from); the live preview beside them: the print's own renderer
and print document in an iframe, on a fixed sample sale (`src/libs/ticket/sample-sale.js`: 3 lines at 19 % and 7 %, a
promotion, a barcode on each item, one long name, a ticket discount, a loyalty member, two payments, its VAT by rate),
with its length in mm. Without the write right the page is read-only. "Réinitialiser" asks to confirm. fr / en / ar.

While a template is saved, each old setting it owns says "Décidé par Modèles de tickets (nouvelle impression)" under
it: Company Information (the address, the thank-you switch and text, the ZS Retail label, the footer text) and General
setup (the loyalty earned and balance rows, the tombola). None on `invoiceFooterNote`.

## The print

- `Payment.vue` (sale, split bill) and `TicketsHistory.vue` (duplicate) read the Vuex module `ticketTemplates`
  (`src/store/ticket-templates`), loaded when they open with `NEW` (and by the page, Company Information and General
  setup): the saved template (`saleTemplate`, null = the default), this PC's POS machine's width (`GET
  /pos-machine/my-machine` `paperWidth`) and the passenger code. A failed read prints the default.
- `buildSaleTicket(sale, { companyInfo, duplicate, tombola, template, paperWidth, passengerCode })` → `{ document,
  layout: { paperWidth, fontSize, spacing, feedLines, separator }, blocks }`. Without a template: the built-in default
  (`defaultSaleTemplate(linkedForPrint())`; step 6 part 1: `linkedForPrint` returns the built-in values, removed in part 2).
- Dividers: each section keeps its own; the payments close with one unless the next section opens with one (the old
  rule); when sections were moved, a divider between two sections that have none at their joint, except before the
  footer and the tombola (as the old ticket).
- `html-renderer.js` maps the separators, the size classes, the cut name and the logo's measures (an inline style:
  width, maximum height, position; a width with the height "as today" has no height limit, so the width wins; neither:
  no style, today's logo). `layout-styles.js`: the CSS added after the old stylesheet (a COPY, unchanged); nothing for
  the default layout.
- Length of the 3-line cash sale (fixture 1, Chrome, CSS mm at 96 dpi, the barcode drawn): 80 mm 278 mm today (AIRY) →
  146 mm COMPACT; 58 mm 305 → 169 mm.

## The return documents (step 3)

The return voucher (`RETURN_VOUCHER`: its first print at the till, `ReturnProducts.printVoucherReceipt`, and its reprint
in Retours, `ReturnsManagement.printVoucher`) and the cash refund slip (`CASH_REFUND`,
`ReturnProducts.printCashRefundReceipt`) have their own template row, the same layout, logo, sizes and texts as the sale
ticket, and their own blocks (`TicketBlocks`, `sale-template.js` `DOCUMENT_SECTIONS`):

| Document | Sections (default order) | Locked |
|---|---|---|
| Bon de retour | En-tête (+ « BON # » badge, DUPLICATA on a reprint) · Texte d'en-tête · Informations (Numéro de bon, Ticket original, Date, Valide jusqu'au, Caissier, Code client) · Articles · Totaux (Remise totale, off: today's voucher never printed it; on: the returned lines' TTC less the voucher · MONTANT DU BON) · Paiements ("Bon de retour" and its reference; the old printing prints "Return Voucher") · Code-barres (the voucher's number) · Pied (the message "Ce bon peut être utilisé pour de futurs achats", its "Valide jusqu'au" reminder, contact, MF, footer text, ZS Retail) | store name, DUPLICATA, the voucher's number, original ticket, date, validity date, the lines, their VAT, Total TVA, the amount, the barcode, MF |
| Remboursement en espèces | En-tête (+ « RETOUR # ») · Texte d'en-tête · Informations (Numéro de retour, Ticket original, Date, Caissier, Code client) · Articles · Totaux (Remise totale = the lines minus the amount paid back, REMBOURSÉ EN ESPÈCES) · Pied (the message "Montant remboursé en espèces", contact, MF, footer text, ZS Retail) | store name, the return's number, original ticket, date, the lines, their VAT, Total TVA, the amount, MF |

No VAT by rate (the line's VAT is locked), no loyalty, no tombola on a return document. The document's message is the
template's text (2 lines; null = today's). One builder, `return-ticket.js` (`buildReturnVoucher`, `buildCashRefund`), on
the data of `return-formats.js` (COPIES of the old code). The voucher's reprint, new printing only: DUPLICATA under the
title, the return's cashier (`createdByUser`), the first print's VAT (`voucherTaxAmount`, one calculation: the
voucher's share of the lines' VAT). `RETURN_VOUCHER_VALIDITY_DAYS` stays in General setup.

The reprint's cashier comes from the return: `GET /return-header` (the list) and `GET /return-header/{id}/details` send
`createdByUser` (id, username, full name: the user who made the return, else its session's cashier; null: no row).

On the page, "Copier depuis un autre document" copies another document's layout, logo and sizes, top text and footer
text into the form (not its blocks, not its own message); nothing is saved until "Enregistrer". Only the documents with
a layout are offered (not the tombola slip). A block is named as its document prints it ("Numéro de bon" on the
voucher; front `admin.ticketTemplates.documentTexts.<document>`, back `TicketBlockLabels` "DOCUMENT:SECTION.ELEMENT",
`fr(type, code)`, for the event log). The chosen document is in the address (`?document=WARRANTY`); each document has
its preview caption (`previewHelps`).

## The warranty ticket (step 3 part 2)

`WARRANTY`: the warranty ticket (`WarrantyCreate.vue`, printed when the warranty is registered) and its duplicate
(`WarrantyList.vue`, "Imprimer le duplicata": DUPLICATA under the title). The same layout, logo, sizes and texts as the
other customer tickets; its blocks:

| Sections (default order) | Locked |
|---|---|
| En-tête (GARANTIE, DUPLICATA on the duplicate, « GARANTIE # » badge) · Texte d'en-tête · Informations (N° de garantie, Ticket d'achat, Article garanti, Début, Fin, Date) · Articles (the sold line: item, quantity covered, the covered share of its TTC; item code, unit price, line VAT rate: off) · Totaux (Total TVA, Article sous garantie) · Code-barres (the warranty's number, `W-<id>`) · Pied (the message "Présentez ce ticket pour faire valoir la garantie", "Valide jusqu'au" the end date, contact, MF, footer text, ZS Retail) | store name, DUPLICATA, the warranty's number, the sale's ticket, the item, the start and end dates, the item line inside its section, MF |

No cashier and no customer code (the warranty has none), no line discount. The builder, `warranty-ticket.js`
(`buildWarrantyTicket`), on `warranty-formats.js` (a COPY of the pages' `buildWarrantyReceiptData`). One old bug fixed
in the new printing only: the old ticket printed "Total TVA : 0,000"; the new one prints the sold line's VAT (its VAT
amount, else TTC - HT, else from its rate), and no VAT row when no amount shows (the item section and "Article sous
garantie" off) or when it is not known (no sold line). And a second one: a warranty covering part of its line printed
the whole line; it prints the share of the quantity covered (the line's TTC, its line discount in it, x covered / sold,
to the millime; the VAT of that amount at the line's rate, else the line's VAT x the share; W-1, 1 of 2 at 34.700:
34.700 and 5.540). Then the sale's ticket discount (the back end's `ticketDiscountFactor`, the returns screen's rule:
the ticket's %, a ticket amount, a ticket paid partly with points its discount only, the points being a payment): the
item line stays as on the sale ticket, a "Remise ticket" row (`TICKET_DISCOUNT`, only when the sale has one), then
"Article sous garantie" the amount paid and the VAT of it at the line's rate (1 of 2 at 34.700, ticket -5 %: -1.735,
32.965, VAT 5.263). A sale without a ticket discount prints as before.

## The tombola slip (step 3 part 2)

`TOMBOLA_SLIP`: how the tombola slip looks under the sale ticket. One section `SLIP` (locked): Sur un ticket à part
(the page break before it), Titre « TOMBOLA », Numéro de vente (locked), Code-barres, Nom du membre (locked), Téléphone
du membre. No layout: it prints with the sale ticket's (its size level included). Whether it prints: `TOMBOLA_ENABLED`
on AND the sale ticket's section "Ticket de tombola" on (the section from `TOMBOLA_ENABLED` while no sale template is
saved) AND a sale with a loyalty member; never on the duplicate. `TOMBOLA_ENABLED` is a feature setting: off, no slip
whatever the template says, a head office's template included (step 5 fix; before it, a saved template's switch alone
decided). General setup shows no "Décidé par Modèles de tickets" on it; "Modèles de tickets" says on the block (and on
the "Ticket de tombola" document) that it has no effect while the tombola is off in the store, and at a head office that
it prints only in the stores where it is on. Its default is the look it was written
for: `layout-styles.js` `TOMBOLA_STYLES`, the old component's scoped styles (they never reached the print window, so
today's slip prints unstyled right under the ticket): its own ticket, a 2 px dashed line at its top, centred, the title
16 px with spaced letters, the number and the name in bold, small grey dashed lines that never wrap. The HTML is the old
one; the print document adds the slip's CSS. The till's print passes its template (`tombolaTemplate`,
`ticketTemplates/templateOf('TOMBOLA_SLIP')`).

## The internal tickets (step 4)

`SESSION_REPORT` (the session ticket X and Z, one template), `STORE_REPORT` (the store ticket X and Z, of the store or of
one POS machine, one template), `CLOSING_DECLARED` (the closing ticket of `CASHIER_CLOSING_TICKET` = DECLARED, a
General setup feature setting), `CASH_MOVEMENT_SLIP`, `CLOSING_WITHDRAWAL_SLIP`. The old code (`printSessionReport.js`,
`ItemSelection.vue` `printSessionCloseTicket`) prints them on the old printing, untouched; the new printing builds them
in `src/libs/ticket/internal-ticket.js` (one builder each, the old rows and order), renders them with
`html-renderer.js` `renderInternalTicketHtml` (the old classes) and `internal-styles.js` (the old stylesheets,
copied) in the old window (`internal-print.js`); the call sites go through `src/views/admin/session/printInternalTickets.js`.

Their template is a layout only, `TicketBlocks.INTERNAL_LAYOUT`:

| Option | Values | Default (today's) |
|---|---|---|
| `paperWidth` | 58 / 80 (the POS machine's width first) | 80 |
| `fontSize` | SMALL / NORMAL / LARGE | NORMAL |
| `spacing` | AIRY / NORMAL / COMPACT | AIRY |
| `hideZeroRows` | true / false, "Masquer les lignes à zéro" | false |

No blocks: the page shows the "Mise en page" tab only; a save that sends blocks answers 400 `TICKET_TEMPLATE_NO_BLOCKS`;
`feedLines` and `separator` are unknown options there. `GET /ticket-templates` marks them `internal: true`.

"Masquer les lignes à zéro" (the rule in the builders, `detail`): a counter or a detail row with no amount and no
movement is not printed; in CONTRÔLE (session and store tickets) and in the verification's table, a method with nothing
expected and nothing counted (its tickets' numbers too) is not printed either (after the step 4 screen test); the totals,
the control of a method with an amount (expected, counted, difference, a difference of 0 included), the opening cash,
the handover, the declared total and the net cash always print; a section left with only zero rows goes with its title
and its divider (PAIEMENTS, MOUVEMENTS DE CAISSE, the closing ticket's methods and counters). The slips: nothing to hide.

Every date of an internal ticket prints as dd/MM/yyyy HH:mm (`ticketDateTime`), as the store ticket: the old session
ticket and closing ticket printed theirs as the API sends them ("2026-11-20 16:45:52"), a fix of the new printing only.

Fixed in the new printing only: the closing ticket's names (store, payment methods) and labels are escaped (the old one
wrote them into the HTML as they are).

## The head office (step 5, back end)

A head office manages the templates of the stores whose switch "Le siège gère les modèles de tickets" is on (its
Stores page, `ho_store.manages_ticket_templates`; `modules/head-office.md`, "Copies down", "Ticket templates").
- `TicketTemplateAPI` is in core: the same API at a head office. There the defaults are the built-in ones (no linked
  setting read: no address line, the thank-you line, the ZS Retail label, the loyalty rows, no tombola slip), every
  document is listed (the warranty ticket too), and ADMIN has the two rights. A save or a reset is a change of the
  copies down `TICKET_TEMPLATES` for every store (`TicketTemplateChanges`, `TicketTemplateDownProvider`); the copy
  carries the built-in thank-you text and no footer text for the texts left null (null at a store: its own Company
  Information's).
- A managed store: its templates are the head office's (`ticket_template.origin = HEAD_OFFICE`, received by
  `TicketTemplateDownHandler`); `PUT` and `DELETE /ticket-templates/{type}` answer 403 `TICKET_TEMPLATE_MANAGED`;
  `GET` says `managedByHeadOffice: true`. When the switch turns on, the store pulls the head office's templates in the
  same heartbeat; only once that page is in hand are the store's own templates copied to `ticket_template_saved` (a
  recovery by hand only) and deleted, so the head office unreachable leaves them printing (never the built-in default by
  accident). While managed, every heartbeat (3 min by default) pulls the domain: a save or a reset at the head office
  reaches the store within that rhythm, not the copies down's 30 minutes. When it turns off, the templates received are
  kept, the store's own again (`LOCAL`). A save always makes a row the store's own (`LOCAL`). The till reads the
  templates when its screen opens (`ticketTemplates/load`).
- Never sent: the POS machine's paper width, the identity of Company Information.

The front (step 5, parts 7-8):
- The head office: "Modèles de tickets" in its Paramètres menu, the store's page itself (`TicketTemplatesPage.vue`;
  route `admin-headoffice-ticket-templates`, twinOf `admin-ticket-templates`, read right
  `read:admin-headoffice-ticket-templates`; the save and reset need the store's `write:admin-ticket-templates`, listed
  on the head office's Roles page through `meta.extraPermissions`; ADMIN has both, `ZZDataInitializer`). There: no
  "ancienne impression" warning, no POS machine width, the "Textes" tab starts from the built-in texts ("Merci pour
  votre achat !", no footer text), no "linked" hints, a notice under the preview "Chaque magasin imprime son nom, son
  logo et son MF"; the preview shows the tombola slip when its block is on.
- The Stores page: the switch "Le siège gère les modèles de tickets" (`managesTicketTemplates`, off by default) under
  Active in the store's form and in its details (saved at once). Turning it on asks first (the store's own templates
  replaced, kept aside, its page read-only); off never asks. On for a store whose reported version is older than 3.5.0
  or unknown: a warning that it ignores it until upgraded. The list shows "Modèles du siège" under the status (orange
  with that warning).
- A managed store's page: read-only, every tab (notice "Géré par le siège"; no save, no reset, no "Copier depuis…"); the
  preview still works; the POS machine's paper width stays on the POS machines page. A 403 `TICKET_TEMPLATE_MANAGED`
  (the switch turned on while the page was open) shows its `serverErrors` text and reads the page again.

## The POS machine

POS machines page: "Largeur du papier" (Celle du modèle de ticket / 58 mm / 80 mm), sent with every save (null = the
template's); the back end refuses another value (`POS_MACHINE_PAPER_WIDTH`).

## Checks and tests

- Step 6: the old side of both tools is the old code's outputs as recorded from the code of before (`tools/fixtures/
  ticket-render-old.json`, `internal-ticket-old.json`, `tools/old-ticket-snapshots.mjs`; `--write-snapshots` records
  again, only while the old code is there): the comparisons outlive the old code. For a fixture with old settings the new
  side is given the template the upgrade writes (`carriedTemplate`, the carry block's mirror); the one expected
  difference (the invoice note's class) is stated, and a check that the built-in values give no row.
- `node tools/ticket-render-check.mjs` (front): the 13 fixtures old = new; the back end's catalogue (sections,
  elements, options, the layout) = the front's default; the event log's names = the page's; the default given as a
  template = the old ticket in the old document; then each option: spacing, 58 mm and the machine's width, blank lines,
  separators, blocks off, sections moved, VAT by rate, unit price, item code / barcode / promotion name, the cut name,
  sizes, the logo, the texts, the contact rows, the passenger code, the tombola switch; step 3: the 6 return fixtures
  (the voucher's "Bon de retour" the one wanted difference), the reprint's fixes, the voucher's "Remise totale"; the 4
  warranty fixtures (the old data read from the pages, the real VAT the one wanted difference), a warranty's template;
  the tombola slip's look and switches; each document's catalogue and labels (5 documents).
- `node tools/internal-ticket-check.mjs` (front, step 4): the 11 internal fixtures old = new (with no template and the
  default as a template; the old tickets from `printSessionReport.js` and from the method of `ItemSelection.vue`), the
  comparison seeing a change, "Masquer les lignes à zéro" on each document, the layout's CSS, the closing ticket's
  escaping, the back end's `INTERNAL_LAYOUT` and labels = the front's. Back end: `TicketTemplateInternalAPITest`.
- `tools/store-menu-check.mjs`: the page in the user menu after Informations société, with its read right.
- `tools/headoffice-menu-check.mjs` (step 5): the head office route (the store's component, twinOf, resource, the
  write right listed), the entry in Paramètres, the back end's `read:admin-headoffice-ticket-templates`.
- `TicketTemplatesRoundTripTest` (network-tests, step 5): the head office saves, the store receives it (HEAD_OFFICE)
  and its page gives it to the till, a save there refused 403; a reset, the built-in default; switch off, the rows kept,
  LOCAL, editable, nothing more pulled; on again, the store's own put aside and replaced, pulled from the start.
- Back end: `TicketTemplateServiceTest` (the built-in defaults, the carried rows of the upgrade script, page, refusals, options, texts, normalising,
  lenient read and format 1, events, part 2, the return documents, the warranty and the tombola slip, the labels by
  document), `ReturnCashierTest` (the reprint's cashier), `TicketTemplateAPITest`, `TicketVatLinesTest`,
  `PosMachineServiceTest#paperWidth`, `PosMachineAPITest#myMachine`, `ZZDataInitializerRolesTest`.
