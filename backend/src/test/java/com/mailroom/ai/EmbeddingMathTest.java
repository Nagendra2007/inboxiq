package com.mailroom.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The arithmetic underneath semantic search.
 *
 * <p>Vectors are stored as bytes and compared as unit vectors, and both of
 * those are the kind of thing that is either exactly right or quietly
 * wrong: a byte order mistake does not throw, it just returns similarities
 * that look like numbers and rank like noise.
 */
class EmbeddingMathTest {

    @Test
    void bytesSurviveTheRoundTrip() {
        float[] original = {0.5f, -0.25f, 0.125f, 0f, 1f, -1f};

        float[] restored = EmbeddingClient.fromBytes(EmbeddingClient.toBytes(original));

        assertThat(restored).containsExactly(original);
    }

    @Test
    void aStoredVectorKeepsItsLength() {
        float[] original = new float[256];
        for (int i = 0; i < original.length; i++) original[i] = (float) Math.sin(i);

        assertThat(EmbeddingClient.toBytes(original)).hasSize(256 * Float.BYTES);
        assertThat(EmbeddingClient.fromBytes(EmbeddingClient.toBytes(original))).containsExactly(original);
    }

    @Test
    void normalisingMakesSimilarityADotProduct() {
        float[] vector = EmbeddingClient.normalise(new float[]{3f, 4f});

        // 3,4 has length 5, so this is 0.6,0.8 — and its similarity with
        // itself is exactly 1, which is what makes the dot product alone a
        // valid comparison later.
        assertThat(vector[0]).isCloseTo(0.6f, within(1e-6f));
        assertThat(vector[1]).isCloseTo(0.8f, within(1e-6f));
        assertThat(EmbeddingClient.similarity(vector, vector)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void aZeroVectorDoesNotDivideByZero() {
        float[] zero = EmbeddingClient.normalise(new float[]{0f, 0f, 0f});

        assertThat(zero).containsExactly(0f, 0f, 0f);
    }

    @Test
    void closerMeaningScoresHigher() {
        float[] question = EmbeddingClient.normalise(new float[]{1f, 1f, 0f});
        float[] near = EmbeddingClient.normalise(new float[]{1f, 0.9f, 0f});
        float[] unrelated = EmbeddingClient.normalise(new float[]{0f, 0f, 1f});

        assertThat(EmbeddingClient.similarity(question, near))
                .isGreaterThan(EmbeddingClient.similarity(question, unrelated));
    }

    @Test
    void vectorsOfDifferentSizesDoNotCompare() {
        // Two models produce two different numbers of dimensions, and
        // comparing across them would be plausible nonsense rather than an
        // error. The lowest possible score keeps such a pair last.
        assertThat(EmbeddingClient.similarity(new float[]{1f, 0f}, new float[]{1f, 0f, 0f})).isEqualTo(-1);
        assertThat(EmbeddingClient.similarity(null, new float[]{1f})).isEqualTo(-1);
    }
}
