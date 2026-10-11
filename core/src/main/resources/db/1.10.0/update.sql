-- ============================================================
-- Version 1.10.0 — Item Group Promotions
-- ============================================================
-- Deployment order:
--   1) Run this script against the database.
--   2) Deploy the 1.10.0 binary.
-- The promotion_group_item join table is normally created by Hibernate
-- ddl-auto=update; it is also created here (idempotently) because the new
-- promotion lookup runs on every POS price calculation — an environment
-- running ddl-auto=validate/none must never boot without this table.
-- No existing table or column is modified by this release.

IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'[dbo].[promotion_group_item]') AND type = N'U')
BEGIN
    CREATE TABLE dbo.promotion_group_item (
        promotion_id BIGINT NOT NULL CONSTRAINT fk_promo_group_item_promotion REFERENCES dbo.promotion(id),
        item_id      BIGINT NOT NULL CONSTRAINT fk_promo_group_item_item      REFERENCES dbo.item(id),
        CONSTRAINT pk_promotion_group_item PRIMARY KEY (promotion_id, item_id)
    );
    CREATE INDEX idx_promo_group_item_item ON dbo.promotion_group_item(item_id);
END;

-- Cross-product benefit target ("buy N of A → benefit on Z"); null = same product
IF COL_LENGTH('dbo.promotion', 'get_item_id') IS NULL
BEGIN
    ALTER TABLE dbo.promotion
        ADD get_item_id BIGINT NULL CONSTRAINT fk_promotion_get_item REFERENCES dbo.item(id);
END;

UPDATE APP_VERSION SET version = '1.10.0';

INSERT INTO APP_RELEASE_NOTES (version, type, description) VALUES
('1.10.0', 'NEW',     'Group promotions: a promotion can now target an arbitrary group of items (new scope "Group of Items"), in addition to a single item, family, or subfamily. Items are picked freely in the promotion form.'),
('1.10.0', 'NEW',     'Cross-product quantity promotions: the benefit of a quantity promotion (percentage, fixed amount, or free units) can now apply to a different product than the purchased one — e.g. "buy 2 of A, get 1 Z free" or "buy 1 from a group, get 10% off Z". Free items are added to the cart automatically; discounts apply when the benefit product is in the cart.'),
('1.10.0', 'IMPROVE', 'Promotion engine resolves group promotions with scope priority Item > Group of Items > SubFamily > Family. Existing promotions are not affected and POS price calculation behavior is unchanged for all existing scopes.');
