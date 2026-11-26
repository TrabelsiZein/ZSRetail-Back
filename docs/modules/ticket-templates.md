# Ticket templates ("Modèles de tickets") — 3.5.0

What the till's tickets print, in which order, on which width. Design and steps: `roadmap/ticket-templates.md`. Built so
far: step 1 (the switch and the new renderer, the same ticket) and step 2 (the table, the API, the page, the sale
ticket's template). The other tickets come at steps 3 (return voucher, refund, warranty) and 4 (internal tickets); the
head office sends its templates down at step 5.

## The two switches

- General setup `TICKET_RENDERER` (store, `OLD` by default): `OLD` prints with the old code (`ReceiptTemplate.vue`,
  `receiptPrintStyles.js`, untouched), which never reads a template; `NEW` prints the sale ticket, its split bill, its
  duplicate and the tombola slip through `src/libs/ticket`, which reads the template.
- The template of a document: **no row = the default**, today's ticket. With `OLD` the page works the same but says,
  in a yellow line, that its template applies only with the new printing.

Nothing else is in General setup: everything about the templates is in the table `ticket_template`.

## The table `ticket_template` (core; db/3.5.0/update.sql)

One row per document type, on a store and on a head office (the head office's rows are for step 5).

| Column | |
|---|---|
| `id`, `active`, `created_at`, `created_by`, `updated_at`, `updated_by` | `_BaseEntity` |
| `document_type` varchar(40), unique `uk_ticket_template_document_type` | `TicketDocumentType`: `SALE` (step 2); `RETURN_VOUCHER`, `CASH_REFUND`, `WARRANTY`, `SESSION_REPORT`, `STORE_REPORT`, `CLOSING_DECLARED`, `CASH_MOVEMENT_SLIP`, `CLOSING_WITHDRAWAL_SLIP` listed as coming |
| `layout` nvarchar(max) | JSON (step 2b): `{"paperWidth": 58 / 80, "fontSize": SMALL / NORMAL / LARGE, "spacing": AIRY / NORMAL / COMPACT, "feedLines": 0-6, "separator": LINE / DASHES / NONE}`; defaults 80, NORMAL, AIRY, 0, LINE (today's ticket) |
| `blocks` nvarchar(max) | JSON: `[{"code", "on", "elements": [{"code", "on", "options": {...}}]}]`, the sections in print order; options (step 2b): the logo's `width` (20-100 %, null = today's 100 px), `maxHeight` (5-60 mm, null = today's 40 px), `align`; the store name's and the TOTAL's `size` (SMALL / MEDIUM / LARGE); the item lines' `name` (WRAP / CUT) |
| `format_version` int, default 2 | the JSON's format (1: the draft of step 2, its columns `paper_width`, `font_size`, `compact`, `hide_zero_rows`, moved into `layout` by db/3.5.0; a layout `"compact": true` reads as COMPACT) |
| `origin` varchar(20), default `LOCAL` | `HEAD_OFFICE` for a copy received (step 5) |

Levels only, never a CSS value (so a printer's renderer, phase 2, reads the same row). "Réinitialiser" deletes the row.
`pos_machine.paper_width` (int, nullable, a store): the machine's printer; null = the template's width.

## The blocks (the sale ticket)

Two levels: the **sections** move up and down (the header always first, the tombola slip always last); the
**elements** inside a section are switches, in the section's order. The catalogue is the back end's
(`core.tickettemplate.TicketBlocks`); the front's default (`src/libs/ticket/sale-template.js`) is the same,
proved by `tools/ticket-render-check.mjs`.

| Section (default order) | Section | Elements |
|---|---|---|
| En-tête (HEADER) | first, locked | Logo · Nom du magasin 🔒 · Adresse (linked) · Titre « REÇU DE VENTE » · DUPLICATA 🔒 (reprints) |
| Informations du ticket (INFO) | locked | Numéro de vente 🔒 · Date et heure 🔒 · Caissier · Code client |
| Articles (ITEMS) | locked | Lignes 🔒 (article, quantité, total) · Prix unitaire (**off**, new: « 2 x 45.000 TND ») · Remise de la ligne · TVA de la ligne (off only with « TVA par taux » on) |
| Totaux (TOTALS) | locked | Remise totale · Total TVA 🔒 · TOTAL 🔒 |
| TVA par taux (VAT_BY_RATE) | **off**, new | — |
| Paiements (PAYMENTS) | locked (functional) | — |
| Fidélité (LOYALTY) | | Carte et titulaire · Points gagnés (linked) · Points utilisés (and the deduction) · Solde de points (linked) |
| Code-barres (BARCODE) | the page warns when off (the returns scan it) | — |
| Pied du ticket (FOOTER) | locked (the MF) | Remerciement (linked) · Contact (site, e-mail, phone, fax) · Matricule fiscal 🔒 · Texte de pied · Mention ZS Retail (linked) |
| Ticket de tombola (TOMBOLA) | last, follows `TOMBOLA_ENABLED` | — |

Locks: `LEGAL` (store name, number, date, the lines, total VAT, TOTAL, MF, DUPLICATA), `FUNCTIONAL` (payments),
`SETTING` (the tombola). The line's VAT: off only while "TVA par taux" is on; switching "TVA par taux" off puts it back
on (page), the back end refuses a save with both off. Not locked, to check with the accountant: the address, the unit
price.

**Linked** elements: while no row exists, their value is the old setting's, as the old code reads it — the address
(Company Information `receiptPrintAddress`, null = no), the thank-you line (`receiptShowThankYou`, null = yes), the
ZS Retail label (`receiptShowSoftwareLabel`, null = yes), the loyalty earned and balance rows
(`TICKET_SHOW_LOYALTY_EARNED`, `_BALANCE`, absent = yes). After the first save the template decides for the new
printing; the old settings are **never cleared** and the old code keeps reading them (back to `OLD` = the old ticket).
The texts and the identity always come from Company Information. Once a row exists, Company Information (section
"Ticket de vente") and General setup (tab Fidélité) say so in a blue line.

## The API (store: `store.tickettemplate.TicketTemplateAPI`; service in core)

- `GET /ticket-templates`, any logged user (the till prints with it): `{ documents: [{ type, available, sections
  (catalogue), template (null: none), defaults }], paperWidths, fontSizes, linked }`.
- `PUT /ticket-templates/{type}` and `DELETE /ticket-templates/{type}` (204, also without a row): the right
  `write:admin-ticket-templates`, checked by the API (403 `TICKET_TEMPLATE_FORBIDDEN`).
- Refusals `{ error, code, params }` (`TicketTemplateException`, `serverErrors.<code>` in the front):
  `TICKET_TEMPLATE_TYPE_UNKNOWN` (404), `_NOT_AVAILABLE`, `_PAPER_WIDTH`, `_FONT_SIZE`, `_UNKNOWN_SECTION`,
  `_UNKNOWN_ELEMENT`, `_DUPLICATE`, `_LOCKED`, `_VAT_NEEDED`.
- A save is normalised (`TicketTemplateService.normalise`, strict): every section once (one not sent goes after the
  one before it in the catalogue), the header first, the tombola last, every element in its order. A row read back is
  normalised leniently (an unknown code dropped, a locked block back on, a wrong width or size the default), so a row of
  another version never breaks a print.
- Rights: `read:admin-ticket-templates` (the page, the menu) and `write:admin-ticket-templates`, store only, given to
  ADMIN on a new store and at every start of a store (`ZZDataInitializer.TICKET_TEMPLATE_ADMIN_PERMISSIONS`); Roles
  page, group "Dashboard & Paramètres".
- Event log (when `eventlog.enabled`): `TICKET_TEMPLATE_SAVED` (Notable, the values before → after: Papier, Taille,
  Compact, Ordre, Masqués; nothing when nothing changed) and `TICKET_TEMPLATE_RESET` (Notable, its values of before);
  subject `TICKET_TEMPLATE`, id the document type. The French names: `TicketBlockLabels` (= the page's, checked).

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
left (the sale ticket; the others "Bientôt"); the settings (width 58 / 80, size, compact, the sections with up / down,
their switches; a lock and its reason in place of the switch); the live preview beside them: the print's own renderer
and print document in an iframe, on a fixed sample sale (`src/libs/ticket/sample-sale.js`: 3 lines at 19 % and 7 %, a
promotion, a ticket discount, a loyalty member, two payments, its VAT by rate), with its length in mm. Without the write
right the page is read-only. "Réinitialiser" asks to confirm. fr / en / ar.

## The print

- `Payment.vue` (sale, split bill) and `TicketsHistory.vue` (duplicate) read the Vuex module `ticketTemplates`
  (`src/store/ticket-templates`), loaded when they open with `NEW` (and by the page after a save, by Company Information
  and General setup for their notice): the saved template (`saleTemplate`, null = the default) and this PC's POS
  machine's width (`GET /pos-machine/my-machine` `paperWidth`). A failed read prints the default.
- The width: the POS machine's printer first (the machine at this PC's address, as for the till), else the template's,
  else 80.
- `buildSaleTicket(sale, { companyInfo, duplicate, tombola, template, paperWidth })` → `{ document, layout: {
  paperWidth, fontSize, compact }, blocks }`. Without a template: `defaultSaleTemplate(linkedForPrint(companyInfo,
  sale))`, which gives the old ticket.
- Dividers: each section keeps its own; the payments close with one unless the next section opens with one (the old
  rule); when sections were moved, a divider between two sections that have none at their joint, except before the
  footer and the tombola (as the old ticket).
- `layout-styles.js`: the CSS added after the old stylesheet (a COPY, unchanged); nothing for the default layout.
  - Sizes: SMALL 11 px, NORMAL 12 px, LARGE 13 px for a row, every other old size in proportion.
  - 58 mm: `@page` 58 mm, content 48 mm (`max-width: 52mm`, 2 mm each side, as 72 of 76 at 80); the item's name on
    its own line, quantity and total under it; a value that does not fit goes to the next line on the right; a divider
    never wraps; the DUPLICATA badge at 15 px without letter spacing.
  - Compact: 3 mm at the end instead of 2 × 10 mm; the header, footer, barcode, block margins; 2 px after an item or
    a payment instead of 8; 1 px between rows; line-height 1.2 instead of 1.4. In the lines (sale-ticket.js): no
    divider under a section title, two dividers in a row become one, every heavy or double divider a thin 1 px rule.
- Length of the 3-line cash sale (fixture 1, Chrome, CSS mm at 96 dpi, the barcode drawn): 80 mm 278 mm normal → 146
  mm compact; 58 mm 305 → 169 mm.

## The POS machine

POS machines page: "Largeur du papier" (Celle du modèle de ticket / 58 mm / 80 mm), sent with every save (null = the
template's); the back end refuses another value (`POS_MACHINE_PAPER_WIDTH`).

## Checks and tests

- `node tools/ticket-render-check.mjs` (front): the 13 fixtures old = new; the back end's catalogue = the front's
  default; the event log's names = the page's; the default given as a template = the old ticket in the old document;
  compact, 58 mm and the machine's width, blocks off, sections moved, VAT by rate, unit price, sizes.
- `tools/store-menu-check.mjs`: the page in the user menu after Informations société, with its read right.
- Back end: `TicketTemplateServiceTest` (default and linked values, page, refusals, normalising, lenient read, events),
  `TicketTemplateAPITest` (read open, writes need the right), `TicketVatLinesTest`, `PosMachineServiceTest#paperWidth`,
  `PosMachineAPITest#myMachine`.
