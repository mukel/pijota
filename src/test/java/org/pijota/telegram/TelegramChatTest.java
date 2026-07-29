package org.pijota.telegram;

import org.pijota.agent.TestSession;
import org.pijota.config.AppConfig;
import org.pijota.config.SettingsManager;
import org.pijota.llm.ModelRegistry;
import org.pijota.modes.SlashCommand;
import org.pijota.telegram.TelegramClient.BotCommand;
import org.pijota.telegram.TelegramClient.InlineKeyboard;
import org.pijota.telegram.TelegramClient.Update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TelegramChatTest {

    @Test
    void commandWordExtractsBareLowercaseCommand() {
        assertEquals("/stop", TelegramChat.commandWord("/stop"));
        assertEquals("/stop", TelegramChat.commandWord("/STOP"));
        assertEquals("/stop", TelegramChat.commandWord("  /stop  "));
    }

    @Test
    void commandWordDropsArgumentsAndBotnameSuffix() {
        assertEquals("/compact", TelegramChat.commandWord("/compact please be terse"));
        assertEquals("/help", TelegramChat.commandWord("/help@pijota_bot")); // Telegram's group-chat suffix
        assertEquals("/model", TelegramChat.commandWord("/model@bot extra"));
    }

    @Test
    void everyMenuCommandIsRecognizedByHandler() {
        // Guard against the menu and the dispatch switch drifting apart: each advertised command must normalize to a
        // distinct "/word" the handler's switch can match.
        for (var c : TelegramChat.COMMANDS)
            assertEquals("/" + c.command(), TelegramChat.commandWord("/" + c.command()));
        assertTrue(TelegramChat.COMMANDS.stream().anyMatch(c -> c.command().equals("status")));
    }

    private static final int LIMIT = 4096; // mirror TelegramChat.MAX_MESSAGE_LEN

    @Test
    void shortTextIsNotSplit() {
        assertEquals(List.of("hello"), TelegramChat.split("hello"));
    }

    @Test
    void longProseSplitsOnLineBoundariesUnderTheLimit() {
        String line = "x".repeat(80);
        String text = (line + "\n").repeat(120); // ~9.7k chars, far over one message
        var chunks = TelegramChat.split(text);

        assertTrue(chunks.size() >= 2, "should split into multiple messages");
        for (String c : chunks) {
            assertTrue(c.length() <= LIMIT, "chunk over limit: " + c.length());
            for (String l : c.split("\n", -1))
                assertEquals(line, l, "a line was cut mid-content"); // every line survives whole
        }
    }

    @Test
    void codeFenceIsClosedAndReopenedAcrossChunks() {
        String code = ("int v = 12345;\n").repeat(400); // big code body, > one message
        var chunks = TelegramChat.split("```java\n" + code + "```");

        assertTrue(chunks.size() >= 2);
        for (String c : chunks) {
            assertTrue(c.length() <= LIMIT, "chunk over limit: " + c.length());
            long fences = c.lines().filter(l -> l.strip().startsWith("```")).count();
            assertEquals(0, fences % 2, "each chunk must have balanced ``` fences:\n" + c);
        }
    }

    @Test
    void unterminatedFenceIsClosedOnTheFinalChunk() {
        // An answer that ends inside a code block (no closing ```) must still produce balanced chunks on every chunk.
        String code = ("int v = 12345;\n").repeat(400);
        var chunks = TelegramChat.split("```java\n" + code); // deliberately never closed

        for (String c : chunks) {
            long fences = c.lines().filter(l -> l.strip().startsWith("```")).count();
            assertEquals(0, fences % 2, "unterminated fence left a chunk unbalanced:\n" + c);
        }
    }

    @Test
    void appendFooterGoesOnLastChunkWhenItFits() {
        var chunks = new ArrayList<>(List.of("body"));
        TelegramChat.appendFooter(chunks, "\n\nfooter");
        assertEquals(1, chunks.size());
        assertTrue(chunks.getLast().endsWith("footer"));
    }

    @Test
    void appendFooterBecomesItsOwnChunkWhenItWouldOverflow() {
        var chunks = new ArrayList<>(List.of("x".repeat(LIMIT)));
        TelegramChat.appendFooter(chunks, "\n\nfooter");
        assertEquals(2, chunks.size());
        assertEquals("footer", chunks.getLast());
    }

    @Test
    void singleOverlongLineIsHardSplit() {
        String blob = "a".repeat(LIMIT * 2 + 10); // one line, no newlines, way over the limit
        var chunks = TelegramChat.split(blob);
        assertTrue(chunks.size() >= 3);
        for (String c : chunks)
            assertTrue(c.length() <= LIMIT, "chunk over limit: " + c.length());
    }

    @Test
    void toolHintUsesTheToolGlyphAndSharedLabelDetail() {
        // glyph is passed in (the caller resolves it from the tool registry); detail comes from the shared ToolLabels.
        assertEquals("$ bash: npm test", TelegramChat.toolHint("bash", () -> "{\"command\":\"npm test\"}", "$", "."));
        assertEquals("📖 read: App.java",
                                        TelegramChat.toolHint("read", () -> "{\"file_path\":\"App.java\"}", "📖", "."));
        assertEquals("🔍 grep: \"TODO\" in .", TelegramChat.toolHint("grep",
                                        () -> "{\"pattern\":\"TODO\",\"path\":\".\"}", "🔍", "."));
    }

    @Test
    void toolHintFallsBackToBareToolWhenArgsAreUnusable() {
        assertEquals("🔧 mystery…", TelegramChat.toolHint("mystery", () -> "not json", "🔧", "."));
        assertEquals("$ bash…", TelegramChat.toolHint("bash", () -> "{}", "$", "."));
        assertEquals("$ bash…", TelegramChat.toolHint("bash", () -> null, "$", "."));
    }

    @Test
    void toolHintTruncatesLongArguments() {
        String hint = TelegramChat.toolHint("bash", () -> "{\"command\":\"" + "x".repeat(200) + "\"}", "$", ".");
        assertTrue(hint.length() < 200, "long command should be shortened: " + hint.length());
        assertTrue(hint.contains("..."), hint);
    }

    @Test
    void largeAnswersAttachSmallOnesInline() {
        assertFalse(TelegramChat.shouldAttach("short answer"));
        assertTrue(TelegramChat.shouldAttach("x".repeat(2 * LIMIT + 1)));
    }

    @Test
    void previewCutsLongTextOnALineBoundaryWithAnEllipsis() {
        String text = ("line\n").repeat(500); // ~2.5k chars, well over the 600 preview cap
        String preview = TelegramChat.previewOf(text);
        assertTrue(preview.length() < text.length());
        assertTrue(preview.endsWith("…"), preview);
        assertFalse(preview.stripTrailing().endsWith("lin"), "should not cut mid-line: " + preview);
    }

    @Test
    void shortTextIsItsOwnPreview() {
        assertEquals("hi there", TelegramChat.previewOf("hi there"));
    }

    @Test
    void contextLineShowsFractionAndPercentOrNothingWhenUnknown() {
        assertEquals("", TelegramChat.contextLine(1000, 0), "no window → no line");
        String line = TelegramChat.contextLine(12_300, 200_000);
        assertTrue(line.contains("12K/200K"), line);
        assertTrue(line.contains("(6%)"), line);
        assertTrue(TelegramChat.contextLine(500_000, 200_000).contains("(100%)"), "caps at 100%");
    }

    @Test
    void toolFailureReasonIsSurfacedAndTruncated() {
        assertEquals("", TelegramChat.reason(null));
        assertEquals("", TelegramChat.reason("   "));
        assertEquals(": Chrome DevTools did not come up", TelegramChat.reason("Chrome DevTools did not come up"));
        assertTrue(TelegramChat.reason("x".repeat(500)).length() <= 202);
        assertTrue(TelegramChat.reason("x".repeat(500)).endsWith("…"));
    }

    @Test
    void modelPickerOnlyOffersModelsWhoseProviderHasAKey() {
        var settings = new SettingsManager(new AppConfig().withApiKey("openai", "sk-test"), c -> {});
        var models = ModelRegistry.availableModels(settings);

        assertFalse(models.isEmpty(), "openai builtins should be offered once an openai key is set");
        // The invariant the picker relies on, env-independent: never offer a model we can't actually run.
        for (var m : models) {
            String key = settings.resolveApiKey(m.provider());
            assertTrue(key != null && !key.isBlank(), "offered a model with no key for provider " + m.provider());
        }
    }

    // ---- command handling (incl. the /start deep-link greeting) ----

    /** A TelegramApi that records the text of every message the chat sends. */
    private static final class RecordingApi implements TelegramApi {
        final List<String> sent = new ArrayList<>();

        @Override
        public List<Update> getUpdates(long offset, int timeout) {
            return List.of();
        }

        @Override
        public long sendMessage(long chatId, String text) {
            sent.add(text);
            return sent.size();
        }

        @Override
        public long sendMessage(long chatId, String text, InlineKeyboard keyboard) {
            sent.add(text);
            return sent.size();
        }

        @Override
        public void editMessageText(long chatId, long messageId, String text) {}

        @Override
        public void answerCallback(String callbackQueryId) {}

        @Override
        public void sendChatAction(long chatId, String action) {}
    }

    private static TelegramChat chat(RecordingApi api, Path tmp) {
        var ts = TestSession.in(tmp).build();
        return new TelegramChat(1L, ts.session(), api, new SettingsManager(new AppConfig(), c -> {}));
    }

    @Test
    void startWithDeepLinkPayloadGreetsWithAConnectionConfirmation(@TempDir Path tmp) {
        var api = new RecordingApi();
        chat(api, tmp).onText("/start pijota"); // arrives from t.me/<bot>?start=pijota
        assertEquals(1, api.sent.size());
        assertTrue(api.sent.get(0).contains("Connected to your pijota session"), api.sent.get(0));
    }

    @Test
    void plainStartGreetsWithoutTheConnectionLine(@TempDir Path tmp) {
        var api = new RecordingApi();
        chat(api, tmp).onText("/start");
        assertEquals(1, api.sent.size());
        assertFalse(api.sent.get(0).contains("Connected to your pijota session"));
    }

    @Test
    void helpRepliesWithoutTouchingTheAgent(@TempDir Path tmp) {
        var api = new RecordingApi();
        chat(api, tmp).onText("/help");
        assertEquals(1, api.sent.size());
        assertFalse(api.sent.get(0).isBlank());
    }

    // ---- command-menu parity (menuCommands) ----

    @Test
    void menuCommandsMergesValidExtensionCommandsAndSkipsHyphenatedOrDuplicates() {
        var ext = List.of(new SlashCommand("plan", "Toggle plan mode", List.of(), a -> {}),
                                        new SlashCommand("remote-control", "hyphen → invalid for Telegram", List.of(),
                                                                        a -> {}),
                                        new SlashCommand("help", "duplicate of a built-in", List.of(), a -> {}));
        var menu = TelegramChat.menuCommands(ext);
        var names = menu.stream().map(BotCommand::command).toList();

        assertTrue(menu.containsAll(TelegramChat.COMMANDS), "keeps all built-ins");
        assertTrue(names.contains("plan"), "valid extension command is added");
        assertFalse(names.contains("remote-control"), "hyphenated names are skipped (Telegram allows only [a-z0-9_])");
        assertEquals(1, names.stream().filter("help"::equals).count(), "a built-in isn't duplicated");
    }
}
