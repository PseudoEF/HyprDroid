local device = hypr.system.getDeviceInfo()
local battery = hypr.system.getBattery()
local batteryText = "недоступно"

if battery.percent ~= nil then
    batteryText = battery.percent .. "%"
    if battery.charging then
        batteryText = batteryText .. " (зарядка)"
    end
end

local text = device.manufacturer .. " " .. device.model
    .. "\nAndroid " .. device.release .. " (API " .. device.sdk .. ")"
    .. "\nБатарея: " .. batteryText

hypr.terminal.print("DroidFetch · Lua\n" .. text)

local window = hypr.ui.createWindow("DroidFetch · Lua")
hypr.ui.showText(window, text)
