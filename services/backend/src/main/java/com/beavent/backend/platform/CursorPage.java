package com.beavent.backend.platform;

import java.util.List;

/** One page of a cursor-paginated list; {@code nextCursor} is {@code null} on the last page. */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public CursorPage {
        items = List.copyOf(items);
    }
}
