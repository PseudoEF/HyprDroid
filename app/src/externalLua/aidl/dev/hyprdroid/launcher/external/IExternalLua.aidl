package dev.hyprdroid.launcher.external;

import android.os.SharedMemory;
import dev.hyprdroid.launcher.external.IExternalLuaReply;

oneway interface IExternalLua {
    void createSession(long id, int apiVersion, in SharedMemory source, in SharedMemory state, IExternalLuaReply reply);
    void dispatchEvent(long id, int request, int event);
    void disposeSession(long id);
    void createSessionV2(long id, int wireVersion, int capabilities, int eventKind, in SharedMemory source, in SharedMemory context, IExternalLuaReply reply);
}
