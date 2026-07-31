package org.pijota.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * JUnit 5 extension that redirects {@code System.out}/{@code System.err} to in-memory buffers for each test (and lets a
 * test supply {@code System.in}), restoring the originals afterward. Replaces the repeated
 * {@code @BeforeEach setOut/setErr} + {@code @AfterEach} restore boilerplate.
 *
 * <pre>{@code
 * &#64;RegisterExtension StdioCapture stdio = new StdioCapture();
 * ...
 * stdio.feedInput("{...}");
 * assertTrue(stdio.out().contains("done"));
 * }</pre>
 */
public final class StdioCapture implements BeforeEachCallback, AfterEachCallback {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;
    private InputStream originalIn;

    @Override
    public void beforeEach(ExtensionContext context) {
        originalOut = System.out;
        originalErr = System.err;
        originalIn = System.in;
        out.reset();
        err.reset();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setIn(originalIn);
    }

    /** Feeds the given text as {@code System.in} for the remainder of the test. */
    public void feedInput(String text) {
        System.setIn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Feeds an object serialized as JSON (e.g. a {@code Map}) as {@code System.in}, so tests describe a task as a map
     * instead of hand-building a JSON string and escaping filesystem paths.
     */
    public void feedInputJson(Object value) {
        try {
            feedInput(JSON.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not JSON-serializable: " + value, e);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Everything written to {@code System.out} so far this test. */
    public String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Everything written to {@code System.err} so far this test. */
    public String err() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
