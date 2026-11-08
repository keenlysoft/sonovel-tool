package com.pcdd.sonovel.util;

import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueGlobalObject;

/**
 * v1.9.6 compatibility patch: a virtual search thread must not retain a native
 * V8 runtime after it finishes. Each invocation owns and closes its resources.
 */
public final class JsCaller {
    public static final String JS_TEMPLATE = """
            function func(r) {
                %s
                return r;
            }
            """;

    private JsCaller() {
    }

    public static String call(String jsCode, String input) {
        try (V8Runtime runtime = V8Host.getV8Instance().createV8Runtime()) {
            runtime.getExecutor(JS_TEMPLATE.formatted(jsCode)).executeVoid();
            try (V8ValueGlobalObject global = runtime.getGlobalObject();
                 V8ValueFunction function = global.get("func")) {
                return function.callString(null, input);
            }
        } catch (JavetException exception) {
            throw new IllegalStateException("JavaScript rule execution failed", exception);
        }
    }

    public static String eval(String jsCode) {
        try (V8Runtime runtime = V8Host.getV8Instance().createV8Runtime()) {
            return runtime.getExecutor(jsCode).executeString();
        } catch (JavetException exception) {
            throw new IllegalStateException("JavaScript expression execution failed", exception);
        }
    }

    public static Object callFunction(String jsFunctionCode, String functionName, Object... args) {
        try (V8Runtime runtime = V8Host.getV8Instance().createV8Runtime()) {
            runtime.getExecutor(jsFunctionCode).executeVoid();
            try (V8ValueGlobalObject global = runtime.getGlobalObject();
                 V8ValueFunction function = global.get(functionName)) {
                return function.callObject(null, args);
            }
        } catch (JavetException exception) {
            throw new IllegalStateException("JavaScript function execution failed", exception);
        }
    }

    /** Kept for binary compatibility; invocation resources are already closed. */
    public static void close() {
    }
}
