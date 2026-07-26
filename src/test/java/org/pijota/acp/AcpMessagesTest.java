package org.pijota.acp;

import org.pijota.modes.SlashCommand;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the pure ACP wire-format mapping. No transport/state involved. */
class AcpMessagesTest {

    @Test
    void initializeResultAdvertisesCapabilities() {
        JsonNode r = AcpMessages.initializeResult(1);
        assertEquals(1, r.get("protocolVersion").asInt());
        assertFalse(r.path("agentCapabilities").path("loadSession").asBoolean());
        assertTrue(r.path("agentCapabilities").path("promptCapabilities").path("image").asBoolean());
        assertTrue(r.path("authMethods").isArray());
        assertEquals(0, r.path("authMethods").size());
    }

    @Test
    void textChunkShape() {
        JsonNode u = AcpMessages.textChunk("agent_message_chunk", "hi");
        assertEquals("agent_message_chunk", u.get("sessionUpdate").asText());
        assertEquals("text", u.path("content").path("type").asText());
        assertEquals("hi", u.path("content").path("text").asText());
    }

    @Test
    void toolCallCarriesKindAndRawInput() {
        JsonNode u = AcpMessages.toolCall("bash", "call-1", "{\"command\":\"echo hi\"}");
        assertEquals("tool_call", u.get("sessionUpdate").asText());
        assertEquals("call-1", u.get("toolCallId").asText());
        assertEquals("bash", u.get("title").asText());
        assertEquals("execute", u.get("kind").asText());
        assertEquals("in_progress", u.get("status").asText());
        assertEquals("echo hi", u.path("rawInput").path("command").asText());
    }

    @Test
    void toolCallOmitsRawInputWhenArgsUnparseable() {
        JsonNode u = AcpMessages.toolCall("read", "c", "not json");
        assertFalse(u.has("rawInput"));
        assertEquals("read", u.get("kind").asText());
    }

    @Test
    void toolResultStatusReflectsError() {
        assertEquals("completed", AcpMessages.toolResult("c", "ok", false).get("status").asText());
        JsonNode err = AcpMessages.toolResult("c", "boom", true);
        assertEquals("failed", err.get("status").asText());
        assertEquals("boom", err.path("content").get(0).path("content").path("text").asText());
    }

    @Test
    void toolResultToleratesNullContent() {
        JsonNode u = AcpMessages.toolResult("c", null, false);
        assertEquals("", u.path("content").get(0).path("content").path("text").asText());
    }

    @Test
    void availableCommandsUsesBareNames() {
        var cmds = List.of(new SlashCommand("model", "Switch model", List.of(), a -> {}),
                                        new SlashCommand("new", null, List.of(), a -> {}));
        JsonNode u = AcpMessages.availableCommands(cmds);
        assertEquals("available_commands_update", u.get("sessionUpdate").asText());
        var arr = u.get("availableCommands");
        assertEquals(2, arr.size());
        assertEquals("model", arr.get(0).get("name").asText()); // no leading slash
        assertEquals("Switch model", arr.get(0).get("description").asText());
        assertEquals("", arr.get(1).get("description").asText()); // null description → ""
    }

    @Test
    void permissionRequestHasThreeOptions() {
        JsonNode p = AcpMessages.permissionRequest("sess-1", "bash", "call-9", "{\"command\":\"ls\"}");
        assertEquals("sess-1", p.get("sessionId").asText());
        assertEquals("call-9", p.path("toolCall").path("toolCallId").asText());
        assertEquals("execute", p.path("toolCall").path("kind").asText());
        var opts = p.get("options");
        assertEquals(3, opts.size());
        assertEquals("allow_once", opts.get(0).get("optionId").asText());
        assertEquals("allow_always", opts.get(1).get("optionId").asText());
        assertEquals("reject_once", opts.get(2).get("optionId").asText());
        assertEquals("allow_once", opts.get(0).get("kind").asText()); // kind mirrors id
    }

    @Test
    void sessionModesShape() {
        JsonNode m = AcpMessages.sessionModes("medium", List.of(new AcpMessages.Mode("off", "Off", "none"),
                                        new AcpMessages.Mode("medium", "Medium", null)));
        assertEquals("medium", m.get("currentModeId").asText());
        assertEquals(2, m.get("availableModes").size());
        assertEquals("off", m.path("availableModes").get(0).get("id").asText());
        assertEquals("Off", m.path("availableModes").get(0).get("name").asText());
        assertEquals("none", m.path("availableModes").get(0).get("description").asText());
        assertFalse(m.path("availableModes").get(1).has("description")); // null description omitted
    }

    @Test
    void currentModeUpdateShape() {
        JsonNode u = AcpMessages.currentModeUpdate("high");
        assertEquals("current_mode_update", u.get("sessionUpdate").asText());
        assertEquals("high", u.get("currentModeId").asText());
    }

    @Test
    void usageUpdateShape() {
        JsonNode u = AcpMessages.usageUpdate(1234, 200000);
        assertEquals("usage_update", u.get("sessionUpdate").asText());
        assertEquals(1234, u.get("used").asLong());
        assertEquals(200000, u.get("size").asLong());
    }

    @Test
    void selectedOptionReadsOutcome() {
        assertEquals("allow_once", AcpMessages.selectedOption(
                                        parse("{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"allow_once\"}}")));
        assertNull(AcpMessages.selectedOption(parse("{\"outcome\":{\"outcome\":\"cancelled\"}}")));
        assertNull(AcpMessages.selectedOption(null));
    }

    @Test
    void fsReadWriteParams() {
        JsonNode r = AcpMessages.fsReadParams("s1", "/abs/a.txt");
        assertEquals("s1", r.get("sessionId").asText());
        assertEquals("/abs/a.txt", r.get("path").asText());
        JsonNode w = AcpMessages.fsWriteParams("s2", "/abs/b.txt", "hi");
        assertEquals("s2", w.get("sessionId").asText());
        assertEquals("/abs/b.txt", w.get("path").asText());
        assertEquals("hi", w.get("content").asText());
    }

    @Test
    void parsePromptExtractsTextAndImages() {
        JsonNode prompt = parse("""
                                        [{"type":"text","text":"line one"},
                                         {"type":"resource","resource":{"text":"line two"}},
                                         {"type":"resource_link","uri":"file:///a.txt"},
                                         {"type":"image","data":"BASE64","mimeType":"image/jpeg"}]""");
        var content = AcpMessages.parsePrompt(prompt);
        assertEquals("line one\nline two\n@file:///a.txt", content.text());
        assertEquals(1, content.attachments().size());
        assertEquals("image/jpeg", content.attachments().get(0).mime());
        assertEquals("BASE64", content.attachments().get(0).base64());
    }

    @Test
    void parsePromptHandlesNonArray() {
        var content = AcpMessages.parsePrompt(null);
        assertEquals("", content.text());
        assertTrue(content.attachments().isEmpty());
    }

    @Test
    void toolKindMapping() {
        assertEquals("execute", AcpMessages.toolKind("bash"));
        assertEquals("execute", AcpMessages.toolKind("tmux_bash")); // canonical shell set, not just bash
        assertEquals("edit", AcpMessages.toolKind("apply_patch"));
        assertEquals("read", AcpMessages.toolKind("ls"));
        assertEquals("search", AcpMessages.toolKind("grep"));
        assertEquals("other", AcpMessages.toolKind("something_else"));
    }

    private static JsonNode parse(String json) {
        try {
            return org.pijota.util.Util.MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
