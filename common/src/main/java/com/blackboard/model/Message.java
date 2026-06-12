package com.blackboard.model;

public class Message {

    private String cmd;
    private Object data;
    private long timestamp;

    public Message() {
    }

    public Message(String cmd, Object data) {
        this.cmd = cmd;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public String getCmd() {
        return cmd;
    }

    public void setCmd(String cmd) {
        this.cmd = cmd;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}