package com.dennysesay.livescribe.scribe;

public enum ChannelState {
    IDLE("Idle"),
    CHECKING("Checking"),
    OFFLINE("Offline"),
    LIVE("Live"),
    RECORDING("Recording"),
    CONVERTING("Converting"),
    FINISHED("Finished"),
    ERROR("Error");

    private final String displayName;

    ChannelState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
