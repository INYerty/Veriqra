#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$MySql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    [string]$TestConfig = (Join-Path $PSScriptRoot '..\config\database-test.local.properties'),
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$properties = @{}
foreach ($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $TestConfig).Path)) {
    if ($line -match '^([^#=]+)=(.*)$') { $properties[$Matches[1]] = $Matches[2] }
}
if ($properties.jdbcUrl -cne 'jdbc:mysql://127.0.0.1:3306/veriqra_test_r1' -or
    $properties.username -cne 'veriqra_test' -or [string]::IsNullOrEmpty($properties.password)) {
    throw 'Migration verification only accepts the existing isolated veriqra_test_r1 account on loopback.'
}
if ($env:VERIQRA_DB_CONFIG -or $env:VERIQRA_DB_URL) {
    throw 'Remove application DB overrides before migration verification.'
}
$clientPath = (Resolve-Path -LiteralPath $MySql).Path
$env:MYSQL_PWD = $properties.password
function Invoke-TestSql([string]$sql) {
    $start = [Diagnostics.ProcessStartInfo]::new($clientPath)
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    $start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $start.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($argument in @('--no-defaults','--protocol=tcp','--host=127.0.0.1','--port=3306',
            '--user=veriqra_test','--database=veriqra_test_r1','--default-character-set=utf8mb4',
            '--batch','--raw','--skip-column-names')) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.Write($sql)
        $process.StandardInput.Close()
        $process.WaitForExit()
        $out = $stdout.GetAwaiter().GetResult()
        $err = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "MySQL migration verification failed: $err" }
        return $out.Trim()
    } finally { $process.Dispose() }
}
try {
    if ((Invoke-TestSql 'SELECT VERSION();') -cne '8.0.46') { throw 'Expected MySQL 8.0.46.' }
    if ((Invoke-TestSql 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();') -cne '0') {
        throw 'The isolated test schema is not empty; refusing to modify it.'
    }
    if ($CheckOnly) { Write-Output 'TEST_SCHEMA_CLEAN: 0 tables.'; return }
    $frozen = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'schema-v1-frozen.sql'))
    $migration = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'migrations\20260924-admin.sql'))
    $current = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'schema.sql'))
    $names = @([regex]::Matches($current, '(?m)^CREATE TABLE `([^`]+)`') | ForEach-Object { $_.Groups[1].Value })
    if ($names.Count -ne 24) { throw 'Expected 24 current table definitions.' }
    try {
        $null = Invoke-TestSql $frozen
        if ((Invoke-TestSql 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();') -cne '19') {
            throw 'Frozen install did not create 19 tables.'
        }
        $null = Invoke-TestSql "INSERT INTO users(username,display_name,password_hash,system_role,status) VALUES('migration_probe','Migration Probe','fixture-not-loginable','USER','ACTIVE');"
        $null = Invoke-TestSql $migration
        $result = Invoke-TestSql 'SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()),(SELECT COUNT(*) FROM credit_accounts),(SELECT COALESCE(SUM(balance),-1) FROM credit_accounts),(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name=''ix_users_admin_lock'');'
        if ($result -cne "24`t1`t0`t3") { throw "Unexpected migration result: $result" }
        $objects = Invoke-TestSql "SELECT (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()),(SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND constraint_type='PRIMARY KEY'),(SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND constraint_type='FOREIGN KEY'),(SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND constraint_type='UNIQUE'),(SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND constraint_type='CHECK'),(SELECT COUNT(DISTINCT table_name,index_name) FROM information_schema.statistics WHERE table_schema=DATABASE());"
        if ($objects -cne "200`t24`t46`t14`t57`t79") { throw "Unexpected Administration object counts: $objects" }
        Write-Output 'ADMIN_MIGRATION_PASS: 19 -> 24 tables; 200 columns, 24 PK, 46 FK, 14 UNIQUE, 57 CHECK, 79 indexes; old user has zero-balance account.'
    } finally {
        [array]::Reverse($names)
        foreach ($name in $names) { $null = Invoke-TestSql ('DROP TABLE IF EXISTS `' + $name + '`;') }
        if ((Invoke-TestSql 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();') -cne '0') {
            throw 'Isolated test schema cleanup did not finish.'
        }
        Write-Output 'TEST_SCHEMA_CLEAN: 0 tables.'
    }
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
