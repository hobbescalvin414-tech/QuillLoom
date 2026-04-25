package io.quillloom.application.postdraft.review.model;

import java.util.LinkedHashSet;
import java.util.Set;

public record ReviewVisitedObjects(
        Set<String> objectIds
) {

    public ReviewVisitedObjects {
        objectIds = normalize(objectIds);
    }

    public static ReviewVisitedObjects empty() {
        return new ReviewVisitedObjects(Set.of());
    }

    public static ReviewVisitedObjects from(Set<String> objectIds) {
        return new ReviewVisitedObjects(objectIds);
    }

    public ReviewVisitedObjects include(String objectId) {
        LinkedHashSet<String> updated = new LinkedHashSet<>(objectIds);
        if (objectId != null && !objectId.isBlank()) {
            updated.add(objectId.trim());
        }
        return new ReviewVisitedObjects(updated);
    }

    public ReviewVisitedObjects includeAll(Set<String> nextObjectIds) {
        LinkedHashSet<String> updated = new LinkedHashSet<>(objectIds);
        updated.addAll(normalize(nextObjectIds));
        return new ReviewVisitedObjects(updated);
    }

    private static Set<String> normalize(Set<String> objectIds) {
        if (objectIds == null) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String objectId : objectIds) {
            if (objectId != null && !objectId.isBlank()) {
                normalized.add(objectId.trim());
            }
        }
        return Set.copyOf(normalized);
    }
}
