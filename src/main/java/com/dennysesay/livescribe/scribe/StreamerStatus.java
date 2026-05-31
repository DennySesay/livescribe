package com.dennysesay.livescribe.scribe;

import java.nio.file.Path;
import java.time.Instant;

public class StreamerStatus {
    private volatile ChannelState state = ChannelState.IDLE;
    private volatile Instant recordStartTime = null;
    private volatile Path activeFilePath = null;

    public ChannelState getState() {
        return state;
    }

    public void setState(ChannelState state) {
        this.state = state;
    }

    public Instant getRecordStartTime() {
        return recordStartTime;
    }

    public void setRecordStartTime(Instant recordStartTime) {
        this.recordStartTime = recordStartTime;
    }

    public Path getActiveFilePath() {
        return activeFilePath;
    }

    public void setActiveFilePath(Path activeFilePath) {
        this.activeFilePath = activeFilePath;
    }
}
