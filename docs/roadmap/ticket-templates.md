# Ticket templates ("Modèles de tickets") — 3.5.0 design

Agreed with Zein on 2026-11-26 (analysis on `release/3.5.0`, no code yet). The customers find the ticket too long (gaps,
paddings, margins) and want to choose what is printed. This file is the design and the order of the steps; when a step
is built, what it did goes to its module doc (to be created: `docs/modules/ticket-templates.md`) and to both CHANGELOGs.

## The rules that frame everything

- **The old print code stays untouched.** Zein has no ticket printer to test with. The new renderer sits behind ONE
  General setup switch, old code by default, for every ticket. The old code is removed later, only when Zein says so.
- **Default = today.** A store that changes nothing (no template row) prints what it prints today, with either code.
- **Old bugs are fixed in the new renderer only**, each as its own CHANGELOG Fixes line, in the step of its ticket.
- **Phase 2** (not in 3.5.0): direct printing from the back end (printer commands), reading the same template, behind a
  switch per POS machine; browser print stays the default. The model below must not block it.

## Today: what is printed on the ticket printer

Every ticket is HTML built in the front, written into a popup (`window.open`) and printed by the browser, 80 mm
(`@page size: 80mm auto`, content `max-width: 76mm`). Three code paths:

| Family | Where | Tickets | Data |
|---|---|---|---|
| A | `ZSRetail-Front/src/components/ReceiptTemplate.vue`, mounted off screen; its `outerHTML` with **only** `receiptPrintStyles.js` | sale (`Payment.vue`, split bill too), duplicate (`TicketsHistory.vue`), return voucher (`ReturnProducts.vue`), voucher reprint (`ReturnsManagement.vue`), cash refund slip (`ReturnProducts.vue`), warranty and its duplicate (`WarrantyCreate.vue`, `WarrantyList.vue`), tombola slip (end of a sale ticket) | sale: the map of `POST /sales-header/process-sale` / `complete-pending/{id}`; duplicate: `GET /sales-header/{id}/details` (payments under `payments`); return, refund, warranty: **rebuilt in the front** as a fake sale |
| B | `ZSRetail-Front/src/views/admin/session/printSessionReport.js`, HTML strings, one `STYLE` | session ticket X / Z (`SessionReportTicketDTO`), store ticket X / Z (`StoreReportTicketDTO`), cash movement slip (`CashMovementDTO`), closing withdrawal slip (`ClosingWithdrawalDTO`) | `GET /cashier-session/{id}/report-ticket`, `/store-report-ticket/x|z`, `POST /cash-movement`, `/cashier-session/{id}/details` |
| C | `ZSRetail-Front/src/views/pos/ItemSelection.vue` `printSessionCloseTicket`, inline CSS (a third copy) | the cashier's closing ticket when `CASHIER_CLOSING_TICKET=DECLARED` | `sessionCloseTicket` of `POST /cashier-session/close` |

Not tickets (A4 or other printers): the day journal (`printDayJournal.js`), invoices (`InvoiceTemplate.vue`,
`invoicePrintStyles.js`), purchase and supply invoices, deliveries, the reports (`window.print`), `List.vue`, product
labels (`productLabelsPdfService.js`).

### The sale ticket's blocks, in print order (family A)

Logo · store name · address (`receiptPrintAddress`) · ━ · title (REÇU DE VENTE / GARANTIE / BON DE RETOUR /
REMBOURSEMENT) · DUPLICATA badge · number badge (voucher, warranty, refund) · ━ · number, [warranty: ticket, item,
start, end], [voucher / refund: original ticket], date, [voucher: valid until], cashier, customer code · ━ · column
header Article / Qté / Total · ━ · per line: "name — variant", quantity, line total TTC (no unit price), then the discount
row ("Remise *promotion* : -x (y %)"), then the "TVA : n%" row; the timbre fiscal is one of these lines (item `TAX_STAMP`,
quantity 1, VAT 0, `SalesHeaderService.addTaxStampLineIfEnabled`) · ━ · Remise totale, Total TVA (one amount, no
breakdown by rate), TOTAL · ━ PAIEMENTS ━, one row per method and its reference, ━, Total payé, Rendu · ━ ★ FIDÉLITÉ ━,
card, holder, points earned (`TICKET_SHOW_LOYALTY_EARNED`), used, deduction, balance (`TICKET_SHOW_LOYALTY_BALANCE`), ━
· ━ CODE128 barcode and its number ━ · thank-you text (or the voucher / warranty / refund text) · ═ · website, e-mail,
phone, fax, MF, footer note (`receiptFooterText`, else `invoiceFooterNote`) · "ZS Retail" · tombola slip.

### What makes it long

| What | Value (family A, `receiptPrintStyles.js`) |
|---|---|
| Text | 12 px, `line-height: 1.4` (a row is 16.8 px), everything weight 600 |
| Dividers | a line of ━ characters: one text row + `margin: 8px 0`, about 25 px (6.6 mm) each; 11 on a cash sale, 14 with a loyalty member, two in a row between the loyalty and the barcode |
| Rows | `.receipt-row` `margin-bottom: 4px`; `.total` 14 px, `margin-top: 8px` |
| Each sold line | name row + discount and VAT rows (10 px, `margin-bottom: 6px`) + `.receipt-item` `margin-bottom: 8px`: about 11 mm |
| Blocks | `.receipt-header` `margin-bottom: 15px`; h2 / h3 `margin: 5px 0`; blocks `margin-bottom: 10px`; `.payment-entry` 8px; `.receipt-section-header` `margin: 8px 0` |
| Badges | `.duplicate-badge` 18 px, padding 10, margin 10, border 3, red background (about 18 mm); `.voucher-badge` 14 px, padding 8, margin 10, border 2 |
| Barcode | `margin: 15px 0`, container `margin: 10px 0`, SVG 50 px, text `margin-top: 5px` |
| Footer | `margin-top: 20px` |
| End | `body` `padding-bottom: 10mm` + `.receipt-template` `padding-bottom: 10mm`: 20 mm of blank paper |

