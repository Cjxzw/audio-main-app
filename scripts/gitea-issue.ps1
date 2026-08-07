[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidateSet("List", "Show", "Create", "Update", "Comment", "Close", "Reopen")]
    [string]$Action,

    [int]$Number,
    [string]$Title,
    [string]$Body,
    [string]$BodyFile,
    [ValidateSet("open", "closed", "all")]
    [string]$State = "open",
    [int]$Limit = 50
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "Gitea.Common.psm1") -Force

function Require-Number {
    if ($Number -le 0) { throw "-$Action requires -Number." }
}

$result = switch ($Action) {
    "List" {
        $boundedLimit = [Math]::Min([Math]::Max($Limit, 1), 50)
        Invoke-GiteaRequest -Method GET -Path "/issues?state=$State&type=issues&limit=$boundedLimit"
    }
    "Show" {
        Require-Number
        Invoke-GiteaRequest -Method GET -Path "/issues/$Number"
    }
    "Create" {
        if (-not $Title) { throw "Create requires -Title." }
        $text = Get-GiteaBodyText -Body $Body -BodyFile $BodyFile
        Invoke-GiteaRequest -Method POST -Path "/issues" -Body @{ title = $Title; body = $text }
    }
    "Update" {
        Require-Number
        if (-not $Title -and -not $Body -and -not $BodyFile) {
            throw "Update requires -Title, -Body or -BodyFile."
        }
        $payload = @{}
        if ($Title) { $payload.title = $Title }
        if ($Body -or $BodyFile) { $payload.body = Get-GiteaBodyText -Body $Body -BodyFile $BodyFile }
        Invoke-GiteaRequest -Method PATCH -Path "/issues/$Number" -Body $payload
    }
    "Comment" {
        Require-Number
        $text = Get-GiteaBodyText -Body $Body -BodyFile $BodyFile
        if (-not $text) { throw "Comment requires -Body or -BodyFile." }
        Invoke-GiteaRequest -Method POST -Path "/issues/$Number/comments" -Body @{ body = $text }
    }
    "Close" {
        Require-Number
        Invoke-GiteaRequest -Method PATCH -Path "/issues/$Number" -Body @{ state = "closed" }
    }
    "Reopen" {
        Require-Number
        Invoke-GiteaRequest -Method PATCH -Path "/issues/$Number" -Body @{ state = "open" }
    }
}

if (@($result).Count -eq 0) {
    "[]"
} else {
    $result |
        Select-Object number, state, title, html_url, created_at, updated_at |
        ConvertTo-Json -Depth 8
}
