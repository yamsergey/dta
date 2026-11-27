package io.yamsergey.dta.protocol.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 074 (A1): the pure part of the origin rule (research R5). */
class ClassOriginTest {
    private val ns = listOf("com.example.app", "com.example.feature")

    @Test fun bootLoaderIsPlatform() {
        assertEquals(HeapOrigin.PLATFORM, classOrigin("java.lang.String", LoaderKind.BOOT, ns))
        assertEquals(HeapOrigin.PLATFORM, classOrigin("byte[]", LoaderKind.BOOT, null))
    }

    @Test fun appLoaderUnderNamespaceIsApp() {
        assertEquals(HeapOrigin.APP, classOrigin("com.example.app.Main", LoaderKind.APP, ns))
        assertEquals(HeapOrigin.APP, classOrigin("com.example.feature.sub.Foo\$Bar", LoaderKind.APP, ns))
    }

    @Test fun namespaceMatchIsOnPackageBoundary() {
        assertEquals(HeapOrigin.LIBRARY, classOrigin("com.example.application.Foo", LoaderKind.APP, ns))
    }

    @Test fun appLoaderOutsideNamespacesIsLibrary() {
        assertEquals(HeapOrigin.LIBRARY, classOrigin("okhttp3.OkHttpClient", LoaderKind.APP, ns))
    }

    @Test fun arraysUseElementClass() {
        assertEquals(HeapOrigin.APP, classOrigin("com.example.app.Main[][]", LoaderKind.APP, ns))
        assertEquals(HeapOrigin.LIBRARY, classOrigin("okhttp3.Call[]", LoaderKind.APP, ns))
    }

    @Test fun unknownNamespacesAreNeverGuessed() {
        assertEquals(HeapOrigin.UNKNOWN, classOrigin("com.example.app.Main", LoaderKind.APP, null))
        assertEquals(HeapOrigin.UNKNOWN, classOrigin("com.example.app.Main", LoaderKind.APP, emptyList()))
    }

    @Test fun secondAppLoaderGivesTheSameOriginAsTheMainOne() {
        // D-119: same name, two loaders, both decided by the name against the namespaces
        for (loader in listOf(LoaderKind.APP, LoaderKind.OTHER)) {
            assertEquals(HeapOrigin.APP, classOrigin("com.example.app.HeldC", loader, ns), "$loader")
            assertEquals(HeapOrigin.LIBRARY, classOrigin("okhttp3.Call", loader, ns), "$loader")
        }
    }

    @Test fun otherLoaderWithUnknownNamespacesIsStillUnknown() {
        assertEquals(HeapOrigin.UNKNOWN, classOrigin("com.example.app.Main", LoaderKind.OTHER, null))
    }
}
