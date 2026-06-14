package com.remoteterm.terminal;

public interface PtyOutputListener {
    void onOutput(String base64Data);
    void onExit();
}
