package org.pijota.extensions.prompts;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void expandNoArgsClearsVariadicPlaceholders() {
        var result = PromptTemplateLoader.expand("Review\n$@\n$ARGUMENTS\n${@:2}", new String[] {});
        assertEquals("Review\n\n\n", result);
    }

    @Test
    void expandNoArgsLeavesPositionalPlaceholders() {
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
    void expandDefaultValues() {
        assertEquals("Create Widget", PromptTemplateLoader.expand("Create ${1:-Thing}", new String[] {"Widget"}));
        assertEquals("Create Thing", PromptTemplateLoader.expand("Create ${1:-Thing}", new String[] {}));
        assertEquals("Args none", PromptTemplateLoader.expand("Args ${@:-none}", new String[] {}));
        assertEquals("Args a b", PromptTemplateLoader.expand("Args ${ARGUMENTS:-none}", new String[] {"a", "b"}));
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

    /**
     * Emptiness is judged before the quotes come off, so {@code key: ""} is a deliberate empty value and {@code key:}
     * is a line that says nothing. Strip first and the two become indistinguishable -- which is exactly what a tidier
     * looking rewrite of this parser does.
     */
    @Test
    void anExplicitlyEmptyFrontmatterValueIsNotTheSameAsAMissingOne() {
        var quotedEmpty = PromptTemplateLoader.parseFrontmatter("---\ndescription: \"\"\n---\nbody");
        assertEquals("", quotedEmpty.frontmatter().get("description"), "key: \"\" is an empty value, and is kept");

        var missing = PromptTemplateLoader.parseFrontmatter("---\ndescription:\n---\nbody");
        assertFalse(missing.frontmatter().containsKey("description"), "key: with nothing after it is not a value");
    }

    @Test
    void frontmatterQuotesComeOffAndTheBodySurvives() {
        var parsed = PromptTemplateLoader.parseFrontmatter("---\nname: 'review'\nargs: \"a b\"\n---\nthe body\n");
        assertEquals("review", parsed.frontmatter().get("name"));
        assertEquals("a b", parsed.frontmatter().get("args"));
        assertEquals("the body", parsed.body());
    }

    /** No frontmatter, or an unterminated block, leaves the whole file as the body rather than eating it. */
    @Test
    void aFileWithNoFrontmatterIsAllBody() {
        assertEquals("just text", PromptTemplateLoader.parseFrontmatter("just text").body());
        assertTrue(PromptTemplateLoader.parseFrontmatter("just text").frontmatter().isEmpty());
        assertEquals("--- unterminated", PromptTemplateLoader.parseFrontmatter("--- unterminated").body());
    }
}
