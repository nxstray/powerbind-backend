# PostToolUse.ps1 -- Cline hook that runs after every tool call completes.
#
# Covers three of the five plan items:
#   1. usage log        -- one plain-text line per tool call in logs/tool-usage.txt
#   2. token thrift     -- only a short preview is stored, never the whole result
#   3. dynamic params   -- every limit below is read from the environment
#
# On top of that it keeps a running token estimate for the task and, the first
# time the configured budget is crossed, nudges Cline to be more concise. It is
# a nudge, never a block: this hook always allows the completed call.
#
# Discovery: on Windows Cline only looks for `<HookName>.ps1`.
# Enable it with the global "Enable Hooks" setting in Cline (no per-hook toggle
# on Windows yet).

. "$PSScriptRoot\_lib.ps1"

$cancel = $false
$context = ''
$errorMessage = ''

try {
    $config = Get-HookConfig
    $raw = Get-HookRawInput
    $payload = ConvertTo-HookObject $raw

    # Field names differ between Cline builds (`tool` vs `toolName`), so both
    # are accepted here rather than betting on one of them.
    $toolName = Get-Nested $payload 'postToolUse.tool' (Get-Nested $payload 'postToolUse.toolName' 'unknown')
    $success = Get-Nested $payload 'postToolUse.success' $true
    $durationMs = Get-Nested $payload 'postToolUse.durationMs' 0
    $result = Get-Nested $payload 'postToolUse.result' ''

    # `result` is free-form: read_file returns text, editor returns an object.
    # Anything that is not a string is flattened to compact JSON, otherwise the
    # log would only contain the useless string "[object Object]".
    if ($null -ne $result -and -not ($result -is [string])) {
        try { $result = ($result | ConvertTo-Json -Compress -Depth 5) } catch { $result = '' }
    }

    $parameters = Get-Field (Get-Field $payload 'postToolUse' $null) 'parameters' $null
    $subject = ''
    if ($null -ne $parameters) {
        $subject = Get-FieldAny $parameters @(
            'command', 'commands', 'path', 'file_path', 'filePath', 'query',
            'url', 'task', 'question', 'message', 'pattern', 'regex', 'key'
        ) ''
    }

    if ($null -ne $subject -and -not ($subject -is [string])) {
        try { $subject = ($subject | ConvertTo-Json -Compress -Depth 5) } catch { $subject = '' }
    }

    $combined = ("$subject $result").Trim()

    # Some tools receive a pre-stringified object, which arrives here as the
    # literal "[object Object]" -- a placeholder that tells the reader nothing.
    # Fall back to the call parameters, which are far more useful.
    # .Contains() is deliberate: in a -like wildcard the brackets of
    # "[object Object]" form a character class and match almost anything.
    if ($combined.Contains('[object Object]') -and $null -ne $parameters) {
        try {
            $fallback = ($parameters | ConvertTo-Json -Compress -Depth 5)
            if (-not [string]::IsNullOrWhiteSpace($fallback)) { $combined = $fallback }
        } catch { }
    }

    $preview = ConvertTo-CompactText $combined $config.PreviewChars
    $estimated = Get-EstimatedTokens $combined

    # 1 + 2: one compact line per tool call, trimmed to the configured length.
    $line = '{0} | {1} | ok={2} | {3}ms | ~{4}tok | {5}' -f (Get-HookTimestamp), $toolName, $success, $durationMs, $estimated, $preview
    Add-HookLogLine (Join-Path $config.LogDir $config.UsageFile) $line

    # 3: running total for the current task; the budget is dynamic state, not a
    # hard-coded constant, so it can be changed between sessions.
    $taskId = Get-Field $payload 'taskId' 'current'
    $statePath = Join-Path $config.LogDir $config.StateFile
    $previous = Read-HookState $statePath

    $sameTask = ($null -ne $previous) -and ((Get-Field $previous 'taskId' '') -eq $taskId)
    $previousCalls = 0
    $previousTokens = 0
    $previousWarned = $false
    if ($sameTask) {
        $previousCalls = [int](Get-Field $previous 'toolCalls' 0)
        $previousTokens = [int](Get-Field $previous 'estimatedTokens' 0)
        $previousWarned = [bool](Get-Field $previous 'budgetWarned' $false)
    }

    $totalTokens = $previousTokens + $estimated
    $warned = $previousWarned

    if ($config.TokenBudget -gt 0 -and $totalTokens -ge $config.TokenBudget -and -not $warned) {
        $warned = $true
        $context = '[HEMAT TOKEN] Estimasi ~{0} token terpakai pada task ini (budget {1}). Ringkas output, hindari membaca ulang file besar, dan jawab seperlunya saja.' -f $totalTokens, $config.TokenBudget
    }

    $state = [pscustomobject]@{
        taskId          = $taskId
        toolCalls       = $previousCalls + 1
        estimatedTokens = $totalTokens
        budgetWarned    = $warned
        updatedAt       = (Get-HookTimestamp)
    }
    Write-HookState $statePath $state

    if ($config.Debug) {
        Write-HookDebug ('PostToolUse {0} ok={1} {2}ms ~{3}tok total=~{4}tok' -f $toolName, $success, $durationMs, $estimated, $totalTokens)
    }
} catch {
    # Fail open: a broken usage log must never break the session.
    Write-HookDebug ('PostToolUse failed: {0}' -f $_.Exception.Message)
    $cancel = $false
    $context = ''
    $errorMessage = ''
}

Write-HookResult -Cancel $cancel -ContextModification $context -ErrorMessage $errorMessage
