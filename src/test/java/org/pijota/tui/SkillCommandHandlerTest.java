package org.pijota.tui;

import org.pijota.extensions.skills.SkillLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillCommandHandlerTest {
    @Test
    void parsesSkillCommandWithInstructions() {
        var parsed = SkillCommandHandler.parseSkillCommand("/skill:review check tests");

        assertEquals("review", parsed.name());
        assertEquals("check tests", parsed.instructions());
    }

    @Test
    void expandsPromptWithOptionalInstructions() {
        var skill = skill("review", "Review code", "Read carefully.");

        String prompt = SkillCommandHandler.expandedPrompt(skill, "focus tests");

        assertTrue(prompt.contains("# Skill: review"));
        assertTrue(prompt.contains("Read carefully."));
        assertTrue(prompt.contains("# Instructions"));
        assertTrue(prompt.contains("focus tests"));
    }

    @Test
    void showSkillsRendersLoadedSkills() {
        var host = new FakeHost(List.of(skill("review", "Review code", "")));
        var handler = new SkillCommandHandler(host);

        handler.showSkills();

        assertTrue(host.output.stream().anyMatch(line -> line.contains("skills (1)")));
        assertTrue(host.output.stream().anyMatch(line -> line.contains("review")));
        assertTrue(host.output.stream().anyMatch(line -> line.contains("usage: /skill:<name>")));
    }

    @Test
    void missingSkillRendersError() {
        var host = new FakeHost(List.of());
        var handler = new SkillCommandHandler(host);

        handler.handleSkillCommand("/skill:missing");

        assertTrue(host.output.stream().anyMatch(line -> line.contains("skill not found: missing")));
        assertNull(host.submittedPrompt);
    }

    @Test
    void foundSkillEchoesInputAndSubmitsExpandedPrompt() {
        var host = new FakeHost(List.of(skill("review", "Review code", "Read carefully.")));
        var handler = new SkillCommandHandler(host);

        handler.handleSkillCommand("/skill:review focus tests");

        assertTrue(host.output.stream().anyMatch(line -> line.contains("/skill:review focus tests")));
        assertNotNull(host.submittedPrompt);
        assertTrue(host.submittedPrompt.contains("# Skill: review"));
        assertTrue(host.submittedPrompt.contains("focus tests"));
        assertEquals(1, host.busyStarted);
        assertEquals(1, host.busyEnded);
        assertEquals(1, host.sessionWarnings);
    }

    private static SkillLoader.Skill skill(String name, String description, String content) {
        return new SkillLoader.Skill(name, description, content, Path.of(name));
    }

    private static final class FakeHost implements SkillCommandHandler.Host {
        private final List<SkillLoader.Skill> skills;
        private final List<String> output = new ArrayList<>();
        private String submittedPrompt;
        private int busyStarted;
        private int busyEnded;
        private int sessionWarnings;

        FakeHost(List<SkillLoader.Skill> skills) {
            this.skills = skills;
        }

        @Override
        public List<SkillLoader.Skill> skills() {
            return skills;
        }

        @Override
        public String styled(String slot, String text) {
            return text;
        }

        @Override
        public void appendPlain(List<String> lines) {
            output.addAll(lines);
        }

        @Override
        public List<String> promptEcho(String text) {
            return List.of(text);
        }

        @Override
        public long beginBusy(String phase) {
            busyStarted++;
            return 7;
        }

        @Override
        public void redrawPrompt() {}

        @Override
        public void submitAgentPrompt(String prompt, Runnable after) {
            submittedPrompt = prompt;
            after.run();
        }

        @Override
        public void endBusy(long generation) {
            assertEquals(7, generation);
            busyEnded++;
        }

        @Override
        public void showSessionWarning() {
            sessionWarnings++;
        }
    }
}
