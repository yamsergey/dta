package io.yamsergey.dta.sidekick.data;

import android.content.Context;
import io.yamsergey.dta.sidekick.SidekickLog;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process invoker for the host app's {@code @AppFunction} methods.
 *
 * <p>Drives the KSP-generated {@code $AggregatedAppFunctionInvoker_Impl}
 * via reflection. No cross-app permissions or system service
 * involvement — sidekick is already in the host process, so we just
 * call the dispatch glue directly.</p>
 *
 * <p>The aggregated invoker's entry point is a Kotlin {@code suspend fun},
 * which in JVM bytecode becomes
 * {@code unsafeInvoke(AppFunctionContext, String, Map, Continuation): Object}.
 * To avoid a runtime dependency on kotlinx-coroutines, we drive it
 * with a hand-rolled {@code Continuation} backed by a
 * {@link CountDownLatch} + result holder. Synchronous-completion case
 * (the function ran without truly suspending) returns immediately;
 * truly-suspending invocations block on the latch up to the caller-
 * supplied timeout.</p>
 */
public class AppFunctionsInvoker {

    private static final String TAG = "AppFunctionsInvoker";
    private static final String AGGREGATED_INVOKER_FQN =
        "androidx.appfunctions.service.internal.$AggregatedAppFunctionInvoker_Impl";
    private static final String APP_FUNCTION_CONTEXT_FQN =
        "androidx.appfunctions.AppFunctionContext";
    private static final String CONTINUATION_FQN =
        "kotlin.coroutines.Continuation";
    private static final String COROUTINE_CONTEXT_FQN =
        "kotlin.coroutines.CoroutineContext";
    private static final String EMPTY_COROUTINE_CONTEXT_FQN =
        "kotlin.coroutines.EmptyCoroutineContext";
    private static final String COROUTINE_SINGLETONS_FQN =
        "kotlin.coroutines.intrinsics.CoroutineSingletons";
    /** Special sentinel returned by suspending fns when they actually suspend. */
    private static final String COROUTINE_SUSPENDED_NAME = "COROUTINE_SUSPENDED";

    private final Context appContext;

    public AppFunctionsInvoker(Context appContext) {
        this.appContext = appContext;
    }

