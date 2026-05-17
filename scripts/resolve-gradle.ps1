$ErrorActionPreference = 'Stop'

$version = '9.2.1'
$distributionUrl = "https://services.gradle.org/distributions/gradle-$version-bin.zip"

$roots = @()
if ($env:GRADLE_USER_HOME) {
    $roots += Join-Path $env:GRADLE_USER_HOME 'wrapper\dists'
}
if ($env:USERPROFILE) {
    $roots += Join-Path $env:USERPROFILE '.gradle\wrapper\dists'
}

foreach ($root in $roots) {
    if (-not (Test-Path -LiteralPath $root)) {
        continue
    }

    $gradleBat = Get-ChildItem -LiteralPath $root -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -like "*gradle-$version*" } |
        Select-Object -First 1 -ExpandProperty FullName

    if ($gradleBat) {
        Write-Output $gradleBat
        exit 0
    }
}

$gradleUserHome = if ($env:GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME
} else {
    Join-Path $env:USERPROFILE '.gradle'
}

$bootstrapRoot = Join-Path $gradleUserHome "wrapper\dists\gradle-$version-bin\pack-utilities-bootstrap"
$gradleHome = Join-Path $bootstrapRoot "gradle-$version"
$gradleBat = Join-Path $gradleHome 'bin\gradle.bat'

if (-not (Test-Path -LiteralPath $gradleBat)) {
    New-Item -ItemType Directory -Force -Path $bootstrapRoot | Out-Null

    $zipPath = Join-Path $bootstrapRoot "gradle-$version-bin.zip"
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -UseBasicParsing -Uri $distributionUrl -OutFile $zipPath
    Expand-Archive -LiteralPath $zipPath -DestinationPath $bootstrapRoot -Force
    Remove-Item -LiteralPath $zipPath -Force
}

Write-Output $gradleBat
