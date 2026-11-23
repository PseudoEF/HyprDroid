local function refresh()
    local device = hypr.system.getDeviceInfo()
    local battery = hypr.system.getBattery()
    local children = {
        { type = "text", value = device.manufacturer .. " " .. device.model },
        { type = "text", value = "Android " .. device.release .. " (API " .. device.sdk .. ")" }
    }

    if battery.percent ~= nil then
        children[3] = { type = "text", value = "Батарея: " .. battery.percent .. "%" }
        children[4] = { type = "progress", value = battery.percent / 100 }
        children[5] = { type = "button", id = "refresh", text = "Refresh" }
    else
        children[3] = { type = "text", value = "Уровень батареи недоступен" }
        children[4] = { type = "button", id = "refresh", text = "Refresh" }
    end

    hypr.ui.render({ type = "scrollableColumn", children = children })
end

hypr.ui.on("refresh", refresh)
refresh()
