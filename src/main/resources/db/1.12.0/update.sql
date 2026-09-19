-- ============================================================
-- Version 1.12.0 — Paliers de fidélité, portée « Tous les articles »,
--                  corrections fidélité (tickets en attente, conversion, retours)
-- ============================================================
-- Deployment order:
--   1) Run db/1.11.0/update.sql first if the database is still on 1.10.0.
--   2) Run this script against the database.
--   3) Deploy the 1.12.0 binaries (backend WAR and frontend).
-- The application refuses to start while APP_VERSION differs from its own
-- version, so step 2 must be done before step 3.
-- No schema statement in this script: the loyalty_program_tier table (optional
-- earning tiers of a loyalty program) is created automatically by Hibernate
-- ddl-auto=update on first startup. No existing table or column is modified
-- by this release.

UPDATE APP_VERSION SET version = '1.12.0';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.12.0', 'NEW', 'Promotions : nouvelle portée « Tous les articles ». Elle s''applique à tout article qu''aucune promotion plus ciblée (article, groupe d''articles, sous-famille ou famille) ne couvre à ce moment-là.'),
('1.12.0', 'NEW', 'Fidélité : paliers de gain. Un programme peut donner un taux de points différent selon le montant du ticket, par exemple 1 point par TND, 1,5 au-delà de 200 TND et 2 au-delà de 500 TND. Le taux du palier atteint s''applique à tout le ticket, hors timbre fiscal ; un ticket pile sur une limite reste dans le palier inférieur. Sans palier, un programme fonctionne exactement comme avant.'),
('1.12.0', 'FIX', 'Tickets en attente : leur finalisation attribue et consomme désormais les points fidélité comme une vente directe, ajoute la ligne de timbre fiscal, et le ticket imprimé affiche le bloc fidélité.'),
('1.12.0', 'FIX', 'Conversion des points : le plafond d''utilisation est calculé de la même façon en caisse et sur le serveur (montant après remises, hors timbre fiscal). Une conversion importante n''est plus refusée à tort, et une conversion refusée affiche sa vraie raison au lieu d''une erreur technique.'),
('1.12.0', 'FIX', 'Retours : seuls les points gagnés par les articles retournés sont retirés (recalcul sur ce qui reste, paliers compris, jamais deux fois), les points utilisés sur le ticket sont re-crédités en proportion, et la part payée en points n''est plus remboursée en argent. L''écran de retour affiche cette part sur une ligne séparée.');
