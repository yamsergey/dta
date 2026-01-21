package io.yamsergey.dta.tools.android.resolver;

import io.yamsergey.dta.tools.sugar.Result;

public interface Resolver<M> {

  Result<M> resolve();
}
