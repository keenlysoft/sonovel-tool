package com.pcdd.sonovel.util;

import com.pcdd.sonovel.model.Chapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * v1.9.6 compatibility patch: chapter identity is its URL, not its title.
 * Keep the last appearance, so a full catalog supersedes a latest-chapter block.
 */
public class TocList extends ArrayList<Chapter> {
    private final Map<String, Chapter> chaptersByUrl = new HashMap<>();

    @Override
    public boolean add(Chapter chapter) {
        String url = chapter.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Chapter URL must not be blank");
        }
        Chapter previous = chaptersByUrl.put(url, chapter);
        if (previous != null) {
            super.remove(previous);
        }
        return super.add(chapter);
    }
}
