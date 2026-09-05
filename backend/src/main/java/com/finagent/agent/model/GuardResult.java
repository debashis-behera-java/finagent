package com.finagent.agent.model;

import java.util.List;

/**
 * Outcome of the {@link com.finagent.agent.SafetyGuard} numeric-consistency check.
 *
 * @param passed             true when every figure in the LLM prose is grounded in the FactBundle
 * @param ungroundedFigures  raw figure strings found in the prose with no match in the bundle
 * @param redactedText       the prose with ungrounded figures replaced by a marker; equals the
 *                           original prose when {@code passed} is true
 */
public record GuardResult(
        boolean passed,
        List<String> ungroundedFigures,
        String redactedText) {

    public GuardResult {
        ungroundedFigures = List.copyOf(ungroundedFigures);
    }
}
