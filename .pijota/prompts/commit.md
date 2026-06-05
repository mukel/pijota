---
description: Write a conventional commit message for staged changes
---
Examine the staged changes with `git diff --cached` and write a single concise 
conventional commit message following this format:

```
<type>(<scope>): <description>
```

Types: feat, fix, refactor, perf, test, docs, chore, style, ci, revert

Rules:
- Use imperative mood ("add" not "added")
- No period at the end
- Keep under 72 characters
- Include breaking changes with `!` after type/scope

$@
