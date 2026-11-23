#include <assert.h>
#include <jni.h>
#include <lua.h>
#include <lauxlib.h>
#include <limits.h>
#include <math.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define ARENA (2u * 1024u * 1024u)
#define SOURCE_LIMIT 16384u
#define FRAME_LIMIT 16384u
#define ALIGNMENT _Alignof(max_align_t)

// All Lua allocation, including parsing and realloc moves, remains inside the same arena.
typedef union Block Block;
union Block {
    max_align_t alignment;
    struct { Block *next; size_t size; int free; } data;
};

typedef struct {
    Block *first;
    unsigned char frame[FRAME_LIMIT];
    size_t used;
    unsigned nodes;
    unsigned instructions;
    uint64_t cpu_started;
    const unsigned char *source;
    size_t source_len;
    const char *state;
    size_t state_len;
    int event;
    int api;
    int capabilities;
    unsigned timer_count;
    struct { char id[33]; lua_Integer ms; } timers[2];
} Runtime;

static size_t align_size(size_t value) {
    return (value + ALIGNMENT - 1) & ~(ALIGNMENT - 1);
}

static void *arena_alloc(void *opaque, void *ptr, size_t old, size_t size) {
    (void)old;
    Runtime *r = opaque;
    Block *previous = ptr ? (Block *)ptr - 1 : NULL;
    if (!size) {
        if (previous) previous->data.free = 1;
        return NULL;
    }
    if (size > ARENA - sizeof(Block)) return NULL;
    size_t aligned = align_size(size);
    if (previous && previous->data.size >= aligned) return ptr;
    for (Block *b = r->first; b; b = b->data.next) {
        while (b->data.free && b->data.next && b->data.next->data.free) {
            Block *next = b->data.next;
            b->data.size += sizeof(Block) + next->data.size;
            b->data.next = next->data.next;
        }
        if (!b->data.free || b->data.size < aligned) continue;
        if (b->data.size - aligned >= sizeof(Block) + ALIGNMENT) {
            Block *split = (Block *)((unsigned char *)(b + 1) + aligned);
            split->data.next = b->data.next;
            split->data.size = b->data.size - aligned - sizeof(Block);
            split->data.free = 1;
            b->data.next = split;
            b->data.size = aligned;
        }
        b->data.free = 0;
        if (previous) {
            memcpy(b + 1, ptr, previous->data.size);
            previous->data.free = 1;
        }
        return b + 1;
    }
    return NULL;
}

static Runtime *runtime(lua_State *L) {
    void *r;
    lua_getallocf(L, &r);
    return r;
}

static uint64_t cpu_time(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &now)) return UINT64_MAX;
    return (uint64_t)now.tv_sec * 1000000000u + (uint64_t)now.tv_nsec;
}

static void hook(lua_State *L, lua_Debug *frame) {
    (void)frame;
    Runtime *r = runtime(L);
    r->instructions += 250;
    if (r->instructions >= 50000 || cpu_time() - r->cpu_started >= 100000000u) {
        luaL_error(L, "instruction or CPU budget exceeded");
    }
}

static void token(lua_State *L, const char *value, size_t length) {
    Runtime *r = runtime(L);
    if (length > 1024 || r->used + 2 + length > FRAME_LIMIT) luaL_error(L, "UI limit");
    r->frame[r->used++] = (unsigned char)(length >> 8);
    r->frame[r->used++] = (unsigned char)length;
    memcpy(r->frame + r->used, value, length);
    r->used += length;
}

static const char *field(lua_State *L, int table, const char *key, size_t *size) {
    lua_pushstring(L, key);
    lua_rawget(L, table);
    if (lua_type(L, -1) != LUA_TSTRING) luaL_error(L, "string field required");
    return lua_tolstring(L, -1, size);
}

