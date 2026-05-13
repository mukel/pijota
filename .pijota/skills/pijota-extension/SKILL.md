---
name: pijota-extension
description: Write Pijota extensions following project conventions
---
When asked to write a Pijota extension:

1. Create class in `src/main/java/ai/pijota/extensions/examples/` implementing `Extension`
2. Override `name()`, `description()`, `init(ExtensionAPI api)`
3. In init, register tools/commands/events using the typed API
4. Register in `AppBootstrap.java`: `ext.register(new MyExtension());`
5. Tool schema: `Map.of("type","object", "properties", Map.of(...), "required", List.of(...))`
6. Execute returns `CompletableFuture.completedFuture(Tool.ToolResult.success(...))`
7. Typed event handlers: `onToolCall`, `onTurnStart`, `onInput`, `onBeforeProviderRequest`, etc.
8. Use `api.runtime()` for exec, cwd, flags. Use `ctx.ui()` for confirm, theme
9. Block tools: return `ExtensionResult.Block("reason")`. Allow: `ExtensionResult.Allow()`
10. Follow existing code conventions - no comments, concise
