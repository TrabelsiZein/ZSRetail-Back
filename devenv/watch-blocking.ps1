# Diagnostic: every 2 s, writes the blocking chains of SQL Server (blocked sessions and what their blocker last ran,
# with its open transaction count) to C:\zsretail-dev\logs\blocking.txt. Read only (DMVs). Stop it with Ctrl+C.
param([int]$Seconds = 1800)
. "$PSScriptRoot\common.ps1"
$out = Join-Path $DevRoot 'logs\blocking.txt'
$query = @"
SELECT CONVERT(varchar, SYSDATETIME(), 108) + ' ' + DB_NAME(r.database_id) + ' session ' + CAST(r.session_id AS varchar)
 + ' waits ' + CAST(r.wait_time AS varchar) + 'ms (' + ISNULL(r.wait_type, '') + ' ' + ISNULL(r.wait_resource, '') + ') on '
 + CAST(r.blocking_session_id AS varchar) + ' [open tran ' + CAST(ISNULL(b.open_transaction_count, -1) AS varchar) + ', ' + ISNULL(b.status, '')
 + ', ' + ISNULL(b.program_name, '') + ', last ' + ISNULL(CONVERT(varchar, b.last_request_end_time, 108), '') + '] waiting: '
 + REPLACE(LEFT(ISNULL(tw.text, ''), 200), CHAR(10), ' ') + ' || blocker last: ' + REPLACE(LEFT(ISNULL(tb.text, ''), 300), CHAR(10), ' ')
FROM sys.dm_exec_requests r
LEFT JOIN sys.dm_exec_sessions b ON b.session_id = r.blocking_session_id
LEFT JOIN sys.dm_exec_connections cb ON cb.session_id = r.blocking_session_id
OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) tw
OUTER APPLY sys.dm_exec_sql_text(cb.most_recent_sql_handle) tb
WHERE r.blocking_session_id <> 0
"@
# Opened once, shared for reading and writing, so a reader (tail -f) never blocks the writes
$stream = New-Object IO.FileStream($out, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite)
$writer = New-Object IO.StreamWriter($stream, (New-Object Text.UTF8Encoding($false)))
$writer.AutoFlush = $true
$end = (Get-Date).AddSeconds($Seconds)
try {
	while ((Get-Date) -lt $end) {
		try {
			foreach ($row in @(Invoke-DevSql 'headoffice' $query 'master')) { $writer.WriteLine($row) }
		} catch {
			$writer.WriteLine("watch error: " + $_.Exception.Message)
		}
		Start-Sleep -Seconds 2
	}
} finally {
	$writer.Dispose()
}
