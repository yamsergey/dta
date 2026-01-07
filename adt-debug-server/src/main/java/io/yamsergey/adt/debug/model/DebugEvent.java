package io.yamsergey.adt.debug.model;

import java.util.Map;

/**
 * A debug event as returned by the REST API.
 */
public class DebugEvent {

    private String id;
    private long timestamp;
    private String type;
    private String tag;
    private Map<String, Object> data;

    public DebugEvent() {}

    public DebugEvent(String id, long timestamp, String type, String tag, Map<String, Object> data) {
        this.id = id;
        this.timestamp = timestamp;
        this.type = type;
        this.tag = tag;
        this.data = data;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}
