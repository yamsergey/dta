package io.yamsergey.dta.tools.android.model;

import lombok.Builder;

@Builder(toBuilder = true)
public record SourceRoot(
    Language language,
    String path) {
  public static enum Language {
    JAVA,
    KOTLIN,
    OTHER
  }
}
