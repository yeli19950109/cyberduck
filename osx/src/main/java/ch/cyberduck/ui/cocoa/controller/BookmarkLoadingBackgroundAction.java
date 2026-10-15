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
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.threading.AbstractBackgroundAction;

import java.util.concurrent.CountDownLatch;

public class BookmarkLoadingBackgroundAction extends AbstractBackgroundAction<Void> {
    private final AbstractHostCollection bookmarks;
    private final AbstractHostCollection sessions;
    private final CountDownLatch loaded;
    private final boolean restoreSessions;

    public BookmarkLoadingBackgroundAction(final AbstractHostCollection bookmarks, final AbstractHostCollection sessions,
                                          final CountDownLatch loaded, final boolean restoreSessions) {
        this.bookmarks = bookmarks;
        this.sessions = sessions;
        this.loaded = loaded;
        this.restoreSessions = restoreSessions;
    }

    @Override
    public void cancel() {
        try {
            super.cancel();
        }
        finally {
            loaded.countDown();
        }
    }

    @Override
    public Void run() throws BackgroundException {
        try {
            BackgroundException failure = null;
            if(restoreSessions) {
                try {
                    sessions.load();
                }
                catch(BackgroundException e) {
                    failure = e;
                }
            }
            try {
                bookmarks.load();
            }
            catch(BackgroundException e) {
                if(failure != null) {
                    e.addSuppressed(failure);
                }
                failure = e;
            }
            if(failure != null) {
                throw failure;
            }
            return null;
        }
        finally {
            try {
                // A failed load must also stop the bookmark progress indicator.
                if(!bookmarks.isLoaded()) {
                    bookmarks.collectionLoaded();
                }
            }
            finally {
                // The importer and application shutdown must never wait forever.
                loaded.countDown();
            }
        }
    }
}