static void node(lua_State *L, int table, unsigned depth) {
    Runtime *r = runtime(L);
    if (depth > 6 || ++r->nodes > 48 || !lua_istable(L, table) || !lua_checkstack(L, 8)) luaL_error(L, "invalid UI");
    size_t n;
    const char *kind = field(L, table, "type", &n);
    if (n > 16 || strlen(kind) != n) luaL_error(L, "invalid node type");
    token(L, kind, n);
    if (!strcmp(kind, "text") || !strcmp(kind, "button")) {
        int button = !strcmp(kind, "button");
        if (button) {
            const char *id = field(L, table, "id", &n);
            if (n > 32) luaL_error(L, "invalid button ID");
            token(L, id, n);
            lua_pop(L, 1);
        }
        const char *text = field(L, table, button ? "text" : "value", &n);
        token(L, text, n);
        lua_pop(L, 1);
    } else if (!strcmp(kind, "spacer") || !strcmp(kind, "progress")) {
        lua_pushstring(L, !strcmp(kind, "spacer") ? "size" : "value");
        lua_rawget(L, table);
        if (lua_type(L, -1) != LUA_TNUMBER) luaL_error(L, "number required");
        const char *value = lua_tolstring(L, -1, &n);
        token(L, value, n);
        lua_pop(L, 1);
    } else if (r->api == 2 && !strcmp(kind, "divider")) {
        /* Divider has no payload. */
    } else if (r->api == 2 && !strcmp(kind, "icon")) {
        const char *name = field(L, table, "glyph", &n);
        token(L, name, n);
        lua_pop(L, 1);
    } else if (r->api == 2 && (!strcmp(kind, "switch") || !strcmp(kind, "textInput"))) {
        const char *id = field(L, table, "id", &n);
        token(L, id, n);
        lua_pop(L, 1);
        if (!strcmp(kind, "switch")) {
            lua_pushliteral(L, "value");
            lua_rawget(L, table);
            if (!lua_isboolean(L, -1)) luaL_error(L, "boolean required");
            int value = lua_toboolean(L, -1);
            token(L, value ? "true" : "false", value ? 4 : 5);
        } else {
            const char *value = field(L, table, "value", &n);
            token(L, value, n);
        }
        lua_pop(L, 1);
    } else if (!strcmp(kind, "row") || !strcmp(kind, "column") || !strcmp(kind, "card") ||
               !strcmp(kind, "scrollableColumn") || (r->api == 2 && !strcmp(kind, "list"))) {
        lua_pushliteral(L, "children");
        lua_rawget(L, table);
        if (!lua_istable(L, -1)) luaL_error(L, "children required");
        int children = lua_gettop(L);
        size_t count = lua_rawlen(L, children);
        if (count > 48) luaL_error(L, "too many children");
        lua_pushinteger(L, (lua_Integer)count);
        const char *number = lua_tolstring(L, -1, &n);
        token(L, number, n);
        lua_pop(L, 1);
        for (size_t i = 1; i <= count; ++i) {
            lua_rawgeti(L, children, (lua_Integer)i);
            node(L, lua_gettop(L), depth + 1);
            lua_pop(L, 1);
        }
        lua_pop(L, 1);
    } else luaL_error(L, "unknown node type");
    lua_pop(L, 1);
}

static int render(lua_State *L) {
    Runtime *r = runtime(L);
    r->used = r->api == 2 ? 2 : 0;
    r->nodes = 0;
    node(L, 1, 1);
    return 0;
}

static int register_event(lua_State *L) {
    const char *name = luaL_checkstring(L, 1);
    luaL_checktype(L, 2, LUA_TFUNCTION);
    if (strcmp(name, "increment") && strcmp(name, "reset")) return luaL_error(L, "unknown event");
    lua_pushvalue(L, 2);
    lua_setfield(L, LUA_REGISTRYINDEX, name);
    return 0;
}

static int get_state(lua_State *L) {
    lua_getfield(L, LUA_REGISTRYINDEX, "hypr.state");
    return 1;
}

static int set_state(lua_State *L) {
    size_t n;
    if (lua_type(L, 1) != LUA_TSTRING) return luaL_error(L, "invalid state");
    lua_tolstring(L, 1, &n);
    if (n > 512) return luaL_error(L, "state too large");
    lua_pushvalue(L, 1);
    lua_setfield(L, LUA_REGISTRYINDEX, "hypr.state");
    return 0;
}

// Scope: the one pinned Hello Tile release. No global coercion of other values.
static int hello_add(lua_State *L) {
    size_t n;
    if (lua_type(L, 1) != LUA_TSTRING || !lua_isinteger(L, 2)) return luaL_error(L, "invalid counter");
    const char *s = lua_tolstring(L, 1, &n);
    lua_Integer a = 0, b = lua_tointeger(L, 2);
    if (!n || n > 19 || b < 0) return luaL_error(L, "invalid counter");
    for (size_t i = 0; i < n; ++i) {
        int digit = s[i] - '0';
        if (digit < 0 || digit > 9 || a > (LUA_MAXINTEGER - digit) / 10) {
            return luaL_error(L, "invalid counter");
        }
        a = a * 10 + digit;
    }
    if (a > LUA_MAXINTEGER - b) return luaL_error(L, "counter overflow");
    lua_pushinteger(L, a + b);
    return 1;
}

