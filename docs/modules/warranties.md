# Warranties (store)

A store registers a warranty by hand on the line of a ticket (start and end dates, whole units covered, a note), lists
them (ACTIVE, EXPIRED, USED), marks one used and prints its slip. Pages `WarrantyCreate.vue` (route
`admin-warranty-create`) and `WarrantyList.vue` (`admin-warranty-list`), menu Paramètres & Outils; API `/warranty`
(`WarrantyAPI`, `WarrantyService`, entity `Warranty`, table `warranty`); permissions `read:admin-warranty`,
`write:admin-warranty`. A warranty covers whole units: one on a decimal line is refused (`decimal-quantities.md`).

## The switch: `warranty.enabled` (3.3.0)

| Key | Values | Absent |
|---|---|---|
| `warranty.enabled` | `true` \| `false`, trimmed, any case; blank (3.5.0) = `false` | `false` |

- A store only. The store type file states `warranty.enabled=false`; an installation turns it on in its outside file
  (`configs/machine-model.properties` has the line, commented).
- Blank (the line with nothing after the `=`, 3.5.0) is `false`, as a missing line (`NodeOwnership.moduleSwitch`, as
  `rfid.enabled` and `eventlog.enabled`).
- Any other value stops the startup: `Invalid value '<v>' for property warranty.enabled: true (this
  store registers warranties) or false (no Warranties page)`. `true` on a head office stops it too (as `rfid.enabled`,
  `NodeOwnership.isWarrantySet`).
- `GET /config` field `warrantyEnabled` (last); the startup summary says `warranties on`.
- **On**: the behaviour of 3.2.0, unchanged.
- **Off**: no menu entry, no dashboard tile, no search entry, no permission line on the Roles page (a role keeps its
  warranty permissions, saved with it, not counted); a typed address goes to the dashboard; every `/warranty` endpoint
  answers 404 `{"error": "Warranties are off on this store (warranty.enabled=true turns them on)."}` before reading
  anything (`WarrantyAPITest`). Nothing is deleted: the table and its rows stay, and come back with the line.
- **Off, the warranty ticket's template too** (3.5.0, `TicketTemplateAPI`): `GET /ticket-templates` leaves the
  document `WARRANTY` out, so "Modèles de tickets" lists no "Garantie" and "Copier depuis…" does not offer it; `PUT` and
  `DELETE /ticket-templates/WARRANTY` answer 404 `{"error"}` with the text of `/warranty` (`WarrantyAPI.OFF_MESSAGE`). A
  saved warranty template stays in `ticket_template` and is listed again once the line is back on
  (`TicketTemplateAPITest`).
- Upgrade: `db/3.3.0/update.sql` prints `Warranties exist: add warranty.enabled=true to this store's file.` on a store
  whose `warranty` table has rows. A store already at 3.3.0 that skips the script checks it with
  `SELECT COUNT(*) FROM warranty`.
- The dev, rehearsal and happyness-from-scratch store files (and the local ones) say `true`, so no dev store lost the
  page; no customer file names the key.

## The ticket discount (3.5.0)

`GET /warranty`, `GET /warranty/{id}` and `POST /warranty` send `ticketDiscountFactor` (`@Transient`, not stored): the
share of the sold line's TTC paid after the sale's ticket discount, by the returns screen's rule
(`WarrantyService.ticketDiscountFactor`): a ticket paid partly with points, its ticket discount only
(`ReturnHeaderService.loyaltyHeaderFactor`; the points are a payment); else the ticket's %; else a ticket discount of an
amount, the ticket's total over its lines' TTC (the lines read only then); null without a ticket discount. The new
warranty ticket (`modules/ticket-templates.md`, "The warranty ticket") prints it as "Remise ticket" and "Article sous
garantie" as the amount paid. Test: `WarrantyTicketDiscountTest`.

## The slip

The warranty ticket (3.5.0: its template, document `WARRANTY` of "Modèles de tickets", `src/libs/ticket`; before, `ReceiptTemplate.vue` with `isWarranty`) prints: the title "GARANTIE", "GARANTIE #W-<id>", the warranty
number, the ticket, the item, the dates, "Article sous garantie" and the footer "Présentez ce ticket pour faire valoir
la garantie" / "Valide jusqu'au : <end date>". Only the two Warranties pages print it (on registering, and "print
duplicate"): a sale ticket never carries a warranty text. The Sale ticket fields of Company information (address,
footer text, software label) apply to the slip as to every ticket (`company-information.md`); none is a warranty field.
With the switch off the slip is simply never printed.