Estimate (CSS at 96 dpi, not measured on paper): about 27 cm for a 3-line cash sale, of which ~7 cm dividers and 2 cm
blank. Families B and C: dividers are a 1 px dashed border with `margin: 6px 0`; `.row` 4px, `.total` 6px, `.footer`
16px, `.signature` 28px; at the end an empty `<div style="margin-top:20px">` and the same 2 × 10 mm. Their length is
mostly content (every row printed, zeros included).

**"Today" is what `receiptPrintStyles.js` produces.** The component's scoped CSS and Bootstrap classes never reach the
popup: the tombola prints without its page break, border or sizes, the footer note is not in italics, `mt-1` / `mt-2`
do nothing. The new renderer reproduces the printed result, not the component's CSS.

### Old bugs (fixed in the new renderer only, step 3)

- Voucher reprint: its Total TVA can differ from the original (`ReturnsManagement` does not apply the voucher's share
  that `ReturnProducts` applies); it prints no DUPLICATA (the template hides the badge on a voucher although the
  caller asks for it); it prints "Caissier : System".
- Warranty: "Total TVA : 0,000" (it passes `taxAmount: 0`).
- Seen, not bugs to fix here: labels hard-coded in French (families B and C use i18n), `receiptBranding.js` unused, the
  warranty data builder copied in two files, the voucher's VAT computed by two copies of the same code.

### Settings that change a ticket today

- Company Information (per installation, never copied down): name, logo, MF, address fields, phone, fax, e-mail, website,
  `invoiceFooterNote` (also on the A4 invoices), and the 2.2.2 ticket fields `receiptPrintAddress`, `receiptShowThankYou`,
  `receiptThankYouText`, `receiptShowSoftwareLabel`, `receiptFooterText`.
- General setup: `TICKET_SHOW_LOYALTY_BALANCE`, `TICKET_SHOW_LOYALTY_EARNED` (sent as `showLoyalty*` in the sale
  responses), `TOMBOLA_ENABLED`, `CASHIER_CLOSING_TICKET` (Z / DECLARED), `ENABLE_TAX_STAMP` +
  `TAX_STAMP_VALUE_MILLIMES`, `RETURN_VOUCHER_VALIDITY_DAYS` (the date on the voucher). `POS_AMOUNTS_3_DECIMALS` changes
  nothing printed. Property `warranty.enabled`. No setting of width, copies or automatic print exists.

### Shared with the A4 documents (do not break)

`appConfig/companyInfo` and the Company Information page (the invoices read name, logo, MF, address, phone,
`invoiceFooterNote`); the helpers `itemLabel`, `formatCurrency`; the i18n keys `admin.sessionReport` and
`admin.storeTickets` (the A4 day journal uses them); the DTOs of family B feed screens too (StoreDay, the session
details). None of them is renamed or changed by this topic.

## Decisions (2026-11-26)

1. **Ownership**: a new domain, LOCAL by default (no change for any installation). Set to HEAD_OFFICE, the stores
   receive the templates read-only, by copies down. Last step.
2. **Paper width**: a default in the template (58 or 80 mm); a POS machine can override it (the printer belongs to the
   machine). No POS machine known (an admin reprint): the template's width.
3. **Internal tickets** (families B and C): width, size, compact, and one switch "hide the rows at zero". No block
   switches.
4. **Settings that move to the template page**: `receiptPrintAddress`, `receiptShowThankYou`,
   `receiptShowSoftwareLabel` (Company Information) and `TICKET_SHOW_LOYALTY_BALANCE`, `TICKET_SHOW_LOYALTY_EARNED`
   (General setup). With no template row, the default reads them as today; the first save of a template takes them
   over. The texts (thank-you, footer, identity) stay in Company Information. `TOMBOLA_ENABLED`,
   `CASHIER_CLOSING_TICKET`, `ENABLE_TAX_STAMP` stay where they are.
