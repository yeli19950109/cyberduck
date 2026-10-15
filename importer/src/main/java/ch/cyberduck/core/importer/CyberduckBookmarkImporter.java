package ch.cyberduck.core.importer;

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

import ch.cyberduck.core.Host;
import ch.cyberduck.core.HostReaderFactory;
import ch.cyberduck.core.Local;
import ch.cyberduck.core.exception.AccessDeniedException;
import ch.cyberduck.core.exception.LocalAccessDeniedException;
import ch.cyberduck.core.serializer.Reader;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Copies explicitly selected bookmarks without modifying their sources or replacing existing UUIDs.
 */
public class CyberduckBookmarkImporter {
    private final Reader<Host> reader;

    public CyberduckBookmarkImporter() {
        this(HostReaderFactory.get());
    }

    public CyberduckBookmarkImporter(final Reader<Host> reader) {
        this.reader = reader;
    }

    public int copy(final List<Local> selection, final Local destination, final BooleanSupplier canceled) throws AccessDeniedException {
        try {
            final List<Path> sources = new ArrayList<>();
            for(Local selected : selection) {
                final Path path = Paths.get(selected.getAbsolute());
                if(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    // Only inspect the selected folder itself, not nested folders or linked files.
                    try(DirectoryStream<Path> files = Files.newDirectoryStream(path, "*.duck")) {
                        for(Path file : files) {
                            if(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                                sources.add(file);
                            }
                        }
                    }
                }
                else if(path.toString().endsWith(".duck") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    sources.add(path);
                }
                else {
                    throw new LocalAccessDeniedException(selected.getAbsolute());
                }
            }
            // Validate all selected bookmarks before writing any files.
            final Map<String, Path> bookmarks = new LinkedHashMap<>();
            for(Path source : sources) {
                if(canceled.getAsBoolean()) {
                    return 0;
                }
                final Host host = reader.read(new Local(source.toString()));
                if(host == null || !host.getUuid().matches("[A-Za-z0-9_-]+")) {
                    throw new LocalAccessDeniedException("Invalid bookmark identifier: " + source.getFileName());
                }
                bookmarks.putIfAbsent(host.getUuid(), source);
            }
            int copied = 0;
            final Path folder = Paths.get(destination.getAbsolute());
            if(!bookmarks.isEmpty()) {
                Files.createDirectories(folder);
            }
            for(Map.Entry<String, Path> bookmark : bookmarks.entrySet()) {
                if(canceled.getAsBoolean()) {
                    break;
                }
                final Path staging = Files.createTempFile(folder, ".import-", ".tmp");
                try {
                    Files.copy(bookmark.getValue(), staging, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.move(staging, folder.resolve(bookmark.getKey() + ".duck"));
                        copied++;
                    }
                    catch(FileAlreadyExistsException e) {
                        // Re-importing must not overwrite edits made in this app.
                    }
                }
                finally {
                    Files.deleteIfExists(staging);
                }
            }
            return copied;
        }
        catch(IOException e) {
            throw new LocalAccessDeniedException(e.getMessage(), e);
        }
    }
}
