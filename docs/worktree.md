# Worktrees

`pj worktree` gives you a disposable git worktree from a reusable pool.

Use it when you want isolation without ceremony: one risky task, one branch, one shell. Keep the main checkout clean.

## The Short Version

```bash
pj worktree
```

Pijota will:

- acquire a managed git worktree
- open a shell inside it
- return it to the pool when the shell exits

The worktree starts detached. If the work matters, create a branch before leaving:

```bash
git switch -c fix-login-timeout
```

If you exit without committing, the pool may reset and reuse the worktree later.

## Real Uses

### Try A Risky Change

```bash
pj worktree
pj -p "rewrite the parser to simplify error handling"
mvn test
git diff
```

If it is good:

```bash
git switch -c parser-cleanup
git add .
git commit -m "refactor(parser): simplify error handling"
```

If it is bad, exit. Your main checkout was never touched.

### Run Two Experiments At Once

Terminal 1:

```bash
pj worktree
git switch -c experiment-a
pj -p "try the minimal implementation"
```

Terminal 2:

```bash
pj worktree
git switch -c experiment-b
pj -p "try the safer implementation"
```

Compare the branches later. Keep the one that wins.

### Use A Worktree From A Script

`get` prints only the path on stdout, so it is script-friendly.

```bash
wt=$(pj worktree get)
trap 'pj worktree return "$wt"' EXIT

cd "$wt"
pj -p "fix the failing tests"
mvn test
```

Return it manually if you do not use a trap:

```bash
pj worktree return "$wt"
```

## Commands

### Open A Shell

```bash
pj worktree
```

Best for humans. The worktree is returned automatically when the shell exits.

### Reserve A Path

```bash
pj worktree get
```

Best for scripts. The worktree stays reserved until you return it.

### List The Pool

```bash
pj worktree status
```

Shows the pool root and each worktree state:

- `idle`: clean and reusable
- `in_use`: held by a running process
- `leased`: reserved by `pj worktree get`
- `dirty`: has uncommitted changes

### Return A Worktree

```bash
pj worktree return /path/to/worktree
```

If you are inside the worktree, the path is optional:

```bash
pj worktree return
```

Returning resets tracked files and cleans untracked, non-ignored files. Gitignored build artifacts stay warm.

### Prune Stale Worktrees

Dry run:

```bash
pj worktree prune
```

Actually remove safe candidates:

```bash
pj worktree prune --yes
```

Prune only removes worktrees that are idle, clean, and merged into the remote default branch.

### Destroy One Worktree

Dry run:

```bash
pj worktree destroy /path/to/worktree
```

Remove it:

```bash
pj worktree destroy /path/to/worktree --yes
```

Force cases are explicit:

```bash
pj worktree destroy /path/to/worktree --yes --include-dirty
pj worktree destroy /path/to/worktree --yes --include-in-use
```

## Where The Pool Lives

Worktrees live under:

```text
~/.pijota/worktrees/<repo-id>/
```

Each repository gets its own pool. Pijota uses a lock file so parallel `pj worktree` commands do not hand out the same worktree.

## Good Habits

- Commit or branch anything you want to keep.
- Use `pj worktree status` before cleanup.
- Use `prune` for routine cleanup.
- Use `destroy` only when you mean it.
- Do not treat pooled worktrees as long-term storage.

## When Not To Use It

Do not use `pj worktree` for tiny edits you already trust. Stay in the main checkout and keep the loop short.

Use a worktree when isolation buys clarity.
