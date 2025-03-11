# This is a wrapper script, that automatically download mill from GitHub release pages
# You can give the required mill version with --mill-version parameter
# If no version is given, it falls back to the value of DEFAULT_MILL_VERSION
#
# Project page: https://github.com/lefou/millw
# Script Version: 0.4.12
#
# If you want to improve this script, please also contribute your changes back!
#
# Licensed under the Apache License, Version 2.0

[CmdletBinding(PositionalBinding = $false)]

param(
    [Parameter(ValueFromRemainingArguments = $true, Position = 0)]
    [string[]] $remainingArgs
)

$DEFAULT_MILL_VERSION = $Env:DEFAULT_MILL_VERSION ?? '0.11.6'

$GITHUB_RELEASE_CDN = $Env:GITHUB_RELEASE_CDN ?? ''

$MILL_REPO_URL = 'https://github.com/com-lihaoyi/mill'

$MILL_VERSION = $null

if ($null -ne $remainingArgs) {
    if ($remainingArgs[0] -eq '--mill-version') {
        $remainingArgs = Select-Object -InputObject $remainingArgs -Skip 1
        if ($null -ne $remainingArgs) {
            $MILL_VERSION = $remainingArgs[0]
            $remainingArgs = Select-Object -InputObject $remainingArgs -Skip 1
        }
        else {
            Write-Error -Message "Please provide a version that matches one provided on $MILL_REPO_URL/releases"
            throw [System.ArgumentNullException] '--mill-version'
        }
    }
}

if ($null -eq $MILL_VERSION) {
    if (Test-Path -Path '.mill-version' -PathType Leaf) {
        $MILL_VERSION = Get-Content -Path '.mill-version' -TotalCount 1
    }
    elseif (Test-Path -Path '.config/mill-version' -PathType Leaf) {
        $MILL_VERSION = Get-Content -Path '.config/mill-version' -TotalCount 1
    }
}

$MILL_USER_CACHE_DIR = Join-Path -Path $Env:LOCALAPPDATA -ChildPath 'mill'

$MILL_DOWNLOAD_PATH = $Env:MILL_DOWNLOAD_PATH ?? @(Join-Path -Path ${MILL_USER_CACHE_DIR} -ChildPath 'download')

if (-not (Test-Path -Path $MILL_DOWNLOAD_PATH)) {
    New-Item -Path $MILL_DOWNLOAD_PATH -ItemType Directory | Out-Null
}

if ($null -eq $MILL_VERSION) {
    Write-Warning -Message 'No mill version specified.'
    Write-Warning -Message "You should provide a version via '.mill-version' file or --mill-version option."

    if (-not (Test-Path -Path "$MILL_DOWNLOAD_PATH" -PathType Container)) {
        New-Item "$MILL_DOWNLOAD_PATH" -ItemType Directory | Out-Null
    }

    $MILL_LATEST_PATH = Join-Path -Path $MILL_DOWNLOAD_PATH -ChildPath '.latest'

    if (Test-Path -Path $MILL_LATEST_PATH -PathType Leaf) {
        if ($(Get-Item -Path $MILL_LATEST_PATH).LastWriteTime -lt $(Get-Date).AddHours(-1)) {
            $MILL_VERSION = Get-Content -Path $MILL_LATEST_PATH -TotalCount 1
        }
    }

    if ($null -eq $MILL_VERSION) {
        Write-Output 'Retrieving latest mill version ...'

        # https://github.com/PowerShell/PowerShell/issues/20964
        $targetUrl = try {
            Invoke-WebRequest -Uri "$MILL_REPO_URL/releases/latest" -MaximumRedirection 0
        }
        catch {
            $_.Exception.Response.Headers.Location.AbsoluteUri
        }

        $targetUrl -match "^$MILL_REPO_URL/releases/tag/(.+)$" | Out-Null

        $MILL_VERSION = $Matches.1

        if ($null -ne $MILL_VERSION) {
            Set-Content -Path $MILL_LATEST_PATH -Value $MILL_VERSION
        }
    }

    if ($null -eq $MILL_VERSION) {
        $MILL_VERSION = $DEFAULT_MILL_VERSION
        Write-Warning "Falling back to hardcoded mill version $MILL_VERSION"
    }
    else {
        Write-Output "Using mill version $MILL_VERSION"
    }
}

$MILL = "$MILL_DOWNLOAD_PATH/$MILL_VERSION.bat"

if (-not (Test-Path -Path $MILL -PathType Leaf)) {
    $DOWNLOAD_SUFFIX, $DOWNLOAD_FROM_MAVEN = switch -Regex ($MILL_VERSION) {
        '^0\.[0-4]\..*$' { '', $false }
        '0\.(?:[5-9]\.|10\.|11\.0-M).*' { '-assembly', $false }
        Default { '-assembly', $true }
    }

    if ($DOWNLOAD_FROM_MAVEN) {
        $DOWNLOAD_URL = "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/$MILL_VERSION/mill-dist-$MILL_VERSION.jar"
    }
    else {
        $MILL_VERSION -match '(\d+\.\d+\.\d+(?:-M\d+)?)' | Out-Null
        $MILL_VERSION_TAG = $Matches.1
        $DOWNLOAD_URL = "$GITHUB_RELEASE_CDN$MILL_REPO_URL/releases/download/$MILL_VERSION_TAG/$MILL_VERSION$DOWNLOAD_SUFFIX"
    }
    Write-Output "Downloading mill $MILL_VERSION from $DOWNLOAD_URL ..."

    Invoke-WebRequest -Uri $DOWNLOAD_URL -OutFile $MILL
}

$MILL_MAIN_CLI = $Env:MILL_MAIN_CLI ?? $PSCommandPath

$MILL_FIRST_ARG = $null
$REMAINING_ARGUMENTS = $remainingArgs

if ($null -ne $remainingArgs) {
    if ($remainingArgs[0] -eq '--bsp' -or $remainingArgs -eq '-i' -or $remainingArgs -eq '--interactive' -or $remainingArgs -eq '--no-server') {
        $MILL_FIRST_ARG = $remainingArgs[0]
        $REMAINING_ARGUMENTS = Select-Object -InputObject $remainingArgs -Skip 1
    }
}

& $MILL $MILL_FIRST_ARG -D "mill.main.cli=$MILL_MAIN_CLI" $REMAINING_ARGUMENTS
