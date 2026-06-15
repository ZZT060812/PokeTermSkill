package com.remoteterm.terminal;

public interface PtyOutputListener {
    void onOutput(String plainText);
    void onExit();
}
