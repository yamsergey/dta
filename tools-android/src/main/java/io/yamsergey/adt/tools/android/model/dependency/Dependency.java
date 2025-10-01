package io.yamsergey.adt.tools.android.model.dependency;

/**
 * TODO: Here must be another type of dependency: project dependency, which
 * represents dependency
 * on another gradle module within the same project.
 **/
public sealed interface Dependency permits ExternalDependency, LocalDependency {
  public static enum Scope {
    COMPILE,
    TEST,
    RUNTIME
  }

  String path();

  Scope scope();
}