5. **Locked blocks** (Zein confirms the legal ones with his accountant; the work does not wait):
   - legal, in our opinion: store name, MF, ticket number, date and time, the item lines (name, quantity, amount), VAT
     (the line's rate and the total), total TTC, the timbre line (a sold line: no block hides it), DUPLICATA on every
     reprint;
   - functional: the voucher's number, barcode, amount and expiry date; the warranty's number, item and dates; the
     payments of a sale. The sale's barcode is not locked but switching it off warns (returns scan it);
   - to check with the accountant: the address, the unit price, an RNE identifier (Company Information has no such
     field today).

## The model

### Table `ticket_template` (core: both applications have it; db/3.5.0)

One row per document type; **no row = the built-in default** (so a new or untouched installation prints today's ticket,
and "Reset" deletes the row).

| Column | Meaning |
|---|---|
| `document_type` | unique: `SALE` (its duplicate too), `RETURN_VOUCHER` (its reprint too), `CASH_REFUND`, `WARRANTY`, `SESSION_REPORT` (X / Z), `STORE_REPORT`, `CLOSING_DECLARED`, `CASH_MOVEMENT_SLIP`, `CLOSING_WITHDRAWAL_SLIP` |
| `paper_width` | 58 or 80 |
| `font_size` | a level, `SMALL` / `NORMAL` / `LARGE` (`NORMAL` = today's 12 px) |
| `compact` | removes the gaps (the renderer's spacing, not the template's) |
| `hide_zero_rows` | internal tickets only (never the totals, the net or the control lines) |
| `blocks` | JSON, the blocks in print order: `[{ "code": "LOGO", "on": true, "options": {} }, ...]`; customer tickets only |
| `format_version` | the JSON's version, so a later block or option reads an older row |
| `origin` | LOCAL or HEAD_OFFICE (a copy received, read-only) |

`pos_machine.paper_width` (nullable): the machine's printer; null = the template's width.

**No CSS value in the template**: only levels (size, compact) and the width in mm (58 → 32 characters, 80 → 48 in
printer terms). That is what keeps phase 2 open: the same template drives ESC/POS (fonts A / B, double size, native
CODE128, raster logo).

**The block catalogue is the back end's** (one enum: per document type its blocks, which are locked, their options),
sent with `GET /ticket-templates`. The server refuses a locked block switched off and an unknown code. The front only
renders.

The template holds the layout; the texts and the identity stay in Company Information. A head office that sends its
templates sends no store's name or MF.

### The renderer (front), two layers

1. **One builder per document type**: API data + template → a printer-neutral list of lines: text (alignment, bold,
   size level), a row of 2 or 3 columns, a divider, a gap, a barcode, an image, a cut. The template is applied here;
   the voucher's VAT and the other front rebuilding of families A / C move here, once.
2. **One HTML renderer** of that list (width, size, compact), used for the print popup and the live preview.

Phase 2 adds a second renderer of the same list on the back end (ESC/POS). Note for phase 2: a server reaches only a
network printer (port 9100); a USB printer on a till needs a small local agent. The printer's address would be a
setting of the POS machine.

### The switch

One General setup row (store): `TICKET_RENDERER` = `OLD` (default) / `NEW` (built at step 1, below), sent in
`GET /config`. Every print call site asks it: `OLD` runs today's code untouched; `NEW` runs the new renderer for the tickets that already
have their builder, the others stay on the old code until their step. The template page applies only with `NEW` (the
page says so while the switch is `OLD`).

The old fields are **never cleared**: when a template takes over `receiptShowThankYou` and the others, the old code
still reads them, so switching back to `OLD` gives back exactly the old ticket.

### Other rules

- API: `GET /ticket-templates` (the catalogue and the rows), `PUT /ticket-templates/{type}`, `DELETE` = reset. The
  writes check their right themselves (`write:admin-ticket-templates`, joining `API_CHECKED_ADMIN_PERMISSIONS`); the
  page `read:admin-ticket-templates`.
- Event log: a template saved or reset is an administrator's gesture (`AdminEvents`, a new code, the type and the
  blocks changed), when `eventlog.enabled` is on.
- The live preview uses fixed sample data (a sale with a discount, a promotion, two VAT rates, a loyalty member,
  the timbre), at the chosen width.
- Fixture check: `tools/ticket-render-check.mjs` in the front, as the other tools: fixtures of each ticket's data, the
  old ticket and the new one compared (step 1, below). It comes on top of the switch, not instead of it.

## Steps

| # | What | Both repos? |
|---|---|---|
| 1 | **Done 2026-11-26** (below). Builder + HTML renderer for the sale ticket and its duplicate, **tombola included**, behind the switch; same output as today (no table yet: the default only, reading today's settings) | back: the switch row and `GET /config`; front: the renderer |
| 2 | **Done 2026-11-26** (below; `modules/ticket-templates.md`; **2b**, the template owning the whole ticket: below). Table, API and page "Modèles de tickets" with live preview, for the sale ticket: switches, order up / down, width, size, compact; new blocks off by default: unit price on the line, VAT by rate; `pos_machine.paper_width` | both |
| 3 | **Done 2026-11-27** (below): part 1 (2026-11-26) the return voucher, its reprint, the refund slip, the three reprint bugs, the document selector and "Copier depuis un autre document"; part 2 the warranty and its duplicate, the tombola slip's own document and look, the fixes of part 1's screen test | front mostly |
| 4 | **Done 2026-11-27** (below): the internal tickets (families B and C): width, size, spacing, hide the rows at zero | front mostly |
| 5 | **Back end done 2026-11-27** (parts 1-6, below; front: parts 7-8). The head office manages the templates of the stores whose switch "Le siège gère les modèles de tickets" (its Stores page, per store) is on: copies down `TICKET_TEMPLATES` (a domain of the copies down only, no `ownership.<x>` line), the store's page read-only | both |

### Notes on the order and the cuts

- **The tombola belongs to step 1, not 3.** It is printed at the end of the sale ticket: with the switch on, a sale
  without it would not be "the same output as today". Step 1 reproduces it as it prints today (unstyled); step 3 may
  give it its intended look (the page break, the border), as an Enhancement.
- **The VAT by rate of step 2 needs the back end.** The sale response sends only the total VAT; the per-line amounts
  do not add up to it when a ticket discount applies (I-48 recomputes the header's VAT by rate without storing it). Step
  2 adds the breakdown to the sale responses (`process-sale`, `complete-pending`, `details`), as `SessionReportTicketDTO`
  has `vatLines`.
- **`pos_machine.paper_width` goes in step 2** (with the table, one db/3.5.0 script), not later: the sale ticket is the
  first printed with a known machine (the session's).
- **Step 2 also changes Company Information and General setup screens**: the three switches and the two loyalty rows
  show, on those pages, "now set in Modèles de tickets" once a template exists (the values kept, read by the old code).
- **Order 3 before 4 is right**: family A's other tickets reuse step 1's builder pieces; families B and C are a
  separate builder with a smaller template (no blocks).
- The head office has no till: before step 5 the page exists on a store only.

## Step 1 — done (2026-11-26)

### The switch

`TICKET_RENDERER` (General setup, tab "Caisse et clôture", label "Impression des tickets", SELECT `OLD` "Ancienne
impression (par défaut)" / `NEW` "Nouvelle impression (modèles de tickets)", a help text; fr / en / ar). A store only:
created at start-up by `ZZDataInitializer` (`OLD`), as every General setup setting; `db/3.5.0/update.sql` only names it
in a comment. `GET /config` sends `ticketRenderer`: `NEW` in any case, else `OLD` (no row, a blank or unknown value);
null on a head office (`AppConfigAPI.TICKET_RENDERER`, test `AppConfigAPITest#ticketRenderer`). The front:
`appConfig.ticketRenderer`, the getter `appConfig/newTicketRenderer`; saving the setting refreshes `/config`. The event
log names it "Caisse et clôture › Impression des tickets" (`AdminLabels`).

### Where the sale ticket and its duplicate are printed

Only two places print them (checked 2026-11-26), each with one branch at its top, the old code below it unchanged:
- `src/views/pos/Payment.vue` `printReceipt`: the sale (process-sale, complete-pending) and the **split bill**
  (`onSplitDone`, the answer of `POST /sales-header/split-and-pay`); the tombola slip under the sale when
  `TOMBOLA_ENABLED` and a loyalty member.
- `src/views/admin/TicketsHistory.vue` `printTicketData`: the duplicate (the till's and the back office's reprint);
  the event log's `tillReprint` stays before the branch.

The other users of `ReceiptTemplate.vue` are the tickets of step 3, still on the old code with either value:
`ReturnProducts.vue` (return voucher, cash refund slip), `ReturnsManagement.vue` (voucher reprint),
`WarrantyCreate.vue` and `WarrantyList.vue` (warranty).

### The files (front, `src/libs/ticket/`)

- `lines.js`: the neutral list (text, columns, divider, gap, barcode, image, cut, group; blocks; roles and levels, no
  CSS value).
- `sale-ticket.js`: `buildSaleTicket(sale, { companyInfo, tombola })`, `buildSaleDuplicate(sale, { companyInfo })`.
- `tombola-slip.js`: `buildTombolaSlip(sale)`, the slip as it prints today (no cut: its page break never reached the
  print).
- `html-renderer.js`: `renderTicketHtml(ticket)` → `{ html, barcodes }`, the old ticket's elements and classes; the
  barcodes' JsBarcode options by size. Step 2's preview renders through it.
- `print.js`: draws the barcodes (JsBarcode, in a detached element as the old component) and prints in the same
  window as the old code (`printTicket`).
- `sale-formats.js` and `ticket-styles.js`: the copies of the old code, below.

### The copies of the old code

The old files stay untouched, so the new renderer carries **copies** of what it needs from them:
- `sale-formats.js`: from `ReceiptTemplate.vue`, the sale's branch only: `formatTunCurrency`,
  `formatDiscountPercentage`, `formatDate`, `getLineTotalForDisplay`, `getLineDiscountDisplay`, `getVatPercent`,
  `totalDiscountDisplay`, `addressLines`, `thankYouLines`, `softwareLabels`, `receiptFooterNote`;
- `ticket-styles.js`: `receiptPrintStyles.js` (the stylesheet and the print document), the same text.
- step 3: `return-formats.js`: from `ReceiptTemplate.vue` the voucher / refund branches of `getLineTotalForDisplay` and
  `getVatPercent`; from `ReturnProducts.vue` the data of `printVoucherReceipt` and `printCashRefundReceipt` (the
  voucher's VAT in one function, used by its reprint too: the reprint's fixes).
- step 3 part 2: `warranty-formats.js`: `buildWarrantyReceiptData` of `WarrantyCreate.vue` and `WarrantyList.vue` (the
  same function in both; the check reads it from the pages), with the real VAT (the fix).
- step 4: `internal-formats.js` (the helpers of `printSessionReport.js` and of `ItemSelection.vue`
  `printSessionCloseTicket`) and `internal-styles.js` (their two stylesheets, the same text).

They exist only because the old code and the switch exist; they are removed **together with the old code and the
switch**, in the same change (when Zein says so): the old originals go, and these copies lose their "COPY" header and
stay as the only version (nothing to merge). Until then a fix of the old code is not copied here (the old bugs are fixed
in the new renderer only, rules above).

### The check

`node tools/ticket-render-check.mjs` (with `tools/ticket-render-loader.mjs`, no new dependency): for each fixture it
renders the **old** ticket with `ReceiptTemplate.vue` itself (Vue's render, its template compiled as the build compiles
it: `whitespace: 'condense'`) and the **new** one (builder, then HTML renderer), and compares the two element trees
(tags, classes, attributes, texts, order), the barcodes' values and JsBarcode options, the two stylesheets and print
documents. A self-test makes five changes to a new ticket (a text, a divider, two rows swapped, a class, the tombola
slip) and checks each is listed. `--out <dir>` writes each fixture's `old.html`, `new.html` and `lines.json`.

Fixtures (13): a cash sale of 3 lines (the three VAT sources, a decimal quantity); discounts and promotions (manual, by
promotion name, the ticket's discount, two return vouchers as payment); a loyalty member (earned, redeemed, deduction,
balance); `TICKET_SHOW_LOYALTY_BALANCE` and `_EARNED` off; the tax stamp line; the duplicate (a loyalty member, no
tombola); the tombola slip; the slip of a member without phone; `TOMBOLA_ENABLED` without a member; the split bill;
Company Information with the address, a footer text with line breaks and an own thank-you text; the thank-you and the
ZS Retail label off, no logo, no contact; no sale number (no barcode, the payments' closing line), `payments` in place
of `paymentHeaders`, a user without full name, HTML characters in a name.

Result on 2026-11-26: the stylesheet and the print document the same text; the 13 fixtures the same tree; the 5
changes seen.

### What differs between the old and the new ticket (none on paper)

- Not reproduced, as they print nothing: Vue's empty comments (`<!---->`), the `data-v-…` attributes of the old
  component's scoped style (its rules never reach the print window), the whitespace a browser does not show (between
  elements, at both ends of a text; the footer text's line breaks are compared as they are).
- The barcode ids are random on both sides (the same form, `barcode-…` / `tombola-…`).
- Timing: the new code draws the barcodes at once, so it opens the print window without the old 200 ms wait (the
  duplicate's window also opens before, not after, that wait). The print (500 ms) and the closing (500 ms after the
  sale, 1000 ms after the duplicate) are the old ones.
- The barcode drawing (the SVG JsBarcode fills) is not compared in node: same library, same value, same options; to
  be compared on screen in Chrome.
- Old bugs reproduced, not fixed (step 3): the double divider between the loyalty block and the barcode. None of the
  old behaviours of the sale ticket could not be reproduced.

### Left after step 1

- On screen: the barcode of each renderer compared in Chrome: done by Zein, 2026-11-26 (a sale, three duplicates, the same).
- Steps 2 to 5 (table above); no paper test is possible here (no ticket printer).

## Step 2 — done (2026-11-26)

What it is now: `modules/ticket-templates.md`. Decisions taken with Zein before coding:
- **Two levels**: sections move (the header always first, the tombola always last), elements inside are switches.
- **Unit price** on the line: a new element, off by default; **VAT by rate**: a new section, off by default, the
  breakdown sent by the back end with the sale (the tickets X / Z's spread, the millime to the largest rate).
- **The line's VAT** is not fully locked: off only while "TVA par taux" is on (the back end refuses both off); Total
  TVA and TOTAL stay locked.
- One template for the sale ticket, its split bill, its duplicate and the tombola slip.
- The width of the **POS machine at this PC's address** wins over the template's (the printer belongs to the PC); a
  reprint on a registered PC takes it too.
- `GET /ticket-templates` open to any logged user (the till prints with it); the writes check
  `write:admin-ticket-templates`; the page `read:admin-ticket-templates`.
- The page in the user menu after "Informations société"; a blue line on Company Information and General setup once a
  template is saved.
- `db/3.5.0/update.sql` run on `pos_franchise_customer` (Zein's Eclipse Happyness store) before the entity existed,
  so Hibernate never made the table its own way.
- The table in core (both applications have it, for step 5); the API in the store (the page is a store's until step 5).

Left after step 2:
- On screen with the back end: the page, a save, a reset, a print with a template (Zein restarts the store first: the
  new table's entity, the API, the rights).
- The accountant's answer on the locks (address, unit price; an RNE field does not exist).
- Steps 3 to 5.

Screen test of step 2: passed (Zein, 2026-11-26), with three page fixes (the preview's length and frame, this PC's
width said, the saved date's format).

## Step 2b — done (2026-11-26)

Zein's rule: everything about the printed sale ticket is configured in the template; nothing new in General setup or
Company Information; the old settings stay for the old printing only. What it is now: `modules/ticket-templates.md`.

- **Part 1** (screen-tested): format 2 (`layout` JSON in place of the draft's columns; db/3.5.0 rewritten, the draft
  table converted in place), the option catalogue (`TicketBlocks.Option`), spacing AIRY / NORMAL / COMPACT (the Compact
  switch became COMPACT), 0 to 6 blank lines before the cut, separators LINE / DASHES / NONE, the item line (code,
  barcode number from the item card, promotion name, the name cut on one line), the store name's and the TOTAL's size,
  the logo (width %, maximum height, position), all live in the preview, the page's settings in tabs.
- **Part 2**: the top text (section "Texte d'en-tête", 3 lines, empty), the thank-you sentence (2 lines) and the footer
  text (6 lines) owned by the template (null: Company Information's; a longer old text shown, never cut silently), the
  58 mm warning above 32 characters, the tombola's switch (from `TOMBOLA_ENABLED`; a saved template decides), the contact
  in four rows, "hide the passenger code", the per-field notices on Company Information and General setup (none on
  `invoiceFooterNote`). Fixes of part 1's test: a logo width with the height "as today" wins; the sample sale has item
  barcodes and a long name; the preview's frame rounded up.
- Decisions: the identity stays in Company Information (the template owns whether and how it prints); options in JSON
  with a format version, not a column each; one text per template, printed as typed (the fixed labels stay French;
  ticket language is a later topic); the item barcode is its number, not a drawn barcode.

Left after step 2b:
- On screen: part 2 (Zein restarts the store first: the catalogue changed).
- The accountant's answer on the locks (address, unit price; an RNE field does not exist).
- Steps 3 to 5.

## Step 3, part 1 — done (2026-11-26)

What it is now: `modules/ticket-templates.md`, "The return documents". Built:
- The page's document list is a selector: the sale ticket, the return voucher, the cash refund slip (a document without a
  template yet: "Bientôt"); one row per document type, no row = the default, "Réinitialiser" deletes the row. The same
  four tabs; the layout, the logo and sizes and the texts are the same catalogue for every document, only the blocks
  differ. A document's own sample in the preview (`SAMPLE_RETURN`: 2 items, the ticket's discount share, a voucher with
  its number and validity date). Adding the warranty or the tombola slip: their `TicketDocumentType` available, their
  list in `TicketBlocks` and in `sale-template.js` (`DOCUMENT_SECTIONS`), their builder, their sample.
- The return voucher (its first print at the till and its reprint in Retours) and the cash refund slip on the new
  renderer: one builder (`return-ticket.js`) for the three prints; the default template gives today's tickets (6
  fixtures in `tools/ticket-render-check.mjs`, each with no template and with the default as a template).
- The three old bugs of the voucher's reprint, fixed in the new printing only: its VAT is the first print's (one
  calculation, `voucherTaxAmount`: the voucher's share of the lines' VAT; the old reprint printed the lines' VAT, e.g.
  24.748 for 22.433), its cashier is the return's (`createdByUser`, not "System"), it prints DUPLICATA under the title.
- "Copier depuis un autre document": the layout, the logo and sizes, the top text and the footer text of another
  document (its saved template, else its default) copied into the form; not the blocks, not a document's own message
  (the thank-you, the voucher's or the refund's message: they belong to their document); nothing saved until
  "Enregistrer".
- `RETURN_VOUCHER_VALIDITY_DAYS` stays a General setup feature setting (the date the voucher prints); the template only
  prints it.

Choices: the return documents have no VAT by rate (the back end sends none for a return; the line's VAT is locked
there), no loyalty and no tombola (they never print one today); the voucher's payment block ("Return Voucher" and its
reference, today's) is kept and can be switched off; its "Valide jusqu'au" prints twice today (info and footer): the
footer's reminder is a switch (`VALIDITY`), the info row is locked. The refund slip has no reason row today: none was
added (it would change today's slip); it can be a new element of part 2 if wanted.

Part 2 (below) did the warranty and its duplicate, the tombola slip's own document and look, and the fixes of part 1's
screen test.

## Step 3, part 2 — done (2026-11-27)

What it is now: `modules/ticket-templates.md`, "The warranty ticket" and "The tombola slip". Built:
- **The warranty ticket and its duplicate** on the new renderer (`warranty-ticket.js`; `WarrantyCreate.vue` prints it
  when the warranty is registered, `WarrantyList.vue` its duplicate), its own document "Garantie" in the selector, its
  own blocks (`TicketBlocks.WARRANTY`, `WARRANTY_SECTIONS`) and sample (`SAMPLE_WARRANTY`). The default gives today's
  ticket (4 fixtures; the old data read from the pages themselves), but for one old bug, fixed in the new printing only:
  "Total TVA : 0,000" (the old pages passed `taxAmount: 0`). The VAT row prints the sold line's VAT (its
  `vat_amount`, else TTC - HT, else from its rate), the VAT of the amount the ticket prints; it goes when no amount
  shows (the item line and "Article sous garantie" both off) or when the VAT is not known (no sold line). DUPLICATA
  under the title on the duplicate, as today.
- **The tombola slip, its own document** "Ticket de tombola" (`TicketBlocks.TOMBOLA_SLIP`, one section `SLIP`, no
  layout: it prints with the sale ticket's): on its own ticket (`NEW_PAGE`), the title, the sale's number (locked), its
  barcode, the member's name (locked), the phone. Its default is the look it was written for, the old component's
  scoped styles (`layout-styles.js` `TOMBOLA_STYLES`: the page break before it, a 2 px dashed line at its top, centred,
  the title 16 px spaced, the number and the name in bold, the grey dashed lines), which never reached the print
  window: today's slip prints unstyled right under the ticket. Whether it prints stays the sale ticket's switch
  (section `TOMBOLA`, from `TOMBOLA_ENABLED` while no template is saved); `TOMBOLA_ENABLED` stays a feature setting.
  The HTML is the old one (the check compares it); only the print document gains the slip's CSS.
- **Fixes of part 1's screen test**: the voucher's reprint prints the return's cashier (the returns list and the
  details now send `createdByUser`, else the session's cashier, else no row; the returns of pos_franchise_customer had
  a cashier stored, the endpoints did not send it); one caption per document under the preview; a return document's
  blocks named as they print ("Numéro de bon", "Montant du bon", "Numéro de retour", "Remboursé en espèces"; the
  page's `documentTexts`, the event log's `DOCUMENT:SECTION.ELEMENT` labels); the blank lines' help without the
  tombola; the General setup help of TICKET_RENDERER without a list of documents ("the documents the page offers"); the
  voucher's payment row "Bon de retour" (it printed "Return Voucher"); the return screen's amounts to the millime as
  stored (23.854 shown for 23.855 stored: a float sum, outside the ticket code; the old printing prints the stored
  23.855); the voucher's "Remise totale" off by default (today's voucher never printed it) and, switched on, the gap
  between the returned lines and the voucher; the chosen document kept in the address (`?document=WARRANTY`).
- The page: the tabs a document has (the tombola slip: "Blocs" only); "Copier depuis…" offers the documents with a
  layout (the warranty is one; the tombola slip has nothing to copy from or into); the barcode warning from the
  catalogue (`warnOff`, the sale ticket's only).

Locked blocks (each refused off by the server):
- Warranty: the header (the store's name, DUPLICATA), the info (the warranty's number, the sale's ticket, the item, the
  start and end dates: what the store checks when the customer comes back; the date is free), the item line inside its
  section, the MF. Free: the item section, the VAT, "Article sous garantie" (not a fiscal ticket), the barcode, the
  badge, the message, the end date reminder, the contact.
- Tombola slip: its section, the sale's number and the member's name (the draw needs them). Free: its own ticket, the
  title, the barcode, the phone.

Choices: the warranty prints no cashier and no customer code (the warranty has none: no switch that would do nothing),
no line discount or promotion (it prints none); new and off: the item code, the unit price, the line's VAT rate. The
tombola slip's sizes follow the sale ticket's size level; its dashed lines never wrap (58 mm).

After the screen test (2026-11-27, passed), two points, new printing only:
- A warranty covering part of its line prints the share of the quantity covered: the line's TTC x covered / sold, to
  the millime, and the VAT of that amount at the line's rate (else the line's VAT x the share). W-1 (1 of 2 AURA
  BILLIONAIRE H EDP 100ML at 34.700): 34.700 and 5.540, where it printed 69.400 and 11.081; its duplicate the same. The
  line discount is in the line's TTC, so shared with it. The sale's ticket discount is not: it is not on the line (the
  sale ticket prints it below the lines), the warranty prints the line's value, and a warranty covering its whole line
  prints as before. Fixtures: W-1, its duplicate, a line with a 10 % line discount; the sample covers 1 of 2.
- The tombola slip's preview caption follows "Sur un ticket à part" (on its own ticket, after the sale ticket; or right
  under it); the other helps say "après le ticket de vente", true either way.

Left: step 4 (internal tickets), step 5 (head office); the accountant on the locks.

## Step 4 — done (2026-11-27)

What it is now: `modules/ticket-templates.md`, "The internal tickets". Built:
- The five internal documents on the new renderer, behind `TICKET_RENDERER` (the old code untouched, printing on the
  old printing): `SESSION_REPORT` (the session ticket X and Z: `SessionDetailsModal.vue`, the till's closing in
  `ItemSelection.vue` when `CASHIER_CLOSING_TICKET` is Z), `STORE_REPORT` (the store ticket X and Z, of the store or of
  one POS machine: `StoreDay.vue`), `CLOSING_DECLARED` (the closing ticket when `CASHIER_CLOSING_TICKET` is DECLARED:
  `ItemSelection.vue`), `CASH_MOVEMENT_SLIP` (`CashMovementModal.vue`), `CLOSING_WITHDRAWAL_SLIP`
  (`SessionDetailsModal.vue`). One builder per document (`src/libs/ticket/internal-ticket.js`) on the sale ticket's
  neutral lines; the HTML renderer gains the internal tickets' class table (`renderInternalTicketHtml`: the old
  elements and classes, families B and C having their own stylesheet); `internal-print.js` the old window and timing;
  the entry points in `src/views/admin/session/printInternalTickets.js` (the saved template, this PC's paper width).
- Their template: the layout only (`TicketBlocks.INTERNAL_LAYOUT`): the paper (58 / 80, the POS machine's width
  first), the text size and the spacing (the customer tickets' values; AIRY today's) and one switch, `hideZeroRows`
  "Masquer les lignes à zéro", off. No blocks, no order: `TicketBlocks.of` is empty and a save that sends blocks is
  refused (`TICKET_TEMPLATE_NO_BLOCKS`); the customer tickets' blank lines and separators are unknown options there. No
  row = today's ticket (11 fixtures, each with no template and with the default as a template:
  `tools/internal-ticket-check.mjs`, the old ticket rendered from `printSessionReport.js` itself and from the
  method read out of `ItemSelection.vue`).
- **One template for X and Z** (`SESSION_REPORT`, `STORE_REPORT`, as the table's document types already were): the
  same printer prints both, the same rows in the same order (Z adds the count and the closing time), and nothing of the
  template differs between them; two templates would mean two settings always kept the same.
- **The rows at zero** (the rule in the builders, one place, `detail`): with the switch on, a counter or a detail row
  with no amount and no movement is not printed (the tickets at 0, a VAT rate at 0, the tax stamp, the line, ticket and
  loyalty discounts, the cancelled and pending tickets, a return kind at 0, a payment method at 0, a scanned tickets'
  deduction, excess or issuer at 0, a cash movement type never used; on the closing ticket a method at 0 and the two
  counters); never the totals (HT, TVA, TTC, returns, net, the scanned tickets' net, the declared total) nor the cash
  control (expected, counted, difference, the verification, the opening cash, the handover, the net cash). **A section
  left with only zero rows goes with its title and its divider** (PAIEMENTS, MOUVEMENTS DE CAISSE; on the closing ticket
  the methods and the counters); a section with a total always keeps its title. The two slips have only their identity
  and their amounts: the switch changes nothing there. After the screen test: in CONTRÔLE and in the verification's
  table, a method with nothing expected and nothing counted goes too (a method with an amount stays, its difference of 0
  included); every date of an internal ticket prints as dd/MM/yyyy HH:mm (the session and closing tickets printed the
  API's "2026-11-20 16:45:52"); the store ticket of one POS machine (Journal, \`?posMachineId=\`) confirmed on the new
  renderer and its template, its machine named (a fixture).
- The page: the internal documents active, each with its caption and its sample (`sample-internal.js`, rows at zero in
  it), the "Mise en page" tab alone (paper, size, spacing, the switch; no blank lines, no separators); "Copier depuis…"
  between internal tickets (the width, the size, the spacing), between customer tickets as before.
- One old bug, fixed in the new printing only: the closing ticket (family C) wrote the store's name, the methods' names
  and its labels into the HTML unescaped ("Dupont & Fils", a method "<x>": a broken or wrong ticket); the renderer
  escapes every text. Family B escaped already.

Not changed: the i18n keys `admin.sessionReport` and `admin.storeTickets` (read, never renamed), the report DTOs, the
A4 day journal and the session and journal screens; `CASHIER_CLOSING_TICKET` stays a General setup feature setting.

Left: step 5 (head office); screen tests of step 4; the accountant on the locks.

## Step 5 — the head office manages its stores' templates (done 2026-11-27)

Decided with Zein (2026-11-27): configurable per store, off by default (a store keeps its own templates, as today). Not
an `ownership.<x>` line: a switch of the store's row on the head office's Stores page, "Le siège gère les modèles de
tickets" (`ho_store.manages_ticket_templates`), sent with the heartbeat as the other store settings. One set of
templates at the head office, its "Modèles de tickets" page the store's page.

Built (back end, `modules/head-office.md`, "Copies down", "Ticket templates"; `modules/ticket-templates.md`, "The head
office"):
1. `DataDomain.TICKET_TEMPLATES`, no property key; `DataDomain.ownershipDomains()` in the six loops of the ownership
   code (the head office's `providerOf` keeps every domain); `ownerOf(TICKET_TEMPLATES)` is LOCAL.
2. The head office: the column, `StoreService` (create, update), the heartbeat answer's `ticketTemplatesManaged`.
3. The store: `TicketTemplatesManaged` (every linked store) saves the switch in `hol_link_right`; off → on in one
   transaction: the store's own rows copied, dated, to `ticket_template_saved` (never read), deleted, the cursor
   deleted, event `TICKET_TEMPLATES_MANAGED_ON`; on → off: the rows received kept, origin LOCAL, editable, event
   `TICKET_TEMPLATES_MANAGED_OFF` (the event log on the transitions only). A save always sets the origin LOCAL.
4. The head office: `TicketTemplateAPI` in core (both applications); a head office's defaults are the built-in ones
   (nothing read from its Company Information or General setup); it always lists "Garantie"; a save or a reset is a
   change of the copies down (`TicketTemplateDownProvider`, every store, a null text sent as the built-in one); ADMIN of
   a head office gets `read:` / `write:admin-ticket-templates`.
5. The store: `TicketTemplateDownHandler` (active while managed); `DownHandler.active()`; the puller on every linked
   store.
6. The store's API: a save or a reset while managed answers 403 `TICKET_TEMPLATE_MANAGED`; `GET /ticket-templates`
   says `managedByHeadOffice` (and `headOffice` at a head office).

What prints at a managed store: every document's template is the head office's (no exception per document); a document
the head office has no template for prints the built-in default; a reset at the head office reaches the store (removed,
the default prints). Always the store's: the POS machine's paper width, the identity of Company Information (name, MF,
address, phone, logo). The store's feature settings still decide whether a feature exists (F1, to check in the front:
the tombola slip, the loyalty rows, the warranty ticket).

Built (front, parts 7-8, `modules/ticket-templates.md`, "The head office"): the head office's menu entry and page
(the store's page: built-in texts, no POS machine, no old-printing warning, the identity notice), the Stores switch
(confirmation on turning on, the version warning, "Modèles du siège" in the list), the store's page read-only with
"Géré par le siège" and the 403's message; the Journal's names of the two events; fr / en / ar.

The tombola fix (F1, every template): the slip prints only when `TOMBOLA_ENABLED` is on AND the template's block is on
AND a loyalty member (a saved template's switch alone decided before); the General setup notice on it removed; the
page says the block has no effect while the tombola is off.

Part 9: `TicketTemplatesRoundTripTest` (network-tests). The local pair for the screen test: the dev pair
(`pos_headoffice` on 888, `pos_store_b` on 555; no ERP, `erp.sync.enabled=false`), brought from 2.1.0 to 3.5.0 by
the release scripts on 2026-11-27.

After the screen test's first half (2026-11-27): the switch reached the store with the heartbeat (3 min) but the
templates only with the copies down (30 min), and turning it on deleted the store's own at once (the built-in default
printed meanwhile). Now the heartbeat pulls `TICKET_TEMPLATES` while managed, and the store's own are replaced only
once a page of the head office is in hand (`modules/head-office.md`, "Ticket templates"). Store side only.

Left: the rest of the screen test on the dev pair; the accountant on the locks.

## Step 6 — the new printing only (done 2026-11-27)

Zein's decision of 2026-11-27: when the ticket templates work is done, the new printing is the only one. Two parts,
each testable alone.

**Part 1 (done):** every print uses the new printing, the old settings carried into the templates, the old code still
in the tree and never called.
- Before any change, the old code's outputs recorded (`tools/fixtures/`, `tools/old-ticket-snapshots.mjs`): the
  check tools compare the new printing with them, so they outlive the old code.
- `db/3.5.0/update.sql`: the carry block (`modules/ticket-templates.md`, "The carry at the upgrade"); `APP_VERSION` is
  now set to 3.5.0 at the end of the script, as in 3.3.0 and 3.4.0, so "still 3.4.0" means the first pass: a customer
  coming from an older version runs the scripts in order, reaches 3.4.0, and the 3.5.0 script carries once; a second run
  sees 3.5.0. The store guard is "`quick_quantity` or no `ho_store`" (a store database created before the projects
  were split has `ho_store`: `pos_store_b` does).
- Back end: no linked element (`TicketBlocks`: the address off; the thank-you, the label, the loyalty rows on; the
  tombola section on); `linkedValues()` the built-in values; a read keeps a text longer than the limits (only a save
  checks them); `AppConfigAPI` sends `NEW` on every store. `TicketTemplateServiceTest#carriedRows`: the script's own
  JSON (captured from `pos_store_b`) read back complete.
- Front: `newTicketRenderer` always true; `linkedForPrint` the built-in values; a null text the built-in text; the
  section lists as the back end; the "Textes" tab starts from the built-in texts.
- Tested on `pos_store_b` (Zein's go): the old values set, the carry block run by hand (its `APP_VERSION` guard
  replaced, the database being at 3.5.0), the rows read; untouched settings give no row and two more runs no second
  row (in a transaction rolled back); the whole script run again changes nothing.

**Part 2 (done):** delete the old code (`ReceiptTemplate.vue`, `receiptPrintStyles.js`, `printSessionReport.js`,
`receiptBranding.js`, the old branches of the 12 print screens, `ItemSelection`'s `printSessionCloseTicket`),
`ticketRenderer` (API, DTO, app-config), `TICKET_RENDERER` and `TICKET_SHOW_LOYALTY_*` no longer created
(`ZZDataInitializer`) nor shown, the Company Information ticket fields (entity, DTO, service, page; the columns stay),
`showLoyalty*` in `SalesHeaderAPI`, the `LINK_*` constants and `linkedValues`, the notices "Décidé par Modèles de
tickets", `AdminLabels`' three entries, the locales' keys; the tools keep their recorded old side. Also: the three retired General
setup rows hidden (`GeneralSetupService.RETIRED_CODES`), the invoice note's hint for the invoices only, the internal
tickets' print functions without "New" (`printSessionTicket`...), the Journal's `TICKET_TEMPLATES_MANAGED_OFF` saying the
store's own templates print when it has some (the store was switched off before the head office's arrived, or had
carried rows), the French `APP_RELEASE_NOTES` of 3.5.0.

**Next release (after 3.5.0), together:** drop the `company_information` columns `receipt_print_address`,
`receipt_show_thank_you`, `receipt_thank_you_text`, `receipt_show_software_label`, `receipt_footer_text` and the
`general_setup` rows `TICKET_RENDERER`, `TICKET_SHOW_LOYALTY_BALANCE`, `TICKET_SHOW_LOYALTY_EARNED` (kept in 3.5.0 as
source data, read by nothing). `invoice_footer_note` stays (the A4 invoices).

Left: the screen test of part 2; the accountant on the locks.
