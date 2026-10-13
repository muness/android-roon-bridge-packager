package io.github.muness.roonpackager.core;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * A zip writer that can align a stored entry's data, which is the one thing
 * {@link java.util.zip.ZipOutputStream} will not do.
 *
 * <p>Alignment matters here for exactly the reason it matters to
 * {@code zipalign}: a native library the platform maps straight out of the APK
 * has to start on a page boundary, and a library the installer extracts has to
 * start on a 4-byte boundary. The padding goes in the local header's extra
 * field, the same place {@code zipalign} puts it, so the entry's content is
 * untouched -- which is what lets the verifier compare its sha256 with the
 * archive member it came from and get the same answer.
 *
 * <p>Timestamps are fixed rather than taken from the clock, so two runs over
 * the same inputs produce the same entry list in the same order with the same
 * headers. That is what makes the desktop-and-device parity check meaningful.
 */
public final class ZipWriter implements Closeable {

    /** 1981-01-01 00:00:00 in MS-DOS form, the value the SDK's tools use. */
    private static final int DOS_TIME = 0x0821 | (0x0101 << 16);

    private final OutputStream out;
    private final List<Central> central = new ArrayList<>();
    private long offset;
    private boolean closed;

    public ZipWriter(File file) throws IOException {
        this.out = new BufferedOutputStream(new FileOutputStream(file), 128 * 1024);
    }

    /** Copies {@code content} in with no compression, aligned to {@code align}. */
    public void stored(String name, File content, int align) throws IOException {
        byte[] bytes = readAll(content);
        stored(name, bytes, align);
    }

    public void stored(String name, byte[] content, int align) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(content, 0, content.length);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int padding = 0;
        if (align > 1) {
            long dataStart = offset + 30 + nameBytes.length;
            int over = (int) (dataStart % align);
            if (over != 0) {
                padding = align - over;
            }
        }
        byte[] extra = new byte[padding];
        writeEntry(name, nameBytes, extra, 0, crc.getValue(), content, content.length);
    }

    /**
     * Compresses {@code content} in, storing it instead if that would be
     * smaller -- which is what {@code zip(1)} does, and therefore what the
     * desktop packers produce for a ten-byte marker file.
     */
    public void deflated(String name, byte[] content) throws IOException {
        deflated(name, content, true);
    }

    /**
     * @param allowStored false to keep the entry deflated even when
     *                    compression does not help. Copying an entry out of
     *                    the app shell uses this: an entry the shell stored one
     *                    way must come out the same way, or the two runs the
     *                    parity check compares would differ over a detail
     *                    neither of them chose.
     */
    public void deflated(String name, byte[] content, boolean allowStored) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(content, 0, content.length);
        byte[] compressed = deflate(content);
        if (allowStored && compressed.length >= content.length) {
            stored(name, content, 1);
            return;
        }
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        writeEntry(name, nameBytes, new byte[0], 8, crc.getValue(), compressed, content.length);
    }

    private void writeEntry(String name, byte[] nameBytes, byte[] extra, int method,
            long crc, byte[] data, int uncompressedSize) throws IOException {
        long localOffset = offset;
        write32(0x04034b50);
        write16(method == 8 ? 20 : 10);   // version needed
        write16(0);                        // flags: no data descriptor, no UTF-8 bit needed
        write16(method);
        write32(DOS_TIME);
        write32(crc);
        write32(data.length);
        write32(uncompressedSize);
        write16(nameBytes.length);
        write16(extra.length);
        writeBytes(nameBytes);
        writeBytes(extra);
        writeBytes(data);
        central.add(new Central(name, nameBytes, method, crc, data.length, uncompressedSize,
                localOffset));
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        long start = offset;
        for (Central entry : central) {
            write32(0x02014b50);
            write16(20);                   // version made by
            write16(entry.method == 8 ? 20 : 10);
            write16(0);
            write16(entry.method);
            write32(DOS_TIME);
            write32(entry.crc);
            write32(entry.compressedSize);
            write32(entry.uncompressedSize);
            write16(entry.nameBytes.length);
            write16(0);                    // no extra in the central record
            write16(0);                    // no comment
            write16(0);                    // disk number
            write16(0);                    // internal attributes
            write32(0);                    // external attributes
            write32(entry.localOffset);
            writeBytes(entry.nameBytes);
        }
        long size = offset - start;
        write32(0x06054b50);
        write16(0);
        write16(0);
        write16(central.size());
        write16(central.size());
        write32(size);
        write32(start);
        write16(0);
        out.flush();
        out.close();
    }

    private static byte[] deflate(byte[] content) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(content);
            deflater.finish();
            java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream(
                    Math.max(64, content.length / 2));
            byte[] buffer = new byte[16 * 1024];
            while (!deflater.finished()) {
                int produced = deflater.deflate(buffer);
                sink.write(buffer, 0, produced);
            }
            return sink.toByteArray();
        } finally {
            deflater.end();
        }
    }

    public static byte[] readAll(File file) throws IOException {
        long length = file.length();
        if (length > Integer.MAX_VALUE - 8) {
            throw new IOException(file.getName() + " is too large for one entry");
        }
        byte[] bytes = new byte[(int) length];
        try (InputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                int step = in.read(bytes, read, bytes.length - read);
                if (step < 0) {
                    throw new IOException("short read on " + file);
                }
                read += step;
            }
        }
        return bytes;
    }

    private void write16(int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        offset += 2;
    }

    private void write32(long value) throws IOException {
        out.write((int) (value & 0xff));
        out.write((int) ((value >>> 8) & 0xff));
        out.write((int) ((value >>> 16) & 0xff));
        out.write((int) ((value >>> 24) & 0xff));
        offset += 4;
    }

    private void writeBytes(byte[] bytes) throws IOException {
        out.write(bytes);
        offset += bytes.length;
    }

    private static final class Central {
        final String name;
        final byte[] nameBytes;
        final int method;
        final long crc;
        final int compressedSize;
        final int uncompressedSize;
        final long localOffset;

        Central(String name, byte[] nameBytes, int method, long crc, int compressedSize,
                int uncompressedSize, long localOffset) {
            this.name = name;
            this.nameBytes = nameBytes;
            this.method = method;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.localOffset = localOffset;
        }
    }
}
