package io.github.muness.roonpackager.core;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The two archive shapes the two vendors ship, opened on the device.
 *
 * <ul>
 *   <li>{@code deb}: an {@code ar} archive whose {@code data.tar.*} member is
 *       the tar stream the files are in. Signalyst's networkaudiod.</li>
 *   <li>{@code tar.bz2}: a bzip2 tarball with one top-level directory. Roon
 *       Bridge for Linux ARMv8.</li>
 * </ul>
 *
 * <p>Pure Java by requirement, not by preference: an app cannot shell out to
 * {@code dpkg}, {@code ar}, {@code tar} or {@code xz} on Android, and the whole
 * point of this app is that the user needs no computer that has them. Nothing
 * here modifies a byte: a member is written to a file and hashed, and the hash
 * is compared with the one that ends up inside the APK.
 */
public final class ArchiveReader {

    /** Extracted files, relative path to the file on disk, in sorted order. */
    public static final class Tree {
        public final File root;
        public final List<String> paths;

        Tree(File root, List<String> paths) {
            this.root = root;
            Collections.sort(paths);
            this.paths = Collections.unmodifiableList(paths);
        }

        public File file(String path) {
            return new File(root, path);
        }

        public boolean has(String path) {
            return paths.contains(path);
        }
    }

    private ArchiveReader() {
    }

    public static boolean knowsFormat(String format) {
        return "deb".equals(format) || "tar.bz2".equals(format);
    }

    /** Unpacks {@code archive} of {@code format} into {@code into}. */
    public static Tree extract(File archive, String format, File into) throws IOException {
        if (!into.isDirectory() && !into.mkdirs()) {
            throw new IOException("cannot create " + into);
        }
        if ("deb".equals(format)) {
            return extractDeb(archive, into);
        }
        if ("tar.bz2".equals(format)) {
            try (InputStream in = new BZip2CompressorInputStream(
                    new BufferedInputStream(new FileInputStream(archive)))) {
                return extractTar(in, into);
            }
        }
        throw new IOException("unknown archive format " + format);
    }

    private static Tree extractDeb(File deb, File into) throws IOException {
        try (ArArchiveInputStream ar = new ArArchiveInputStream(
                new BufferedInputStream(new FileInputStream(deb)))) {
            ArArchiveEntry entry;
            while ((entry = ar.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.startsWith("data.tar")) {
                    continue;
                }
                InputStream data;
                if (name.endsWith(".xz")) {
                    data = new XZCompressorInputStream(ar);
                } else if (name.endsWith(".gz")) {
                    data = new GzipCompressorInputStream(ar);
                } else if (name.endsWith(".bz2")) {
                    data = new BZip2CompressorInputStream(ar);
                } else if (name.equals("data.tar")) {
                    data = ar;
                } else {
                    throw new IOException("the .deb's " + name + " is compressed in a way this "
                            + "app cannot open. Only xz, gzip, bzip2 and plain tar are known.");
                }
                return extractTar(data, into);
            }
        }
        throw new IOException("no data.tar.* member in " + deb.getName()
                + ". Is that file really a Debian package?");
    }

    private static Tree extractTar(InputStream in, File into) throws IOException {
        List<String> paths = new ArrayList<>();
        TarArchiveInputStream tar = new TarArchiveInputStream(in);
        TarArchiveEntry entry;
        while ((entry = tar.getNextEntry()) != null) {
            if (!entry.isFile()) {
                continue;
            }
            String relative = normalise(entry.getName());
            if (relative == null) {
                continue;
            }
            File target = new File(into, relative);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            try (OutputStream out = new FileOutputStream(target)) {
                copy(tar, out);
            }
            paths.add(relative);
        }
        return new Tree(into, paths);
    }

    /**
     * {@code ./usr/sbin/x} and {@code usr/sbin/x} are the same member, and a
     * path that tries to leave the directory is not a member at all.
     */
    static String normalise(String name) {
        String path = name.replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty() || path.endsWith("/")) {
            return null;
        }
        for (String part : path.split("/")) {
            if (part.equals("..")) {
                return null;
            }
        }
        return path;
    }

    /** True when the file starts with the four bytes {@code \x7fELF}. */
    public static boolean isElf(File file) throws IOException {
        if (!file.isFile() || file.length() < 4) {
            return false;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            int read = 0;
            while (read < 4) {
                int step = in.read(magic, read, 4 - read);
                if (step < 0) {
                    return false;
                }
                read += step;
            }
            return magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
        }
    }

    /**
     * The same check {@code verify_elf_arm64} makes in the desktop packer: an
     * ELF64 AArch64 executable, so a user who downloaded the amd64 or armhf
     * package is told that rather than handed an APK that cannot run.
     */
    public static void requireAarch64Executable(File file) throws IOException {
        byte[] header = new byte[20];
        try (InputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < header.length) {
                int step = in.read(header, read, header.length - read);
                if (step < 0) {
                    throw new IOException(file.getName() + " is too short to be an ELF file");
                }
                read += step;
            }
        }
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
            throw new IOException(file.getName() + " is not an ELF file");
        }
        if (header[4] != 2) {
            throw new IOException(file.getName() + " is not 64-bit ELF");
        }
        int type = (header[16] & 0xff) | ((header[17] & 0xff) << 8);
        if (type != 2 && type != 3) {
            throw new IOException(file.getName() + " is not an executable (e_type " + type + ")");
        }
        int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        if (machine != 0xb7) {
            throw new IOException(file.getName() + " is not an AArch64 binary. Make sure the "
                    + "download is the arm64 build, not amd64 or armhf.");
        }
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    public static void deleteTree(File file) {
        if (file == null) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
