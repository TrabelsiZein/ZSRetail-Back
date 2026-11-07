/*
 * ZS Retail — add items to an ITEM_GROUP promotion, directly in the database (SQL Server).
 *
 * Purpose
 *   Adds items, by item code, to the item group of one ITEM_GROUP promotion (table promotion_group_item,
 *   the @JoinTable of Promotion.groupItems). Items already in the group are left as they are; nothing is removed.
 *
 * How to use
 *   1. Run it on the database of the installation that owns the promotion (a store database).
 *   2. Fill @promoCode (promotion.code) and the item codes (item.item_code) below.
 *   3. Run the whole script as one batch (no GO inside). It stops with a message when the promotion does not exist,
 *      is not ITEM_GROUP, or has already been used in sales (see @allowUsedPromotion).
 *   4. Read the messages (unknown codes) and the final list of the group.
 *
 * Notes
 *   - No restart needed: the till reads the promotions from the database at each price calculation (no cache);
 *     the admin page shows the new items once its list is reloaded.
 *   - The admin page refuses to change the items of a promotion used in sales. This script follows that rule unless
 *     @allowUsedPromotion = 1 is set on purpose (past sales are not changed either way).
 *   - The promotion's audit columns (updated_at, ...) are not touched.
 */
SET NOCOUNT ON;
SET XACT_ABORT ON;

DECLARE @promoCode NVARCHAR(100) = N'PROMO_CODE';   -- to fill: promotion.code
DECLARE @allowUsedPromotion BIT = 0;                -- 1 = add items even if the promotion was used in sales

DECLARE @codes TABLE (code NVARCHAR(255) NOT NULL);
INSERT INTO @codes (code) VALUES (N'ITEM1'), (N'ITEM2'), (N'ITEM3');   -- to fill: item.item_code

-- Codes as typed, trimmed, once each
DECLARE @wanted TABLE (code NVARCHAR(255) NOT NULL PRIMARY KEY);
INSERT INTO @wanted (code)
SELECT DISTINCT LTRIM(RTRIM(code)) FROM @codes WHERE LTRIM(RTRIM(code)) <> N'';

-- 1. The promotion: it exists and its scope is ITEM_GROUP
DECLARE @promotionId BIGINT, @scope NVARCHAR(30), @promotionName NVARCHAR(255);
SELECT @promotionId = id, @scope = scope, @promotionName = name FROM promotion WHERE code = @promoCode;

IF @promotionId IS NULL
BEGIN
	PRINT N'Stopped: no promotion with code ' + @promoCode + N'.';
	RETURN;
END;
IF @scope <> N'ITEM_GROUP'
BEGIN
	PRINT N'Stopped: promotion ' + @promoCode + N' has scope ' + @scope + N', not ITEM_GROUP.';
	RETURN;
END;

DECLARE @usages INT =
	(SELECT COUNT(*) FROM sales_line WHERE promotion_id = @promotionId)
	+ (SELECT COUNT(*) FROM sales_header WHERE promotion_id = @promotionId);
IF @usages > 0 AND @allowUsedPromotion = 0
BEGIN
	PRINT N'Stopped: promotion ' + @promoCode + N' is used in ' + CAST(@usages AS NVARCHAR(20))
		+ N' sale line(s)/header(s); the admin page locks its items. Set @allowUsedPromotion = 1 to add them anyway.';
	RETURN;
END;

PRINT N'Promotion ' + @promoCode + N' (id ' + CAST(@promotionId AS NVARCHAR(20)) + N', ' + ISNULL(@promotionName, N'') + N').';

-- 2. Codes with no item: listed and printed as a warning, the others are still added
SELECT w.code AS unknown_item_code
FROM @wanted w
WHERE NOT EXISTS (SELECT 1 FROM item i WHERE i.item_code = w.code)
ORDER BY w.code;

DECLARE @unknown NVARCHAR(MAX);
SELECT @unknown = COALESCE(@unknown + N', ', N'') + w.code
FROM @wanted w
WHERE NOT EXISTS (SELECT 1 FROM item i WHERE i.item_code = w.code);
IF @unknown IS NOT NULL
	PRINT N'Warning: no item with code(s): ' + @unknown + N' (not added).';

-- 3. The found items not already in the group, in one transaction
DECLARE @added INT = 0;
BEGIN TRY
	BEGIN TRANSACTION;

	INSERT INTO promotion_group_item (promotion_id, item_id)
	SELECT @promotionId, i.id
	FROM item i
	JOIN @wanted w ON w.code = i.item_code
	WHERE NOT EXISTS (SELECT 1 FROM promotion_group_item g WHERE g.promotion_id = @promotionId AND g.item_id = i.id);
	SET @added = @@ROWCOUNT;

	COMMIT TRANSACTION;
	PRINT CAST(@added AS NVARCHAR(20)) + N' item(s) added to the group.';
END TRY
BEGIN CATCH
	IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
	PRINT N'Failed, nothing added: ' + ERROR_MESSAGE();
	THROW;
END CATCH;

-- 4. The group as it is now
SELECT i.item_code, i.name
FROM promotion_group_item g
JOIN item i ON i.id = g.item_id
WHERE g.promotion_id = @promotionId
ORDER BY i.item_code;

SELECT COUNT(*) AS group_item_count FROM promotion_group_item WHERE promotion_id = @promotionId;
