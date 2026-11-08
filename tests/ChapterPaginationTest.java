import com.pcdd.sonovel.context.HttpClientContext;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.core.OkHttpClientFactory;
import com.pcdd.sonovel.parse.ChapterParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Real ChapterParser pipeline against a single-worker, loopback-only HTTP fixture. */
public final class ChapterPaginationTest {
    public static void main(String[] args) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bodyRequests = new AtomicInteger();
        var nextChapterRequests = new AtomicInteger();
        var networkRequests = new AtomicInteger();
        Map<String, String> nextPages = Map.of(
                "/part_2.html", "/part_3.html",
                "/part_3.html", "/next.html",
                "/part_9.html", "/part_10.html",
                "/part_10.html", "/part_11.html",
                "/part_11.html", "/next.html");
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String next = nextPages.get(path);
                String body;
                int status;
                if (next != null) {
                    bodyRequests.incrementAndGet();
                    String number = path.substring("/part_".length(), path.length() - ".html".length());
                    body = "<html><body><div class=\"content\">PAGE_TOKEN_" + number
                            + "_END</div><a href=\"" + next + "\">下一章</a></body></html>";
                    status = 200;
                } else if ("/next.html".equals(path)) {
                    nextChapterRequests.incrementAndGet();
                    body = "<div class=\"content\">UNRELATED_NEXT_CHAPTER</div>";
                    status = 200;
                } else {
                    body = "Not found";
                    status = 404;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        okhttp3.OkHttpClient client = null;
        try {
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var config = AppConfigLoader.loadConfig();
            config.setSourceId(13);
            client = OkHttpClientFactory.create(config).newBuilder()
                    .addNetworkInterceptor(chain -> {
                        if (networkRequests.incrementAndGet() > 8) {
                            throw new IOException("Local pagination fixture request budget exceeded");
                        }
                        check("127.0.0.1".equals(chain.request().url().host()),
                                "Fixture cannot call an external host");
                        chain.call().timeout().timeout(10, TimeUnit.SECONDS);
                        return chain.proceed(chain.request());
                    }).build();
            HttpClientContext.set(client);
            var parser = new ChapterParser(config);
            var rule = parser.rule.getChapter();
            rule.setBaseUri(base + "/");
            rule.setContent(".content");
            rule.setPagination(true);
            rule.setNextPage("a");
            rule.setNextPageInJs(null);
            rule.setNextChapterLink(Pattern.quote(base) + "/next\\.html");
            rule.setTimeout(5);

            String control = parser.fetchContent(base + "/part_2.html", 0);
            check(control.contains("PAGE_TOKEN_2_END") && control.contains("PAGE_TOKEN_3_END"),
                    "Single-digit pagination regressed");
            check(bodyRequests.get() == 2 && nextChapterRequests.get() == 0,
                    "Control must fetch only the two pages in its chapter");

            bodyRequests.set(0);
            String result = parser.fetchContent(base + "/part_9.html", 0);
            check(result.contains("PAGE_TOKEN_9_END") && result.contains("PAGE_TOKEN_10_END")
                            && result.contains("PAGE_TOKEN_11_END"),
                    "Chapter pagination stopped at the single-digit boundary");
            check(bodyRequests.get() == 3, "Expected exactly three chapter-body requests");
            check(nextChapterRequests.get() == 0 && !result.contains("UNRELATED_NEXT_CHAPTER"),
                    "Parser must stop before the next chapter");
            System.out.println("Chapter pagination regression passed: 9 -> 10 -> 11; next chapter not fetched");
        } finally {
            HttpClientContext.clear();
            if (client != null) {
                client.dispatcher().executorService().shutdownNow();
                client.connectionPool().evictAll();
            }
            server.stop(0);
            executor.shutdownNow();
            check(executor.awaitTermination(5, TimeUnit.SECONDS), "HTTP fixture worker did not stop");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
