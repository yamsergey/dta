package io.yamsergey.dta.tools.android.model.project;

import java.util.Collection;
import io.yamsergey.dta.tools.android.model.module.ResolvedModule;

import lombok.Builder;

@Builder(toBuilder = true)
public record Project(
    String path,
    String name,
    Collection<ResolvedModule> modules) {
}
