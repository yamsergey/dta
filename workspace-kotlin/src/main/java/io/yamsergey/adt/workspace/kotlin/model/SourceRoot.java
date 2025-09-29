package io.yamsergey.adt.workspace.kotlin.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;

/**
 * Source root definition within a content root.
 *
 * Represents a specific source directory with its type and path,
 * such as Java sources, Kotlin sources, or resource directories.
 * Also used for library roots where type can be SOURCES.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder(toBuilder = true)
public record SourceRoot(
    String path,
    SourceRootType type) {

  public enum SourceRootType {
    java("java-source"),
    kotlin("kotlin-source"),
    resources("resources"),
    testJava("java-test"),
    testKotlin("kotlin-test"),
    testResources("test-resources"),
    sources("SOURCES"); // For library source JARs

    private final String value;

    SourceRootType(String value) {
      this.value = value;
    }

    @com.fasterxml.jackson.annotation.JsonValue
    public String getValue() {
      return value;
    }
  }
}
