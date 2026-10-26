[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$To,
    [switch]$Check
)

# Local helper: the Python process receives credentials only through process environment.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pythonPath = Join-Path $projectRoot 'chronic-disease/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $pythonPath)) {
    $pythonPath = (Get-Command python -ErrorAction Stop).Source
}
$previousSender = $env:IHMS_SMTP_TEST_FROM
$previousAuth = $env:IHMS_SMTP_TEST_AUTH_CODE
$previousEncoding = [Console]::OutputEncoding
$secureAuth = $null
$authPointer = [IntPtr]::Zero
$testExit = 2
try {
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    if (-not $env:IHMS_SMTP_TEST_FROM) {
        $env:IHMS_SMTP_TEST_FROM = Read-Host 'Sender QQ email address'
    }
    if (-not $env:IHMS_SMTP_TEST_AUTH_CODE) {
        $secureAuth = Read-Host 'QQ SMTP authorization code (hidden; not your login password)' -AsSecureString
        $authPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureAuth)
        $env:IHMS_SMTP_TEST_AUTH_CODE = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($authPointer)
    }
    $arguments = @('-B', '-X', 'utf8', (Join-Path $PSScriptRoot 'smtp_test.py'), '--to', $To)
    if ($Check) { $arguments += '--check' }
    & $pythonPath @arguments
    $testExit = $LASTEXITCODE
}
finally {
    if ($authPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($authPointer)
    }
    if ($null -ne $secureAuth) { $secureAuth.Dispose() }
    $env:IHMS_SMTP_TEST_FROM = $previousSender
    $env:IHMS_SMTP_TEST_AUTH_CODE = $previousAuth
    [Console]::OutputEncoding = $previousEncoding
}
exit $testExit
