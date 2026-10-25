package io.yamsergey.dta.shim;

/**
 * Runs before a hooked method's body and may change what the method does (feature 005).
 * Rewrite {@code params} in place and return {@link HookDispatcher#PROCEED} to run the
 * method with them, or return any other value to return it from the method instead of
 * running it (ignored for void methods).
 */
public interface ControlHandler {
    Object onEntry(Object self, Object[] params);
}
