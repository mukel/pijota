package org.pijota.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class SessionExporterTest {

    @Test
    void exportMissingFileThrows() {
        assertThrows(IOException.class, () -> {
            SessionExporter.toMarkdown(Path.of("/nonexistent/session.jsonl"));
        });
    }

    @Test
    void exportEmptyJsonlProducesEmptyMarkdown(@TempDir Path tmp) throws Exception {
        var sessionFile = tmp.resolve("session.jsonl");
        Files.writeString(sessionFile, """
                                        {"type":"session","id":"abc123","timestamp":"2025-01-15T10:30:00Z"}

                                        """);

        var md = SessionExporter.toMarkdown(sessionFile);
        assertTrue(md.contains("# Pijota Session"));
        assertTrue(md.contains("*(empty session)*"));
    }

    @Test
    void exportSessionWithUserAssistantTurn(@TempDir Path tmp) throws Exception {
        var sessionFile = tmp.resolve("session.jsonl");
        Files.writeString(sessionFile, """
                                        {"type":"session","id":"abc12345","timestamp":"2025-01-15T10:30:00Z","cwd":"/home/user/project"}
                                        {"type":"message","role":"user","id":"u1","parentId":null,"timestamp":"2025-01-15T10:31:00Z","content":"hello"}
                                        {"type":"message","role":"assistant","id":"a1","parentId":"u1","timestamp":"2025-01-15T10:31:05Z","content":"Hi there!"}
                                        {"type":"message","role":"user","id":"u2","parentId":"a1","timestamp":"2025-01-15T10:32:00Z","content":"help me"}
                                        {"type":"message","role":"assistant","id":"a2","parentId":"u2","timestamp":"2025-01-15T10:32:05Z","content":"Sure, what do you need?"}
                                        """);

        var md = SessionExporter.toMarkdown(sessionFile);
        assertTrue(md.contains("# Pijota Session"));
        assertTrue(md.contains("**Working Directory:** `/home/user/project`"));
        assertTrue(md.contains("**Session:** `abc12345`"));
        assertTrue(md.contains("## Turn "));
        assertTrue(md.contains("### User"));
        assertTrue(md.contains("hello"));
        assertTrue(md.contains("### Assistant"));
        assertTrue(md.contains("Hi there!"));
        assertTrue(md.contains("help me"));
        assertTrue(md.contains("Sure, what do you need?"));
    }

    @Test
    void exportSessionWithToolCalls(@TempDir Path tmp) throws Exception {
        var sessionFile = tmp.resolve("session.jsonl");
        Files.writeString(sessionFile, """
                                        {"type":"session","id":"abc12345","timestamp":"2025-01-15T10:30:00Z"}
                                        {"type":"message","role":"user","id":"u1","parentId":null,"timestamp":"2025-01-15T10:31:00Z","content":"read file.txt"}
                                        {"type":"message","role":"assistant","id":"a1","parentId":"u1","timestamp":"2025-01-15T10:31:05Z","content":"Let me read that","tool_calls":[{"id":"call1","name":"read","arguments":"{\\"file\\":\\"file.txt\\"}"}]}
                                        {"type":"message","role":"tool_result","id":"t1","parentId":"a1","timestamp":"2025-01-15T10:31:06Z","tool_name":"read","content":"file contents here","is_error":false}
                                        """);

        var md = SessionExporter.toMarkdown(sessionFile);
        assertTrue(md.contains("*Tool calls:*"));
        assertTrue(md.contains("- `read`"));
        assertTrue(md.contains("### Tool: `read`"));
        assertTrue(md.contains("file contents here"));
    }

    @Test
    void exportSessionWithThinking(@TempDir Path tmp) throws Exception {
        var sessionFile = tmp.resolve("session.jsonl");
        Files.writeString(sessionFile, """
                                        {"type":"session","id":"abc12345","timestamp":"2025-01-15T10:30:00Z"}
                                        {"type":"message","role":"user","id":"u1","parentId":null,"timestamp":"2025-01-15T10:31:00Z","content":"question"}
                                        {"type":"message","role":"assistant","id":"a1","parentId":"u1","timestamp":"2025-01-15T10:31:05Z","content":"answer","thinking":"Let me think about this"}
                                        """);

        var md = SessionExporter.toMarkdown(sessionFile);
        assertTrue(md.contains("<details>"));
        assertTrue(md.contains("<summary>Thinking</summary>"));
        assertTrue(md.contains("Let me think about this"));
    }

    @Test
    void exportSessionWithCompaction(@TempDir Path tmp) throws Exception {
        var sessionFile = tmp.resolve("session.jsonl");
        Files.writeString(sessionFile, """
                                        {"type":"session","id":"abc12345","timestamp":"2025-01-15T10:30:00Z"}
                                        {"type":"message","role":"user","id":"u1","parentId":null,"timestamp":"2025-01-15T10:31:00Z","content":"old msg"}
                                        {"type":"message","role":"assistant","id":"a1","parentId":"u1","timestamp":"2025-01-15T10:31:05Z","content":"old response"}
                                        {"type":"compaction","id":"c1","parentId":"a1","summary":"summarized context","compactedCount":2}
                                        {"type":"message","role":"user","id":"u2","parentId":"c1","timestamp":"2025-01-15T10:32:00Z","content":"new msg"}
                                        {"type":"message","role":"assistant","id":"a2","parentId":"u2","timestamp":"2025-01-15T10:32:05Z","content":"new response"}
                                        """);

        var md = SessionExporter.toMarkdown(sessionFile);
        assertTrue(md.contains("## Compaction"));
        assertTrue(md.contains("summarized context"));
        assertTrue(md.contains("## Turn "));
        assertTrue(md.contains("new msg"));
        assertTrue(md.contains("new response"));
    }

    @Test
    void exportSessionWithErrorToolResult(@TempDir Path tmp) throws Exception {
        var sessionFile = tmp.resolve("session.jsonl");
        Files.writeString(sessionFile, """
                                        {"type":"session","id":"abc12345","timestamp":"2025-01-15T10:30:00Z"}
                                        {"type":"message","role":"user","id":"u1","parentId":null,"timestamp":"2025-01-15T10:31:00Z","content":"run command"}
                                        {"type":"message","role":"assistant","id":"a1","parentId":"u1","timestamp":"2025-01-15T10:31:05Z","content":"","tool_calls":[{"id":"call1","name":"bash","arguments":"{\\"command\\":\\"bad\\"}"}]}
                                        {"type":"message","role":"tool_result","id":"t1","parentId":"a1","timestamp":"2025-01-15T10:31:06Z","tool_name":"bash","content":"command not found","is_error":true}
                                        """);

        var md = SessionExporter.toMarkdown(sessionFile);
        assertTrue(md.contains("error"));
    }

    @Test
    void exportSessionWithBashToolDetails(@TempDir Path tmp) throws Exception {
        var sessionFile = tmp.resolve("session.jsonl");
        Files.writeString(sessionFile, """
                                        {"type":"session","id":"abc12345","timestamp":"2025-01-15T10:30:00Z"}
                                        {"type":"message","role":"user","id":"u1","parentId":null,"timestamp":"2025-01-15T10:31:00Z","content":"run ls"}
                                        {"type":"message","role":"assistant","id":"a1","parentId":"u1","timestamp":"2025-01-15T10:31:05Z","content":"","tool_calls":[{"id":"call1","name":"bash","arguments":"{\\"command\\":\\"ls\\"}"}]}
                                        {"type":"message","role":"tool_result","id":"t1","parentId":"a1","timestamp":"2025-01-15T10:31:06Z","tool_name":"bash","content":"output here","is_error":false,"details":{"exitCode":1}}
                                        """);

        var md = SessionExporter.toMarkdown(sessionFile);
        assertTrue(md.contains("**Exit code:** 1"));
    }

    @Test
    void exportSessionWithGlobToolDetails(@TempDir Path tmp) throws Exception {
        var sessionFile = tmp.resolve("session.jsonl");
        Files.writeString(sessionFile, """
                                        {"type":"session","id":"abc12345","timestamp":"2025-01-15T10:30:00Z"}
                                        {"type":"message","role":"user","id":"u1","parentId":null,"timestamp":"2025-01-15T10:31:00Z","content":"search"}
                                        {"type":"message","role":"assistant","id":"a1","parentId":"u1","timestamp":"2025-01-15T10:31:05Z","content":"","tool_calls":[{"id":"call1","name":"glob","arguments":"{\\"pattern\\":\\"*.java\\"}"}]}
                                        {"type":"message","role":"tool_result","id":"t1","parentId":"a1","timestamp":"2025-01-15T10:31:06Z","tool_name":"glob","content":"result","is_error":false,"details":{"resultCount":5}}
                                        """);

        var md = SessionExporter.toMarkdown(sessionFile);
        assertTrue(md.contains("5 result(s)"));
    }

    @Test
    void exportSessionWithGrepToolDetails(@TempDir Path tmp) throws Exception {
        var sessionFile = tmp.resolve("session.jsonl");
        Files.writeString(sessionFile, """
                                        {"type":"session","id":"abc12345","timestamp":"2025-01-15T10:30:00Z"}
                                        {"type":"message","role":"user","id":"u1","parentId":null,"timestamp":"2025-01-15T10:31:00Z","content":"grep for test"}
                                        {"type":"message","role":"assistant","id":"a1","parentId":"u1","timestamp":"2025-01-15T10:31:05Z","content":"","tool_calls":[{"id":"call1","name":"grep","arguments":"{\\"pattern\\":\\"test\\"}"}]}
                                        {"type":"message","role":"tool_result","id":"t1","parentId":"a1","timestamp":"2025-01-15T10:31:06Z","tool_name":"grep","content":"matches","is_error":false,"details":{"matchCount":3,"pattern":"test"}}
                                        """);

        var md = SessionExporter.toMarkdown(sessionFile);
        assertTrue(md.contains("3 match(es)"));
        assertTrue(md.contains("test"));
    }

    @Test
    void exportSessionWithWriteToolDetails(@TempDir Path tmp) throws Exception {
        var sessionFile = tmp.resolve("session.jsonl");
        Files.writeString(sessionFile, """
                                        {"type":"session","id":"abc12345","timestamp":"2025-01-15T10:30:00Z"}
                                        {"type":"message","role":"user","id":"u1","parentId":null,"timestamp":"2025-01-15T10:31:00Z","content":"write file"}
                                        {"type":"message","role":"assistant","id":"a1","parentId":"u1","timestamp":"2025-01-15T10:31:05Z","content":"","tool_calls":[{"id":"call1","name":"write","arguments":"{\\"filePath\\":\\"/tmp/out.txt\\"}"}]}
                                        {"type":"message","role":"tool_result","id":"t1","parentId":"a1","timestamp":"2025-01-15T10:31:06Z","tool_name":"write","content":"ok","is_error":false,"details":{"filePath":"/tmp/out.txt"}}
                                        """);

        var md = SessionExporter.toMarkdown(sessionFile);
        assertTrue(md.contains("**File:** `/tmp/out.txt`"));
    }

    @Test
    void exportSessionWithLargeToolOutputTruncates(@TempDir Path tmp) throws Exception {
        var sessionFile = tmp.resolve("session.jsonl");
        var largeContent = "x".repeat(15_000);
        Files.writeString(sessionFile, """
                                        {"type":"session","id":"abc12345","timestamp":"2025-01-15T10:30:00Z"}
                                        {"type":"message","role":"user","id":"u1","parentId":null,"timestamp":"2025-01-15T10:31:00Z","content":"run"}
                                        {"type":"message","role":"assistant","id":"a1","parentId":"u1","timestamp":"2025-01-15T10:31:05Z","content":"","tool_calls":[{"id":"call1","name":"bash","arguments":"{}"}]}
                                        {"type":"message","role":"tool_result","id":"t1","parentId":"a1","timestamp":"2025-01-15T10:31:06Z","tool_name":"bash","content":"%s","is_error":false}
                                        """
                                        .formatted(largeContent));

        var md = SessionExporter.toMarkdown(sessionFile);
        assertTrue(md.contains("(output truncated"));
    }

    @Test
    void exportSessionWithModelChangeSkipped(@TempDir Path tmp) throws Exception {
        var sessionFile = tmp.resolve("session.jsonl");
        Files.writeString(sessionFile, """
                                        {"type":"session","id":"abc12345","timestamp":"2025-01-15T10:30:00Z"}
                                        {"type":"model_change","id":"mc1","parentId":null,"provider":"openai","model":"gpt-4"}
                                        {"type":"message","role":"user","id":"u1","parentId":"mc1","timestamp":"2025-01-15T10:31:00Z","content":"hello"}
                                        {"type":"message","role":"assistant","id":"a1","parentId":"u1","timestamp":"2025-01-15T10:31:05Z","content":"hi"}
                                        """);

        var md = SessionExporter.toMarkdown(sessionFile);
        assertTrue(md.contains("hello"));
        assertTrue(md.contains("hi"));
    }

    @Test
    void exportSessionWithReadToolDetails(@TempDir Path tmp) throws Exception {
        var sessionFile = tmp.resolve("session.jsonl");
        Files.writeString(sessionFile, """
                                        {"type":"session","id":"abc12345","timestamp":"2025-01-15T10:30:00Z"}
                                        {"type":"message","role":"user","id":"u1","parentId":null,"timestamp":"2025-01-15T10:31:00Z","content":"read file"}
                                        {"type":"message","role":"assistant","id":"a1","parentId":"u1","timestamp":"2025-01-15T10:31:05Z","content":"","tool_calls":[{"id":"call1","name":"read","arguments":"{\\"filePath\\":\\"/tmp/file.txt\\"}"}]}
                                        {"type":"message","role":"tool_result","id":"t1","parentId":"a1","timestamp":"2025-01-15T10:31:06Z","tool_name":"read","content":"file contents","is_error":false,"details":{"totalLines":42,"offset":10,"limit":20}}
                                        """);

        var md = SessionExporter.toMarkdown(sessionFile);
        assertTrue(md.contains("42 line(s)"));
        assertTrue(md.contains("offset 10"));
        assertTrue(md.contains("limit 20"));
    }
}
