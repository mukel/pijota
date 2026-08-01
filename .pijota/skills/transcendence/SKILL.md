---
name: transcendence
description: Autonomous self-modification, edit pijota's source, build, test, and respawn
---

## What transcendence is

You are running inside pijota and can modify pijota itself. There are two distinct modes:

- Source-only edits: change pijota source code, then compile/test only. Do **not** build
  a fat jar and do **not** respawn unless the user explicitly asks for transcendence.
- Transcendence: integrate source changes by building a new jar, running tests, and
  restarting with the new code. After respawn, the session resumes exactly where it left
  off, now running the new code.

Important: `mvn package`, `mvnd package`, `mvn package -Pfat`, and the
`transcendence action="build"`/`respawn` actions may overwrite or replace jar artifacts
that the current process was launched from. Never package casually while the current
agent is still running. Use `/transcend` as the boundary where packaging and restart are
allowed.

## The `transcendence` tool

Use the `transcendence` tool for all self-modification actions:

| Action | What it does |
|--------|-------------|
| `build` | Backs up the current jar to `target/pijota-backup.jar`, runs `mvn package -Pfat -q`. Returns build output + exit code. |
| `test` | Runs `mvn test`. Returns test output + exit code. |
| `respawn` | Builds (with backup), then queues a process restart. The restart fires **after** your current turn completes. |
| `rollback` | Restores `target/pijota-backup.jar` over the current jar, then queues a respawn. |

All actions accept an optional `worktree` parameter to operate on a git worktree path
instead of the current directory.

## Full workflow

### Source-only workflow: user asks for code changes, not live integration

If the user asks you to modify pijota source code but does not ask to make the changes
live, do this:

```bash
mvn compile
mvn test
```

If `mvnd` is available and already used by the project, `mvnd compile` and `mvnd test`
are fine. Do **not** run `mvn package`, `mvnd package`, `mvn package -Pfat`, or
`transcendence action="build"`. Those produce jar artifacts and may disturb the running
agent. Tell the user the source changes are ready and that `/transcend` will integrate
them when they want the new version to go live.

### Integration workflow: user invokes `/transcend`

When the user invokes `/transcend` with no goal, assume the current source changes are
the changes to integrate. The command is responsible for compile/test/package/respawn.
If everything passes, it should restart into the new version and preserve the current
session.

### Step 1: Create a git worktree (optional but recommended)

For non-trivial changes, isolate edits in a worktree to keep the running tree clean:

```bash
git worktree add ../pijota-wip -b feat/my-feature
```

If you skip the worktree, edits go directly in the current directory. This works too
but mixing running code and edits can cause issues if the build fails mid-change.

### Step 2: Edit source files

Use `read`, `write`, `edit`, `glob`, `grep` on files under the worktree (or current
tree if no worktree). Source files live under `src/main/java/ai/pijota/`.

Follow existing code conventions. Never add comments unless asked. Prefer `edit` for
targeted changes, `write` only for new files or complete rewrites.

### Step 3: Build/package only when integrating

```
transcendence action="build" [worktree="../pijota-wip"]
```

This creates `target/pijota-backup.jar` (your safety net) and builds a fat jar. Only do
this as part of a `/transcend` integration flow. For ordinary source-only edits, use
`mvn compile`/`mvn test` instead and leave packaging to `/transcend`.

### Step 4: Test

```
transcendence action="test" [worktree="../pijota-wip"]
```

Runs all tests. If any fail, fix them and re-test. Never skip this step.

### Step 5: Respawn

```
transcendence action="respawn" [worktree="../pijota-wip"]
```

This builds one more time (with backup), queues the respawn, and the process
restarts **after your current turn completes**. You can give the user a final
message summarizing what changed, it will be visible before the restart.

After respawn, the same session resumes automatically. The user won't even notice
a transition except that the new code is running.

### Step 6: Merge (if using worktree)

After confirming the new version works, merge the worktree branch into main:

```
/transcend --merge feat/my-feature
```

This merges, builds from main, and respawns with the merged code.

### Step 7: Cleanup (if using worktree)

```bash
git worktree remove ../pijota-wip
git branch -d feat/my-feature
```

## Recovery

If the new build is broken or crashes after respawn:

1. The backup jar is at `target/pijota-backup.jar`
2. Recover with: `java -jar target/pijota-backup.jar --recover`
3. This restores the previous version AND resumes the exact session where you left off
4. Alternatively, call: `transcendence action="rollback"` followed by manual restart

## Key rules

- If the user asks for source changes only, compile/test only. Do not package.
- Never run `mvn package`, `mvnd package`, or `mvn package -Pfat` casually while the
  current agent is running.
- `/transcend` is the explicit integration boundary: it may compile, test, package, and
  respawn with the current changes.
- ALWAYS build before respawning. The respawn action builds internally but
  explicitly building first catches errors earlier.
- ALWAYS test before respawning. Don't respawn with a broken test suite.
- NEVER skip the backup: the respawn action creates it automatically.
- NEVER respawn twice in one turn: the second request is ignored.
- The respawn happens AFTER your turn, so finish your message before calling it.
- If running in a worktree, use `worktree="..."` on ALL actions.
- After respawn, you are the same agent in the same conversation: the session
  file is passed to the new process so no context is lost.

## Example: Adding a new tool

User: "add a reverse tool that echoes the input backwards"

```
1. read src/main/java/ai/pijota/tools/Tool.java          (understand the interface)
2. write src/main/java/ai/pijota/tools/ReverseTool.java   (implement the tool)
3. read src/main/java/ai/pijota/tools/ToolRegistry.java   (find registration point)
4. edit ToolRegistry.java to register ReverseTool         (add to createDefault())
5. transcendence action="build"                            (compile + package)
6. transcendence action="test"                             (run tests)
7. transcendence action="respawn"                          (restart with new tool)
```

## Example: Fixing a bug

User: "the read tool truncates output at 1000 lines instead of 2000"

```
1. glob src/main/java/ai/pijota/tools/ReadTool.java
2. read ReadTool.java, find the limit constant
3. edit ReadTool.java: change 1000 -> 2000
4. transcendence action="build"
5. transcendence action="test"
6. transcendence action="respawn"
```

## /transcend slash command

- `/transcend "add a reverse tool"`: set a self-modification goal. A git worktree
  is created automatically (the user doesn't see this). The agent autonomously
  edits, builds, tests, and respawns. Worktrees are an implementation detail , 
  don't mention them to the user.
- `/transcend`: integrate current source changes: compile/test/package and respawn from
  the current tree (no goal)
- `/transcend --test`: build and launch a test instance (interactive)
- `/transcend --rollback`: restore backup jar and respawn
- `/transcend --merge feat/name`: merge branch, build, respawn
