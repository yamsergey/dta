package io.yamsergey.dta.tools.android.model.module;

import java.util.Collection;

import io.yamsergey.dta.tools.android.model.SourceRoot;
import io.yamsergey.dta.tools.android.model.dependency.Dependency;
import io.yamsergey.dta.tools.android.model.variant.BuildVariant;
import lombok.Builder;
import lombok.Singular;

@Builder(toBuilder = true)
public record ResolvedAndroidModule(
    String name,
    String path,
    Type type,
    @Singular Collection<BuildVariant> buildVariants,
    BuildVariant selectedVariant,
    @Singular Collection<SourceRoot> roots,
    @Singular Collection<Dependency> dependencies) implements ResolvedModule {

  public static enum Type {
    APPLICATION,
    LIBRARY
  }
}
