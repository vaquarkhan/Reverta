package org.atp.log;

import org.atp.ledger.LedgerEntry;
import org.atp.model.Types.AtpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalTest {
    @TempDir
    Path dir;

    @Test
    void fileJournalRejectsTornAndUnreadableAnchors() throws Exception {
        Path wal = dir.resolve("wal.bin");
        FrameLog opened = FrameLog.open(wal);
        opened.appendAll(List.of("S{}".getBytes(), new byte[0]));
        opened.close();
        Files.write(dir.resolve("not-a-dir"), new byte[] {1});
        assertThrows(AtpException.class, () -> FrameLog.open(dir.resolve("not-a-dir").resolve("child")));
        Path shortFile = dir.resolve("short.bin");
        Files.write(shortFile, new byte[] {1});
        assertThrows(AtpException.class, () -> FrameLog.open(shortFile));

        FrameLog tail = FrameLog.open(dir.resolve("tail.bin"));
        tail.close();
        appendRaw(dir.resolve("tail.bin"), new byte[] {1, 2, 3});
        assertTrue(FrameLog.open(dir.resolve("tail.bin")).readAll().isEmpty());

        FrameLog negative = FrameLog.open(dir.resolve("neg.bin"));
        negative.close();
        ByteBuffer badLen = ByteBuffer.allocate(8);
        badLen.putInt(-1);
        badLen.putInt(0);
        appendRaw(dir.resolve("neg.bin"), badLen.array());
        assertTrue(FrameLog.open(dir.resolve("neg.bin")).readAll().isEmpty());

        FrameLog crc = FrameLog.open(dir.resolve("crc.bin"));
        crc.close();
        ByteBuffer badCrc = ByteBuffer.allocate(9);
        badCrc.putInt(1);
        badCrc.putInt(1);
        badCrc.put((byte) 'A');
        appendRaw(dir.resolve("crc.bin"), badCrc.array());
        assertTrue(FrameLog.open(dir.resolve("crc.bin")).readAll().isEmpty());

        FrameLog closed = FrameLog.open(dir.resolve("closed.bin"));
        closed.close();
        assertThrows(AtpException.class, closed::readAll);
        ScriptedChannel boom = new ScriptedChannel(4, true);
        FrameLog adopted = FrameLog.adopt(boom);
        assertThrows(AtpException.class, adopted::close);
        FrameLog tiny = FrameLog.adopt(new ScriptedChannel(2, false));
        assertTrue(tiny.readAll().isEmpty());
        assertTrue(FrameLog.adopt(new ScriptedChannel(20, false)).readAll().isEmpty());
        assertTrue(FrameLog.adopt(new NegativeChannel()).readAll().isEmpty());
        Path emptyDir = dir.resolve("empty-frame");
        Files.createDirectories(emptyDir);
        FrameLog empty = FrameLog.open(emptyDir.resolve("wal.bin"));
        empty.appendAll(List.of(new byte[0]));
        empty.close();
        assertTrue(new FileDurableLog(emptyDir).load().ledger().isEmpty());
        FrameLog shortBody = FrameLog.adopt(new ShortReadChannel());
        assertTrue(shortBody.readAll().isEmpty());

        Files.createDirectory(dir.resolve("anchor-dir"));
        Files.createDirectory(dir.resolve("anchor-dir").resolve("wal.anchor"));
        FileDurableLog blocked = new FileDurableLog(dir.resolve("anchor-dir"));
        assertThrows(AtpException.class, () -> blocked.writeAnchor("abc"));
        assertThrows(AtpException.class, blocked::load);

        MemoryLog memory = new MemoryLog();
        LedgerEntry entry = LedgerEntry.seal(1, "tx", "step", "0".repeat(64), "h", "t", Map.of(), null, null, "NOT_APPLICABLE", "INIT");
        memory.persist(Map.of("txId", "tx"), entry);
        memory.anchor(entry.entryHash);
        assertEquals(entry.entryHash, memory.load().anchor());

        JdbcDurableLog jdbc = new JdbcDurableLog("jdbc:h2:mem:waltest;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc.persist(Map.of("txId", "tx"), entry);
        jdbc.anchor("f".repeat(64));
        assertEquals("f".repeat(64), jdbc.load().anchor());
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:h2:mem:waltest", "sa", "")) {
            connection.createStatement().execute("INSERT INTO atp_record (seq, kind, body) VALUES (99, 'Z', '{}')");
        }
        assertThrows(AtpException.class, jdbc::load);
        assertThrows(AtpException.class, () -> new JdbcDurableLog("jdbc:not-a-driver:x", "sa", ""));
    }

    private static void appendRaw(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.position(channel.size());
            channel.write(ByteBuffer.wrap(bytes));
        }
    }

    private static class ScriptedChannel extends FileChannel {
        private final long size;
        private final boolean failClose;
        private long position = 4;

        ScriptedChannel(long size, boolean failClose) {
            this.size = size;
            this.failClose = failClose;
        }

        @Override
        public int read(ByteBuffer dst) {
            return -1;
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) {
            return -1;
        }

        @Override
        public int read(ByteBuffer dst, long position) {
            return -1;
        }

        @Override
        public int write(ByteBuffer src) {
            return src.remaining();
        }

        @Override
        public int write(ByteBuffer src, long position) {
            return src.remaining();
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) {
            return 0;
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public FileChannel position(long newPosition) {
            this.position = newPosition;
            return this;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public FileChannel truncate(long size) {
            return this;
        }

        @Override
        public void force(boolean metaData) {
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) {
            return 0;
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) {
            return 0;
        }

        @Override
        public java.nio.MappedByteBuffer map(MapMode mode, long position, long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) {
            return null;
        }

        @Override
        protected void implCloseChannel() throws IOException {
            if (failClose) {
                throw new IOException("close failed");
            }
        }
    }

    private static final class NegativeChannel extends ScriptedChannel {
        NegativeChannel() {
            super(20, false);
        }

        @Override
        public int read(ByteBuffer dst) {
            if (position() == 4 && dst.remaining() >= 8) {
                dst.putInt(100);
                dst.putInt(0);
                position(12);
                return 8;
            }
            return -1;
        }
    }

    private static final class ShortReadChannel extends ScriptedChannel {
        ShortReadChannel() {
            super(20, false);
        }

        @Override
        public int read(ByteBuffer dst) {
            if (position() == 4 && dst.remaining() >= 8) {
                dst.putInt(4);
                dst.putInt(0);
                position(12);
                return 8;
            }
            return -1;
        }
    }
}
