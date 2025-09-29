package io.yamsergey.adt.tools.android.model.dependency;

public sealed interface Dependency permits ExternalDependency, LocalDependency {
  public static enum Scope {
    COMPILE,
    TEST,
    RUNTIME
  }

  String path();
  Scope scope();
}
