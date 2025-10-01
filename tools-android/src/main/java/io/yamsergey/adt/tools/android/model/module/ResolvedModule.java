package io.yamsergey.adt.tools.android.model.module;

/**
 * TODO: each class that implements this interface has properties path and name,
 * hence they
 * can be moved under parent interface.
 **/
public sealed interface ResolvedModule
    permits ResolvedAndroidModule, ResolvedGenericModule, UnknownModule, FailedModule {
}
