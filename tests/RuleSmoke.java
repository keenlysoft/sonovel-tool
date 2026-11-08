import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.context.BookContext;
import com.pcdd.sonovel.context.HttpClientContext;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.core.OkHttpClientFactory;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Book;
import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.model.ContentType;
import com.pcdd.sonovel.model.Rule;
import com.pcdd.sonovel.parse.BookParser;
import com.pcdd.sonovel.parse.ChapterParser;
import com.pcdd.sonovel.parse.TocParser;
import com.pcdd.sonovel.util.JsoupUtils;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded live smoke test for the bundled v1.9.6 parsers and repository patches.
 * Compile: javac -cp app.jar tests/RuleSmoke.java
 * Run: timeout 180 java -Xmx256m -XX:ActiveProcessorCount=1
 *      -Dconfig.file=/absolute/test-config.ini
 *      -cp '/app/patches:/app/app.jar:/app/lib/*:tests'
 *      RuleSmoke SOURCE_ID BOOK_URL SAMPLE_COUNT [MAX_REQUESTS] [DEADLINE_SECONDS]
 * Run one source at a time. No whole-book download or output manuscript is made.
 * Keep patches before app.jar; the runtime image libraries provide the JavaScript engine.
 * BookParser's separate cover lookups require the outer process timeout.
 */
