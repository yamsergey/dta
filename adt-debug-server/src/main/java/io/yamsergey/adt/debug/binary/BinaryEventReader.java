package io.yamsergey.adt.debug.binary;

import io.yamsergey.adt.debug.model.DebugEvent;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import org.msgpack.value.ValueType;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads binary events from the MessagePack-encoded event file.
 *
 * <p>File format:</p>
 * <pre>
 * [4-byte magic: "ADTE"]
 * [4-byte version: 1]
 * [4-byte record count]
 * [4-byte reserved]
 * [record 1: 4-byte length + MessagePack data]
 * [record 2: 4-byte length + MessagePack data]
 * ...
 * </pre>
 */
public class BinaryEventReader {

    private static final byte[] MAGIC = {'A', 'D', 'T', 'E'};
    private static final int HEADER_SIZE = 16;

    /**
     * Reads all events from the given binary file.
     */
    public List<DebugEvent> readEvents(File file) throws IOException {
        List<DebugEvent> events = new ArrayList<>();

        if (!file.exists() || file.length() < HEADER_SIZE) {
            return events;
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            // Read and validate header
            byte[] magic = new byte[4];
            raf.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new IOException("Invalid event file: bad magic bytes");
            }

            int version = raf.readInt();
            if (version != 1) {
                throw new IOException("Unsupported event file version: " + version);
            }

            int recordCount = raf.readInt();
            raf.skipBytes(4); // reserved

            // Read records
            for (int i = 0; i < recordCount && raf.getFilePointer() < raf.length(); i++) {
                int length = raf.readInt();
                byte[] data = new byte[length];
                raf.readFully(data);

                DebugEvent event = parseEvent(data);
                if (event != null) {
                    events.add(event);
                }
            }
        }

        return events;
    }

    /**
     * Reads events from offset (for incremental reads).
     */
    public List<DebugEvent> readEventsFromOffset(File file, long offset) throws IOException {
        List<DebugEvent> events = new ArrayList<>();

        if (!file.exists() || file.length() <= offset) {
            return events;
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);

            while (raf.getFilePointer() < raf.length()) {
                int length = raf.readInt();
                byte[] data = new byte[length];
                raf.readFully(data);

                DebugEvent event = parseEvent(data);
                if (event != null) {
                    events.add(event);
                }
            }
        }

        return events;
    }

    private DebugEvent parseEvent(byte[] data) {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data)) {
            int mapSize = unpacker.unpackMapHeader();

            String id = null;
            long timestamp = 0;
            String type = null;
            String tag = null;
            Map<String, Object> eventData = null;

            for (int i = 0; i < mapSize; i++) {
                String key = unpacker.unpackString();
                switch (key) {
                    case "id":
                        id = unpacker.unpackString();
                        break;
                    case "ts":
                        timestamp = unpacker.unpackLong();
                        break;
                    case "type":
                        type = typeCodeToString(unpacker.unpackByte());
                        break;
                    case "tag":
                        tag = unpacker.unpackString();
                        break;
                    case "data":
                        eventData = unpackMap(unpacker);
                        break;
                    default:
                        unpacker.skipValue();
                        break;
                }
            }

            return new DebugEvent(id, timestamp, type, tag, eventData);
        } catch (IOException e) {
            return null;
        }
    }

    private String typeCodeToString(byte code) {
        return switch (code) {
            case 0x01 -> "http";
            case 0x02 -> "websocket";
            case 0x03 -> "plain";
            default -> "custom";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unpackMap(MessageUnpacker unpacker) throws IOException {
        if (!unpacker.hasNext()) {
            return null;
        }

        Value value = unpacker.unpackValue();
        if (value.isNilValue()) {
            return null;
        }

        return (Map<String, Object>) convertValue(value);
    }

    private Object convertValue(Value value) {
        if (value.isNilValue()) {
            return null;
        } else if (value.isBooleanValue()) {
            return value.asBooleanValue().getBoolean();
        } else if (value.isIntegerValue()) {
            return value.asIntegerValue().toLong();
        } else if (value.isFloatValue()) {
            return value.asFloatValue().toDouble();
        } else if (value.isStringValue()) {
            return value.asStringValue().asString();
        } else if (value.isBinaryValue()) {
            return value.asBinaryValue().asByteArray();
        } else if (value.isArrayValue()) {
            List<Object> list = new ArrayList<>();
            for (Value item : value.asArrayValue()) {
                list.add(convertValue(item));
            }
            return list;
        } else if (value.isMapValue()) {
            Map<String, Object> map = new HashMap<>();
            value.asMapValue().entrySet().forEach(entry -> {
                String key = entry.getKey().asStringValue().asString();
                Object val = convertValue(entry.getValue());
                map.put(key, val);
            });
            return map;
        }
        return value.toString();
    }
}
