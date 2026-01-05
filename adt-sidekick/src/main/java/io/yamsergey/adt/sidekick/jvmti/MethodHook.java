package io.yamsergey.adt.sidekick.jvmti;

/**
 * Interface for defining method hooks that intercept method calls at runtime.
 *
 * <p>Hooks are registered with {@link HookRegistry} and invoked by transformed
 * bytecode when the target method is called.</p>
 *
 * <h3>Example: Hooking OkHttp calls</h3>
 * <pre>{@code
 * MethodHook hook = new MethodHook() {
 *     @Override
 *     public String getTargetClass() {
 *         return "okhttp3.OkHttpClient";
 *     }
 *
 *     @Override
 *     public String getTargetMethod() {
 *         return "newCall";
 *     }
 *
 *     @Override
 *     public String getMethodSignature() {
 *         return "(Lokhttp3/Request;)Lokhttp3/Call;";
 *     }
 *
 *     @Override
 *     public Object onExit(Object thisObj, Object result) {
 *         // Intercept the Call object
 *         return wrapCall((Call) result);
 *     }
 * };
 * }</pre>
 */
public interface MethodHook {

    /**
     * Returns the fully qualified name of the target class.
     *
     * @return class name using dots (e.g., "okhttp3.OkHttpClient")
     */
    String getTargetClass();

    /**
     * Returns the name of the target method to hook.
     *
     * @return method name (e.g., "newCall")
     */
    String getTargetMethod();

    /**
     * Returns the JVM method signature.
     *
     * <p>Use JVM internal format with slashes for class names:</p>
     * <ul>
     *   <li>{@code (Ljava/lang/String;)V} - void method taking String</li>
     *   <li>{@code (II)I} - int method taking two ints</li>
     *   <li>{@code ()Ljava/util/List;} - no args, returns List</li>
     * </ul>
     *
     * <p>If null or empty, hooks ALL methods with the given name
     * (useful when signature is unknown or varies).</p>
     *
     * @return JVM method signature, or null to match any signature
     */
    String getMethodSignature();

    /**
     * Called before the target method executes.
     *
     * <p>This is invoked on the same thread as the target method.
     * Exceptions thrown here will propagate to the caller.</p>
     *
     * @param thisObj the 'this' reference for instance methods, null for static methods
     * @param args    the method arguments (may be empty array, never null)
     */
    default void onEnter(Object thisObj, Object[] args) {
        // Default: no-op
    }

    /**
     * Called after the target method executes successfully.
     *
     * <p>This is invoked on the same thread as the target method.
     * The returned value replaces the original return value.</p>
     *
     * @param thisObj the 'this' reference for instance methods, null for static methods
     * @param result  the original return value (null for void methods)
     * @return the (possibly modified) return value to pass to the caller
     */
    default Object onExit(Object thisObj, Object result) {
        return result;
    }

    /**
     * Called when the target method throws an exception.
     *
     * <p>This allows hooks to intercept and potentially modify or suppress exceptions.
     * Return the exception to propagate it, or return null to suppress it
     * (in which case {@link #onExit} will be called with null result).</p>
     *
     * @param thisObj   the 'this' reference for instance methods, null for static methods
     * @param throwable the exception that was thrown
     * @return the exception to propagate, or null to suppress it
     */
    default Throwable onException(Object thisObj, Throwable throwable) {
        return throwable;
    }

    /**
     * Returns the priority of this hook. Higher values execute first.
     *
     * <p>When multiple hooks target the same method, they are ordered by priority.
     * Hooks with the same priority are ordered by registration order.</p>
     *
     * @return priority value (default 0)
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Returns a unique identifier for this hook.
     *
     * <p>If null, an ID is auto-generated based on class+method+signature.
     * Custom IDs are useful for programmatic hook management.</p>
     *
     * @return unique hook ID, or null for auto-generated ID
     */
    default String getId() {
        return null;
    }

    /**
     * Returns whether this hook is currently enabled.
     *
     * <p>Disabled hooks are not invoked but remain registered.
     * This allows temporarily disabling hooks without re-transforming classes.</p>
     *
     * @return true if enabled (default), false if disabled
     */
    default boolean isEnabled() {
        return true;
    }
}
