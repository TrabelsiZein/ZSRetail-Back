-- Boot 4 migration, step 1.8: the schema of a database as lines, one per object of dbo, sorted (core.support.SchemaCatalog).
-- Read only, catalog views only. Names SQL Server makes up (is_system_named) are left out; flyway_schema_history too.
-- One line per object of the dbo schema, sorted; names SQL Server makes up (is_system_named) are left out
WITH cols AS (
  SELECT c.object_id, c.column_id, c.name COLLATE DATABASE_DEFAULT AS name,
    t.name COLLATE DATABASE_DEFAULT + CASE
      WHEN t.name COLLATE DATABASE_DEFAULT IN ('varchar','char','varbinary','binary') THEN '(' + CASE WHEN c.max_length = -1 THEN 'max' ELSE CAST(c.max_length AS varchar(10)) END + ')'
      WHEN t.name COLLATE DATABASE_DEFAULT IN ('nvarchar','nchar') THEN '(' + CASE WHEN c.max_length = -1 THEN 'max' ELSE CAST(c.max_length / 2 AS varchar(10)) END + ')'
      WHEN t.name COLLATE DATABASE_DEFAULT IN ('decimal','numeric') THEN '(' + CAST(c.precision AS varchar(5)) + ',' + CAST(c.scale AS varchar(5)) + ')'
      WHEN t.name COLLATE DATABASE_DEFAULT IN ('datetime2','time','datetimeoffset') THEN '(' + CAST(c.scale AS varchar(5)) + ')'
      WHEN t.name COLLATE DATABASE_DEFAULT = 'float' THEN '(' + CAST(c.precision AS varchar(5)) + ')'
      ELSE '' END AS type_text,
    c.is_nullable, c.is_identity, c.default_object_id
  FROM sys.columns c JOIN sys.types t ON t.user_type_id = c.user_type_id
),
tabs AS (SELECT object_id, name FROM sys.tables WHERE schema_id = SCHEMA_ID('dbo') AND name <> 'flyway_schema_history' AND is_ms_shipped = 0),
idxcols AS (
  SELECT ic.object_id, ic.index_id,
    STUFF((SELECT ', ' + c2.name COLLATE DATABASE_DEFAULT + CASE WHEN ic2.is_descending_key = 1 THEN ' desc' ELSE '' END FROM sys.index_columns ic2 JOIN sys.columns c2 ON c2.object_id = ic2.object_id AND c2.column_id = ic2.column_id
           WHERE ic2.object_id = ic.object_id AND ic2.index_id = ic.index_id AND ic2.is_included_column = 0 ORDER BY ic2.key_ordinal FOR XML PATH('')), 1, 2, '') AS keys,
    STUFF((SELECT ', ' + c2.name COLLATE DATABASE_DEFAULT FROM sys.index_columns ic2 JOIN sys.columns c2 ON c2.object_id = ic2.object_id AND c2.column_id = ic2.column_id
           WHERE ic2.object_id = ic.object_id AND ic2.index_id = ic.index_id AND ic2.is_included_column = 1 ORDER BY c2.name COLLATE DATABASE_DEFAULT FOR XML PATH('')), 1, 2, '') AS included
  FROM sys.index_columns ic GROUP BY ic.object_id, ic.index_id
)
SELECT line FROM (
  SELECT 'TABLE ' + t.name COLLATE DATABASE_DEFAULT AS line FROM tabs t
  UNION ALL
  SELECT 'COLUMN ' + t.name COLLATE DATABASE_DEFAULT + '.' + c.name COLLATE DATABASE_DEFAULT + ' ' + c.type_text + CASE WHEN c.is_nullable = 1 THEN ' null' ELSE ' not null' END
       + CASE WHEN c.is_identity = 1 THEN ' identity' ELSE '' END
       + ISNULL(' default ' + dc.definition COLLATE DATABASE_DEFAULT, '')
       + CASE WHEN dc.object_id IS NOT NULL AND dc.is_system_named = 0 THEN ' (' + dc.name COLLATE DATABASE_DEFAULT + ')' ELSE '' END
  FROM tabs t JOIN cols c ON c.object_id = t.object_id LEFT JOIN sys.default_constraints dc ON dc.object_id = c.default_object_id
  UNION ALL
  SELECT CASE WHEN i.is_primary_key = 1 THEN 'PRIMARY KEY ' WHEN i.is_unique_constraint = 1 THEN 'UNIQUE CONSTRAINT ' WHEN i.is_unique = 1 THEN 'UNIQUE INDEX ' ELSE 'INDEX ' END
       + t.name COLLATE DATABASE_DEFAULT + ' (' + x.keys + ')' + ISNULL(' include (' + x.included + ')', '') + ISNULL(' where ' + i.filter_definition COLLATE DATABASE_DEFAULT, '')
       + CASE WHEN i.type_desc COLLATE DATABASE_DEFAULT = 'CLUSTERED' AND i.is_primary_key = 0 THEN ' clustered' WHEN i.type_desc COLLATE DATABASE_DEFAULT = 'NONCLUSTERED' AND i.is_primary_key = 1 THEN ' nonclustered' ELSE '' END
       + CASE WHEN kc.object_id IS NOT NULL AND kc.is_system_named = 1 THEN '' ELSE ' ' + i.name COLLATE DATABASE_DEFAULT END
  FROM tabs t JOIN sys.indexes i ON i.object_id = t.object_id AND i.type > 0 JOIN idxcols x ON x.object_id = i.object_id AND x.index_id = i.index_id
  LEFT JOIN sys.key_constraints kc ON kc.parent_object_id = i.object_id AND kc.unique_index_id = i.index_id
  UNION ALL
  SELECT 'FOREIGN KEY ' + t.name COLLATE DATABASE_DEFAULT + ' ('
       + STUFF((SELECT ', ' + pc.name COLLATE DATABASE_DEFAULT FROM sys.foreign_key_columns fkc JOIN sys.columns pc ON pc.object_id = fkc.parent_object_id AND pc.column_id = fkc.parent_column_id WHERE fkc.constraint_object_id = fk.object_id ORDER BY fkc.constraint_column_id FOR XML PATH('')), 1, 2, '')
       + ') -> ' + OBJECT_NAME(fk.referenced_object_id) COLLATE DATABASE_DEFAULT + ' ('
       + STUFF((SELECT ', ' + rc.name COLLATE DATABASE_DEFAULT FROM sys.foreign_key_columns fkc JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id WHERE fkc.constraint_object_id = fk.object_id ORDER BY fkc.constraint_column_id FOR XML PATH('')), 1, 2, '')
       + ')' + CASE WHEN fk.delete_referential_action <> 0 THEN ' on delete ' + fk.delete_referential_action_desc COLLATE DATABASE_DEFAULT ELSE '' END
       + CASE WHEN fk.update_referential_action <> 0 THEN ' on update ' + fk.update_referential_action_desc COLLATE DATABASE_DEFAULT ELSE '' END
       + CASE WHEN fk.is_disabled = 1 THEN ' disabled' ELSE '' END
       + CASE WHEN fk.is_system_named = 1 THEN '' ELSE ' ' + fk.name COLLATE DATABASE_DEFAULT END
  FROM tabs t JOIN sys.foreign_keys fk ON fk.parent_object_id = t.object_id
  UNION ALL
  SELECT 'CHECK ' + t.name COLLATE DATABASE_DEFAULT + ' ' + cc.definition COLLATE DATABASE_DEFAULT + CASE WHEN cc.is_system_named = 1 THEN '' ELSE ' ' + cc.name COLLATE DATABASE_DEFAULT END
  FROM tabs t JOIN sys.check_constraints cc ON cc.parent_object_id = t.object_id
  UNION ALL
  SELECT 'VIEW ' + v.name COLLATE DATABASE_DEFAULT FROM sys.views v WHERE v.schema_id = SCHEMA_ID('dbo')
  UNION ALL
  SELECT 'ROUTINE ' + o.type_desc COLLATE DATABASE_DEFAULT + ' ' + o.name COLLATE DATABASE_DEFAULT FROM sys.objects o WHERE o.schema_id = SCHEMA_ID('dbo') AND o.type IN ('P','FN','IF','TF','TR') AND o.is_ms_shipped = 0
  UNION ALL
  SELECT 'SEQUENCE ' + s.name COLLATE DATABASE_DEFAULT FROM sys.sequences s WHERE s.schema_id = SCHEMA_ID('dbo')
) lines
ORDER BY line COLLATE Latin1_General_BIN2;
