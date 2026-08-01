package org.pijota.llm;

/**
 * Nothing is configured yet: no provider key, no stored login. This is a first run, not a failure, so the CLI prints
 * the message as onboarding guidance instead of wrapping it in "Error: Pijota failed / Cause:", which reads like a
 * crash for someone who has simply not signed in yet.
 */
public final class SetupRequiredException extends IllegalStateException {

    public SetupRequiredException(String message) {
        super(message);
    }
}
