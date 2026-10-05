# Cline hooks

Workspace hooks for [Cline](https://docs.cline.bot) that log tool usage and keep
git history reviewable. They live in `.clinerules/hooks/`, so they apply to
every task Cline runs inside this repository.

> **Mirrored copy.** This folder is a copy of `.clinerules/hooks/` in the
> `powerbind-frontend` repository, which is where it is maintained and
> documented. When one copy changes, copy the file across so both repositories
> run the same hooks. The scripts are identical apart from this note.

## Files

| File | Kind | Purpose |
| --- | --- | --- |
| `PostToolUse.ps1` | hook | Logs every completed tool call, trims it to a fixed length, and nudges Cline when the session's token budget is crossed. |
| `PreToolUse.ps1` | hook | Blocks bulk commits that would lump several files together. |
| `_lib.ps1` | helper | Shared helpers. Its name matches no hook type, so Cline never runs it directly. |
| `.gitignore` | support | Keeps the generated `logs/` folder out of git. |

## Enabling

1. VS Code settings for Cline -> **Feature Settings** -> tick **Enable Hooks**.
2. On Windows hooks run whenever `<HookName>.ps1` exists in `.clinerules/hooks/`;
   there is no per-hook on/off toggle on Windows yet.
3. `powershell.exe` must be on `PATH` (verify with
   `powershell -NoProfile -Command "$PSVersionTable.PSVersion"`).

File naming is platform specific: Windows runs `PreToolUse.ps1`, macOS/Linux run
an extensionless `PreToolUse`. Names for the wrong platform are ignored.

## What the hooks do

| Plan item | Where | Behaviour |
| --- | --- | --- |
| Usage log | `PostToolUse.ps1` | Appends one line per tool call to `logs/tool-usage.txt`. |
| Token thrift | `PostToolUse.ps1` | Stores only a truncated preview, never the full tool result. |
| Dynamic parameters | `_lib.ps1` | Every limit is read from the environment at run time. |
| One commit per file | `PreToolUse.ps1` | Blocks bulk commits (see below). |

### `logs/tool-usage.txt`

```
2026-10-01T18:02:11+07:00 | read_file | ok=True | 42ms | ~310tok | package.json {...}
2026-10-01T18:02:19+07:00 | execute_command | ok=True | 890ms | ~54tok | npm run test ...
```

`~<n>tok` is a rough estimate (characters / 4), which is enough to spot a
runaway session without adding a dependency.

### Blocked commits

`PreToolUse.ps1` cancels the command (and explains why) when it detects:

- `git commit -a`, `git commit -am`, `git commit --all`
- `git commit .`
- `git add -A`, `git add --all`, `git add .`
- a plain `git commit` while **more than one** file is staged

Blocking is reported through `errorMessage`, so Cline sees the rule and can
re-run a per-file commit, for example:

```bash
git commit -m "fix(ui): do the thing" -- src/pages/LogPage.vue
```

## Parameters

All of these are optional; the defaults are tuned for this repository.

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `CLINE_HOOKS_PREVIEW_CHARS` | `160` | How much of a tool call is kept in the usage log. |
| `CLINE_HOOKS_TOKEN_BUDGET` | `0` | Estimated token budget per task; `0` disables the nudge. |
| `CLINE_HOOKS_ENFORCE_PER_FILE` | `true` | Set to `false` to allow bulk commits again. |
| `CLINE_HOOKS_LOG_DIR` | `<hooks>/logs` | Where the logs are written. |
| `CLINE_HOOKS_USAGE_FILE` | `tool-usage.txt` | Usage log file name. |
| `CLINE_HOOKS_WORKSPACE` | payload / repo root | Repository the git checks run against. |
| `CLINE_HOOKS_DEBUG` | `false` | Writes per-call diagnostics to stderr. |

On Windows set them for the VS Code process, for example:

```powershell
[Environment]::SetEnvironmentVariable('CLINE_HOOKS_TOKEN_BUDGET', '250000', 'User')
```

## Testing a hook by hand

A hook is an ordinary script that reads JSON on stdin and prints one line of
JSON on stdout, so it can be exercised without Cline:

```powershell
$payload = '{ "taskId": "t1", "postToolUse": { "tool": "read_file", "parameters": { "path": "package.json" }, "result": "ok", "success": true, "durationMs": 42 } }'
$payload | powershell -NoProfile -ExecutionPolicy Bypass -File .\.clinerules\hooks\PostToolUse.ps1
```

Expected output: `{"cancel":false}` (plus `contextModification` once the token
budget is crossed).

## Troubleshooting

- **Hook not running** - check that Hooks are enabled globally, that
  `powershell.exe` is on `PATH`, and that the file is named `<HookName>.ps1`.
  Hook errors are reported in VS Code's Output panel (Cline channel); set
  `CLINE_HOOKS_DEBUG=true` for more detail.
- **Output not parsed** - the result must be a single line of JSON on stdout.
  All diagnostics here go to stderr on purpose.
- **Nothing in the log** - the folder is `logs/` next to these scripts; make
  sure it is writable.
- **Script blocked** - if the machine's execution policy rejects scripts,
  either relax the policy or keep `-ExecutionPolicy Bypass` in mind when
  testing manually.

## Not covered yet

- macOS/Linux need the same logic as extensionless, executable bash scripts.
  This repository is developed on Windows, where only the `.ps1` files are
  discovered.
- The one-commit-per-file check reads the git index *before* the command runs,
  so a single command that stages several files and only then commits them (two
  `git add` calls followed by one `git commit`, all on one line) is not
  detected. Split those into separate commands and the rule applies.