// All operations that can longjmp are called underneath lua_pcall in the same C frame.
static int execute(lua_State *L) {
    Runtime *r = runtime(L);
    static const luaL_Reg ui[] = {
        {"on", register_event}, {"render", render}, {"getState", get_state}, {"setState", set_state}, {NULL, NULL}
    };
    lua_newtable(L);
    luaL_newlib(L, ui);
    lua_setfield(L, -2, "ui");
    lua_setglobal(L, "hypr");
    lua_pushlstring(L, r->state, r->state_len);
    lua_setfield(L, LUA_REGISTRYINDEX, "hypr.state");
    lua_pushliteral(L, "");
    lua_newtable(L);
    lua_pushcfunction(L, hello_add);
    lua_setfield(L, -2, "__add");
    lua_setmetatable(L, -2);
    lua_pop(L, 1);
    if (luaL_loadbufferx(L, (const char *)r->source, r->source_len, "@entry.lua", "t") != LUA_OK) {
        return lua_error(L);
    }
    lua_call(L, 0, 0);
    if (r->event) {
        lua_getfield(L, LUA_REGISTRYINDEX, r->event == 1 ? "increment" : "reset");
        lua_call(L, 0, 0);
    }
    lua_getfield(L, LUA_REGISTRYINDEX, "hypr.state");
    size_t n;
    const char *state = lua_tolstring(L, -1, &n);
    token(L, state, n);
    return 0;
}

#include "runtime_v2.inc"

static int deadline(timer_t *timer) {
    struct sigevent event = {.sigev_notify = SIGEV_SIGNAL, .sigev_signo = SIGKILL};
    struct itimerspec limit = {.it_value = {.tv_sec = 1}};
    if (timer_create(CLOCK_MONOTONIC, &event, timer)) return 0;
    if (!timer_settime(*timer, 0, &limit, NULL)) return 1;
    timer_delete(*timer);
    return 0;
}

static jbyteArray run(JNIEnv *env, jbyteArray input, jbyteArray state, jint event, int api, int capabilities) {
    jsize n = (*env)->GetArrayLength(env, input), state_len = (*env)->GetArrayLength(env, state);
    if (n <= 0 || n > (jsize) SOURCE_LIMIT || state_len > (api == 2 ? 2048 : 512) || event < 0 || event > (api == 2 ? 5 : 2) || capabilities < 0 || capabilities > 7) return NULL;
    timer_t timer;
    if (!deadline(&timer)) return NULL;
    unsigned char source[SOURCE_LIMIT];
    char saved[2048];
    (*env)->GetByteArrayRegion(env, input, 0, n, (jbyte *)source);
    (*env)->GetByteArrayRegion(env, state, 0, state_len, (jbyte *)saved);
    jbyteArray result = NULL;
    if (!(*env)->ExceptionCheck(env)) {
        // Only in this opt-in non-distributable worker. The launcher never admits this
        // fixture as package source; instrumentation uses it to verify actual Binder death.
        static const char watchdog_fixture[] = "-- hypr-test-force-kernel-timeout";
        if ((size_t)n == sizeof(watchdog_fixture) - 1 &&
            !memcmp(source, watchdog_fixture, sizeof(watchdog_fixture) - 1)) {
            for (;;) pause();
        }
        Runtime r = {.source = source, .source_len = (size_t)n, .state = saved,
                     .state_len = (size_t)state_len, .event = event, .api = api, .capabilities = capabilities, .cpu_started = cpu_time()};
        r.first = malloc(ARENA);
        int status = LUA_ERRMEM;
        if (r.first) {
            *r.first = (Block){.data = {.size = ARENA - sizeof(Block), .free = 1}};
            lua_State *L = lua_newstate(arena_alloc, &r);
            if (L) {
                lua_sethook(L, hook, LUA_MASKCOUNT, 250);
                lua_pushcfunction(L, api == 2 ? execute_v2 : execute);
                status = lua_pcall(L, 0, 0, 0);
                lua_close(L);
            }
            free(r.first);
        }
        jbyte code = status == LUA_OK ? 0 : status == LUA_ERRMEM ? 1 : 2;
        jsize size = code ? 1 : (jsize)r.used + 1;
        result = (*env)->NewByteArray(env, size);
        if (result) {
            (*env)->SetByteArrayRegion(env, result, 0, 1, &code);
            if (!code) (*env)->SetByteArrayRegion(env, result, 1, size - 1, (jbyte *)r.frame);
        }
    }
    if (timer_delete(timer)) raise(SIGKILL);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_hyprdroid_launcher_external_NativeLua_run(JNIEnv *env, jobject self, jbyteArray input,
                                                    jbyteArray state, jint event) {
    (void)self;
    return run(env, input, state, event, 1, 0);
}

JNIEXPORT jbyteArray JNICALL
Java_dev_hyprdroid_launcher_external_NativeLua_runV2(JNIEnv *env, jobject self, jbyteArray input,
                                                      jbyteArray context, jint event, jint capabilities) {
    (void)self;
    return run(env, input, context, event, 2, capabilities);
}
