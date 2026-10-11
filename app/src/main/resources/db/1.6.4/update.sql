-- ============================================================
-- Version 1.6.4 — Fonction du membre fidélité
-- ============================================================

UPDATE APP_VERSION SET version = '1.6.4';

INSERT INTO member_function (code, name, display_order, created_by, updated_by) VALUES
    ('BATIMENT',    'Bâtiment',   1, 'System', 'System'),
    ('MECANICIEN',  'Mécanicien', 2, 'System', 'System'),
    ('PLAQUISTE',   'Plaquiste',  3, 'System', 'System'),
    ('MENUISIER',   'Menuisier',  4, 'System', 'System'),
    ('PLOMBIER',    'Plombier',   5, 'System', 'System'),
    ('ELECTRICIEN', 'Electricien',6, 'System', 'System'),
    ('FORGERON',    'Forgeron',   7, 'System', 'System'),
    ('BRICOLEUR',   'Bricoleur',  8, 'System', 'System'),
    ('CARRELEUR',   'Carreleur',  9, 'System', 'System'),
    ('AGRICOLE',    'Agricole',  10, 'System', 'System');


INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.6.4', 'NEW', 'Membre fidélité: nouveau champ obligatoire "Fonction" (ex: Bâtiment, Plombier, Electricien...). Table de référence gérée depuis l''administration via /member-function.');
