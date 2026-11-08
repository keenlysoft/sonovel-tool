import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.util.TocList;

/** Run against the patched classpath; no network or test framework is required. */
public final class TocListTest {
    public static void main(String[] args) {
        TocList sameTitle = new TocList();
        sameTitle.add(chapter("同名章节", "https://example.com/1.html", 1));
        sameTitle.add(chapter("同名章节", "https://example.com/2.html", 2));
        check(sameTitle.size() == 2, "Distinct chapter URLs sharing a title must survive");

        TocList duplicateCatalog = new TocList();
        duplicateCatalog.add(chapter("最新章节", "https://example.com/2.html", 1));
        duplicateCatalog.add(chapter("第一章", "https://example.com/1.html", 2));
        duplicateCatalog.add(chapter("第二章", "https://example.com/2.html", 3));
        check(duplicateCatalog.size() == 2, "Repeated URLs should be deduplicated");
        check(duplicateCatalog.get(0).getUrl().endsWith("/1.html"), "Full catalog order must win");
        check(duplicateCatalog.get(1).getTitle().equals("第二章"), "Last metadata must win");
        check(duplicateCatalog.get(0).getOrder() == 2
                && duplicateCatalog.get(1).getOrder() == 3, "Retain original extraction ordinals");

        boolean rejected = false;
        try {
            duplicateCatalog.add(chapter("无效章节", "", 4));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "A blank URL cannot identify a chapter");
        System.out.println("TocList regression checks passed");
    }

    private static Chapter chapter(String title, String url, int order) {
        return Chapter.builder().title(title).url(url).order(order).build();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
