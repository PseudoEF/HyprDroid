/* Version 2 uses only bounded UTF-8 tokens and Lua scalars. All calls run under lua_pcall. */
typedef struct { const unsigned char *bytes; size_t remaining; } Reader;

static void push_token(lua_State *L, Reader *in) {
    if (in->remaining < 2) luaL_error(L, "short context");
    size_t size = ((size_t)in->bytes[0] << 8) | in->bytes[1];
    if (size > 512 || size > in->remaining - 2) luaL_error(L, "invalid context token");
    lua_pushlstring(L, (const char *)in->bytes + 2, size);
    in->bytes += size + 2;
    in->remaining -= size + 2;
}

static const char *strict_string(lua_State *L, int index, size_t limit) {
    size_t n;
    if (lua_type(L, index) != LUA_TSTRING) luaL_error(L, "string required");
    const char *value = lua_tolstring(L, index, &n);
    if (n > limit || strlen(value) != n) luaL_error(L, "invalid string");
    return value;
}

static void scalar(lua_State *L, int index) {
    if (lua_type(L, index) == LUA_TBOOLEAN) {
        token(L, "b", 1);
        token(L, lua_toboolean(L, index) ? "1" : "0", 1);
    } else {
        int type = lua_type(L, index);
        if (type != LUA_TSTRING && type != LUA_TNUMBER) luaL_error(L, "invalid state type");
        if (type == LUA_TNUMBER && !isfinite(lua_tonumber(L, index))) luaL_error(L, "invalid number");
        lua_pushvalue(L, index);
        size_t n;
        const char *value = lua_tolstring(L, -1, &n);
        if (n > 128) luaL_error(L, "scalar too long");
        token(L, type == LUA_TSTRING ? "s" : "n", 1);
        token(L, value, n);
        lua_pop(L, 1);
    }
}

static void read_scalar(lua_State *L, Reader *in) {
    push_token(L, in);
    const char *kind = strict_string(L, -1, 1);
    char type = *kind;
    lua_pop(L, 1);
    push_token(L, in);
    if (type == 's') return;
    if (type == 'b') {
        const char *value = strict_string(L, -1, 1);
        if (strcmp(value, "0") && strcmp(value, "1")) luaL_error(L, "invalid boolean");
        int flag = *value == '1';
        lua_pop(L, 1);
        lua_pushboolean(L, flag);
        return;
    }
    int valid;
    lua_Number number = lua_tonumberx(L, -1, &valid);
    if (type != 'n' || !valid || !isfinite(number)) luaL_error(L, "invalid scalar");
    lua_pop(L, 1);
    lua_pushnumber(L, number);
}

static int state_get_v2(lua_State *L) {
    strict_string(L, 1, 32);
    lua_getfield(L, LUA_REGISTRYINDEX, "state.v2");
    lua_pushvalue(L, 1);
    lua_rawget(L, -2);
    if (lua_isnil(L, -1)) lua_pushvalue(L, lua_gettop(L) >= 4 ? 2 : -1);
    return 1;
}

static int state_set_v2(lua_State *L) {
    if (!(runtime(L)->capabilities & 1)) return luaL_error(L, "state denied");
    strict_string(L, 1, 32);
    int type = lua_type(L, 2);
    if (type != LUA_TBOOLEAN && type != LUA_TNUMBER && type != LUA_TSTRING) return luaL_error(L, "invalid state value");
    lua_getfield(L, LUA_REGISTRYINDEX, "state.v2");
    lua_pushvalue(L, 1);
    lua_pushvalue(L, 2);
    lua_rawset(L, -3);
    return 0;
}

static int system_get_v2(lua_State *L) {
    const char *key = strict_string(L, 1, 24);
    if (strcmp(key, "device.time") && strcmp(key, "battery.level") && strcmp(key, "battery.charging")) {
        return luaL_error(L, "unknown system field");
    }
    lua_getfield(L, LUA_REGISTRYINDEX, "system.v2");
    lua_getfield(L, -1, key);
    return 1;
}

static int timer_every_v2(lua_State *L) {
    Runtime *r = runtime(L);
    const char *id = strict_string(L, 1, 32);
    if (!lua_isinteger(L, 2)) return luaL_error(L, "timer interval required");
    lua_Integer interval = lua_tointeger(L, 2);
    if (!*id || interval < 1000 || interval > 86400000) return luaL_error(L, "invalid timer");
    for (unsigned i = 0; i < r->timer_count; i++) {
        if (!strcmp(r->timers[i].id, id)) return luaL_error(L, "duplicate timer");
    }
    if (r->timer_count >= 2) return luaL_error(L, "too many timers");
    strcpy(r->timers[r->timer_count].id, id);
    r->timers[r->timer_count++].ms = interval;
    return 0;
}

static const char *event_name(int event) {
    static const char *names[] = {"", "onClick", "onChange", "onSubmit", "onTimer", "onResume"};
    return names[event];
}

static int register_v2(lua_State *L) {
    const char *kind = strict_string(L, 1, 8);
    const char *id = strict_string(L, 2, 32);
    int valid = 0;
    for (int i = 1; i <= 5; i++) valid |= !strcmp(kind, event_name(i));
    if (!valid || (strcmp(kind, "onResume") ? !*id : *id)) return luaL_error(L, "invalid handler");
    luaL_checktype(L, 3, LUA_TFUNCTION);
    lua_pushfstring(L, "event:%s:%s", kind, id);
    lua_pushvalue(L, 3);
    lua_rawset(L, LUA_REGISTRYINDEX);
    return 0;
}

