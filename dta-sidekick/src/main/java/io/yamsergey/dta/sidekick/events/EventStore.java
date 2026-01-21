package io.yamsergey.dta.sidekick.events;

import android.content.Context;
import io.yamsergey.dta.sidekick.SidekickLog;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Binary event store for efficient append-only event capture.
 *
 * <p>Events are written to a binary file using MessagePack format for compact,
 * fast serialization. The file format is:</p>
 *
 * <pre>
 * [4-byte magic: "ADTE"]
 * [4-byte version: 1]
 * [4-byte record count]
 * [4-byte reserved]
 * [record 1]
 * [record 2]
 * ...
 *
 * Each record:
 * [4-byte length]
 * [MessagePack encoded EventRecord]
 * </pre>
 *
 * <p>Thread-safe for concurrent writes.</p>
 */
public final class EventStore {

    private static final String TAG = "EventStore";
    private static final String FILE_NAME = "events.bin";
    private static final int HEADER_SIZE = 16;
    private static final byte[] MAGIC = {'A', 'D', 'T', 'E'};
    private static final int VERSION = 1;
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    private static volatile EventStore instance;

    private final File eventFile;
    private final Object writeLock = new Object();
    private volatile int recordCount = 0;
    private volatile boolean initialized = false;

    private EventStore(Context context) {
        File cacheDir = context.getCacheDir();
        this.eventFile = new File(cacheDir, FILE_NAME);
    }

    /**
     * Gets the singleton instance, initializing if needed.
     */
    public static EventStore getInstance(Context context) {
        if (instance == null) {
            synchronized (EventStore.class) {
                if (instance == null) {
                    instance = new EventStore(context.getApplicationContext());
                    instance.initialize();
                }
            }
        }
        return instance;
    }

    /**
     * Gets the instance if already initialized, or null otherwise.
     */
    public static EventStore getInstanceOrNull() {
        return instance;
    }

    private void initialize() {
        synchronized (writeLock) {
            try {
                if (!eventFile.exists()) {
                    // Create new file with header
                    writeHeader();
                    recordCount = 0;
                } else if (eventFile.length() > MAX_FILE_SIZE) {
                    // Rotate file if too large
                    rotate();
                } else {
                    // Read existing record count from header
                    recordCount = readRecordCount();
                }
                initialized = true;
                SidekickLog.i(TAG, "EventStore initialized: " + eventFile.getAbsolutePath() +
                        " (" + recordCount + " records)");
            } catch (IOException e) {
                SidekickLog.e(TAG, "Failed to initialize EventStore", e);
            }
        }
    }

    /**
     * Records an event using the provided adapter.
     *
     * @param adapter the adapter to use for conversion
     * @param source  the source object to record
     * @param <T>     the type of the source object
     */
    public <T> void record(EventAdapter<T> adapter, T source) {
        if (!initialized) {
            SidekickLog.w(TAG, "EventStore not initialized, skipping event");
            return;
        }

        try {
            EventRecord record = adapter.toRecord(source);
            writeRecord(record);
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to record event", e);
        }
    }

    /**
     * Records an event record directly.
     */
    public void record(EventRecord record) {
        if (!initialized) {
            SidekickLog.w(TAG, "EventStore not initialized, skipping event");
            return;
        }

        try {
            writeRecord(record);
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to record event", e);
        }
    }

    private void writeRecord(EventRecord record) throws IOException {
        byte[] data = serializeRecord(record);

        synchronized (writeLock) {
            // Check if rotation needed
            if (eventFile.length() + data.length + 4 > MAX_FILE_SIZE) {
                rotate();
            }

            try (FileOutputStream fos = new FileOutputStream(eventFile, true)) {
                // Write length prefix
                ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
                lengthBuffer.putInt(data.length);
                fos.write(lengthBuffer.array());

                // Write data
                fos.write(data);
            }

            recordCount++;
            updateRecordCount();
        }
    }

    private byte[] serializeRecord(EventRecord record) throws IOException {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packMapHeader(5);

            // id
            packer.packString("id");
            packer.packString(record.getId());

            // timestamp
            packer.packString("ts");
            packer.packLong(record.getTimestamp());

            // type
            packer.packString("type");
            packer.packByte(record.getType().getCode());

            // tag
            packer.packString("tag");
            packer.packString(record.getTag());

            // payload
            packer.packString("data");
            packMap(packer, record.getPayload());

            return packer.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private void packMap(MessageBufferPacker packer, Map<String, Object> map) throws IOException {
        if (map == null) {
            packer.packNil();
            return;
        }

        packer.packMapHeader(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            packer.packString(entry.getKey());
            packValue(packer, entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private void packValue(MessageBufferPacker packer, Object value) throws IOException {
        if (value == null) {
            packer.packNil();
        } else if (value instanceof String) {
            packer.packString((String) value);
        } else if (value instanceof Integer) {
            packer.packInt((Integer) value);
        } else if (value instanceof Long) {
            packer.packLong((Long) value);
        } else if (value instanceof Double) {
            packer.packDouble((Double) value);
        } else if (value instanceof Float) {
            packer.packFloat((Float) value);
        } else if (value instanceof Boolean) {
            packer.packBoolean((Boolean) value);
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            packer.packBinaryHeader(bytes.length);
            packer.writePayload(bytes);
        } else if (value instanceof Map) {
            packMap(packer, (Map<String, Object>) value);
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            packer.packArrayHeader(list.size());
            for (Object item : list) {
                packValue(packer, item);
            }
        } else {
            // Fallback to string representation
            packer.packString(String.valueOf(value));
        }
    }

    private void writeHeader() throws IOException {
        try (FileOutputStream fos = new FileOutputStream(eventFile)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
            header.put(MAGIC);
            header.putInt(VERSION);
            header.putInt(0); // record count
            header.putInt(0); // reserved
            fos.write(header.array());
        }
    }

    private void updateRecordCount() {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(eventFile, "rw")) {
            raf.seek(8); // Skip magic and version
            raf.writeInt(recordCount);
        } catch (IOException e) {
            SidekickLog.e(TAG, "Failed to update record count", e);
        }
    }

    private int readRecordCount() throws IOException {
        if (!eventFile.exists() || eventFile.length() < HEADER_SIZE) {
            return 0;
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(eventFile, "r")) {
            raf.seek(8); // Skip magic and version
            return raf.readInt();
        }
    }

    private void rotate() throws IOException {
        SidekickLog.i(TAG, "Rotating event file (size: " + eventFile.length() + " bytes)");

        // Delete old backup if exists
        File backup = new File(eventFile.getParent(), FILE_NAME + ".old");
        if (backup.exists()) {
            backup.delete();
        }

        // Rename current to backup
        if (eventFile.exists()) {
            eventFile.renameTo(backup);
        }

        // Create new file
        writeHeader();
        recordCount = 0;
    }

    /**
     * Clears all recorded events.
     */
    public void clear() {
        synchronized (writeLock) {
            try {
                writeHeader();
                recordCount = 0;
                SidekickLog.i(TAG, "EventStore cleared");
            } catch (IOException e) {
                SidekickLog.e(TAG, "Failed to clear EventStore", e);
            }
        }
    }

    /**
     * Returns the number of recorded events.
     */
    public int getRecordCount() {
        return recordCount;
    }

    /**
     * Returns the path to the event file.
     */
    public String getFilePath() {
        return eventFile.getAbsolutePath();
    }

    /**
     * Returns the size of the event file in bytes.
     */
    public long getFileSize() {
        return eventFile.exists() ? eventFile.length() : 0;
    }
}
