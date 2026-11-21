// SPDX-License-Identifier: Apache-2.0
package org.atp.log;

import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/** Length-prefixed CRC frames. A torn tail is ignored; a bad magic is tampering. */
public final class FrameLog implements AutoCloseable {
    static final int MAGIC = 0x41545031;
    private static final int MAX = 8_000_000;
    private final FileChannel channel;
    private final Object lock = new Object();

    private FrameLog(FileChannel channel) {
        this.channel = channel;
    }

    static FrameLog adopt(FileChannel channel) {
        return new FrameLog(channel);
    }

    public static FrameLog open(Path path) {
        try {
            Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FrameLog log = new FrameLog(channel);
            if (channel.size() == 0) {
                ByteBuffer header = ByteBuffer.allocate(4);
                header.putInt(MAGIC);
                header.flip();
                channel.write(header);
                channel.force(true);
            } else {
                ByteBuffer header = ByteBuffer.allocate(4);
                channel.position(0);
                readFully(channel, header);
                header.flip();
                if (header.getInt() != MAGIC) {
                    throw new AtpException(AtpErrorCode.LEDGER_TAMPERED, "WAL magic mismatch");
                }
            }
            return log;
        } catch (AtpException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new AtpException(AtpErrorCode.INVALID, "cannot open WAL: " + ex.getMessage());
        }
    }

    public void appendAll(List<byte[]> frames) {
        synchronized (lock) {
            try {
                channel.position(channel.size());
                for (byte[] frame : frames) {
                    CRC32C crc = new CRC32C();
                    crc.update(frame);
                    ByteBuffer buffer = ByteBuffer.allocate(8 + frame.length);
                    buffer.putInt(frame.length);
                    buffer.putInt((int) crc.getValue());
                    buffer.put(frame);
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                }
                if (!frames.isEmpty()) {
                    channel.force(true);
                }
            } catch (IOException ex) {
                throw new AtpException(AtpErrorCode.INVALID, "WAL append failed: " + ex.getMessage());
            }
        }
    }

    public List<byte[]> readAll() {
        synchronized (lock) {
            try {
                List<byte[]> frames = new ArrayList<>();
                if (channel.size() < 4) {
                    return frames;
                }
                channel.position(4);
                ByteBuffer prefix = ByteBuffer.allocate(8);
                while (channel.position() + 8 <= channel.size()) {
                    prefix.clear();
                    int got = readAvailable(channel, prefix);
                    if (got < 8) {
                        break;
                    }
                    prefix.flip();
                    int length = prefix.getInt();
                    int crc = prefix.getInt();
                    if (length < 0 || length > MAX || channel.position() + length > channel.size()) {
                        break;
                    }
                    byte[] body = new byte[length];
                    ByteBuffer data = ByteBuffer.wrap(body);
                    if (readAvailable(channel, data) < length) {
                        break;
                    }
                    CRC32C computed = new CRC32C();
                    computed.update(body);
                    if ((int) computed.getValue() != crc) {
                        break;
                    }
                    frames.add(body);
                }
                return frames;
            } catch (IOException ex) {
                throw new AtpException(AtpErrorCode.LEDGER_TAMPERED, "WAL read failed: " + ex.getMessage());
            }
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ex) {
            throw new AtpException(AtpErrorCode.INVALID, "WAL close failed: " + ex.getMessage());
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("short header");
            }
        }
    }

    private static int readAvailable(FileChannel channel, ByteBuffer buffer) throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }
}
