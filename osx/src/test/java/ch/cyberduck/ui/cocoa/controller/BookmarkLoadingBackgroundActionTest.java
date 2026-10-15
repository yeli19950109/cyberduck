package ch.cyberduck.ui.cocoa.controller;

/*
 * Copyright (c) 2002-2026 iterate GmbH. All rights reserved.
 * https://cyberduck.io/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

import ch.cyberduck.core.AbstractHostCollection;
import ch.cyberduck.core.exception.AccessDeniedException;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class BookmarkLoadingBackgroundActionTest {
    @Test
    public void testDeniedBookmarksReleaseImporterAndStopLoading() throws Exception {
        final AbstractHostCollection bookmarks = denied();
        final CountDownLatch loaded = new CountDownLatch(1);
        assertThrows(AccessDeniedException.class, () -> new BookmarkLoadingBackgroundAction(
                bookmarks, empty(), loaded, false).run());
        assertTrue(bookmarks.isLoaded());
        assertTrue(loaded.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testDeniedSessionsDoNotPreventLoadingBookmarks() throws Exception {
        final AbstractHostCollection bookmarks = empty();
        final CountDownLatch loaded = new CountDownLatch(1);
        assertThrows(AccessDeniedException.class, () -> new BookmarkLoadingBackgroundAction(
                bookmarks, denied(), loaded, true).run());
        assertTrue(bookmarks.isLoaded());
        assertEquals(0, loaded.getCount());
    }

    @Test
    public void testCancelBeforeLoadingReleasesImporter() {
        final CountDownLatch loaded = new CountDownLatch(1);
        final BookmarkLoadingBackgroundAction action = new BookmarkLoadingBackgroundAction(empty(), empty(), loaded, true);
        action.cancel();
        assertEquals(0, loaded.getCount());
        assertTrue(action.isCanceled());
    }

    @Test
    public void testSuccessfulLoad() throws Exception {
        final AbstractHostCollection bookmarks = empty();
        final AbstractHostCollection sessions = empty();
        final CountDownLatch loaded = new CountDownLatch(1);
        new BookmarkLoadingBackgroundAction(bookmarks, sessions, loaded, true).run();
        assertTrue(bookmarks.isLoaded());
        assertTrue(sessions.isLoaded());
        assertEquals(0, loaded.getCount());
    }

    private AbstractHostCollection empty() {
        return new AbstractHostCollection() { };
    }

    private AbstractHostCollection denied() {
        return new AbstractHostCollection() {
            @Override
            public void load() throws AccessDeniedException {
                throw new AccessDeniedException("Bookmarks directory is not accessible");
            }
        };
    }
}
