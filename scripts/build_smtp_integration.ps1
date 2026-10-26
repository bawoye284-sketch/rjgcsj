[CmdletBinding()]
param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$backend = Join-Path (Split-Path -Parent $PSScriptRoot) 'IHMS-admin'
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
$maven = if ($mavenCommand) { $mavenCommand.Source } else { 'D:/Java/apache-maven-3.9.4/bin/mvn.cmd' }
if (-not (Test-Path -LiteralPath $maven)) { throw 'Maven not found. Add Maven to PATH.' }
$buildPom = Join-Path $backend ('smtp-integration-' + [Guid]::NewGuid().ToString('N') + '.build-pom.xml')
$source = [IO.File]::ReadAllText((Join-Path $backend 'pom.xml'))
if ($source -notmatch '<build>' -or $source -match '<finalName>') { throw 'Unexpected build configuration; review finalName first.' }
try {
    [IO.File]::WriteAllText($buildPom, $source.Replace('<build>', '<build><finalName>personal-health-api-smtp-integration</finalName>'), [Text.UTF8Encoding]::new($false))
    Push-Location $backend
    try {
        $arguments = @('-f', $buildPom)
        if ($Offline) {
            if (-not (Test-Path -LiteralPath 'target/alert-build-settings.xml')) { throw 'Offline settings missing; use normal online build.' }
            $arguments += @('-gs', 'target/alert-build-settings.xml', '-s', 'target/alert-build-settings.xml', '-o', '-Dmaven.repo.local=.build-tools/repo')
        }
        & $maven @arguments package
        $buildExit = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    # Only this invocation's temporary POM, within the known project directory.
    if (Test-Path -LiteralPath $buildPom) { Remove-Item -LiteralPath $buildPom }
}
exit $buildExit
