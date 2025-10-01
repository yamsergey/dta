package io.yamsergey.adt.tools.android.model.module;

import java.util.Collection;

import javax.annotation.Nonnull;

import org.gradle.tooling.model.GradleProject;

import com.android.builder.model.v2.models.AndroidDsl;
import com.android.builder.model.v2.models.AndroidProject;
import com.android.builder.model.v2.models.BasicAndroidProject;
import com.android.builder.model.v2.models.VariantDependencies;

import io.yamsergey.adt.tools.sugar.Result;
import lombok.Builder;

/**
 * Rerpresents all models that can be found in android project.
 **/
@Builder(toBuilder = true)
public record RawAndroidModule(
    @Nonnull GradleProject project,
    @Nonnull Collection<RawModule> children,
    @Nonnull Result<BasicAndroidProject> basicAndroidProject,
    @Nonnull Result<AndroidProject> androidProject,
    @Nonnull Result<AndroidDsl> androidDsl,
    @Nonnull Result<VariantDependencies> variantDependencies) implements RawModule {
}
