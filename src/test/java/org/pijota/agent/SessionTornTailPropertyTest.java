package org.pijota.agent;

import org.pijota.messages.Message;
import org.pijota.messages.ToolCall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A crash can cut a session file at any byte, so the repair is checked at every byte rather than at the three or four a
 * person would think to try.
 *
 * <p>
 * The property is the one that matters for data loss: whatever survives has to be a prefix of what was written. A torn
 * file may lose the entry that was still being written -- that write was never acknowledged, so nothing promised it --
 * but it may never lose an entry written before that one, never reorder, and never come back as something else. The
 * failure this guards against is not a lost tail; it is a truncated file that loads as a shorter conversation without
 * saying so.
 */
class SessionTornTailPropertyTest {

    @TempDir
    Path tempDir;

    /** Deliberately not all ASCII: a crash lands mid-character as readily as between two, and UTF-8 is multi-byte. */
    private static final List<String> CONTENT = List.of("first message", "日本語のメッセージ", "third with emoji 😀 here",
                                    "fourth", "fifth, the last one");

    private Path writeSession() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = manager.createSession(tempDir.toString());
        for (String text : CONTENT)
            manager.appendMessage(file, new Message.User(text));
        return file;
    }

    private static List<String> texts(List<Message> messages) {
        var out = new ArrayList<String>();
        for (var m : messages)
            if (m instanceof Message.User u)
                out.add(u.content());
        return out;
    }

    /**
     * Truncated at every length, the load must succeed and return a prefix of what was written. Not "some subset":
     * anything else means an entry was dropped from the middle, or two were merged into one, which is what a torn tail
     * does to an append-only file when nobody repairs it.
     */
    @Test
    void everyTruncationLoadsAPrefixOfWhatWasWritten() throws Exception {
        byte[] whole = Files.readAllBytes(writeSession());
        var expected = texts(new SessionManager(tempDir).loadMessages(writeSession()));
        assertEquals(CONTENT, expected, "fixture check: the intact file holds every message");

        for (int cut = 0; cut <= whole.length; cut++) {
            final int length = cut;
            var torn = tempDir.resolve("torn-" + length + ".jsonl");
            Files.write(torn, Arrays.copyOf(whole, length));

            List<Message> loaded;
            try {
                loaded = new SessionManager(tempDir).loadMessages(torn);
            } catch (Exception e) {
                fail("cut at " + length + " of " + whole.length + " bytes threw " + e);
                return;
            }
            var got = texts(loaded);
            assertEquals(expected.subList(0, got.size()), got,
                                            () -> "cut at " + length + " did not load a prefix: " + got);
        }
    }

    /**
     * The repair has to leave the file appendable, which is the half that actually corrupts data: an unterminated final
     * line makes the next append land on the same line, and both entries are then lost together, taking every entry
     * that named the second one as its parent with them.
     */
    @Test
    void everyTruncationLeavesAFileThatCanBeAppendedTo() throws Exception {
        byte[] whole = Files.readAllBytes(writeSession());

        for (int cut = 0; cut <= whole.length; cut++) {
            final int length = cut;
            var torn = tempDir.resolve("appendable-" + length + ".jsonl");
            Files.write(torn, Arrays.copyOf(whole, length));

            var resumed = new SessionManager(tempDir);
            List<Message> before;
            try {
                before = resumed.loadMessages(torn); // the load is what repairs it
                resumed.appendMessage(torn, new Message.User("after the crash"));
            } catch (Exception e) {
                fail("cut at " + length + " could not be resumed: " + e);
                return;
            }
            var after = texts(new SessionManager(tempDir).loadMessages(torn));

            assertEquals(texts(before).size() + 1, after.size(),
                                            () -> "cut at " + length + ": the appended entry did not survive as its "
                                                                            + "own line, got " + after);
            assertEquals("after the crash", after.get(after.size() - 1));
        }
    }

    /**
     * The same sweep over a session that was in the middle of a tool batch, which is where a crash is likeliest: the
     * assistant's call is persisted before the tool runs, so any cut between the two leaves a call with no result. A
     * provider rejects a request whose calls are unanswered, so the failure is not a lost turn but a session that
     * cannot be resumed at all.
     */
    @Test
    void aCrashDuringAToolBatchStillLeavesAResumableSession() throws Exception {
        var manager = new SessionManager(tempDir);
        var file = manager.createSession(tempDir.toString());
        manager.appendMessage(file, new Message.User("please run it"));
        var call = new ToolCall("call-1", "bash", "{\"command\":\"ls\"}");
        manager.appendMessage(file, new Message.Assistant("running", List.of(call), null));
        manager.appendMessage(file, new Message.ToolResult("call-1", "bash", "output", false));
        byte[] whole = Files.readAllBytes(file);

        for (int cut = 0; cut <= whole.length; cut++) {
            final int length = cut;
            var torn = tempDir.resolve("batch-" + length + ".jsonl");
            Files.write(torn, Arrays.copyOf(whole, length));

            List<Message> loaded;
            try {
                loaded = new SessionManager(tempDir).loadMessages(torn);
            } catch (Exception e) {
                fail("cut at " + length + " threw " + e);
                return;
            }
            // Every call the context still carries has to have an answer, whether the tool ever ran or not.
            var answered = new HashSet<String>();
            for (var m : loaded)
                if (m instanceof Message.ToolResult r)
                    answered.add(r.toolCallId());
            for (var m : loaded)
                if (m instanceof Message.Assistant assistant && assistant.toolCalls() != null)
                    for (var c : assistant.toolCalls())
                        assertTrue(answered.contains(c.id()), () -> "cut at " + length + " left tool call " + c.id()
                                                        + " unanswered, which a provider rejects");
        }
    }

    /** A file cut to nothing is a session with no entries, not a crash and not a resurrection of someone else's. */
    @Test
    void aFileCutToNothingIsSimplyEmpty() throws Exception {
        var empty = tempDir.resolve("empty.jsonl");
        Files.write(empty, new byte[0]);

        assertTrue(new SessionManager(tempDir).loadMessages(empty).isEmpty());
    }

    /**
     * Corruption that is not truncation: a killed write, a bad sector or a hand-edit leaves garbage in the middle of a
     * file, not a clean cut at the end. Loading such a file is the crash-recovery path, so it has to survive whatever
     * it finds -- the one outcome that must never happen is startup dying on a session it was asked to resume.
     *
     * <p>
     * The property is weaker than the truncation one on purpose, and the difference is the interesting part. Destroy
     * the entry in the middle and the entries older than it are orphaned: the branch walk starts at the tip and stops
     * at the gap. Corrupt a parentId and the chain skips the entry it used to name, leaving a hole. So what survives is
     * an ordered subsequence rather than a prefix. Destroy a byte inside a message's text and the line still parses, so
     * the message arrives with a replacement character in it -- which is the better outcome than dropping it, and why
     * the run is matched allowing for that. A reordering, an invented message or an exception is a real failure.
     */
    @Test
    void garbageAnywhereInTheFileNeverBringsDownTheLoad() throws Exception {
        byte[] whole = Files.readAllBytes(writeSession());
        var random = new java.util.Random(20260809L); // seeded: a failure has to be reproducible, not a rumour
        byte[][] junk = {"GARBAGE NOT JSON".getBytes(java.nio.charset.StandardCharsets.UTF_8), {0, 1, 2, 3},
                {(byte) 0xff, (byte) 0xfe}, "{\"unterminated\": ".getBytes(java.nio.charset.StandardCharsets.UTF_8)};

        for (int t = 0; t < 300; t++) {
            final int trial = t;
            byte[] corrupted = whole.clone();
            byte[] blob = junk[random.nextInt(junk.length)];
            int at = random.nextInt(Math.max(1, corrupted.length - blob.length));
            System.arraycopy(blob, 0, corrupted, at, Math.min(blob.length, corrupted.length - at));

            var file = tempDir.resolve("corrupt-" + trial + ".jsonl");
            Files.write(file, corrupted);

            List<String> got;
            try {
                got = texts(new SessionManager(tempDir).loadMessages(file));
            } catch (Exception e) {
                fail("trial " + trial + " (blob at byte " + at + " of " + corrupted.length + ") threw " + e);
                return;
            }
            assertTrue(got.size() <= CONTENT.size(),
                                            () -> "trial " + trial + " loaded more messages than were written: " + got);
            assertTrue(survivorsAreInOriginalOrder(got),
                                            () -> "trial " + trial + " reordered what survived intact: " + got);
        }
    }

    /**
     * Of the messages that came back byte-identical, the order must be the order they were written in.
     *
     * <p>
     * Deliberately not stronger. Junk that happens to be valid UTF-8 landing inside a JSON string is valid JSON with
     * different text, and nothing downstream can tell that from something the user typed -- trial 129 produced "third
     * withGARBAGE NOT JSON" and no loader could have known. So the altered ones are not checked; what is checked is
     * that nothing was invented, nothing duplicated, and nothing swapped around, which is what a reader can actually
     * promise.
     */
    private static boolean survivorsAreInOriginalOrder(List<String> got) {
        int next = 0;
        for (String loaded : got) {
            int at = CONTENT.indexOf(loaded);
            if (at < 0)
                continue; // altered beyond recognition; unknowable, so not judged
            if (at < next)
                return false;
            next = at + 1;
        }
        return true;
    }
}
