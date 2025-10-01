package io.yamsergey.adt.tools.android.resolver;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.android.builder.model.v2.dsl.ProductFlavor;
import com.android.builder.model.v2.ide.Variant;
import com.android.builder.model.v2.models.AndroidDsl;
import com.android.builder.model.v2.models.AndroidProject;

import io.yamsergey.adt.tools.android.model.variant.BuildVariant;
import io.yamsergey.adt.tools.sugar.Result;

/**
 * Resolve build variants for Android Project.
 */
public class BuildVariantsResolver implements Resolver<Collection<BuildVariant>> {

  private final AndroidProject androidProject;
  @Nullable
  private final AndroidDsl androidDsl;

  public BuildVariantsResolver(AndroidProject androidProject) {
    this(androidProject, null);
  }

  public BuildVariantsResolver(AndroidProject androidProject, @Nullable AndroidDsl androidDsl) {
    this.androidProject = androidProject;
    this.androidDsl = androidDsl;
  }

  public Result<Collection<BuildVariant>> resolve() {
    // Build a map of flavor names to their isDefault flag
    Map<String, Boolean> flavorDefaults = androidDsl != null
        ? androidDsl.getProductFlavors().stream()
            .collect(Collectors.toMap(
                ProductFlavor::getName,
                pf -> pf.isDefault() != null && pf.isDefault()))
        : Map.of();

    return Result.<Collection<BuildVariant>>success().value(
        androidProject.getVariants()
            .stream()
            .map(variant -> BuildVariant.builder()
                .name(variant.getName())
                .displayName(variant.getDisplayName())
                .isDefault(computeIsDefault(variant, flavorDefaults)).build())
            .toList())
        .description(
            String.format("Successfully resolved build variants for Android Project: %s",
                androidProject.getNamespace()))
        .build().asResult();
  }

  /**
   * Determine if a variant should be marked as default based on its constituent flavors.
   * A variant is default if it contains at least one flavor that has isDefault=true.
   *
   * @param variant the variant to check
   * @param flavorDefaults map of flavor name to isDefault flag
   * @return true if the variant contains a default flavor, false otherwise, null if no DSL info
   */
  private Boolean computeIsDefault(Variant variant, Map<String, Boolean> flavorDefaults) {
    if (flavorDefaults.isEmpty()) {
      return null; // No DSL information available
    }

    // Variant name format: [flavor1][flavor2]...[BuildType]
    // Check if any flavor in the variant name is marked as default
    return flavorDefaults.entrySet().stream()
        .anyMatch(entry -> entry.getValue() && variant.getName().toLowerCase().contains(entry.getKey().toLowerCase()))
            ? true : null;
  }
}
