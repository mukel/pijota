package ai.pijota.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArgParserTest {

    @Test
    void parseProvider() {
        var args = ArgParser.parse(new String[] {"--provider", "openai"});
        assertEquals("openai", args.provider());
    }

    @Test
    void parseModel() {
        var args = ArgParser.parse(new String[] {"--model", "gpt-4"});
        assertEquals("gpt-4", args.model());
    }

    @Test
    void parseModelWithProviderSlash() {
        var args = ArgParser.parse(new String[] {"-m", "openai/gpt-4"});
        assertEquals("openai", args.provider());
        assertEquals("gpt-4", args.model());
    }

    @Test
    void parseModelShortFlag() {
        var args = ArgParser.parse(new String[] {"-m", "gpt-4"});
        assertEquals("gpt-4", args.model());
    }

    @Test
    void parseApiKey() {
        var args = ArgParser.parse(new String[] {"--api-key", "sk-123"});
        assertEquals("sk-123", args.apiKey());
    }

    @Test
    void parseSystemPrompt() {
        var args = ArgParser.parse(new String[] {"--system-prompt", "You are helpful"});
        assertEquals("You are helpful", args.systemPrompt());
    }

    @Test
    void parseAppendSystemPromptAccumulates() {
        var args = ArgParser.parse(new String[] {"--append-system-prompt", "rule1", "--append-system-prompt", "rule2"});
        assertEquals(2, args.appendSystemPrompts().size());
        assertTrue(args.appendSystemPrompts().contains("rule1"));
        assertTrue(args.appendSystemPrompts().contains("rule2"));
    }

    @Test
    void parseThinkingLevel() {
        var args = ArgParser.parse(new String[] {"--thinking", "high"});
        assertEquals("high", args.thinkingLevel());
    }

    @Test
    void parseModeText() {
        var args = ArgParser.parse(new String[] {"--mode", "text"});
        assertEquals(Args.OutputMode.TEXT, args.outputMode());
    }

    @Test
    void parseModeJson() {
        var args = ArgParser.parse(new String[] {"--mode", "json"});
        assertEquals(Args.OutputMode.JSON, args.outputMode());
    }

    @Test
    void parseModeRpc() {
        var args = ArgParser.parse(new String[] {"--mode", "rpc"});
        assertEquals(Args.OutputMode.RPC, args.outputMode());
    }

    @Test
    void parseModeUnknownDefaultsToText() {
        var args = ArgParser.parse(new String[] {"--mode", "unknown"});
        assertEquals(Args.OutputMode.TEXT, args.outputMode());
    }

    @Test
    void parseSession() {
        var args = ArgParser.parse(new String[] {"--session", "abc123"});
        assertEquals("abc123", args.sessionPath());
    }

    @Test
    void parseSessionShortFlag() {
        var args = ArgParser.parse(new String[] {"-s", "abc123"});
        assertEquals("abc123", args.sessionPath());
    }

    @Test
    void parseFork() {
        var args = ArgParser.parse(new String[] {"--fork", "entry-id"});
        assertEquals("entry-id", args.forkPath());
    }

    @Test
    void parseExport() {
        var args = ArgParser.parse(new String[] {"--export", "out.md"});
        assertEquals("out.md", args.exportPath());
    }

    @Test
    void parseToolsCsv() {
        var args = ArgParser.parse(new String[] {"--tools", "read,write,edit"});
        assertEquals(3, args.enabledTools().size());
        assertTrue(args.enabledTools().contains("read"));
    }

    @Test
    void parseToolsShortFlag() {
        var args = ArgParser.parse(new String[] {"-t", "read,write"});
        assertEquals(2, args.enabledTools().size());
    }

    @Test
    void parseExtension() {
        var args = ArgParser.parse(new String[] {"--extension", "/path/to/ext"});
        assertEquals(1, args.extensionPaths().size());
        assertEquals("/path/to/ext", args.extensionPaths().get(0));
    }

    @Test
    void parseSkill() {
        var args = ArgParser.parse(new String[] {"--skill", "/path/to/skill"});
        assertEquals(1, args.skillPaths().size());
    }

    @Test
    void parseTheme() {
        var args = ArgParser.parse(new String[] {"--theme", "dark"});
        assertEquals(1, args.themePaths().size());
    }

    @Test
    void parseFlag_noSession() {
        var args = ArgParser.parse(new String[] {"--no-session"});
        assertTrue(args.noSession());
    }

    @Test
    void parseFlag_noTools() {
        var args = ArgParser.parse(new String[] {"--no-tools"});
        assertTrue(args.noTools());
    }

    @Test
    void parseFlag_noToolsShort() {
        var args = ArgParser.parse(new String[] {"-nt"});
        assertTrue(args.noTools());
    }

    @Test
    void parseFlag_noExtensions() {
        var args = ArgParser.parse(new String[] {"--no-extensions"});
        assertTrue(args.noExtensions());
    }

    @Test
    void parseFlag_noSkills() {
        var args = ArgParser.parse(new String[] {"--no-skills"});
        assertTrue(args.noSkills());
    }

    @Test
    void parseFlag_noThemes() {
        var args = ArgParser.parse(new String[] {"--no-themes"});
        assertTrue(args.noThemes());
    }

    @Test
    void parseFlag_noContextFiles() {
        var args = ArgParser.parse(new String[] {"--no-context-files"});
        assertTrue(args.noContextFiles());
    }

    @Test
    void parseFlag_noPromptTemplates() {
        var args = ArgParser.parse(new String[] {"--no-prompt-templates"});
        assertTrue(args.noPromptTemplates());
    }

    @Test
    void parseFlag_print() {
        var args = ArgParser.parse(new String[] {"--print"});
        assertTrue(args.print());
    }

    @Test
    void parseFlag_resume() {
        var args = ArgParser.parse(new String[] {"--resume"});
        assertTrue(args.resume());
    }

    @Test
    void parseFlag_continue() {
        var args = ArgParser.parse(new String[] {"--continue"});
        assertTrue(args.continueSession());
    }

    @Test
    void parseFlag_recover() {
        var args = ArgParser.parse(new String[] {"--recover"});
        assertTrue(args.recoverSession());
    }

    @Test
    void parseFlag_listModels() {
        var args = ArgParser.parse(new String[] {"--list-models"});
        assertTrue(args.listModels());
    }

    @Test
    void parseFlag_listModelsWithFilter() {
        var args = ArgParser.parse(new String[] {"--list-models", "openai"});
        assertTrue(args.listModels());
        assertEquals("openai", args.listModelsFilter());
    }

    @Test
    void parseFlag_verbose() {
        var args = ArgParser.parse(new String[] {"--verbose"});
        assertTrue(args.verbose());
    }

    @Test
    void parseFlag_help() {
        var args = ArgParser.parse(new String[] {"--help"});
        assertTrue(args.help());
    }

    @Test
    void parseFlag_version() {
        var args = ArgParser.parse(new String[] {"--version"});
        assertTrue(args.version());
    }

    @Test
    void parseFlag_agent() {
        var args = ArgParser.parse(new String[] {"--agent"});
        assertTrue(args.agent());
    }

    @Test
    void parseFlag_worker() {
        var args = ArgParser.parse(new String[] {"--worker"});
        assertTrue(args.worker());
    }

    @Test
    void parseFileArgsAtSign() {
        var args = ArgParser.parse(new String[] {"@file.txt"});
        assertEquals(1, args.fileArgs().size());
        assertEquals("file.txt", args.fileArgs().get(0));
    }

    @Test
    void parsePromptArgsPlain() {
        var args = ArgParser.parse(new String[] {"hello", "world"});
        assertEquals(2, args.promptArgs().size());
        assertEquals("hello", args.promptArgs().get(0));
    }

    @Test
    void parsePrintWithPromptArg() {
        var args = ArgParser.parse(new String[] {"--print", "what is java?"});
        assertTrue(args.print());
        assertEquals(1, args.promptArgs().size());
        assertEquals("what is java?", args.promptArgs().get(0));
    }

    @Test
    void parseExtensionFlags() {
        var args = ArgParser.parse(new String[] {"--myflag", "value"});
        assertEquals(1, args.extensionFlags().size());
        assertEquals("value", args.extensionFlags().get("myflag"));
    }

    @Test
    void parseExtensionFlagWithoutValue() {
        var args = ArgParser.parse(new String[] {"--flag"});
        assertEquals("true", args.extensionFlags().get("flag"));
    }

    @Test
    void parseExtensionFlagWithEquals() {
        var args = ArgParser.parse(new String[] {"--key=val"});
        assertEquals("val", args.extensionFlags().get("key"));
    }

    @Test
    void emptyArgsDefaultsToEmpty() {
        var args = ArgParser.parse(new String[] {});
        assertNull(args.provider());
        assertNull(args.model());
        assertEquals(Args.OutputMode.TEXT, args.outputMode());
        assertFalse(args.noSession());
    }

    @Test
    void missingValueThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            ArgParser.parse(new String[] {"--provider"});
        });
    }

    @Test
    void dashFlagsAreIgnored() {
        var args = ArgParser.parse(new String[] {"-x"});
        assertEquals(0, args.promptArgs().size());
        assertEquals(0, args.fileArgs().size());
    }

    @Test
    void promptTemplateArgument() {
        var args = ArgParser.parse(new String[] {"--prompt-template", "/path/tmpl"});
        assertEquals(1, args.promptTemplatePaths().size());
    }
}
