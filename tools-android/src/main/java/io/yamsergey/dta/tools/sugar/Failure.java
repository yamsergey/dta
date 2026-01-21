package io.yamsergey.dta.tools.sugar;

import javax.annotation.Nullable;

import lombok.Builder;

@Builder
public record Failure<T>(
    @Nullable Exception cause,
    @Nullable String description) implements Result<T> {

  /**
   * Copy this {@link Failure} as new {@link Result} of required type.
   * Use it to forward the error in scope of new expected result, e.g when result
   * on which current method depends returned failure and it you want to use it as
   * new result.
   **/
  public <N> Result<N> forward() {
    return Result.<N>failure()
        .cause(this.cause)
        .description(this.description)
        .build()
        .asResult();
  }
}
