package com.zhongbai233.net_music_can_play_bili.editor.core;

import com.zhongbai233.net_music_can_play_bili.editor.core.math.EditorTransform;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorTransformTest {
    private static final float EPSILON = 1.0e-5F;

    @Test
    void identityAndTranslationTransformPoints() {
        Vector3f point = new Vector3f(1.0F, 2.0F, 3.0F);
        EditorTransform.identity().matrix().transformPosition(point);
        assertVector(point, 1.0F, 2.0F, 3.0F);

        EditorTransform translated = EditorTransform.identity().withPosition(new Vector3f(4.0F, -2.0F, 1.0F));
        translated.matrix().transformPosition(point.set(1.0F, 2.0F, 3.0F));
        assertVector(point, 5.0F, 0.0F, 4.0F);
    }

    @Test
    void pivotRemainsFixedDuringRotation() {
        EditorTransform transform = new EditorTransform(new Vector3f(),
                new Quaternionf().rotateZ((float) (Math.PI * 0.5D)), new Vector3f(1.0F),
                new Vector3f(2.0F, 3.0F, 0.0F), 0.0F, 0.0F);
        Vector3f pivot = new Vector3f(2.0F, 3.0F, 0.0F);
        transform.matrix().transformPosition(pivot);
        assertVector(pivot, 2.0F, 3.0F, 0.0F);
    }

    @Test
    void rejectsNonFiniteAndNonPositiveValues() {
        assertThrows(IllegalArgumentException.class,
                () -> EditorTransform.identity().withPosition(new Vector3f(Float.NaN, 0.0F, 0.0F)));
        assertThrows(IllegalArgumentException.class,
                () -> new EditorTransform(new Vector3f(), new Quaternionf(), new Vector3f(1.0F, 0.0F, 1.0F),
                        new Vector3f(), 0.0F, 0.0F));
    }

                @Test
                void supportsCompleteEulerRotationAndIndependentTransformUpdates() {
                EditorTransform transform = EditorTransform.fromEulerDegrees(new Vector3f(), 90.0F, 0.0F, 0.0F,
                    new Vector3f(1.0F), new Vector3f(), 0.0F, 0.0F);
                Vector3f rotatedForward = transform.matrix().transformDirection(new Vector3f(0.0F, 0.0F, 1.0F));
                assertEquals(1.0F, rotatedForward.x, EPSILON);

                EditorTransform updated = transform.withScale(new Vector3f(2.0F, 1.0F, 3.0F))
                    .withPivot(new Vector3f(0.25F, 0.0F, 0.0F)).withSkew(0.2F, -0.1F);
                assertEquals(2.0F, updated.scale().x, EPSILON);
                assertEquals(0.25F, updated.pivot().x, EPSILON);
                assertEquals(0.2F, updated.skewXByY(), EPSILON);
                }

    private static void assertVector(Vector3f actual, float x, float y, float z) {
        assertEquals(x, actual.x, EPSILON);
        assertEquals(y, actual.y, EPSILON);
        assertEquals(z, actual.z, EPSILON);
    }
}