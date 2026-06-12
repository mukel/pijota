package ai.pijota.extensions.prompts;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptTemplateLoaderTest {

    @Test
    void expandPositionalArgs() {
        var result = PromptTemplateLoader.expand("Hello $1, meet $2", new String[] {"Alice", "Bob"});
        assertEquals("Hello Alice, meet Bob", result);
    }

    @Test
    void expandAllArgs() {
        var result = PromptTemplateLoader.expand("args: $@", new String[] {"a", "b", "c"});
        assertEquals("args: a b c", result);
    }

    @Test
    void expandArgumentsVariable() {
        var result = PromptTemplateLoader.expand("$ARGUMENTS", new String[] {"x", "y"});
        assertEquals("x y", result);
    }

    @Test
    void expandSlice() {
        var result = PromptTemplateLoader.expand("slice: ${@:2}", new String[] {"a", "b", "c", "d"});
        assertEquals("slice: b c d", result);
    }

    @Test
    void expandSliceWithLength() {
        var result = PromptTemplateLoader.expand("${@:2:2}", new String[] {"a", "b", "c", "d"});
        assertEquals("b c", result);
    }

    @Test
    void expandNoArgsReturnsContent() {
        var result = PromptTemplateLoader.expand("unchanged $1", new String[] {});
        assertEquals("unchanged $1", result);
    }

    @Test
    void expandSliceStartZeroClamps() {
        var result = PromptTemplateLoader.expand("${@:0:2}", new String[] {"a", "b", "c"});
        assertEquals("a b", result);
    }

    @Test
    void expandSliceOutOfRange() {
        var result = PromptTemplateLoader.expand("${@:10}", new String[] {"a", "b"});
        assertEquals("", result);
    }

    @Test
    void parseArgsSimple() {
        var args = PromptTemplateLoader.parseArgs("hello world test");
        assertEquals(3, args.length);
        assertEquals("hello", args[0]);
        assertEquals("world", args[1]);
        assertEquals("test", args[2]);
    }

    @Test
    void parseArgsWithQuotes() {
        var args = PromptTemplateLoader.parseArgs("arg1 \"arg two\" 'arg three'");
        assertEquals(3, args.length);
        assertEquals("arg1", args[0]);
        assertEquals("arg two", args[1]);
        assertEquals("arg three", args[2]);
    }

    @Test
    void parseArgsEmpty() {
        assertEquals(0, PromptTemplateLoader.parseArgs("").length);
        assertEquals(0, PromptTemplateLoader.parseArgs(null).length);
    }

    @Test
    void parseArgsBlank() {
        assertEquals(0, PromptTemplateLoader.parseArgs("   ").length);
    }

    @Test
    void parseArgsMultipleSpaces() {
        var args = PromptTemplateLoader.parseArgs("a   b\tc");
        assertEquals(3, args.length);
    }

    @Test
    void buildPromptWithTemplates() {
        var t = new PromptTemplate("test", "A test template", null, "content", null);
        var prompt = PromptTemplateLoader.buildPrompt(List.of(t));
        assertTrue(prompt.contains("<available_prompt_templates>"));
        assertTrue(prompt.contains("<name>/test</name>"));
        assertTrue(prompt.contains("A test template"));
    }

    @Test
    void buildPromptEmpty() {
        assertEquals("", PromptTemplateLoader.buildPrompt(List.of()));
    }

    @Test
    void buildPromptWithArgumentHint() {
        var t = new PromptTemplate("test", "desc", "<arg1> <arg2>", "body", null);
        var prompt = PromptTemplateLoader.buildPrompt(List.of(t));
        assertTrue(prompt.contains("<argument-hint>"));
        assertTrue(prompt.contains("<arg1> <arg2>"));
    }

    @Test
    void buildPromptWithoutArgumentHint() {
        var t = new PromptTemplate("test", "desc", null, "body", null);
        var prompt = PromptTemplateLoader.buildPrompt(List.of(t));
        assertFalse(prompt.contains("<argument-hint>"));
    }
}
