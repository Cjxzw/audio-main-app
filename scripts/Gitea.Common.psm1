Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Import-GiteaDotEnv {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) { return }

    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
        $separator = $trimmed.IndexOf("=")
        if ($separator -le 0) { continue }

        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if (-not [Environment]::GetEnvironmentVariable($name, "Process")) {
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

function Get-GiteaContext {
    param([string]$RepoRoot = (Join-Path $PSScriptRoot ".."))

    $resolvedRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
    Import-GiteaDotEnv -Path (Join-Path $resolvedRoot ".env")

    $remote = (& git -C $resolvedRoot remote get-url origin 2>$null).Trim()
    if (-not $remote) { throw "Git remote 'origin' was not found." }

    $baseUrl = $env:GITEA_URL
    $owner = $env:GITEA_OWNER
    $repo = $env:GITEA_REPO

    if ($remote -match '^https?://') {
        $remoteUri = [Uri]$remote
        $segments = $remoteUri.AbsolutePath.Trim('/').Split('/')
        if ($segments.Count -lt 2) { throw "Cannot parse owner/repository from origin: $remote" }
        if (-not $baseUrl) { $baseUrl = $remoteUri.GetLeftPart([UriPartial]::Authority) }
        if (-not $owner) { $owner = $segments[-2] }
        if (-not $repo) { $repo = $segments[-1] -replace '\.git$', '' }
    }

    if (-not $baseUrl -or -not $owner -or -not $repo) {
        throw "Set GITEA_URL, GITEA_OWNER and GITEA_REPO in .env when origin is not an HTTP URL."
    }

    $headers = @{}
    if ($env:GITEA_TOKEN) {
        $headers.Authorization = "token $($env:GITEA_TOKEN)"
    } elseif ($env:GITEA_USERNAME -and $env:GITEA_PASSWORD) {
        $credential = "$($env:GITEA_USERNAME):$($env:GITEA_PASSWORD)"
        $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($credential))
        $headers.Authorization = "Basic $encoded"
    } else {
        throw "Set GITEA_TOKEN or GITEA_USERNAME and GITEA_PASSWORD in the ignored .env file."
    }

    [pscustomobject]@{
        BaseUrl = $baseUrl.TrimEnd('/')
        Owner = $owner
        Repo = $repo
        Headers = $headers
    }
}

function Invoke-GiteaRequest {
    param(
        [Parameter(Mandatory)][ValidateSet("GET", "POST", "PATCH", "DELETE")][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [object]$Body,
        [string]$RepoRoot = (Join-Path $PSScriptRoot "..")
    )

    $context = Get-GiteaContext -RepoRoot $RepoRoot
    $owner = [Uri]::EscapeDataString($context.Owner)
    $repo = [Uri]::EscapeDataString($context.Repo)
    $uri = "$($context.BaseUrl)/api/v1/repos/$owner/$repo$Path"
    $arguments = @{
        Method = $Method
        Uri = $uri
        Headers = $context.Headers
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 20
        $arguments.ContentType = "application/json; charset=utf-8"
        $arguments.Body = [Text.Encoding]::UTF8.GetBytes($json)
    }

    try {
        Invoke-RestMethod @arguments
    } catch {
        $details = $_.ErrorDetails.Message
        if ($details) { throw "Gitea API $Method $Path failed: $details" }
        throw
    }
}

function Get-GiteaBodyText {
    param([string]$Body, [string]$BodyFile)

    if ($BodyFile) {
        return Get-Content -Raw -LiteralPath $BodyFile -Encoding UTF8
    }
    return $Body
}

Export-ModuleMember -Function Invoke-GiteaRequest, Get-GiteaBodyText
