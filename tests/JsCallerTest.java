import com.caoccao.javet.interop.V8Host;
import com.pcdd.sonovel.util.JsCaller;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Run in the Linux runtime image with patches and native Javet on the classpath. */
public final class JsCallerTest {
    public static void main(String[] args) throws Exception {
        V8Host host = V8Host.getV8Instance();
        check(host.getV8RuntimeCount() == 0, "Fresh test process must have no retained V8 runtimes");

        check(JsCaller.call("r=r.replace(/^\\[[^\\]]*\\]/,'');", "[武侠]凡人修仙传")
                .equals("凡人修仙传"), "Rule string transformation changed");
        checkReleased(host, "call");

        check(JsCaller.eval("'结果' + 2").equals("结果2"), "String expression evaluation changed");
        checkReleased(host, "eval");

        Object result = JsCaller.callFunction(
                "function build(value) { return {title: value, count: 2}; }", "build", "测试");
        check(result instanceof Map<?, ?>, "Function result must be a detached Java object");
        Map<?, ?> object = (Map<?, ?>) result;
        check("测试".equals(object.get("title"))
                && object.get("count") instanceof Number
                && ((Number) object.get("count")).intValue() == 2, "Function result changed");
        checkReleased(host, "callFunction");

        expectFailure(() -> JsCaller.call("throw new Error('expected');", "input"));
        checkReleased(host, "failed call");
        expectFailure(() -> JsCaller.eval("(() => { throw new Error('expected'); })()"));
        checkReleased(host, "failed eval");
        expectFailure(() -> JsCaller.callFunction(
                "function fail() { throw new Error('expected'); }", "fail"));
        checkReleased(host, "failed callFunction");

        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < 16; i++) {
                final String input = "input-" + i;
                results.add(executor.submit(() -> JsCaller.call("r=r.toUpperCase();", input)));
            }
            for (int i = 0; i < results.size(); i++) {
                check(results.get(i).get(15, TimeUnit.SECONDS).equals("INPUT-" + i),
                        "Concurrent calls crossed input state");
            }
        } finally {
            executor.shutdownNow();
            check(executor.awaitTermination(20, TimeUnit.SECONDS), "Test workers did not stop");
        }
        checkReleased(host, "two-worker concurrent calls");
        JsCaller.close();
        checkReleased(host, "compatibility close");
        System.out.println("JsCaller regression checks passed; retained V8 runtimes=0");
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected JavaScript failure");
        } catch (IllegalStateException expected) {
            check(expected.getCause() != null, "JavaScript failure lost its cause");
        }
    }

    private static void checkReleased(V8Host host, String operation) {
        check(host.getV8RuntimeCount() == 0, operation + " retained a native V8 runtime");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
