# JNI entry points of libdta-agent.so are looked up by name.
-keep class io.yamsergey.dta.sidekick.hooks.NativeAgent { native <methods>; }

# The shim is not in the app: the agent puts it on the boot class path at run time.
-dontwarn io.yamsergey.dta.shim.**
