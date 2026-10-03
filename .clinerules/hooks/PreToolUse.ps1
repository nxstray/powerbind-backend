# PreToolUse.ps1 -- Cline hook that runs before a tool call is executed.
#
# Covers the remaining two plan items:
#   4. pre-commit summary -- writes a summary block for every `git commit` into
#      logs/commit-log.txt and reminds Cline to show it to the user
#   5. one commit per file -- blocks `git commit -a/-am/--all`, `git add -A/
#      --all/.` and any `git commit` with more than one staged file
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

# Shell tool names that actually reach this hook. Cline builds differ: a single
# `execute_command` tool, or the batched `run_commands` tool whose `commands`
# parameter is a JSON array (often pre-stringified). Every name must be listed
# here, otherwise the guard silently never runs for that build.
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

function Get-CommitMessage {
    param([string]$Command)
    # -m "msg" and -am "msg" first, then the long form.
    if ($Command -match '(?i)(?:^|\s)-[a-zA-Z]*m\s+"([^"]*)"') { return $Matches[1] }
    if ($Command -match "(?i)(?:^|\s)-[a-zA-Z]*m\s+'([^']*)'") { return $Matches[1] }
    if ($Command -match '(?i)--message=\s*"([^"]*)"') { return $Matches[1] }
    return '(not parsed)'
}

function Get-GitGuardDecision {
    param($Config, $Payload, [string]$Command)

    $decision = [pscustomobject]@{ Cancel = $false; Context = ''; ErrorMessage = '' }
    $workspacePath = Resolve-WorkspacePath $Payload

    $commitTail = $null
    $addTail = $null
    if ($Command -match $gitCommitPattern) { $commitTail = $Matches[1] }
    if ($Command -match $gitAddPattern) { $addTail = $Matches[1] }

    # -- 5. one commit per file ------------------------------------------------
    if ($Config.EnforcePerFile) {
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

        $staged = @()
        if ($reason -eq '' -and $null -ne $commitTail) {
            $staged = Get-StagedFiles $workspacePath
            if ($staged.Count -gt 1) {
                $reason = 'Ada {0} file ter-stage, sedangkan aturan commit per-file berarti tepat satu file.' -f $staged.Count
            }
        }

        if ($reason -ne '') {
            if ($staged.Count -eq 0) { $staged = Get-StagedFiles $workspacePath }
            $list = ($staged | Select-Object -First 10) -join ', '
            if ($staged.Count -gt 10) {
                $list = $list + (', ... (+{0} more)' -f ($staged.Count - 10))
            }
            if ($list -eq '') { $list = '(none)' }

            $decision.Cancel = $true
            $decision.ErrorMessage = '[COMMIT PER-FILE] Diblokir: {0} File ter-stage: {1}. Commit satu file per commit, contoh: git commit -m "scope: pesan" -- path/ke/file. Set CLINE_HOOKS_ENFORCE_PER_FILE=false untuk mematikan aturan ini.' -f $reason, $list
            return $decision
        }
    }

    # -- 4. pre-commit summary -------------------------------------------------
    if ($Config.PreCommitSummary -and $null -ne $commitTail) {
        $staged = Get-StagedFiles $workspacePath
        $branch = (Invoke-GitLines $workspacePath @('rev-parse', '--abbrev-ref', 'HEAD') | Select-Object -First 1)
        $stat = (Invoke-GitLines $workspacePath @('diff', '--cached', '--shortstat') | Select-Object -First 1)
        $message = Get-CommitMessage $Command

        $summaryPath = Join-Path $Config.LogDir $Config.SummaryFile
        # Blank line separates successive summaries in the same file.
        Add-HookLogLine $summaryPath ''
        Add-HookLogLine $summaryPath ('=== PRE-COMMIT SUMMARY {0} ===' -f (Get-HookTimestamp))
        Add-HookLogLine $summaryPath ('branch : {0}' -f $branch)
        Add-HookLogLine $summaryPath ('message: {0}' -f $message)
        Add-HookLogLine $summaryPath ('files  : {0}' -f $staged.Count)
        foreach ($file in $staged) { Add-HookLogLine $summaryPath ('  - {0}' -f $file) }
        Add-HookLogLine $summaryPath ('diff   : {0}' -f $stat)

        $decision.Context = '[PRE-COMMIT SUMMARY] Ringkasan pra-commit sudah ditulis ke {0} ({1} file, branch {2}). Tampilkan ringkasan ini ke user sebelum menjalankan commit.' -f $Config.SummaryFile, $staged.Count, $branch
        return $decision
    }

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

    # An unknown shell tool must not silently disable the guard, but guessing
    # that any tool is a shell would run git checks on reads and edits.
    $isShellTool = $shellToolNames -contains $toolName
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
