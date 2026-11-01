package io.yamsergey.dta.shim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Feature 015 (R2): targeted reads of members the app's code may not reach. */
class ShimReflectTest {
    static class Base {
        private final String mText = "base";
    }

    static class Child extends Base {
        private int mDuration = 1;

        private String title() {
            return "t";
        }

        void addView(java.util.List<String> v, int flags) {}
    }

    @Test
    void readsPrivateFieldsUpTheHierarchy() throws Exception {
        assertEquals("base", ShimReflect.field(new Child(), "mText"));
        assertEquals(1, ShimReflect.field(new Child(), "mDuration"));
    }

    @Test
    void missingFieldThrows() {
        assertThrows(NoSuchFieldException.class, () -> ShimReflect.field(new Child(), "mNope"));
    }

    @Test
    void callsPrivateMethods() throws Exception {
        assertEquals("t", ShimReflect.call(Child.class, new Child(), "title"));
    }

    @Test
    void descriptorsOfDeclaredOverloads() {
        assertArrayEquals(new String[] {"(Ljava/util/List;I)V"}, ShimReflect.descriptors(Child.class, "addView"));
    }
}
