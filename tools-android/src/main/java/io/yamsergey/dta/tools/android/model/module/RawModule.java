package io.yamsergey.dta.tools.android.model.module;

/**
 * RawModule is a direct representation of Gradle models available for given
 * project.
 **/
public sealed interface RawModule permits RawAndroidModule, RawGenericModule, UnknownModule, FailedModule {

}
