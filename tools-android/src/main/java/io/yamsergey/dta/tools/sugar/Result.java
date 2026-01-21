package io.yamsergey.dta.tools.sugar;

import java.io.Serializable;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * This project relies on connection with Gradle daemon to resolve dependencies
 * and meta information about project.
 * Unfortunatel it means that a lot of code will be executed on separate process
 * (gradle deamon process) and it's not possible
 * to have shared logging configuration, instead every operation should use this
 * result which is serializable and can
 * be passes from one process to another and from which this application can
 * learn about executation details on another process
 * and react appropriately.
 **/
public sealed interface Result<T> extends Serializable permits Success, Failure {

  /**
   * Human or/and Coding Agent readable description of the result. It can be
   * helpfull for debugging or
   * general understanding of the process flow. But not limited to it, can hold
   * whatever information
   * usefull.
   **/
  @Nullable
  String description();

  default Result<T> asResult() {
    return (Result<T>) this;
  }

  default boolean isSuccess() {
    return this instanceof Success;
  }

  default <O> O map(Function<Success<T>, O> successMapper, Function<Failure<T>, O> failureMapper) {
    return switch (this) {
      case Success<T> success -> successMapper.apply(success);
      case Failure<T> failure -> failureMapper.apply(failure);
    };
  }

  public static <T> Success.SuccessBuilder<T> success() {
    return Success.builder();
  }

  public static <T> Failure.FailureBuilder<T> failure() {
    return Failure.<T>builder();
  }
}
