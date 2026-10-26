[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$To,
    [string]$UserAccount = 'demo_elder',
    [switch]$EnableRealMail,
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
if ($To -notmatch '^[A-Za-z0-9.!#$%&''+/=?^_`{|}~-]+@qq\.com$') { throw 'Specify exactly one QQ test mailbox.' }
if ([string]::IsNullOrWhiteSpace($UserAccount)) { throw 'Specify the ordinary user account that owns this test.' }
if (-not $EnableRealMail -and -not $Check) { throw 'Real mail is OFF. Add -EnableRealMail only when ready for acceptance.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'IHMS-admin/target/personal-health-api-smtp-integration.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Run scripts/build_smtp_integration.ps1 first.' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
if ($Check) { Write-Output 'Local H2 mail parameters checked. No database connection, process start or email.'; exit 0 }
if ([System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq 21090) {
    throw 'Port 21090 is already in use. Close the existing backend console normally before starting controlled mail mode. Do not delete H2 files.'
}
$names = @('SPRING_PROFILES_ACTIVE','IHMS_LOCAL_MAIL_ENABLED','IHMS_LOCAL_MAIL_MODE','IHMS_LOCAL_MAIL_USER','IHMS_LOCAL_MAIL_TO','IHMS_SMTP_TEST_FROM','IHMS_SMTP_TEST_AUTH_CODE')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$secureAuth = $null
$authPointer = [IntPtr]::Zero
try {
    $env:SPRING_PROFILES_ACTIVE = 'demo,local-mail-test'
    $env:IHMS_LOCAL_MAIL_ENABLED = 'true'
    $env:IHMS_LOCAL_MAIL_MODE = 'SMTP'
    $env:IHMS_LOCAL_MAIL_USER = $UserAccount
    $env:IHMS_LOCAL_MAIL_TO = $To
    if (-not $env:IHMS_SMTP_TEST_FROM) { $env:IHMS_SMTP_TEST_FROM = Read-Host 'Sender QQ mailbox' }
    if (-not $env:IHMS_SMTP_TEST_AUTH_CODE) {
        $secureAuth = Read-Host 'QQ SMTP authorization code (hidden)' -AsSecureString
        $authPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureAuth)
        $env:IHMS_SMTP_TEST_AUTH_CODE = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($authPointer)
    }
    if (-not $env:IHMS_SMTP_TEST_FROM -or -not $env:IHMS_SMTP_TEST_AUTH_CODE) { throw 'Sender and SMTP authorization code are required.' }
    Push-Location (Join-Path $projectRoot 'IHMS-admin')
    try {
        # Same demo datasource/model configuration and port as the existing project.
        # Do not run alongside an existing backend holding the same H2 file.
        Write-Output 'Starting port 21090 with demo,local-mail-test; real mail limited to one account/mailbox and new events only.'
        & $java -jar $jar '--spring.profiles.active=demo,local-mail-test' --server.address=127.0.0.1 --server.port=21090
        $runExit = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    if ($authPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($authPointer) }
    if ($null -ne $secureAuth) { $secureAuth.Dispose() }
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
exit $runExit
