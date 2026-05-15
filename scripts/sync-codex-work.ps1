<#
.SYNOPSIS
    Syncs a local checkout with the Codex/GitHub work branch.

.DESCRIPTION
    Use this from Windows PowerShell when the Codex "Apply" button reports
    conflicts. The script makes the local checkout match origin/work so there is
    nothing left to merge manually.

    By default it is safe: dirty local changes are stashed before the sync. Pass
    -DiscardLocalChanges if you explicitly want to delete local uncommitted work.
#>
param(
    [string]$RepoUrl = "https://github.com/Kronv3rk/TG-for-work.git",
    [string]$Branch = "work",
    [string]$Path = (Get-Location).Path,
    [switch]$DiscardLocalChanges
)

$ErrorActionPreference = "Stop"

function Invoke-Git {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    & git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Test-GitRepository {
    param([string]$RepositoryPath)

    if (-not (Test-Path -LiteralPath $RepositoryPath)) {
        return $false
    }

    Push-Location -LiteralPath $RepositoryPath
    try {
        & git rev-parse --is-inside-work-tree *> $null
        return $LASTEXITCODE -eq 0
    } finally {
        Pop-Location
    }
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw "Git is not installed or is not available in PATH."
}

if (-not (Test-GitRepository -RepositoryPath $Path)) {
    $parent = Split-Path -Parent $Path
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }

    Invoke-Git clone --branch $Branch $RepoUrl $Path
    Write-Host "Cloned $RepoUrl branch $Branch into $Path"
    exit 0
}

Push-Location -LiteralPath $Path
try {
    $originUrl = (& git remote get-url origin 2>$null)
    if ($LASTEXITCODE -ne 0) {
        Invoke-Git remote add origin $RepoUrl
    } elseif ($originUrl -ne $RepoUrl) {
        Invoke-Git remote set-url origin $RepoUrl
    }

    $dirty = (& git status --porcelain)
    if ($dirty) {
        if ($DiscardLocalChanges) {
            Invoke-Git reset --hard
            Invoke-Git clean -fd
        } else {
            Invoke-Git stash push -u -m "before-codex-work-sync"
        }
    }

    Invoke-Git fetch origin $Branch

    $localBranchExists = $true
    & git rev-parse --verify $Branch *> $null
    if ($LASTEXITCODE -ne 0) {
        $localBranchExists = $false
    }

    if ($localBranchExists) {
        Invoke-Git checkout $Branch
    } else {
        Invoke-Git checkout -b $Branch origin/$Branch
    }

    Invoke-Git reset --hard origin/$Branch
    Invoke-Git branch --set-upstream-to=origin/$Branch $Branch

    Write-Host "Synced $Path to origin/$Branch."
} finally {
    Pop-Location
}
