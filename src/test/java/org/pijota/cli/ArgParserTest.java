package org.pijota.cli;

import org.pijota.testkit.StdioCapture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ArgParserTest {

    @RegisterExtension
    final StdioCapture stdio = new StdioCapture();

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
    void parseMaxTurns() {
        assertNull(ArgParser.parse(new String[] {}).maxTurns(), "unset → null → config default applies");
        assertEquals(12, ArgParser.parse(new String[] {"--max-turns", "12"}).maxTurns());
        assertEquals(0, ArgParser.parse(new String[] {"--max-turns", "0"}).maxTurns(), "0 = unlimited");
        assertNull(ArgParser.parse(new String[] {"--max-turns", "abc"}).maxTurns(), "invalid → null (warned)");
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
    void unknownModeFallsBackToText() {
        // an unrecognized mode warns (stderr) but stays usable rather than blowing up
        var args = ArgParser.parse(new String[] {"--mode", "jsn"});
        assertEquals(Args.OutputMode.TEXT, args.outputMode());
    }

    @Test
    void thinkingValuePassesThroughVerbatim() {
        assertEquals("high", ArgParser.parse(new String[] {"--thinking", "high"}).thinkingLevel());
        // unknown value still passes through (warned, defaulted downstream); parser doesn't reject it
        assertEquals("hgh", ArgParser.parse(new String[] {"--thinking", "hgh"}).thinkingLevel());
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
        var args = ArgParser.parse(new String[] {"--tool", "read,write,edit"});
        assertEquals(3, args.enabledTools().size());
        assertTrue(args.enabledTools().contains("read"));
    }

    @Test
    void parseNoToolCsv() {
        var args = ArgParser.parse(new String[] {"--no-tool", "bash,write"});
        assertEquals(List.of("bash", "write"), args.excludeTools());
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
    void parseApproveFlags() {
        assertTrue(ArgParser.parse(new String[] {"--approve"}).approveProject());
        assertTrue(ArgParser.parse(new String[] {"-a"}).approveProject());
        assertTrue(ArgParser.parse(new String[] {"--no-approve"}).noApproveProject());
        assertTrue(ArgParser.parse(new String[] {"-na"}).noApproveProject());
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

    @Test
    void frontendSubcommandAcp() {
        var args = ArgParser.parse(new String[] {"acp"});
        assertEquals("true", args.extensionFlags().get("acp"));
        assertEquals(0, args.promptArgs().size());
    }

    @Test
    void frontendSubcommandTelegram() {
        var args = ArgParser.parse(new String[] {"telegram"});
        assertEquals("true", args.extensionFlags().get("telegram"));
        assertEquals(0, args.promptArgs().size());
    }

    @Test
    void frontendSubcommandWebTakesAddress() {
        var args = ArgParser.parse(new String[] {"web", "0.0.0.0:8080"});
        assertEquals("0.0.0.0:8080", args.extensionFlags().get("web"));
        assertEquals(0, args.promptArgs().size());
    }

    @Test
    void frontendSubcommandWebWithoutAddress() {
        var args = ArgParser.parse(new String[] {"web"});
        assertEquals("true", args.extensionFlags().get("web"));
    }

    /**
     * Regression: a value-optional flag before the subcommand used to eat it.
     * {@code pijota --suggest web 127.0.0.1:8080} gave "web" to --suggest as its guidance, left no frontend at all, and
     * the address fell through to the prompt, so pijota ran one headless turn asking the model about an address instead
     * of serving anything on it.
     */
    @Test
    void aValueOptionalFlagDoesNotSwallowTheFrontendSubcommand() {
        var args = ArgParser.parse(new String[] {"--suggest", "web", "127.0.0.1:8080"});

        assertEquals("true", args.extensionFlags().get("suggest"), "the bare on-switch, not the word \"web\"");
        assertEquals("127.0.0.1:8080", args.extensionFlags().get("web"), "the frontend still gets its address");
        assertEquals(0, args.promptArgs().size(), "and nothing is left over to be mistaken for a prompt");
    }

    /** The documented way to pass a value still passes any value, including one that names a frontend. */
    @Test
    void anExplicitValueIsStillTakenVerbatim() {
        var args = ArgParser.parse(new String[] {"--suggest=web"});
        assertEquals("web", args.extensionFlags().get("suggest"));
    }

    @Test
    void frontendSubcommandTuiIsDefaultNoOp() {
        var args = ArgParser.parse(new String[] {"tui"});
        assertEquals(0, args.extensionFlags().size());
        assertEquals(0, args.promptArgs().size());
    }

    @Test
    void frontendNameIsSubcommandOnlyAsFirstToken() {
        // A frontend name that isn't the first positional is an ordinary prompt word, not a subcommand.
        var args = ArgParser.parse(new String[] {"fix", "the", "web"});
        assertEquals(0, args.extensionFlags().size());
        assertEquals(3, args.promptArgs().size());
    }

    private String help() {
        ArgParser.printHelp();
        return stdio.out();
    }

    /** {@code --help} is read in a terminal, and 80 columns is the one width every terminal has. */
    @Test
    void helpFitsAnEightyColumnTerminal() {
        for (String line : help().split("\n"))
            assertTrue(line.length() <= 80, line.length() + " columns: " + line);
    }

    /**
     * The description column is hand-padded with literal spaces, which is exactly how it drifts: two of these rows sat
     * one column right of the other twenty-seven, and nothing said so.
     */
    @Test
    void theOptionColumnDoesNotDrift() {
        var starts = new TreeMap<Integer, String>();
        for (String line : help().split("\n")) {
            if (!line.startsWith("  -") || line.isBlank())
                continue;
            var m = Pattern.compile("^ {2}\\S.*?  +(\\S)").matcher(line);
            if (m.find())
                starts.putIfAbsent(m.start(1), line);
        }
        assertTrue(starts.size() >= 1, "no option rows found; the help shape changed");
        // Rows whose flag is too long to fit the column legitimately push their description right; every other row
        // must share one column, so at most two distinct starts (the column, and the overflow).
        assertTrue(starts.size() <= 2, "the description column drifted across " + starts.size() + " positions: "
                                        + starts.values());
    }

    /** Sections are separated by exactly one blank line; two read as the help having ended. */
    @Test
    void helpHasNoDoubleBlankLines() {
        assertFalse(help().contains("\n\n\n"), help());
    }

    /**
     * A binary is what most people have, and the repository is not part of it. Apache-2.0 §4(d) names three places the
     * attribution notices may live -- a NOTICE file, the documentation, "or, within a display generated by the
     * Derivative Works" -- and this command is the third. It has to carry the full texts, not a list of names, because
     * BSD-3-Clause wants its conditions and disclaimer reproduced and MIT wants its notice included, not cited.
     */
    @Test
    void theLicenseCommandPrintsTextsAndNotJustNames() {
        int code = LicenseCommand.run();
        String out = stdio.out();

        assertEquals(0, code);
        assertTrue(stdio.err().isEmpty(), "nothing on stderr when the texts are present: " + stdio.err());
        assertTrue(out.contains("Apache License"), "pijota's own license text is printed");
        assertTrue(out.contains("Version 2.0, January 2004"), out.substring(0, Math.min(200, out.length())));
        for (String attribution : new String[] {"Arc90", "PDF.js", "marked", "DOMPurify", "highlight.js", "Inter"})
            assertTrue(out.contains(attribution), attribution + " is embedded in the binary and must be attributed");
        assertFalse(out.contains("is missing from this build"), "the texts are packaged, not just referenced: " + out);
    }

    /** It sits beside --version, where people look for it, rather than as a top-level noun. */
    @Test
    void theLicenseFlagIsParsedLikeItsSiblings() {
        assertTrue(ArgParser.parse(new String[] {"--license"}).license());
        assertTrue(ArgParser.parse(new String[] {"--licence"}).license(), "the other spelling too");
        assertFalse(ArgParser.parse(new String[] {"--version"}).license());
        assertFalse(ArgParser.parse(new String[] {}).license());
    }
}
