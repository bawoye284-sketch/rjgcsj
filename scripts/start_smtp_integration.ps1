[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$To,
    [Parameter(Mandatory = $true)][string]$DatabaseUrl,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($To -notmatch '^[A-Za-z0-9.!#$%&''+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$') { throw 'Specify exactly one test mailbox.' }
if ($DatabaseUrl -cnotmatch '^jdbc:mysql://(?:127\.0\.0\.1|localhost):3306/ihms_smtp_test_[a-z0-9_]{1,40}(?:\?[^\r\n]*)?$') {
    throw 'Use a separate local MySQL database named ihms_smtp_test_... on port 3306.'
}
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
$jar = Join-Path $projectRoot 'IHMS-admin/target/personal-health-api-smtp-integration.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Package the backend first.' }
if ($Check) { Write-Output 'Configuration syntax checked; no connection, email or database change was made.'; exit 0 }

# Environment only: never put credentials in command arguments or generated files.
$names = @('SPRING_PROFILES_ACTIVE','IHMS_SMTP_ALLOWED_RECIPIENTS','IHMS_SMTP_TEST_MYSQL_URL',
    'IHMS_SMTP_TEST_MYSQL_USERNAME','IHMS_SMTP_TEST_MYSQL_PASSWORD','IHMS_SMTP_TEST_FROM','IHMS_SMTP_TEST_AUTH_CODE')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
function Read-TemporarySecret([string]$Prompt) {
    $value = Read-Host $Prompt -AsSecureString
    $pointer = [IntPtr]::Zero
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        $value.Dispose()
    }
}
try {
    $env:SPRING_PROFILES_ACTIVE = 'smtp-integration'
    $env:IHMS_SMTP_ALLOWED_RECIPIENTS = $To
    $env:IHMS_SMTP_TEST_MYSQL_URL = $DatabaseUrl
    if (-not $env:IHMS_SMTP_TEST_MYSQL_USERNAME) { $env:IHMS_SMTP_TEST_MYSQL_USERNAME = Read-Host 'Isolated MySQL account' }
    if (-not $env:IHMS_SMTP_TEST_MYSQL_PASSWORD) { $env:IHMS_SMTP_TEST_MYSQL_PASSWORD = Read-TemporarySecret 'Isolated MySQL password (hidden)' }
    if (-not $env:IHMS_SMTP_TEST_FROM) { $env:IHMS_SMTP_TEST_FROM = Read-Host 'Sender QQ mailbox' }
    if (-not $env:IHMS_SMTP_TEST_AUTH_CODE) { $env:IHMS_SMTP_TEST_AUTH_CODE = Read-TemporarySecret 'QQ SMTP authorization code (hidden)' }
    Push-Location (Join-Path $projectRoot 'IHMS-admin')
    try {
        & $java -jar $jar --spring.profiles.active=smtp-integration --server.address=127.0.0.1 --server.port=21290
        $runExit = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
exit $runExit