public final class RuleSmoke {
    private static final Pattern NUMBERED_PAGE = Pattern.compile("(.*/[^/]*?)(\\d+)(\\.html)");

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 5) {
            throw new IllegalArgumentException(
                    "Expected SOURCE_ID BOOK_URL SAMPLE_COUNT [MAX_REQUESTS] [DEADLINE_SECONDS]");
        }
        int sourceId = boundedInt(args[0], 1, 1000, "source ID");
        URI bookUri = URI.create(args[1]);
        require(bookUri.getHost() != null && Set.of("http", "https").contains(bookUri.getScheme())
                && bookUri.getUserInfo() == null, "Invalid source book URL");
        int sampleCount = boundedInt(args[2], 1, 2, "sample count");
        int maxRequests = args.length > 3 ? boundedInt(args[3], 1, 80, "request budget") : 80;
        int seconds = args.length > 4 ? boundedInt(args[4], 1, 120, "deadline") : 120;
        Guard guard = new Guard(bookUri, maxRequests, seconds);
        AppConfig config = AppConfigLoader.loadConfig();
        config.setSourceId(sourceId);
        config.setEnableRetry(1);
        config.setMaxRetries(0);
        OkHttpClient client = OkHttpClientFactory.create(config).newBuilder()
                .addInterceptor(guard::captureResponse)
                .addNetworkInterceptor(guard)
                .build();
        HttpClientContext.set(client);
        try {
            Book book = new BookParser(config).parse(bookUri.toString());
            require(book != null, "Book parser returned null");
            require(notBlank(book.getBookName()), "Book metadata has no title");
            require(notBlank(book.getAuthor()), "Book metadata has no author");
            BookContext.set(book);

            TocParser tocParser = new TocParser(config);
            guard.configureToc(tocParser.rule.getToc(), tocParser.rule.getBook(), bookUri);
            List<Chapter> toc = tocParser.parse(bookUri.toString());
            require(toc != null && !toc.isEmpty(), "TOC is empty");
            require(toc.size() <= 10000, "TOC exceeds smoke-test size limit");
            Set<String> chapterUrls = new HashSet<>();
            int previousOrder = 0;
            for (int i = 0; i < toc.size(); i++) {
                Chapter chapter = toc.get(i);
                require(chapter != null && notBlank(chapter.getTitle()), "TOC has a blank chapter title");
                require(notBlank(chapter.getUrl()), "TOC has a blank chapter URL");
                URI chapterUri = URI.create(chapter.getUrl());
                require(sameOrigin(bookUri, chapterUri), "TOC chapter has a different origin");
                require(chapterUrls.add(canonical(chapterUri.toString())), "TOC repeats a chapter URL");
                require(chapter.getOrder() != null && chapter.getOrder() > previousOrder,
                        "TOC chapter order is not strictly increasing");
                previousOrder = chapter.getOrder();
            }
            guard.verifyTocPages();
            guard.verifyTocEntries(toc);

            ChapterParser parser = new ChapterParser(config);
            // Source construction may apply per-rule crawl settings. Keep smoke retries disabled.
            config.setEnableRetry(1);
            config.setMaxRetries(0);
            List<Map<String, Object>> samples = new ArrayList<>();
            for (int i = 0; i < Math.min(sampleCount, toc.size()); i++) {
                guard.checkDeadline();
                Chapter chapter = toc.get(i);
                guard.beginChapter(parser.rule.getChapter());
                Chapter parsed = parser.parse(Chapter.builder()
                        .url(chapter.getUrl()).title(chapter.getTitle()).order(i + 1).build());
                require(parsed != null && notBlank(parsed.getContent()), "Chapter parser returned no text");
                String text = parsed.getContent();
                require(text.codePointCount(0, text.length()) >= 80, "Chapter text is shorter than 80 characters");
                int pagesRead = guard.verifyChapterContent(text);
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("title", parsed.getTitle());
                sample.put("characters", text.codePointCount(0, text.length()));
                sample.put("pagesRead", pagesRead);
                sample.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(text.getBytes(StandardCharsets.UTF_8))));
                samples.add(sample);
            }
            guard.checkDeadline();
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("sourceId", sourceId);
            receipt.put("bookName", book.getBookName());
            receipt.put("author", book.getAuthor());
            receipt.put("tocCount", toc.size());
            receipt.put("lastChapterTitle", toc.get(toc.size() - 1).getTitle());
            receipt.put("tocPages", guard.tocPages.size());
            receipt.put("advertisedLastTocPage", guard.advertisedLastTocPage);
            receipt.put("rawTocAnchorCount", guard.rawTocAnchorCount);
            receipt.put("duplicateTitlesWithDifferentUrls", guard.duplicateTitlesWithDifferentUrls);
            receipt.put("chapterSamples", samples);
            receipt.put("requestCount", guard.requests);
            receipt.put("elapsedSeconds", Math.round(guard.elapsedSeconds() * 1000) / 1000.0);
            receipt.put("passed", true);
            System.out.println(JSONUtil.toJsonStr(receipt));
        } finally {
            BookContext.clear();
            HttpClientContext.clear();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            if (client.cache() != null) {
                client.cache().close();
            }
        }
    }

    private static int boundedInt(String value, int min, int max, String label) {
        int result = Integer.parseInt(value);
        require(result >= min && result <= max, "Invalid " + label + ": " + value);
        return result;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String canonical(String url) {
        int fragment = url.indexOf('#');
        return URI.create(fragment < 0 ? url : url.substring(0, fragment)).normalize().toString();
    }

    private static String normalizedText(String text) {
        return text.replaceAll("[\\s\\p{Z}\\u200B\\uFEFF]+", "");
    }

    private static boolean sameOrigin(URI expected, URI actual) {
        return expected.getScheme().equalsIgnoreCase(actual.getScheme())
                && expected.getHost().equalsIgnoreCase(actual.getHost())
                && port(expected) == port(actual) && actual.getUserInfo() == null;
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class Guard implements Interceptor {
        private final URI origin;
        private final int maxRequests;
        private final long started = System.nanoTime();
        private final long deadline;
        private final Map<String, Integer> perUrl = new HashMap<>();
        private final Set<Integer> tocPages = new HashSet<>();
        private final Set<Integer> advertisedTocPages = new HashSet<>();
        private final Map<String, List<RawAnchor>> tocCaptures = new LinkedHashMap<>();
        private final Map<String, String> chapterTokens = new LinkedHashMap<>();
        private Rule.Chapter samplingChapterRule;
        private Rule.Toc tocRule;
        private URI firstTocUri;
        private URI bookTocRoot;
        private String pagePrefix;
        private String pageSuffix;
        private int advertisedLastTocPage;
        private int rawTocAnchorCount;
        private int duplicateTitlesWithDifferentUrls;
        private int requests;

        private Guard(URI origin, int maxRequests, int seconds) {
            this.origin = origin;
            this.maxRequests = maxRequests;
            this.deadline = started + seconds * 1_000_000_000L;
        }

        private void checkDeadline() throws IOException {
            if (System.nanoTime() >= deadline) {
                throw new IOException("Smoke-test deadline exceeded");
            }
        }

        private double elapsedSeconds() {
            return (System.nanoTime() - started) / 1_000_000_000.0;
        }

        private void beginChapter(Rule.Chapter rule) {
            samplingChapterRule = rule;
            chapterTokens.clear();
        }

        private void captureChapter(URI url, Response response) throws IOException {
            if (samplingChapterRule == null) {
                return;
            }
            String key = canonical(url.toString());
            if (chapterTokens.containsKey(key)) {
                return;
            }
            Document document = Jsoup.parse(response.peekBody(2_000_000).string(), url.toString());
            String html = JsoupUtils.selectAndInvokeJs(
                    document, samplingChapterRule.getContent(), ContentType.HTML);
            require(notBlank(html), "Sampled chapter page has no selected content");
            Document selected = Jsoup.parse(html);
            if (notBlank(samplingChapterRule.getFilterTag())) {
                selected.select(samplingChapterRule.getFilterTag()).remove();
            }
            String cleaned = selected.text();
            if (notBlank(samplingChapterRule.getFilterTxt())) {
                cleaned = cleaned.replaceAll(samplingChapterRule.getFilterTxt(), "");
            }
            cleaned = normalizedText(cleaned);
            int characters = cleaned.codePointCount(0, cleaned.length());
            require(characters > 0, "Sampled chapter page has no verifiable text after filtering");
            int tokenCharacters = Math.min(80, characters);
            int offset = (characters - tokenCharacters) / 2;
            int start = cleaned.offsetByCodePoints(0, offset);
            int end = cleaned.offsetByCodePoints(start, tokenCharacters);
            chapterTokens.put(key, cleaned.substring(start, end));
        }

        private int verifyChapterContent(String content) {
            require(!chapterTokens.isEmpty(), "No chapter page response was captured");
            String normalized = normalizedText(content);
            for (String token : chapterTokens.values()) {
                require(normalized.contains(token), "Rendered chapter omitted text from a fetched page");
            }
            int pages = chapterTokens.size();
            samplingChapterRule = null;
            chapterTokens.clear();
            return pages;
        }

        private void configureToc(Rule.Toc toc, Rule.Book book, URI bookUri) {
            require(!toc.isDesc(), "Descending TOCs require an explicit smoke-test oracle");
            tocRule = toc;
            bookTocRoot = bookUri;
            String id = null;
            if (notBlank(book.getUrl())) {
                String expression = book.getUrl().split("@js:", 2)[0];
                Matcher match = Pattern.compile(expression).matcher(bookUri.toString());
                if (match.find() && match.groupCount() > 0) {
                    id = match.group(1);
                }
            }
            firstTocUri = URI.create(notBlank(toc.getUrl())
                    ? toc.getUrl().formatted(id) : bookUri.toString());
            Matcher page = NUMBERED_PAGE.matcher(firstTocUri.getPath());
            if (page.matches()) {
                pagePrefix = page.group(1);
                pageSuffix = page.group(3);
            }
        }

        private int tocPageNumber(URI url) {
            if (firstTocUri == null || !sameOrigin(firstTocUri, url)) {
                return -1;
            }
            if (pagePrefix != null) {
                if (canonical(bookTocRoot.toString()).equals(canonical(url.toString()))) {
                    return 0;
                }
                Matcher page = NUMBERED_PAGE.matcher(url.getPath());
                if (page.matches() && pagePrefix.equals(page.group(1)) && pageSuffix.equals(page.group(3))) {
                    return Integer.parseInt(page.group(2));
                }
            }
            return canonical(firstTocUri.toString()).equals(canonical(url.toString())) ? 0 : -1;
        }

        private void captureToc(URI url, Response response) throws IOException {
            int number = tocPageNumber(url);
            if (number < 0) {
                return;
            }
            tocPages.add(number);
            String key = canonical(url.toString());
            if (tocCaptures.containsKey(key)) {
                return;
            }
            String baseUri = notBlank(tocRule.getBaseUri()) ? tocRule.getBaseUri() : url.toString();
            Document document = Jsoup.parse(response.peekBody(2_000_000).string(), baseUri);
            var links = document.select(".pagination a[href]");
            if (notBlank(tocRule.getNextPage())) {
                links.addAll(JsoupUtils.select(document, tocRule.getNextPage()));
            }
            for (var link : links) {
                String target = link.absUrl("href");
                if (!notBlank(target)) {
                    target = link.absUrl("value");
                }
                if (notBlank(target)) {
                    int advertised = tocPageNumber(URI.create(target));
                    if (advertised >= 0) {
                        advertisedTocPages.add(advertised);
                        advertisedLastTocPage = Math.max(advertisedLastTocPage, advertised);
                    }
                }
            }
            Document chapterDocument = document;
            if (notBlank(tocRule.getList())) {
                chapterDocument = Jsoup.parse(JsoupUtils.selectAndInvokeJs(
                        document, tocRule.getList(), ContentType.HTML));
            }
            List<RawAnchor> anchors = new ArrayList<>();
            for (var anchor : JsoupUtils.select(chapterDocument, tocRule.getItem())) {
                String chapterUrl = JsoupUtils.getStrAndInvokeJs(
                        anchor, tocRule.getNextPage(), ContentType.ATTR_HREF);
                require(notBlank(chapterUrl), "Raw TOC contains a blank chapter URL");
                anchors.add(new RawAnchor(canonical(chapterUrl), anchor.text()));
                if (++rawTocAnchorCount > 20000) {
                    throw new IOException("Raw TOC exceeds smoke-test anchor limit");
                }
            }
            tocCaptures.put(key, anchors);
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            checkDeadline();
            URI url = chain.request().url().uri();
            if (!sameOrigin(origin, url)) {
                throw new IOException("Smoke test refused a request to another origin");
            }
            if (++requests > maxRequests) {
                throw new IOException("Smoke-test HTTP request budget exceeded");
            }
            if (perUrl.merge(url.toString(), 1, Integer::sum) > 3) {
                throw new IOException("Smoke test detected a repeated URL: " + url);
            }
            chain.call().timeout().deadlineNanoTime(deadline);
            return chain.proceed(chain.request());
        }

        // Application responses are transparently decompressed by OkHttp before HTML capture.
        private Response captureResponse(Interceptor.Chain chain) throws IOException {
            checkDeadline();
            chain.call().timeout().deadlineNanoTime(deadline);
            Response response = chain.proceed(chain.request());
            if (!response.isSuccessful()) {
                response.close();
                throw new IOException("Source returned HTTP " + response.code());
            }
            try {
                captureToc(response.request().url().uri(), response);
                captureChapter(response.request().url().uri(), response);
            } catch (IOException | RuntimeException failure) {
                response.close();
                throw failure;
            }
            return response;
        }

        private void verifyTocPages() {
            for (int page : advertisedTocPages) {
                require(tocPages.contains(page), "TOC omitted an advertised page " + page);
            }
            for (int page = 1; page <= advertisedLastTocPage; page++) {
                require(tocPages.contains(page), "TOC pagination omitted page " + page);
            }
        }

        private void verifyTocEntries(List<Chapter> actual) {
            require(!tocCaptures.isEmpty(), "No raw TOC response was captured");
            var pages = new ArrayList<>(tocCaptures.entrySet());
            if (pagePrefix != null) {
                pages.sort(Comparator.comparingInt(page -> tocPageNumber(URI.create(page.getKey()))));
            }
            Map<String, ExpectedChapter> expected = new LinkedHashMap<>();
            Map<String, Set<String>> titleUrls = new HashMap<>();
            int ordinal = 0;
            for (var page : pages) {
                for (RawAnchor anchor : page.getValue()) {
                    ExpectedChapter chapter = new ExpectedChapter(anchor.url(), anchor.title(), ++ordinal);
                    // Repeated latest/full-directory entries retain their last occurrence and original order.
                    expected.remove(anchor.url());
                    expected.put(anchor.url(), chapter);
                    titleUrls.computeIfAbsent(anchor.title(), ignored -> new HashSet<>()).add(anchor.url());
                }
            }
            duplicateTitlesWithDifferentUrls = (int) titleUrls.values().stream()
                    .filter(urls -> urls.size() > 1).count();
            require(actual.size() == expected.size(), "TOC lost or added unique chapter URLs: expected="
                    + expected.size() + ", actual=" + actual.size()
                    + ", duplicateTitlesWithDifferentUrls=" + duplicateTitlesWithDifferentUrls);
            int index = 0;
            for (ExpectedChapter expectedChapter : expected.values()) {
                Chapter chapter = actual.get(index++);
                require(expectedChapter.url().equals(canonical(chapter.getUrl())),
                        "TOC changed chapter URL or last-occurrence ordering at entry " + index);
                require(expectedChapter.title().equals(chapter.getTitle()),
                        "TOC changed chapter title at entry " + index);
                require(Integer.valueOf(expectedChapter.ordinal()).equals(chapter.getOrder()),
                        "TOC changed the raw anchor ordinal at entry " + index);
            }
        }
    }

    private record RawAnchor(String url, String title) { }

    private record ExpectedChapter(String url, String title, int ordinal) { }
}
