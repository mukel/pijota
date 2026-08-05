package org.pijota.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sub-agent talks to its parent in JSON, and in the native image Jackson can only serialise a record the reachability
 * metadata knows about. Adding an event record without registering it produces the worst possible failure: the JVM
 * build works, the whole suite passes, and the shipped binary silently drops that event forever.
 *
 * <p>
 * That is not hypothetical -- it is how a live token counter reached the parent and did nothing. This test is the thing
 * that makes the omission a build failure rather than a debugging session.
 */
class HeadlessEventsAreReflectionRegisteredTest {

    private static final Path METADATA = Path.of(
                                    "src/main/resources/META-INF/native-image/org.pijota/pijota/reachability-metadata.json");

    @Test
    void everyHeadlessEventRecordIsRegisteredForReflection() throws Exception {
        String metadata = Files.readString(METADATA);

        var unregistered = new ArrayList<String>();
        var events = new ArrayList<String>();
        for (Class<?> nested : HeadlessAgentRunner.class.getDeclaredClasses()) {
            if (!nested.isRecord() || !nested.getSimpleName().endsWith("Event"))
                continue;
            events.add(nested.getSimpleName());
            if (!metadata.contains(nested.getName()))
                unregistered.add(nested.getName());
        }

        assertFalse(events.isEmpty(), "found no event records to check -- the test is looking in the wrong place");
        assertTrue(unregistered.isEmpty(), "these events would be dropped by the native binary: " + unregistered
                                        + "\nadd each to " + METADATA + " with allDeclaredFields/Methods/Constructors");
    }

    /** The protocol is a discriminated union: an event with no {@code type} is unreadable by the parent. */
    @Test
    void everyHeadlessEventCarriesATypeDiscriminator() {
        var missing = new ArrayList<String>();
        for (Class<?> nested : HeadlessAgentRunner.class.getDeclaredClasses()) {
            if (!nested.isRecord() || !nested.getSimpleName().endsWith("Event"))
                continue;
            List<String> components = Arrays.stream(nested.getRecordComponents()).map(c -> c.getName()).toList();
            if (!components.contains("type"))
                missing.add(nested.getSimpleName());
        }
        assertTrue(missing.isEmpty(), "events without a type field: " + missing);
    }
}
