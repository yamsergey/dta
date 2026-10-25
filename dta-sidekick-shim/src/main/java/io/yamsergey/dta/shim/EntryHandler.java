package io.yamsergey.dta.shim;

/** Receives a hooked method's arguments before its body runs. */
public interface EntryHandler {
    /**
     * @param self the receiver, or null for a static method
     * @param params the method's parameters, primitives boxed
     */
    void onEntry(Object self, Object[] params);
}
