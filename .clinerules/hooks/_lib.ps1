# _lib.ps1 -- shared helpers for the Cline hooks that live in this folder.
#
# The name matches no Cline hook type on purpose, so hook discovery never
# executes this file on its own. The hooks load it with:
#
#     . "$PSScriptRoot\_lib.ps1"
#
# Invariants kept by this file:
#   * ASCII only. Windows PowerShell 5.1 decodes a BOM-less UTF-8 script as
#     ANSI, so a non-ASCII byte would be mangled and could silently break a
#     hook (and with it, block the tool call it guards).
#   * Helpers never write to stdout. Cline parses the hook's stdout as the
#     result JSON, so logs go to files and diagnostics go to stderr.

$script:HookLibRoot = $PSScriptRoot

function Get-HooksRoot {
    return $script:HookLibRoot
}

function Get-EnvString {
    param([string]$Name, [string]$Default)
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value.Trim()
}

function Get-EnvInt {
    param([string]$Name, [int]$Default)
    $value = Get-EnvString $Name ''
    if ($value -eq '') { return $Default }
    $parsed = 0
    if ([int]::TryParse($value, [ref]$parsed)) { return $parsed }
    return $Default
}

function Get-EnvBool {
    param([string]$Name, [bool]$Default)
    $value = Get-EnvString $Name ''
    if ($value -eq '') { return $Default }
    return @('1', 'true', 'yes', 'on') -contains $value.ToLowerInvariant()
}

# Every tunable lives here, resolved from the environment at run time so the
# values can be changed per session without editing any script (sub-task:
# dynamic parameters). Defaults are the values tuned for this repository.
function Get-HookConfig {
    $root = Get-HooksRoot
    return [pscustomobject]@{
        HooksRoot        = $root
        LogDir           = Get-EnvString 'CLINE_HOOKS_LOG_DIR' (Join-Path $root 'logs')
        UsageFile        = Get-EnvString 'CLINE_HOOKS_USAGE_FILE' 'tool-usage.txt'
        StateFile        = Get-EnvString 'CLINE_HOOKS_STATE_FILE' 'session-state.json'
        PreviewChars     = Get-EnvInt    'CLINE_HOOKS_PREVIEW_CHARS' 160
        TokenBudget      = Get-EnvInt    'CLINE_HOOKS_TOKEN_BUDGET' 0
        EnforcePerFile   = Get-EnvBool   'CLINE_HOOKS_ENFORCE_PER_FILE' $true
        Debug            = Get-EnvBool   'CLINE_HOOKS_DEBUG' $false
    }
}

function Resolve-WorkspacePath {
    param($Payload)
    $fromEnv = Get-EnvString 'CLINE_HOOKS_WORKSPACE' ''
    if ($fromEnv -ne '') { return $fromEnv }
    $fromPayload = Get-Nested $Payload 'workspacePath' ''
    if ($fromPayload -ne '') { return $fromPayload }
    $root = Get-HooksRoot
    return (Split-Path -Parent (Split-Path -Parent $root))
}

# ---------------------------------------------------------------- JSON input
# Cline versions differ in the payload key names (the published docs use
# `preToolUse.tool`, older builds used `preToolUse.toolName`), so every lookup
# goes through these helpers and callers query several candidate names.

function Get-HookRawInput {
    return [Console]::In.ReadToEnd()
}

function ConvertTo-HookObject {
    param([string]$Raw)
    if ([string]::IsNullOrWhiteSpace($Raw)) { return $null }
    try { return ($Raw | ConvertFrom-Json) } catch { return $null }
}

function Get-Field {
    param($Object, [string]$Name, $Default = $null)
    if ($null -eq $Object) { return $Default }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $Default }
    $value = $property.Value
    if ($null -eq $value) { return $Default }
    return $value
}

function Get-FieldAny {
    param($Object, [string[]]$Names, $Default = $null)
    foreach ($name in $Names) {
        $value = Get-Field $Object $name $null
        if ($null -ne $value -and "$value".Trim() -ne '') { return $value }
    }
    return $Default
}