static void init_context_v2(lua_State *L, Reader *in) {
    Runtime *r = runtime(L);
    push_token(L, in);
    const char *count = strict_string(L, -1, 2);
    char *end;
    long total = strtol(count, &end, 10);
    if (!*count || *end || total < 0 || total > 16 || (!(r->capabilities & 1) && total)) luaL_error(L, "invalid state count");
    lua_pop(L, 1);
    lua_newtable(L);
    for (long i = 0; i < total; i++) {
        push_token(L, in);
        strict_string(L, -1, 32);
        read_scalar(L, in);
        lua_rawset(L, -3);
    }
    lua_setfield(L, LUA_REGISTRYINDEX, "state.v2");
    lua_newtable(L);
    const char *clock[] = {"date", "time24", "time12"};
    lua_newtable(L);
    for (int i = 0; i < 3; i++) {
        push_token(L, in);
        const char *text = strict_string(L, -1, 128);
        if (*text && (r->capabilities & 2)) lua_setfield(L, -2, clock[i]); else lua_pop(L, 1);
    }
    lua_pushnil(L);
    if (lua_next(L, -2)) {
        lua_pop(L, 2);
        lua_setfield(L, -2, "device.time");
    } else lua_pop(L, 1);
    push_token(L, in);
    const char *level = strict_string(L, -1, 3);
    if (*level && (r->capabilities & 4)) {
        lua_Integer number = lua_tointeger(L, -1);
        if (!lua_isnumber(L, -1) || number < 0 || number > 100) luaL_error(L, "invalid battery level");
        lua_pop(L, 1);
        lua_pushinteger(L, number);
        lua_setfield(L, -2, "battery.level");
    } else lua_pop(L, 1);
    push_token(L, in);
    const char *charging = strict_string(L, -1, 1);
    if (*charging && (r->capabilities & 4)) {
        if (strcmp(charging, "0") && strcmp(charging, "1")) luaL_error(L, "invalid charge status");
        int flag = *charging == '1';
        lua_pop(L, 1);
        lua_pushboolean(L, flag);
        lua_setfield(L, -2, "battery.charging");
    } else lua_pop(L, 1);
    lua_setfield(L, LUA_REGISTRYINDEX, "system.v2");
}

static int execute_v2(lua_State *L) {
    Runtime *r = runtime(L);
    Reader in = {(const unsigned char *)r->state, r->state_len};
    init_context_v2(L, &in);
    static const luaL_Reg ui[] = {{"render", render}, {"on", register_v2}, {NULL, NULL}};
    static const luaL_Reg state[] = {{"get", state_get_v2}, {"set", state_set_v2}, {NULL, NULL}};
    static const luaL_Reg system[] = {{"get", system_get_v2}, {NULL, NULL}};
    static const luaL_Reg timer[] = {{"every", timer_every_v2}, {NULL, NULL}};
    luaL_newlib(L, ui);
    lua_pushvalue(L, -1);
    lua_setglobal(L, "ui");
    lua_newtable(L);
    lua_insert(L, -2);
    lua_setfield(L, -2, "ui");
    lua_setglobal(L, "hypr");
    luaL_newlib(L, state); lua_setglobal(L, "state");
    luaL_newlib(L, system); lua_setglobal(L, "system");
    luaL_newlib(L, timer); lua_setglobal(L, "timer");
    r->used = 2;
    if (luaL_loadbufferx(L, (const char *)r->source, r->source_len, "@entry.lua", "t") != LUA_OK) return lua_error(L);
    lua_call(L, 0, 0);
    push_token(L, &in);
    const char *id = strict_string(L, -1, 32);
    lua_pushfstring(L, "event:%s:%s", event_name(r->event), id);
    lua_rawget(L, LUA_REGISTRYINDEX);
    if (lua_isfunction(L, -1) && r->event) {
        lua_newtable(L);
        lua_pushvalue(L, -3); lua_setfield(L, -2, "id");
        if (r->event == 2 || r->event == 3) {
            read_scalar(L, &in);
            lua_setfield(L, -2, "value");
        }
        lua_call(L, 1, 0);
    } else if (r->event == 2 || r->event == 3) read_scalar(L, &in);
    if (in.remaining) return luaL_error(L, "trailing request");
    size_t ui_size = r->used - 2;
    r->frame[0] = (unsigned char)(ui_size >> 8);
    r->frame[1] = (unsigned char)ui_size;
    lua_getfield(L, LUA_REGISTRYINDEX, "state.v2");
    lua_pushnil(L);
    unsigned entries = 0;
    while (lua_next(L, -2)) {
        if (++entries > 16) return luaL_error(L, "too many state keys");
        const char *key = strict_string(L, -2, 32);
        token(L, key, strlen(key));
        scalar(L, -1);
        lua_pop(L, 1);
    }
    token(L, "", 0);
    for (unsigned i = 0; i < r->timer_count; i++) {
        token(L, r->timers[i].id, strlen(r->timers[i].id));
        lua_pushinteger(L, r->timers[i].ms);
        size_t n;
        const char *interval = lua_tolstring(L, -1, &n);
        token(L, interval, n);
        lua_pop(L, 1);
    }
    return 0;
}
