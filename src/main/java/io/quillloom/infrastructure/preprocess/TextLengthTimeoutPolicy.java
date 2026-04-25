package io.quillloom.infrastructure.preprocess;

public class TextLengthTimeoutPolicy {

    public ResolvedTextTimeout resolve(String text,
                                       int baseTimeoutSeconds,
                                       int timeoutStepChars,
                                       int timeoutStepSeconds,
                                       int maxTimeoutSeconds) {
        int charCount = text == null ? 0 : text.length();
        int safeBase = Math.max(1, baseTimeoutSeconds);
        int safeStepChars = Math.max(1, timeoutStepChars);
        int safeStepSeconds = Math.max(0, timeoutStepSeconds);
        int safeMax = Math.max(safeBase, maxTimeoutSeconds);

        int extraSteps = 0;
        if (charCount > safeStepChars) {
            extraSteps = 1 + (int) Math.ceil((charCount - safeStepChars) / (double) safeStepChars);
        }

        int timeoutSeconds = Math.min(safeMax, safeBase + (extraSteps * safeStepSeconds));
        return new ResolvedTextTimeout(charCount, timeoutSeconds);
    }
}
