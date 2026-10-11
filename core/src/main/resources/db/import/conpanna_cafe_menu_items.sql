/*
 * Import menu — café (familles / sous-familles / articles).
 * Tables: item_family, item_sub_family, item (noms Hibernate snake_case pour SQL Server).
 *
 * - Un seul article "Macchiato / Cappuccino" (ITM-CF-MACCHI-CAPP) + "Cappuccino" séparé (ITM-CF-CAPPUCINO).
 * - Pas de variantes : mojitos / milkshakes / crêpes = un produit par ligne.
 *
 * Exécuter une fois. Si codes déjà présents : changer préfixes FAM_/SUB_/ITM_ ou supprimer avant import.
 */

SET XACT_ABORT ON;
BEGIN TRANSACTION;

/* ---- Families ---- */
INSERT INTO item_family (code, name, description, display_order, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
VALUES
(N'FAM_MENU',     N'Menus & formules',        NULL, 10, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1),
(N'FAM_BOISS',    N'Boissons froides',         NULL, 20, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1),
(N'FAM_GLACE',    N'Glaces & desserts glacés', NULL, 30, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1),
(N'FAM_CAFE',     N'Café & boissons chaudes',  NULL, 40, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1),
(N'FAM_VIENNO',   N'Viennoiserie',             NULL, 50, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1),
(N'FAM_CREPES',   N'Crêpes & brunch',          NULL, 60, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1);

/* ---- Subfamilies ---- */
INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_MENU_PDJ',    N'Petit déjeuner',      NULL, 1, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_MENU';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_BOISS_EAU',  N'Eau & soft',          NULL, 1, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_BOISS';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_BOISS_JUS',  N'Jus',                 NULL, 2, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_BOISS';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_BOISS_COCK', N'Cocktails sans alcool', NULL, 3, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_BOISS';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_BOISS_MOJITO', N'Mojito sans alcool', NULL, 4, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_BOISS';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_GLACE_GLACE', N'Glaces',            NULL, 1, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_GLACE';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_GLACE_MILK',  N'Milkshakes',        NULL, 2, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_GLACE';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_GLACE_SMOOTH', N'Smoothies',        NULL, 3, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_GLACE';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_CAFE_ICED',  N'Café glacé (Iced)', NULL, 1, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_CAFE';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_CAFE_CHAUD', N'Café chaud',        NULL, 2, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_CAFE';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_CAFE_SUPPL', N'Suppléments',       NULL, 3, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_CAFE';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_CAFE_THE',   N'Thé & chocolat chaud', NULL, 4, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_CAFE';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_VIENNO_DEF', N'Viennoiseries',     NULL, 1, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_VIENNO';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_CREP_SUCR',  N'Crêpes sucrées',    NULL, 1, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_CREPES';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_CREP_SALE',  N'Crêpes salées',     NULL, 2, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_CREPES';

INSERT INTO item_sub_family (code, name, description, display_order, item_family_id, erp_external_id, image_filename, created_at, updated_at, created_by, updated_by, active)
SELECT N'SUB_CREP_OMLET', N'Omelettes',         NULL, 3, id, NULL, NULL, SYSDATETIME(), SYSDATETIME(), N'IMPORT', NULL, 1 FROM item_family WHERE code = N'FAM_CREPES';

/* ---- Items (fam_code + sub_code = pas de CASE massif) ---- */
INSERT INTO item (item_code, name, description, type, unit_price, item_family_id, item_sub_family_id, show_in_pos, from_franchise_admin, created_at, updated_at, created_by, active)
SELECT v.item_code, v.name, v.description, N'PRODUCT', v.unit_price, f.id, s.id, 1, 0, SYSDATETIME(), SYSDATETIME(), N'IMPORT', 1
FROM (VALUES
  (N'ITM-MENU-REVEIL',      N'Réveil du matin', NULL, 5.0,   N'FAM_MENU',  N'SUB_MENU_PDJ'),
  (N'ITM-MENU-TICTAC',      N'Tic Tac', NULL, 9.0,           N'FAM_MENU',  N'SUB_MENU_PDJ'),
  (N'ITM-MENU-GOLD',        N'Gold Conpanna', NULL, 18.0,   N'FAM_MENU',  N'SUB_MENU_PDJ'),
  (N'ITM-MENU-GOLD-DUO',    N'Gold Conpanna Duo', NULL, 25.0, N'FAM_MENU', N'SUB_MENU_PDJ'),
  (N'ITM-EAU-1L',           N'Eau minérale 1L', NULL, 3.0,   N'FAM_BOISS', N'SUB_BOISS_EAU'),
  (N'ITM-EAU-05L',          N'Eau 0,5L', NULL, 2.0,           N'FAM_BOISS', N'SUB_BOISS_EAU'),
  (N'ITM-SODA-CAN',         N'Canette soda', NULL, 3.5,     N'FAM_BOISS', N'SUB_BOISS_EAU'),
  (N'ITM-ENER-DRINK',       N'Boisson énergétique', NULL, 5.0, N'FAM_BOISS', N'SUB_BOISS_EAU'),
  (N'ITM-JUS-FRAISE',       N'Jus fraise', NULL, 6.5,        N'FAM_BOISS', N'SUB_BOISS_JUS'),
  (N'ITM-JUS-CITRONNADE',   N'Citronnade', NULL, 4.0,       N'FAM_BOISS', N'SUB_BOISS_JUS'),
  (N'ITM-JUS-CITRON-BRE',   N'Citron brésilien (citron + menthe frais)', NULL, 5.0, N'FAM_BOISS', N'SUB_BOISS_JUS'),
  (N'ITM-JUS-CITRON-AMAND', N'Citron aux amandes', NULL, 6.0, N'FAM_BOISS', N'SUB_BOISS_JUS'),
  (N'ITM-JUS-BANANE',       N'Jus banane', NULL, 7.0,       N'FAM_BOISS', N'SUB_BOISS_JUS'),
  (N'ITM-JUS-KIWI',         N'Jus kiwi', NULL, 8.0,         N'FAM_BOISS', N'SUB_BOISS_JUS'),
  (N'ITM-JUS-PINA',         N'Pina Colada', NULL, 10.0,      N'FAM_BOISS', N'SUB_BOISS_JUS'),
  (N'ITM-JUS-PECHE',        N'Jus pêche', NULL, 7.0,         N'FAM_BOISS', N'SUB_BOISS_JUS'),
  (N'ITM-COCK-ROSA',        N'Rosa Estiva (citron + fraise + glace)', NULL, 9.0, N'FAM_BOISS', N'SUB_BOISS_COCK'),
  (N'ITM-COCK-SWEET',       N'Sweet Summer (fraise + banane + pêche)', NULL, 10.5, N'FAM_BOISS', N'SUB_BOISS_COCK'),
  (N'ITM-COCK-MATR',        N'Matrimonio (datte + banane + fruits secs + miel)', NULL, 10.5, N'FAM_BOISS', N'SUB_BOISS_COCK'),
  (N'ITM-MOJ-VIRGIN',       N'Mojito virgin', NULL, 6.0,     N'FAM_BOISS', N'SUB_BOISS_MOJITO'),
  (N'ITM-MOJ-RED',          N'Mojito red', NULL, 8.0,        N'FAM_BOISS', N'SUB_BOISS_MOJITO'),
  (N'ITM-MOJ-BLUE',         N'Mojito blue', NULL, 8.0,       N'FAM_BOISS', N'SUB_BOISS_MOJITO'),
  (N'ITM-MOJ-GREEN',        N'Mojito green', NULL, 8.0,     N'FAM_BOISS', N'SUB_BOISS_MOJITO'),
  (N'ITM-MOJ-ENER',         N'Mojito énergétique', NULL, 10.0, N'FAM_BOISS', N'SUB_BOISS_MOJITO'),
  (N'ITM-GLC-2B',           N'Glaces 2 boules', NULL, 6.0,   N'FAM_GLACE', N'SUB_GLACE_GLACE'),
  (N'ITM-GLC-3B',           N'Glaces 3 boules', NULL, 8.5,  N'FAM_GLACE', N'SUB_GLACE_GLACE'),
  (N'ITM-GLC-COUPE',        N'Coupe spéciale', NULL, 15.0,  N'FAM_GLACE', N'SUB_GLACE_GLACE'),
  (N'ITM-MS-CHOC',          N'Milkshake chocolat', NULL, 8.0, N'FAM_GLACE', N'SUB_GLACE_MILK'),
  (N'ITM-MS-VAN',           N'Milkshake vanille', NULL, 8.0, N'FAM_GLACE', N'SUB_GLACE_MILK'),
  (N'ITM-MS-FRAIS',         N'Milkshake fraise', NULL, 8.0,  N'FAM_GLACE', N'SUB_GLACE_MILK'),
  (N'ITM-MS-NUT',           N'Milkshake Nutella', NULL, 10.0, N'FAM_GLACE', N'SUB_GLACE_MILK'),
  (N'ITM-MS-OREO',          N'Milkshake Oreo', NULL, 10.0,   N'FAM_GLACE', N'SUB_GLACE_MILK'),
  (N'ITM-MS-SPEC',          N'Milkshake spéculoos', NULL, 10.0, N'FAM_GLACE', N'SUB_GLACE_MILK'),
  (N'ITM-MS-FERR',          N'Milkshake Ferrero', NULL, 10.0, N'FAM_GLACE', N'SUB_GLACE_MILK'),
  (N'ITM-MS-KINDER',        N'Milkshake Kinder', NULL, 10.0, N'FAM_GLACE', N'SUB_GLACE_MILK'),
  (N'ITM-MS-BOUNT',         N'Milkshake Bounty', NULL, 10.0, N'FAM_GLACE', N'SUB_GLACE_MILK'),
  (N'ITM-MS-SNICK',         N'Milkshake Snickers', NULL, 10.0, N'FAM_GLACE', N'SUB_GLACE_MILK'),
  (N'ITM-MS-PIST',          N'Milkshake pistache', NULL, 10.0, N'FAM_GLACE', N'SUB_GLACE_MILK'),
  (N'ITM-SM-RED',           N'Smoothie fruits rouges', NULL, 8.5, N'FAM_GLACE', N'SUB_GLACE_SMOOTH'),
  (N'ITM-SM-BAN',           N'Smoothie banane', NULL, 8.5,    N'FAM_GLACE', N'SUB_GLACE_SMOOTH'),
  (N'ITM-SM-KIWI',          N'Smoothie kiwi', NULL, 8.5,      N'FAM_GLACE', N'SUB_GLACE_SMOOTH'),
  (N'ITM-SM-PASS',          N'Smoothie fruit de la passion', NULL, 8.5, N'FAM_GLACE', N'SUB_GLACE_SMOOTH'),
  (N'ITM-SM-FAB',           N'Smoothie Fabulous (fraise + banane)', NULL, 10.0, N'FAM_GLACE', N'SUB_GLACE_SMOOTH'),
  (N'ITM-SM-TROP',          N'Smoothie Tropical', NULL, 12.0, N'FAM_GLACE', N'SUB_GLACE_SMOOTH'),
  (N'ITM-IC-AM',            N'Iced Americano', NULL, 5.0,    N'FAM_CAFE',  N'SUB_CAFE_ICED'),
  (N'ITM-IC-LAT',           N'Iced Latte', NULL, 5.0,        N'FAM_CAFE',  N'SUB_CAFE_ICED'),
  (N'ITM-IC-ARO',           N'Iced café aromatisé', NULL, 6.5, N'FAM_CAFE', N'SUB_CAFE_ICED'),
  (N'ITM-IC-ZEB',           N'Iced Zebra Latte', NULL, 7.5,   N'FAM_CAFE',  N'SUB_CAFE_ICED'),
  (N'ITM-IC-MATCH',         N'Iced Matcha Latte', NULL, 8.0, N'FAM_CAFE',  N'SUB_CAFE_ICED'),
  (N'ITM-IC-FRAP',          N'Frappuccino', NULL, 7.0,        N'FAM_CAFE',  N'SUB_CAFE_ICED'),
  (N'ITM-CF-ESP',           N'Expresso', NULL, 3.2,          N'FAM_CAFE',  N'SUB_CAFE_CHAUD'),
  (N'ITM-CF-MACCHI-CAPP',   N'Macchiato / Cappuccino', NULL, 3.5, N'FAM_CAFE', N'SUB_CAFE_CHAUD'),
  (N'ITM-CF-AMER',          N'Américano', NULL, 3.5,         N'FAM_CAFE',  N'SUB_CAFE_CHAUD'),
  (N'ITM-CF-LATTE',         N'Café Latte', NULL, 3.8,        N'FAM_CAFE',  N'SUB_CAFE_CHAUD'),
  (N'ITM-CF-NESC',          N'Nescafé', NULL, 3.8,           N'FAM_CAFE',  N'SUB_CAFE_CHAUD'),
  (N'ITM-CF-CAPPUCINO',     N'Cappuccino', NULL, 4.5,        N'FAM_CAFE',  N'SUB_CAFE_CHAUD'),
  (N'ITM-CF-TURC',          N'Café turc', NULL, 4.5,          N'FAM_CAFE',  N'SUB_CAFE_CHAUD'),
  (N'ITM-CF-AFFOG',         N'Affogato', NULL, 6.5,           N'FAM_CAFE',  N'SUB_CAFE_CHAUD'),
  (N'ITM-CF-MATCHA',        N'Matcha Coffee', NULL, 6.0,    N'FAM_CAFE',  N'SUB_CAFE_CHAUD'),
  (N'ITM-CF-ZEBRA',         N'Zebra Latte', NULL, 6.0,        N'FAM_CAFE',  N'SUB_CAFE_CHAUD'),
  (N'ITM-SUP-AROME',        N'Supplément arôme', NULL, 1.8,   N'FAM_CAFE',  N'SUB_CAFE_SUPPL'),
  (N'ITM-SUP-NEST',         N'Supplément Nestlé', NULL, 1.8, N'FAM_CAFE',  N'SUB_CAFE_SUPPL'),
  (N'ITM-SUP-CHANT',        N'Supplément chantilly', NULL, 2.0, N'FAM_CAFE', N'SUB_CAFE_SUPPL'),
  (N'ITM-SUP-TAKE',         N'Supplément take away', NULL, 2.0, N'FAM_CAFE', N'SUB_CAFE_SUPPL'),
  (N'ITM-TH-VERT',          N'Thé vert', NULL, 2.8,          N'FAM_CAFE',  N'SUB_CAFE_THE'),
  (N'ITM-TH-INF',           N'Thé infusion', NULL, 3.5,      N'FAM_CAFE',  N'SUB_CAFE_THE'),
  (N'ITM-TH-AMAND',         N'Thé aux amandes', NULL, 5.0,    N'FAM_CAFE',  N'SUB_CAFE_THE'),
  (N'ITM-CHOC-CH',          N'Chocolat chaud', NULL, 5.5,    N'FAM_CAFE',  N'SUB_CAFE_THE'),
  (N'ITM-CHOC-NUT',         N'Nutella chaud', NULL, 7.0,      N'FAM_CAFE',  N'SUB_CAFE_THE'),
  (N'ITM-VN-CROIS',         N'Croissant', NULL, 2.0,         N'FAM_VIENNO', N'SUB_VIENNO_DEF'),
  (N'ITM-VN-PCHOC',         N'Pain au chocolat', NULL, 2.5, N'FAM_VIENNO', N'SUB_VIENNO_DEF'),
  (N'ITM-VN-CAKE',          N'Cake', NULL, 2.0,              N'FAM_VIENNO', N'SUB_VIENNO_DEF'),
  (N'ITM-VN-AMAND',         N'Amandine', NULL, 3.5,          N'FAM_VIENNO', N'SUB_VIENNO_DEF'),
  (N'ITM-CR-SN-NUT',        N'Crêpe sucrée Nutella', NULL, 9.0, N'FAM_CREPES', N'SUB_CREP_SUCR'),
  (N'ITM-CR-SN-OREO',       N'Crêpe sucrée Oreo', NULL, 9.0, N'FAM_CREPES', N'SUB_CREP_SUCR'),
  (N'ITM-CR-SN-SNICK',      N'Crêpe sucrée Snickers', NULL, 9.0, N'FAM_CREPES', N'SUB_CREP_SUCR'),
  (N'ITM-CR-SN-SPEC',       N'Crêpe sucrée spéculoos', NULL, 9.0, N'FAM_CREPES', N'SUB_CREP_SUCR'),
  (N'ITM-CR-SN-KINDER',     N'Crêpe sucrée Kinder', NULL, 9.0, N'FAM_CREPES', N'SUB_CREP_SUCR'),
  (N'ITM-CR-SN-BOUNT',      N'Crêpe sucrée Bounty', NULL, 9.0, N'FAM_CREPES', N'SUB_CREP_SUCR'),
  (N'ITM-CR-SN-FERR',       N'Crêpe sucrée Ferrero', NULL, 9.0, N'FAM_CREPES', N'SUB_CREP_SUCR'),
  (N'ITM-CR-SN-PIST',       N'Crêpe sucrée pistache', NULL, 11.0, N'FAM_CREPES', N'SUB_CREP_SUCR'),
  (N'ITM-CR-SN-ZEBRA',      N'Crêpe sucrée zebra', NULL, 11.0, N'FAM_CREPES', N'SUB_CREP_SUCR'),
  (N'ITM-CR-SN-NUT-BAN',    N'Crêpe sucrée Nutella + banane', NULL, 12.0, N'FAM_CREPES', N'SUB_CREP_SUCR'),
  (N'ITM-CR-SN-NUT-SEC',    N'Crêpe sucrée Nutella + fruits secs', NULL, 12.0, N'FAM_CREPES', N'SUB_CREP_SUCR'),
  (N'ITM-CR-SN-NUT-FRAIS',  N'Crêpe sucrée Nutella + fraise', NULL, 12.0, N'FAM_CREPES', N'SUB_CREP_SUCR'),
  (N'ITM-CR-SL-THON',       N'Crêpe salée fromage + thon', NULL, 9.0, N'FAM_CREPES', N'SUB_CREP_SALE'),
  (N'ITM-CR-SL-JAMB',       N'Crêpe salée fromage + jambon', NULL, 9.0, N'FAM_CREPES', N'SUB_CREP_SALE'),
  (N'ITM-CR-SL-CONP',       N'Crêpe Conpanna (mozzarella, œufs, thon, gruyère)', NULL, 12.0, N'FAM_CREPES', N'SUB_CREP_SALE'),
  (N'ITM-CR-SL-PEP',        N'Crêpe pepperoni (mozzarella, pepperoni, gruyère)', NULL, 10.0, N'FAM_CREPES', N'SUB_CREP_SALE'),
  (N'ITM-OM-NAT',           N'Omelette nature', NULL, 5.5,   N'FAM_CREPES', N'SUB_CREP_OMLET'),
  (N'ITM-OM-FROM',          N'Omelette fromage', NULL, 6.5,   N'FAM_CREPES', N'SUB_CREP_OMLET'),
  (N'ITM-OM-JF',            N'Omelette jambon + fromage', NULL, 8.0, N'FAM_CREPES', N'SUB_CREP_OMLET'),
  (N'ITM-OM-TF',            N'Omelette thon + fromage', NULL, 8.0, N'FAM_CREPES', N'SUB_CREP_OMLET')
) AS v(item_code, name, description, unit_price, fam_code, sub_code)
JOIN item_family f ON f.code = v.fam_code
JOIN item_sub_family s ON s.code = v.sub_code AND s.item_family_id = f.id;

COMMIT TRANSACTION;
