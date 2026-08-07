[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidateSet("List", "Show", "Create", "Comment", "Close", "Reopen")]
    [string]$Action,

    [int]$Number,
    [string]$Title,
    [string]$Body,
    [string]$BodyFile,
    [string]$Head,
    [string]$Base,
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
        Invoke-GiteaRequest -Method GET -Path "/pulls?state=$State&limit=$boundedLimit"
    }
    "Show" {
        Require-Number
        Invoke-GiteaRequest -Method GET -Path "/pulls/$Number"
    }
    "Create" {
        if (-not $Title -or -not $Head -or -not $Base) {
            throw "Create requires -Title, -Head and -Base."
        }
        $text = Get-GiteaBodyText -Body $Body -BodyFile $BodyFile
        Invoke-GiteaRequest -Method POST -Path "/pulls" -Body @{
            title = $Title
            head = $Head
            base = $Base
            body = $text
        }
    }
    "Comment" {
        Require-Number
        $text = Get-GiteaBodyText -Body $Body -BodyFile $BodyFile
        if (-not $text) { throw "Comment requires -Body or -BodyFile." }
        Invoke-GiteaRequest -Method POST -Path "/issues/$Number/comments" -Body @{ body = $text }
    }
    "Close" {
        Require-Number
        Invoke-GiteaRequest -Method PATCH -Path "/pulls/$Number" -Body @{ state = "closed" }
    }
    "Reopen" {
        Require-Number
        Invoke-GiteaRequest -Method PATCH -Path "/pulls/$Number" -Body @{ state = "open" }
    }
}

if (@($result).Count -eq 0) {
    "[]"
} else {
    $result |
        Select-Object number, state, title, html_url, created_at, updated_at |
        ConvertTo-Json -Depth 8
}
