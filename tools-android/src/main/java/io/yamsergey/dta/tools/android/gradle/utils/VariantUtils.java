package io.yamsergey.dta.tools.android.gradle.utils;

import java.util.Collection;

import com.android.builder.model.v2.ide.Variant;

import io.yamsergey.dta.tools.android.model.variant.BuildVariant;

public class VariantUtils {

  public static BuildVariant mapToBuildVariant(Variant variant) {
    return BuildVariant.builder()
        .displayName(variant.getDisplayName())
        .name(variant.getName())
        .isDefault(false)
        .build();
  }

  /**
   * Helper method to select build variant for android module based on selected
   * variant for main app module.
   *
   * @param referenceBuildVariant build variant selected for main application
   *                              module.
   * @param variants              variants to choose from.
   *
   * @return build variant that match main application build variant.
   **/
  public static BuildVariant chooseBuildVariant(BuildVariant referenceBuildVariant, Collection<BuildVariant> variants) {
    // Primary strategy: exact name match
    var exactMatch = variants.stream()
        .filter(variant -> variant.name().equals(referenceBuildVariant.name()))
        .findFirst();

    if (exactMatch.isPresent()) {
      return exactMatch.get();
    }

    // Fallback strategy: match by camel case parts
    var referenceParts = splitCamelCase(referenceBuildVariant.name());
    var partialMatch = variants.stream()
        .filter(variant -> {
          var variantParts = splitCamelCase(variant.name());
          return hasCommonParts(referenceParts, variantParts);
        })
        .max((v1, v2) -> {
          // Prefer variant with more matching parts
          var parts1 = splitCamelCase(v1.name());
          var parts2 = splitCamelCase(v2.name());
          int score1 = countCommonParts(referenceParts, parts1);
          int score2 = countCommonParts(referenceParts, parts2);
          return Integer.compare(score1, score2);
        });

    if (partialMatch.isPresent()) {
      return partialMatch.get();
    }

    // Final fallback: return default variant or first available
    return variants.stream()
        .filter(BuildVariant::isDefault)
        .findFirst()
        .orElse(variants.iterator().next());
  }

  /**
   * Splits camel case string into individual words
   * e.g., "myFlavorDebug" -> ["my", "Flavor", "Debug"]
   */
  private static String[] splitCamelCase(String input) {
    return input.split("(?=\\p{Upper})");
  }

  /**
   * Checks if two arrays of parts have any common elements (case-insensitive)
   */
  private static boolean hasCommonParts(String[] parts1, String[] parts2) {
    for (String part1 : parts1) {
      for (String part2 : parts2) {
        if (part1.equalsIgnoreCase(part2)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Counts common parts between two arrays (case-insensitive)
   */
  private static int countCommonParts(String[] parts1, String[] parts2) {
    int count = 0;
    for (String part1 : parts1) {
      for (String part2 : parts2) {
        if (part1.equalsIgnoreCase(part2)) {
          count++;
          break; // Count each reference part only once
        }
      }
    }
    return count;
  }
}
