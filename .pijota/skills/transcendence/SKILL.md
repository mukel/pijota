---
name: transcendence
description: Self-modification workflow — edit, build, test, and respawn pijota
---

When asked to modify pijota's own source code, follow this workflow:

## 1. Create an isolated git worktree

```bash
git worktree add ../pijota-wip -b feat/description
```

All edits go in the worktree, not the current tree. The worktree is an isolated copy with its own branch.

## 2. Edit source files

Use ReadTool, WriteTool, EditTool on files under `../pijota-wip/src/main/java/ai/pijota/`.
Follow existing code conventions — no comments unless asked.

## 3. Build

Use the `transcendental` tool with `action: "build"` and `worktree: "../pijota-wip"`.
The tool backs up the current jar to `target/pijota-backup.jar`, then runs `mvn package -Pfat -q`.

## 4. Test

Use the `transcendental` tool with `action: "test"` and `worktree: "../pijota-wip"`.
Runs `mvn test`. Fix any failures before continuing.

## 5. Test interactively (optional)

Ask the user to test the new build manually:

```bash
/respawn --test --worktree ../pijota-wip
```

This launches the new jar as a sub-process that takes over the terminal.
The user can interact with the new version, then exit (Ctrl+C) to return to you.
Ask the user if the new behavior works correctly.

## 6. Respawn

When ready, call `transcendental` with `action: "respawn"`.

The tool builds, queues the respawn, and after your current turn completes, the
process restarts with the new jar. The session auto-resumes — the user sees the
same conversation with the new code running.

After respawn, the user is now running the new version with the session intact.

## 7. Merge (finalize)

After confirming the new version works, merge the worktree branch and respawn
from the merged main tree:

```
/respawn --merge feat/description
```

This merges the branch, builds from the main tree, and respawns.

## 8. Cleanup

Remove the worktree:

```bash
git worktree remove ../pijota-wip
git branch -d feat/description
```

## Recovery

If the new build is broken or crashes:
- The backup jar is at `target/pijota-backup.jar`
- The user can run: `java -jar target/pijota-backup.jar --continue`
- Or you can call `transcendental` with `action: "rollback"` to restore the backup and respawn

## Key rules

- NEVER edit files in the current tree — always use a worktree
- ALWAYS build and test before respawning
- NEVER respawn without a backup (the tool handles this automatically)
- If the build fails, fix errors and rebuild — do not respawn with a broken build
- The respawn happens AFTER your current turn completes, so you can give a final message
- After respawn, the session auto-resumes with full context
