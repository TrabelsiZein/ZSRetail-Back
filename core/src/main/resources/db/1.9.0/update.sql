-- ============================================================
-- Version 1.9.0 — Dynamic Role & Permission Management
-- ============================================================
-- Deployment order:
--   1) Run this script against the database.
--   2) Deploy the 1.9.0 binary.
-- The app_role, app_role_permission tables and app_role_id column
-- on user_account are created automatically by Hibernate ddl-auto=update.
-- ZZDataInitializer.ensureDefaultRoles() seeds ADMIN/RESPONSIBLE/POS_USER
-- roles and migrates existing users on first startup.

UPDATE APP_VERSION SET version = '1.9.0';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.9.0', 'NEW',     'Role & Permission Management: administrators can now create custom roles with fine-grained permission sets. Each user is assigned a role; permissions are returned on login and drive all menu/feature visibility on the frontend.'),
('1.9.0', 'NEW',     'Three default roles are seeded on startup: Administrateur (full access), Responsable (store manager access), Caissier (POS cashier interface only). Existing users are automatically migrated to their matching default role.'),
('1.9.0', 'IMPROVE', 'Backend API endpoints are no longer role-gated individually; access control is handled by the frontend permission system. Authentication is still required for all admin endpoints.');
