package dev.hyprdroid.launcher.external;

import android.os.SharedMemory;

oneway interface IExternalLuaReply {
    void render(long id, int request, in SharedMemory frame);
    void error(long id, int request, int code);
}
