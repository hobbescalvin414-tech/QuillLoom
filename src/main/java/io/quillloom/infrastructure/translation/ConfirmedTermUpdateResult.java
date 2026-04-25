package io.quillloom.infrastructure.translation;

public record ConfirmedTermUpdateResult(
        String sourceTerm,
        String translatedTerm
) {
}
