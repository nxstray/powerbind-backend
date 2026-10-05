# PreToolUse.ps1 -- Cline hook that runs before a tool call is executed.
#
# Enforces one commit per file: blocks `git commit -a`, `-am`, `--all`,
# `git commit .`, `git add -A/--all/.`, and any `git commit` while more than one
# file is staged.
#
# Only shell commands that actually invoke git are inspected; every other tool
# call is allowed straight through. A failure falls open (allow) so a broken
# hook can never wedge the session.
#
# Discovery: on Windows Cline only looks for `<HookName>.ps1`.

. "$PSScriptRoot\_lib.ps1"

# Matches `git`, optional global flags such as -C <path>, then the subcommand,
# so `git log --grep=commit` is not mistaken for a commit.
$gitCommitPattern = '(?i)\bgit\s+(?:-[^\s]+\s+)*commit\b([^\r\n]*)'
$gitAddPattern = '(?i)\bgit\s+(?:-[^\s]+\s+)*add\b([^\r\n]*)'

# Shell tool names known to reach this hook: a single `execute_command` tool, or
# the batched `run_commands` whose `commands` parameter is a JSON array (often
# pre-stringified). The guard also treats any payload carrying a command-shaped
# parameter as a shell call, so a renamed tool cannot silently disable it.
$shellToolNames = @('execute_command', 'run_commands')

function Get-CommandText {
    param($Parameters)
    if ($null -eq $Parameters) { return '' }

    # Single-command tools: `command` / `cmd`.
    $single = Get-FieldAny $Parameters @('command', 'cmd') $null
    if ($null -ne $single -and "$single".Trim() -ne '') { return "$single" }

    # Batched tools: `commands` -- a string array, or a JSON string holding one.
    $multi = Get-Field $Parameters 'commands' $null
    if ($null -eq $multi) { return '' }
    if ($multi -is [string]) {
        $parsed = $null
        try { $parsed = $multi | ConvertFrom-Json } catch { return $multi }
        if ($null -eq $parsed) { return $multi }
        $multi = $parsed
    }

    # Join with newlines so the [^\r\n]* tail of a git pattern stops at the end
    # of its own command instead of running across the whole batch.
    $parts = @()
    foreach ($item in @($multi)) {
        $text = "$item"
        if ($text.Trim() -ne '') { $parts += $text }
    }
    return ($parts -join "`n")
}

function Get-GitGuardDecision {
    param($Config, $Payload, [string]$Command)

    $decision = [pscustomobject]@{ Cancel = $false; Context = ''; ErrorMessage = '' }
    $workspacePath = Resolve-WorkspacePath $Payload

    $commitTail = $null
    $addTail = $null
    if ($Command -match $gitCommitPattern) { $commitTail = $Matches[1] }
    if ($Command -match $gitAddPattern) { $addTail = $Matches[1] }

    # -- one commit per file --------------------------------------------------
    if (-not $Config.EnforcePerFile) { return $decision }

    $reason = ''

    if ($null -ne $addTail -and $addTail -match '(?i)(^|\s)(-A|--all|\.)(\s|$)') {
        $reason = 'git add -A / --all / "." men-stage semua perubahan sekaligus.'
    }
    if ($reason -eq '' -and $null -ne $commitTail -and $commitTail -match '(?i)(^|\s)(-[a-zA-Z]*a[a-zA-Z]*|--all)(\s|$)') {
        $reason = 'git commit -a / -am / --all men-commit semua file yang dimodifikasi sekaligus.'
    }
    if ($reason -eq '' -and $null -ne $commitTail -and $commitTail -match '(?i)(^|\s)\.(\s|$)') {
        $reason = 'git commit . men-commit seluruh working tree.'
    }
    if ($reason -eq '' -and $null -ne $commitTail) {
        $staged = Get-StagedFiles $workspacePath
        if ($staged.Count -gt 1) {
            $reason = 'Ada {0} file ter-stage, sedangkan aturan commit per-file berarti tepat satu file.' -f $staged.Count
        }
    }

    if ($reason -eq '') { return $decision }

    $staged = Get-StagedFiles $workspacePath
    $list = ($staged | Select-Object -First 10) -join ', '
    if ($staged.Count -gt 10) {
        $list = $list + (', ... (+{0} more)' -f ($staged.Count - 10))
    }
    if ($list -eq '') { $list = '(none)' }

    $decision.Cancel = $true
    $decision.ErrorMessage = '[COMMIT PER-FILE] Diblokir: {0} File ter-stage: {1}. Commit satu file per commit, contoh: git commit -m "scope: pesan" -- path/ke/file. Set CLINE_HOOKS_ENFORCE_PER_FILE=false untuk mematikan aturan ini.' -f $reason, $list
    return $decision
}

$result = [pscustomobject]@{ Cancel = $false; Context = ''; ErrorMessage = '' }

try {
    $config = Get-HookConfig
    $raw = Get-HookRawInput
    $payload = ConvertTo-HookObject $raw

    $toolName = Get-Nested $payload 'preToolUse.tool' (Get-Nested $payload 'preToolUse.toolName' '')
    $parameters = Get-Field (Get-Field $payload 'preToolUse' $null) 'parameters' $null

    $command = (Get-CommandText $parameters).Trim()

    # An unknown shell tool must not silently disable the guard: any payload that
    # carries a command-shaped parameter is treated as a shell call. Guessing
    # from the tool name alone would either miss new names or run git checks on
    # reads and edits.
    $hasCommandField = $null -ne (Get-FieldAny $parameters @('command', 'cmd', 'commands') $null)
    $isShellTool = $hasCommandField -or ($shellToolNames -contains $toolName)
    if ($hasCommandField -and -not ($shellToolNames -contains $toolName)) {
        Write-HookDebug ('Unknown shell tool "{0}" - guarding through its command parameter.' -f $toolName)
    }

    $isGitCommand = $isShellTool -and ($command -ne '') -and ($command -match '(?i)\bgit\s')

    if ($isGitCommand) {
        if ($config.Debug) { Write-HookDebug ('PreToolUse git via ' + $toolName + ': ' + $command) }
        $result = Get-GitGuardDecision -Config $config -Payload $payload -Command $command
    }
} catch {
    Write-HookDebug ('PreToolUse failed: {0}' -f $_.Exception.Message)
    $result = [pscustomobject]@{ Cancel = $false; Context = ''; ErrorMessage = '' }
}

Write-HookResult -Cancel $result.Cancel -ContextModification $result.Context -ErrorMessage $result.ErrorMessage
