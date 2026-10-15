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
import ch.cyberduck.core.Local;
import ch.cyberduck.core.ProtocolFactory;
import ch.cyberduck.core.TestProtocol;
import ch.cyberduck.core.exception.AccessDeniedException;
import ch.cyberduck.core.serializer.impl.dd.HostPlistReader;
import ch.cyberduck.core.serializer.impl.dd.PlistWriter;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class CyberduckBookmarkImporterTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private final CyberduckBookmarkImporter importer = new CyberduckBookmarkImporter(new HostPlistReader());

    @BeforeClass
    public static void register() {
        ProtocolFactory.get().register(new TestProtocol());
    }

    private Path bookmark(final Path folder, final Host host) throws Exception {
        final Path file = folder.resolve(host.getUuid() + ".duck");
        new PlistWriter<Host>().write(host, new Local(file.toString()));
        return file;
    }

    private int copy(final Path source, final Path target) throws Exception {
        return importer.copy(Collections.singletonList(new Local(source.toString())), new Local(target.toString()), () -> false);
    }

    @Test
    public void testCopyAndReloadWithoutChangingSource() throws Exception {
        final Path source = temporary.newFolder("source").toPath();
        final Path target = temporary.getRoot().toPath().resolve("target/Bookmarks");
        final Host host = new Host(new TestProtocol(), "127.0.0.1", 9000);
        host.getCredentials().setUsername("minio");
        final Path file = bookmark(source, host);
        final byte[] original = Files.readAllBytes(file);
        assertEquals(1, copy(source, target));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertArrayEquals(original, Files.readAllBytes(target.resolve(file.getFileName())));
        final Host imported = new HostPlistReader().read(new Local(target.resolve(file.getFileName()).toString()));
        assertEquals(host.getUuid(), imported.getUuid());
        assertEquals(9000, imported.getPort());
        assertEquals("minio", imported.getCredentials().getUsername());
    }

    @Test
    public void testRepeatedImportKeepsExistingEdits() throws Exception {
        final Path source = temporary.newFolder("source").toPath();
        final Path target = temporary.newFolder("target").toPath();
        final Host host = new Host(new TestProtocol(), "original.example");
        final Path file = bookmark(source, host);
        host.setHostname("edited.example");
        final Path existing = bookmark(target, host);
        final byte[] original = Files.readAllBytes(existing);
        assertEquals(0, copy(file, target));
        assertArrayEquals(original, Files.readAllBytes(existing));
        assertEquals(0, copy(source, target));
    }

    @Test
    public void testIgnoresNestedFoldersAndSymlinks() throws Exception {
        final Path source = temporary.newFolder("source").toPath();
        final Path target = temporary.newFolder("target").toPath();
        final Path nested = Files.createDirectory(source.resolve("nested"));
        final Path file = bookmark(nested, new Host(new TestProtocol()));
        Files.createSymbolicLink(source.resolve("linked.duck"), file);
        Files.write(source.resolve("unrelated.txt"), new byte[]{1});
        assertEquals(0, copy(source, target));
        assertTrue(Files.exists(file));
    }

    @Test
    public void testMalformedSelectionDoesNotPartiallyImport() throws Exception {
        final Path source = temporary.newFolder("source").toPath();
        final Path target = temporary.newFolder("target").toPath();
        final Path valid = bookmark(source, new Host(new TestProtocol()));
        final Path broken = Files.write(source.resolve("broken.duck"), new byte[]{1});
        try {
            importer.copy(Arrays.asList(new Local(valid.toString()), new Local(broken.toString())), new Local(target.toString()), () -> false);
            fail("Expected a parse error");
        }
        catch(AccessDeniedException expected) {
            assertFalse(Files.exists(target.resolve(valid.getFileName())));
        }
    }

    @Test
    public void testDestinationFailureIsReported() throws Exception {
        final Path source = temporary.newFolder("source").toPath();
        final Path target = temporary.newFile("target").toPath();
        final Path file = bookmark(source, new Host(new TestProtocol()));
        try {
            copy(source, target);
            fail("Expected a write error");
        }
        catch(AccessDeniedException expected) {
            assertTrue(Files.exists(file));
        }
    }

    @Test
    public void testCanceledImportDoesNotCopy() throws Exception {
        final Path source = temporary.newFolder("source").toPath();
        final Path target = temporary.getRoot().toPath().resolve("target");
        bookmark(source, new Host(new TestProtocol()));
        assertEquals(0, importer.copy(Collections.singletonList(new Local(source.toString())), new Local(target.toString()), () -> true));
        assertFalse(Files.exists(target));
    }
}
