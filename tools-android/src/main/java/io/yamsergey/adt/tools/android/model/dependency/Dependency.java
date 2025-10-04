package io.yamsergey.adt.tools.android.model.dependency;

public sealed interface Dependency permits ExternalDependency, LocalDependency, ProjectDependency {
  public static enum Scope {
    COMPILE,
    TEST,
    RUNTIME
  }

  String path();

  Scope scope();
}
