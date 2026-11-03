package com.mailroom.entity;

import org.junit.jupiter.api.Test;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which field each timestamp annotation is actually on.
 *
 * <p>A new field inserted between {@code @CreationTimestamp} and the field
 * it belonged to silently moves the annotation onto the new one. The
 * compiler is happy, every test passes, and the only symptom is that rows
 * inserted from then on have a null {@code created_at} — which the test
 * database allows, because it builds its schema from these classes rather
 * than from the migrations.
 *
 * <p>That combination cost a day: new accounts could not sign in at all,
 * while the one account that already had a row carried on working, because
 * it only ever took the update path.
 */
class TimestampColumnsTest {

    private static final Path ENTITIES = Path.of("src/main/java/com/mailroom/entity");

    @Test
    void creationTimestampsAreOnTheFieldTheyName() throws Exception {
        for (Class<?> entity : entityClasses()) {
            for (Field field : entity.getDeclaredFields()) {
                if (field.isAnnotationPresent(CreationTimestamp.class)) {
                    assertThat(field.getName())
                            .as("@CreationTimestamp in %s is on '%s'", entity.getSimpleName(), field.getName())
                            .isEqualTo("createdAt");
                }
                if (field.isAnnotationPresent(UpdateTimestamp.class)) {
                    assertThat(field.getName())
                            .as("@UpdateTimestamp in %s is on '%s'", entity.getSimpleName(), field.getName())
                            .isEqualTo("updatedAt");
                }
            }
        }
    }

    @Test
    void everyCreatedAtFieldStillHasItsAnnotation() throws Exception {
        for (Class<?> entity : entityClasses()) {
            for (Field field : entity.getDeclaredFields()) {
                if (!field.getName().equals("createdAt")) continue;
                // The other half of the same mistake: the annotation moving
                // away leaves this field looking perfectly ordinary.
                assertThat(field.isAnnotationPresent(CreationTimestamp.class))
                        .as("%s.createdAt has no @CreationTimestamp, so it will be inserted as null",
                                entity.getSimpleName())
                        .isTrue();
            }
        }
    }

    private static List<Class<?>> entityClasses() throws IOException, ClassNotFoundException {
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> files = Files.list(ENTITIES)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java")) continue;
                Class<?> type = Class.forName("com.mailroom.entity." + name.substring(0, name.length() - 5));
                if (type.isAnnotationPresent(jakarta.persistence.Entity.class)) classes.add(type);
            }
        }
        // If this ever finds nothing it would pass while checking nothing,
        // which is the failure mode this whole class exists to prevent.
        assertThat(classes).as("entity classes found").isNotEmpty();
        return classes;
    }
}
