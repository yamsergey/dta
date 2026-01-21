package io.yamsergey.dta.tools.android.resolver;

import java.util.List;

import javax.annotation.Nonnull;

import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import io.yamsergey.dta.tools.android.model.module.ResolvedModule;
import io.yamsergey.dta.tools.android.model.variant.BuildVariant;

public interface ModuleResolutionStrategy {

    List<ResolvedModule> resolveModules(
            @Nonnull ProjectConnection connection,
            @Nonnull GradleProject gradleProject,
            @Nonnull BuildVariant selectedBuildVariant);
}