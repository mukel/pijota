package org.pijota.extensions.suggest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * A suggestion is unsolicited, so a bad one costs more than showing nothing. These are the rules that keep it from
 * becoming noise: they are why the feature is pleasant rather than something people switch off.
 */
class SuggestionTextTest {

    private static String accepted(String raw) {
        return SuggestionText.validate(raw).orElseThrow(() -> new AssertionError("rejected: " + raw));
    }

    private static void rejected(String raw, String why) {
        assertTrue(SuggestionText.validate(raw).isEmpty(), why + " -- but it was accepted: " + raw);
    }

    @Test
    void acceptsSomethingAUserWouldActuallyType() {
        assertEquals("run the tests for calc.py", accepted("run the tests for calc.py"));
        assertEquals("commit this with a short message", accepted("  commit this with a short message  "));
    }

    /**
     * A newline in a rendered prompt row advances a line the layout never counted, which puts the frame's row
     * bookkeeping out by one and eats a row of transcript on the next redraw. Dropped, never flattened.
     */
    @Test
    void rejectsAnythingMultiLine() {
        rejected("run the tests\nthen commit", "a newline corrupts the prompt frame");
        rejected("first line\n\nsecond", "blank lines too");
    }

    @Test
    void rejectsAQuestionBecauseThatIsTheModelAskingNotPredicting() {
        rejected("should I run the tests?", "a question is the opposite of predicting what the user types");
        rejected("what would you like next?", "same");
    }

    @Test
    void rejectsTheModelAnsweringAsItself() {
        for (String voice : new String[] {"I'll run the tests now", "Let me check the output", "Sure, running tests",
                "Here's what I found next", "I can add the guard for you"})
            rejected(voice, "agent voice, not the user's next message");
    }

    @Test
    void rejectsSomethingTooLongToReadAtAGlance() {
        rejected("run the tests and then commit the change and push it to origin main please", "over the word cap");
        assertEquals(12, accepted("one two three four five six seven eight nine ten eleven twelve").split(" ").length,
                                        "exactly at the cap is fine");
    }

    @Test
    void rejectsAnExplanationRatherThanAPrompt() {
        rejected("run the tests. they should pass now.", "two sentences is an explanation, not a prompt");
    }

    @Test
    void unwrapsQuotesTheModelAddedAroundItsAnswer() {
        assertEquals("run the tests", accepted("\"run the tests\""));
        assertEquals("run the tests", accepted("'run the tests'"));
        assertEquals("run the tests", accepted("`run the tests`"));
    }

    @Test
    void dropsATrailingFullStopBecauseNobodyTypesOne() {
        assertEquals("run the tests", accepted("run the tests."));
    }

    @Test
    void emptyIsANormalOutcomeNotAnError() {
        // The prompt tells the model to say nothing when the next step is not obvious; that must not throw.
        rejected("", "empty");
        rejected("   ", "blank");
        rejected(null, "null");
        rejected("\"\"", "an empty quoted string");
    }
}
