package com.example.pettrackerapplication;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The COCO classes that count as a pet. */
public final class PetLabels {

    private static final List<String> ANIMALS = Collections.unmodifiableList(Arrays.asList(
            "cat", "dog", "bird", "horse", "sheep", "cow", "bear"));

    private static final Set<String> ANIMAL_SET = new HashSet<>(ANIMALS);

    private PetLabels() {
    }

    public static List<String> allowList() {
        return ANIMALS;
    }

    public static boolean isAnimal(String label) {
        return label != null && ANIMAL_SET.contains(label.toLowerCase());
    }
}
