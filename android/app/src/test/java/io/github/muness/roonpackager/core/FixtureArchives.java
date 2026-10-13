package io.github.muness.roonpackager.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;

/**
 * The stand-in archives the tests pack, byte-identical to what
 * {@code scripts/make-fixtures.sh} writes.
 *
 * <p>The real vendor archives cannot be in this repository -- that is the point
 * of the project -- so parity is proved on synthetic inputs. Everything here is
 * produced by a rule so the shell script and this class agree: a fake
 * "networkaudiod" that is a valid ELF64 AArch64 header followed by
 * {@code byte[i] = (i * 37 + 11) mod 256}, and a fake app shell with the
 * entries a packing run touches.
 */
final class FixtureArchives {

    static final byte[] ELF64_AARCH64_HEADER = {
            0x7f, 0x45, 0x4c, 0x46, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            3, 0, (byte) 0xb7, 0};

    private FixtureArchives() {
    }

    /** The same bytes {@code scripts/make-fixtures.sh} writes. */
    static byte[] fakeExecutable(int bodyLength) {
        byte[] bytes = new byte[ELF64_AARCH64_HEADER.length + bodyLength];
        System.arraycopy(ELF64_AARCH64_HEADER, 0, bytes, 0, ELF64_AARCH64_HEADER.length);
        for (int index = 0; index < bodyLength; index++) {
            bytes[ELF64_AARCH64_HEADER.length + index] = (byte) ((index * 37 + 11) % 256);
        }
        return bytes;
    }

    /** An ELF file whose content depends on its name, so collisions show up. */
    static byte[] fakeElf(String name) {
        byte[] label = name.getBytes(StandardCharsets.UTF_8);
        byte[] body = fakeExecutable(256);
        byte[] bytes = new byte[body.length + label.length];
        System.arraycopy(body, 0, bytes, 0, body.length);
        System.arraycopy(label, 0, bytes, body.length, label.length);
        return bytes;
    }

    /** A .deb: an ar archive with the three members a real one has. */
    static File deb(File file, String memberPath, byte[] content) throws IOException {
        byte[] dataTarXz = xz(tar(memberPath, content));
        try (OutputStream out = new FileOutputStream(file)) {
            out.write("!<arch>\n".getBytes(StandardCharsets.US_ASCII));
            arMember(out, "debian-binary", "2.0\n".getBytes(StandardCharsets.US_ASCII));
            arMember(out, "control.tar", "Package: fake\n".getBytes(StandardCharsets.US_ASCII));
            arMember(out, "data.tar.xz", dataTarXz);
        }
        return file;
    }

    /** A bzip2 tarball with one top-level directory, like Roon's. */
    static File tarBz2(File file, String root, String[] names, byte[][] contents)
            throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(raw)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (int index = 0; index < names.length; index++) {
                TarArchiveEntry entry = new TarArchiveEntry(root + "/" + names[index]);
                entry.setSize(contents[index].length);
                entry.setModTime(0);
                tar.putArchiveEntry(entry);
                tar.write(contents[index]);
                tar.closeArchiveEntry();
            }
        }
        try (OutputStream out = new BZip2CompressorOutputStream(new FileOutputStream(file))) {
            out.write(raw.toByteArray());
        }
        return file;
    }

    /** The fake app shell the NAA fixture is packed into. */
    static File shellApk(File file) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            putBytes(zip, "AndroidManifest.xml", binaryManifest(), ZipEntry.DEFLATED);
            put(zip, "assets/runtime-variant", "unpatched\n", ZipEntry.DEFLATED);
            put(zip, "lib/arm64-v8a/libc_glibc.so", "shell libc\n", ZipEntry.STORED);
            put(zip, "lib/arm64-v8a/libnetworkaudiod_exec.so", "stale\n", ZipEntry.STORED);
            put(zip, "META-INF/CERT.SF", "x\n", ZipEntry.DEFLATED);
        }
        return file;
    }

    /** A fake app shell for a tree plan: the shell's own libraries and a stale map. */
    static File treeShellApk(File file, String[] ownLibraries, String staleMap)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            putBytes(zip, "AndroidManifest.xml", binaryManifest(), ZipEntry.DEFLATED);
            for (String own : ownLibraries) {
                put(zip, "lib/arm64-v8a/" + own, own + "\n", ZipEntry.STORED);
            }
            // A previous run's leavings: an entry whose name does not end in
            // the rename suffix, so only that run's own map can say it has to
            // go, plus one that does.
            put(zip, "lib/arm64-v8a/libstalekept.so", "x\n", ZipEntry.STORED);
            put(zip, "lib/arm64-v8a/libstale_roon.so", "x\n", ZipEntry.STORED);
            if (staleMap != null) {
                put(zip, "assets/roon-exec-map.txt", staleMap, ZipEntry.DEFLATED);
            }
            put(zip, "META-INF/CERT.SF", "x\n", ZipEntry.DEFLATED);
        }
        return file;
    }

    /**
     * A real binary AndroidManifest.xml, out of the same base64 text file
     * {@code scripts/make-fixtures.sh} reads. It has to be a real one:
     * {@code apksigner} refuses an APK whose manifest it cannot parse, which is
     * how a plain-text placeholder was found out.
     */
    static byte[] binaryManifest() throws IOException {
        InputStream in = FixtureArchives.class.getClassLoader()
                .getResourceAsStream("parity/fixture-manifest.axml.base64");
        if (in == null) {
            throw new IOException("no parity/fixture-manifest.axml.base64 on the classpath");
        }
        StringBuilder base64 = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#")) {
                    base64.append(line.trim());
                }
            }
        }
        return java.util.Base64.getDecoder().decode(base64.toString());
    }

    private static void put(ZipOutputStream zip, String name, String content, int method)
            throws IOException {
        putBytes(zip, name, content.getBytes(StandardCharsets.UTF_8), method);
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] bytes, int method)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(method);
        entry.setTime(347155261000L);
        if (method == ZipEntry.STORED) {
            entry.setSize(bytes.length);
            entry.setCompressedSize(bytes.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(bytes);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void arMember(OutputStream out, String name, byte[] content)
            throws IOException {
        String header = String.format("%-16s%-12s%-6s%-6s%-8s%-10s`\n",
                name, 0, 0, 0, 100644, content.length);
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(content);
        if (content.length % 2 == 1) {
            out.write('\n');
        }
    }

    private static byte[] tar(String path, byte[] content) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(raw)) {
            TarArchiveEntry entry = new TarArchiveEntry(path);
            entry.setSize(content.length);
            entry.setModTime(0);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return raw.toByteArray();
    }

    private static byte[] xz(byte[] content) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (OutputStream out = new XZCompressorOutputStream(raw)) {
            out.write(content);
        }
        return raw.toByteArray();
    }
}
