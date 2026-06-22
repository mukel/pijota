package ai.pijota.telegram;

import ai.pijota.config.AppConfig;
import ai.pijota.config.SettingsManager;

import org.junit.jupiter.api.Test;

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
    void toolHintShowsToolEmojiAndKeyArgument() {
        assertEquals("💻 bash: npm test", TelegramChat.toolHint("bash", "{\"command\":\"npm test\"}"));
        assertEquals("📖 read: App.java", TelegramChat.toolHint("read", "{\"file_path\":\"App.java\"}"));
        assertEquals("🔍 grep: TODO", TelegramChat.toolHint("grep", "{\"pattern\":\"TODO\",\"path\":\".\"}"));
    }

    @Test
    void toolHintFallsBackToBareToolWhenArgsAreUnusable() {
        assertEquals("🔧 mystery…", TelegramChat.toolHint("mystery", "not json"));
        assertEquals("💻 bash…", TelegramChat.toolHint("bash", "{}"));
        assertEquals("💻 bash…", TelegramChat.toolHint("bash", null));
    }

    @Test
    void toolHintTruncatesLongArguments() {
        String hint = TelegramChat.toolHint("bash", "{\"command\":\"" + "x".repeat(200) + "\"}");
        assertTrue(hint.length() <= 80 + "💻 bash: ".length(), hint.length() + "");
        assertTrue(hint.endsWith("…"), hint);
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
    void commandArgsExtractsTextAfterTheCommand() {
        assertEquals("", TelegramChat.commandArgs("/plan"));
        assertEquals("what is X", TelegramChat.commandArgs("/btw what is X"));
        assertEquals("a b", TelegramChat.commandArgs("  /cmd   a b  "));
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
        var models = TelegramChat.availableModels(settings);

        assertFalse(models.isEmpty(), "openai builtins should be offered once an openai key is set");
        // The invariant the picker relies on, env-independent: never offer a model we can't actually run.
        for (var m : models) {
            String key = settings.resolveApiKey(m.provider());
            assertTrue(key != null && !key.isBlank(), "offered a model with no key for provider " + m.provider());
        }
    }
}
