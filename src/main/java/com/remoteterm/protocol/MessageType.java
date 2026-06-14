package com.remoteterm.protocol;

public final class MessageType {

    private MessageType() {}

    // Terminal
    public static final String TERM_INIT = "term.init";
    public static final String TERM_OUTPUT = "term.output";
    public static final String TERM_INPUT = "term.input";
    public static final String TERM_RESIZE = "term.resize";
    public static final String TERM_REPLAY = "term.replay";

    // File system
    public static final String FS_LIST = "fs.list";
    public static final String FS_READ = "fs.read";
    public static final String FS_WRITE = "fs.write";
    public static final String FS_DELETE = "fs.delete";
    public static final String FS_MKDIR = "fs.mkdir";
    public static final String FS_RESULT = "fs.result";

    // Auth
    public static final String AUTH = "auth";
}