function Get-Nested {
    param($Object, [string]$Path, $Default = $null)
    $current = $Object
    foreach ($part in $Path.Split('.')) {
        $current = Get-Field $current $part $null
        if ($null -eq $current) { return $Default }
    }
    if ("$current".Trim() -eq '') { return $Default }
    return $current
}

# ------------------------------------------------------------------- logging
# The log directory is disposable: it is git-ignored and only ever appended to.

# "Initialize" is an approved PowerShell verb (PSUseApprovedVerbs); the helper
# only creates the folder when it is missing, so it stays idempotent.
function Initialize-HookDir {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function Add-HookLogLine {
    param([string]$Path, [string]$Line)
    $dir = Split-Path -Parent $Path
    Initialize-HookDir $dir
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::AppendAllText($Path, $Line + "`r`n", $utf8)
}

function Read-HookState {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        try {
            $raw = [System.IO.File]::ReadAllText($Path)
            if (-not [string]::IsNullOrWhiteSpace($raw)) { return ($raw | ConvertFrom-Json) }
        } catch { }
    }
    return $null
}

function Write-HookState {
    param([string]$Path, $State)
    $dir = Split-Path -Parent $Path
    Initialize-HookDir $dir
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, ($State | ConvertTo-Json -Depth 5), $utf8)
}

function Write-HookDebug {
    param([string]$Message)
    [Console]::Error.WriteLine('[cline-hooks] ' + $Message)
}

# ------------------------------------------------------------------ text ops

# Collapses a multi-line value into one line and cuts it to a fixed length.
# The length is the "dynamic parameter" of the usage log: it decides how much
# of each tool call is remembered, and therefore how many tokens the log (and
# any later read of it) costs.
function ConvertTo-CompactText {
    param($Text, [int]$Max)
    if ($null -eq $Text) { return '' }
    $value = [string]$Text
    $value = $value -replace "(`r`n|`n|`r)", ' '
    $value = $value -replace '\s+', ' '
    $value = $value.Trim()
    if ($Max -gt 0 -and $value.Length -gt $Max) {
        $value = $value.Substring(0, $Max) + '...(truncated)'
    }
    return $value
}

# Rough token estimate (four characters per token). Cheap and dependency-free;
# good enough to spot a runaway session.
function Get-EstimatedTokens {
    param($Text)
    if ($null -eq $Text) { return 0 }
    $length = ([string]$Text).Length
    if ($length -eq 0) { return 0 }
    return [int][math]::Ceiling($length / 4.0)
}

function Get-HookTimestamp {
    return (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
}

# ------------------------------------------------------------------------ git

function Invoke-GitLines {
    param([string]$WorkspacePath, [string[]]$Arguments)
    try {
        $output = & git -C $WorkspacePath @Arguments 2>$null
    } catch {
        return @()
    }
    if ($null -eq $output) { return @() }
    $result = @()
    foreach ($line in $output) {
        $text = "$line"
        if ($text.Trim() -ne '') { $result += $text }
    }
    return $result
}

function Get-StagedFiles {
    param([string]$WorkspacePath)
    return @(Invoke-GitLines $WorkspacePath @('diff', '--cached', '--name-only'))
}

# ------------------------------------------------------------------- output
# The single place that writes to stdout. `cancel` is always present because
# Cline treats a missing field as the safe default (allow / not cancelled).

function Write-HookResult {
    param([bool]$Cancel = $false, [string]$ContextModification = '', [string]$ErrorMessage = '')
    $payload = [ordered]@{ cancel = $Cancel }
    if (-not [string]::IsNullOrWhiteSpace($ContextModification)) {
        $payload['contextModification'] = (ConvertTo-CompactText $ContextModification 4000)
    }
    if (-not [string]::IsNullOrWhiteSpace($ErrorMessage)) {
        $payload['errorMessage'] = $ErrorMessage
    }
    $json = ($payload | ConvertTo-Json -Compress -Depth 6)
    $json = $json -replace "(`r|`n)", ' '
    [Console]::Out.WriteLine($json)
}
