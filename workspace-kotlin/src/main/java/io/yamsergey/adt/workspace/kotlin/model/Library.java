package io.yamsergey.adt.workspace.kotlin.model;

import java.util.Collection;

import com.fasterxml.jackson.annotation.JsonValue;

import lombok.Builder;
import lombok.Singular;

/**
 * Library definition for external dependencies.
 *
 * Represents an external library with its metadata, including
 * the main JAR file, source JARs, and library attributes.
 */
@Builder(toBuilder = true)
public record Library(
    String name,
    LibraryType type,
    @Singular Collection<SourceRoot> roots,
    Properties properties) {

  public enum LibraryType {
    repository("java-imported"),
    jar("java-imported"),
    directory("java-imported");

    String name;

    private LibraryType(String name) {
      this.name = name;
    }

    @JsonValue
    @Override
    public String toString() {
      return this.name;
    }
  }

  @Builder(toBuilder = true)
  public record LibraryAttributes(
      String groupId,
      String artifactId,
      String version,
      String baseVersion) {
  }

  @Builder(toBuilder = true)
  public record Properties(
      LibraryAttributes attributes) {
  };
}
