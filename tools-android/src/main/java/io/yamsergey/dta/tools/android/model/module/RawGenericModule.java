package io.yamsergey.dta.tools.android.model.module;

import java.util.Collection;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.idea.IdeaProject;

import io.yamsergey.dta.tools.sugar.Result;
import lombok.Builder;

/**
 * Repsenets modules which are not Android modules but generic Java/Kotlin
 * libraries.
 **/
@Builder(toBuilder = true)
public record RawGenericModule(
    @Nonnull GradleProject project,
    @Nonnull Collection<RawModule> children,
    @Nullable Result<IdeaProject> ideaProject) implements RawModule {
}
