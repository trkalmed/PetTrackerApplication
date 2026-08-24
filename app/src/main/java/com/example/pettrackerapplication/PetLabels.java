package com.example.pettrackerapplication;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which COCO classes count as a pet.
 *
 * One definition, used twice: the detector takes it as an allow-list so the
 * overlay never boxes a sofa, and the recorder re-checks it before storing a
 * sighting. Keeping the two in sync by hand is exactly the sort of thing that
 * drifts, so they read the same list.
 *
 * COCO has no "rabbit", "hamster" or "guinea pig" class, so those species are
 * simply not detectable with this model however the app lets you name them.
 * "teddy bear" is deliberately absent too: it fires constantly on real pets and
 * on actual toys, and either way it is not an animal.
 */
public final class PetLabels {

    private static final List<String> ANIMALS = Collections.unmodifiableList(Arrays.asList(
            "cat", "dog", "bird", "horse", "sheep", "cow", "bear"));

    private static final Set<String> ANIMAL_SET = new HashSet<>(ANIMALS);

    private PetLabels() {
        // static helper, no instances
    }

    /** For {@code ObjectDetectorOptions.setLabelAllowList}. */
    public static List<String> allowList() {
        return ANIMALS;
    }

    public static boolean isAnimal(String label) {
        return label != null && ANIMAL_SET.contains(label.toLowerCase());
    }
}
