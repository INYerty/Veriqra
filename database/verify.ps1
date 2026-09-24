#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$MySql,
    [Parameter(Mandatory)][ValidatePattern('^veriqra_v1_verify_[a-z0-9_]+$')][string]$Database,
    [string]$Server = '127.0.0.1',
    [int]$Port = 3306,
    [string]$User = 'root',
    [string]$LoginPath,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'verification')
)
$ErrorActionPreference = 'Stop'
$clientPath = (Resolve-Path -LiteralPath $MySql).Path
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$evidenceRoot = (Resolve-Path -LiteralPath $OutputDirectory).Path
function Invoke-ValidationSql([string]$Sql, [string]$LogName, [bool]$SelectDatabase = $true) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new($clientPath)
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    $startInfo.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $startInfo.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    $clientArguments = @('--no-defaults')
    if ($LoginPath) { $clientArguments += "--login-path=$LoginPath" }
    $clientArguments += @('--protocol=tcp', "--host=$Server", "--port=$Port",
        "--user=$User", '--default-character-set=utf8mb4', '--batch', '--raw', '--show-warnings')
    if ($SelectDatabase) { $clientArguments += "--database=$Database" }
    foreach ($argument in $clientArguments) { $startInfo.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($startInfo)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.Write($Sql)
    $process.StandardInput.Close()
    $process.WaitForExit()
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $evidenceRoot $LogName), $stdout + $stderr, [Text.UTF8Encoding]::new($false))
    $exitCode = $process.ExitCode
    $process.Dispose()
    if ($exitCode -ne 0) { throw "$LogName failed (exit $exitCode): $stderr" }
    return $stdout
}
$versionResult = Invoke-ValidationSql 'SELECT VERSION();' 'version.tsv' $false
if (($versionResult -split "\r?\n")[1] -cne '8.0.46') { throw 'This validation targets exactly MySQL 8.0.46.' }
# CREATE without IF NOT EXISTS deliberately refuses an existing database.
$null = Invoke-ValidationSql "CREATE DATABASE $Database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" 'create-database.tsv' $false
foreach ($fileName in @('schema-v1-frozen.sql','seed.sql','constraint-tests.sql','queries.sql','service-invariants.sql','inspect.sql')) {
    $sql = [IO.File]::ReadAllText((Join-Path $PSScriptRoot $fileName))
    $result = Invoke-ValidationSql $sql ($fileName + '.tsv')
    if ($fileName -eq 'constraint-tests.sql' -and $result -notmatch '(?m)^178\t178\t0\r?$') {
        throw 'Expected all 178 constraint/fixture checks to pass.'
    }
    if ($fileName -eq 'service-invariants.sql') {
        $violations = @($result -split "\r?\n" | Where-Object { $_ -match '^([a-z_]+)\t([0-9]+)$' -and [int]$Matches[2] -ne 0 })
        if ($violations.Count) { throw "Fixture Service invariants failed: $($violations -join ', ')" }
        if (@($result -split "\r?\n" | Where-Object { $_ -match '^[a-z_]+\t0$' }).Count -ne 22) {
            throw 'Expected 22 zero-violation fixture checks.'
        }
    }
    if ($fileName -eq 'inspect.sql') {
        $expectedCounts = @{'tables'=19;'columns'=154;'PRIMARY KEY'=19;'FOREIGN KEY'=40;'UNIQUE'=13;'CHECK'=51;'all_indexes'=56;'ordinary_indexes'=24;'routines'=0;'triggers'=0;'views'=0}
        foreach ($kind in $expectedCounts.Keys) {
            $pattern = '(?m)^' + [regex]::Escape($kind) + "\t" + $expectedCounts[$kind] + '\r?$'
            if ($result -notmatch $pattern) { throw "Unexpected object count: $kind" }
        }
    }
    Write-Output "${fileName}: PASS"
}
Write-Output "Validated $Database on MySQL 8.0.46; evidence: $evidenceRoot"
# Keep the disposable database for inspection. No database or server is dropped/stopped here.
