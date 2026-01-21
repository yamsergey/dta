package io.yamsergey.dta.tools.android.model.module;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.gradle.tooling.model.GradleProject;

import io.yamsergey.dta.tools.sugar.Failure;
import lombok.Builder;

@Builder
public record FailedModule(
    @Nonnull String name,
    @Nonnull String path,
    @Nullable Throwable err,
    @Nonnull String details) implements ResolvedModule, RawModule {

  public static FailedModule from(Failure<?> failure, GradleProject project) {
    return FailedModule.builder()
        .name(project.getName())
        .path(project.getPath())
        .details(failure.description())
        .err(failure.cause())
        .build();
  }
}
