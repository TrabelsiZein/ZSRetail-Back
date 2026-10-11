-- ============================================================
-- Version 1.11.0 — Articles de type Pack (Kit / Assemblage)
-- ============================================================
-- Deployment order:
--   1) Run db/1.10.0/update.sql first if the database is still on 1.9.0.
--   2) Run this script against the database.
--   3) Deploy the 1.11.0 binary.
-- The item_composition table is created automatically by Hibernate
-- ddl-auto=update on first startup (kit lookup is on-demand, not on the
-- boot-critical price path). No existing table or column is modified by
-- this release.

UPDATE APP_VERSION SET version = '1.11.0';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.11.0', 'NEW', 'Articles de type Pack : un article « Pack » regroupe plusieurs composants (article + quantité) configurés dans la fiche article. En caisse, le scan ou la sélection d''un pack ajoute automatiquement chaque composant au ticket comme ligne indépendante — le stock, les remises, les promotions et l''export NAV s''appliquent normalement par composant. Le pack lui-même n''a pas de prix et n''apparaît jamais sur le ticket.');