    /**
     * Invokes the function identified by {@code functionId} with
     * {@code parameters} (already in the {@code Map<String, Any?>}
     * shape the invoker expects — string keys matching the @AppFunction
     * parameter names, values pre-coerced to the expected types).
     *
     * @return a map containing {@code result} on success or {@code error} on failure.
     */
    public Map<String, Object> invoke(String functionId, Map<String, Object> parameters,
                                       long timeoutMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            ClassLoader cl = appContext.getClassLoader();

            Class<?> invokerCls = Class.forName(AGGREGATED_INVOKER_FQN, true, cl);
            Object invoker = invokerCls.getConstructor().newInstance();

            Class<?> appFunctionContextCls = Class.forName(APP_FUNCTION_CONTEXT_FQN, true, cl);
            Object appFunctionContext = newAppFunctionContext(appFunctionContextCls);

            Class<?> continuationCls = Class.forName(CONTINUATION_FQN, true, cl);

            Method unsafeInvoke = findUnsafeInvokeMethod(invokerCls,
                appFunctionContextCls, continuationCls);
            if (unsafeInvoke == null) {
                out.put("error", "Could not find unsafeInvoke(AppFunctionContext, String, Map, Continuation) on "
                    + AGGREGATED_INVOKER_FQN);
                return out;
            }

            // CountDownLatch + result holder = a Continuation that
            // blocks until the suspending fn resumes us. If the fn
            // doesn't actually suspend (most @AppFunction impls are
            // plain `fun`, just compiled with a suspend signature for
            // dispatch uniformity), unsafeInvoke returns the result
            // directly and we never wait on the latch.
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Object> resultHolder = new AtomicReference<>();
            AtomicReference<Throwable> errorHolder = new AtomicReference<>();
            Object continuation = newContinuation(cl, continuationCls, done, resultHolder, errorHolder);

            Object directResult = unsafeInvoke.invoke(invoker,
                appFunctionContext, functionId, parameters, continuation);

            if (isCoroutineSuspended(cl, directResult)) {
                if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    out.put("error", "Timed out after " + timeoutMs
                        + " ms waiting for suspending AppFunction to complete");
                    return out;
                }
                Throwable err = errorHolder.get();
                if (err != null) {
                    out.put("error", err.getClass().getName() + ": " + err.getMessage());
                    return out;
                }
                out.put("result", resultHolder.get());
            } else {
                // Synchronous completion — the typical case for our
                // non-suspending @AppFunction impls.
                out.put("result", directResult);
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "AppFunction invoke failed: " + functionId, t);
            // Unwrap InvocationTargetException so the caller sees the
            // real failure (e.g. AppFunctionInvalidArgumentException
            // thrown by the impl) rather than the reflection wrapper.
            Throwable root = t;
            while (root.getCause() != null && root instanceof java.lang.reflect.InvocationTargetException) {
                root = root.getCause();
            }
            out.put("error", root.getClass().getName() + ": " + root.getMessage());
        }
        return out;
    }

    /**
     * Builds a dynamic-proxy implementing AppFunctionContext that
     * returns sidekick's host Context. The interface has a single
     * abstract method, so the proxy is trivial.
     */
    private Object newAppFunctionContext(Class<?> appFunctionContextCls) {
        return Proxy.newProxyInstance(
            appFunctionContextCls.getClassLoader(),
            new Class<?>[]{appFunctionContextCls},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getContext".equals(name)) return appContext;
                // Stock Object methods (equals/hashCode/toString) — answer cheaply.
                if ("toString".equals(name)) return "DtaAppFunctionContext(" + appContext.getPackageName() + ")";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name) && args != null && args.length == 1) return proxy == args[0];
                return null;
            });
    }

    private Method findUnsafeInvokeMethod(Class<?> invokerCls,
            Class<?> appFunctionContextCls, Class<?> continuationCls) {
        for (Method m : invokerCls.getMethods()) {
            if (!"unsafeInvoke".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 4) continue;
            if (!appFunctionContextCls.isAssignableFrom(p[0])) continue;
            if (!String.class.equals(p[1])) continue;
            if (!Map.class.isAssignableFrom(p[2])) continue;
            if (!continuationCls.isAssignableFrom(p[3])) continue;
            return m;
        }
        return null;
    }

    /**
     * Builds a dynamic-proxy implementing kotlin.coroutines.Continuation.
     * The interface has two abstract members: {@code getContext()} and
     * {@code resumeWith(Object)} (where the argument is a
     * {@code kotlin.Result} value class — boxed at the JVM layer to a
     * Throwable-or-value via the synthetic exception field).
     */
    private Object newContinuation(ClassLoader cl, Class<?> continuationCls,
            CountDownLatch done,
            AtomicReference<Object> resultHolder,
            AtomicReference<Throwable> errorHolder) throws Exception {
        Class<?> emptyContextCls = Class.forName(EMPTY_COROUTINE_CONTEXT_FQN, true, cl);
        Object emptyContext = emptyContextCls.getField("INSTANCE").get(null);

        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("getContext".equals(name)) return emptyContext;
            if ("resumeWith".equals(name) && args != null && args.length == 1) {
                Object kotlinResult = args[0];
                Throwable failure = extractKotlinResultFailure(cl, kotlinResult);
                if (failure != null) errorHolder.set(failure);
                else resultHolder.set(kotlinResult);
                done.countDown();
                return null;
            }
            if ("toString".equals(name)) return "DtaContinuation";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name) && args != null && args.length == 1) return proxy == args[0];
            return null;
        };
        return Proxy.newProxyInstance(cl, new Class<?>[]{continuationCls}, handler);
    }

    /**
     * Kotlin's {@code Result} is a JVM value class — at the bytecode
     * level it's just {@code Object}, with failures represented by a
     * sentinel wrapper {@code Result.Failure(Throwable)}. We extract
     * the wrapped throwable via reflection if present, else null
     * means the boxed value is the actual success payload.
     */
    private Throwable extractKotlinResultFailure(ClassLoader cl, Object kotlinResult) {
        if (kotlinResult == null) return null;
        try {
            Class<?> failureCls = Class.forName("kotlin.Result$Failure", true, cl);
            if (failureCls.isInstance(kotlinResult)) {
                java.lang.reflect.Field exception = failureCls.getDeclaredField("exception");
                exception.setAccessible(true);
                Object t = exception.get(kotlinResult);
                if (t instanceof Throwable) return (Throwable) t;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isCoroutineSuspended(ClassLoader cl, Object value) {
        if (value == null) return false;
        try {
            Class<?> singletons = Class.forName(COROUTINE_SINGLETONS_FQN, true, cl);
            for (Object constant : singletons.getEnumConstants()) {
                if (COROUTINE_SUSPENDED_NAME.equals(constant.toString()) && constant == value) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
