import pyvjoy
import json
import ssl
from aiohttp import web

device = pyvjoy.VJoyDevice(1)


def norm(i):
    return int( max(0, min(1, i)) * 0x7FFF) + 1


async def handler(req):
    ws = web.WebSocketResponse()
    await ws.prepare(req)
    async for msg in ws:
        d = json.loads(msg.data)
        device.set_axis(pyvjoy.HID_USAGE_X, norm((d["steer"] + 1)/2))
        device.set_axis(pyvjoy.HID_USAGE_Y, norm((d["gas"] + 1)))
        device.set_axis(pyvjoy.HID_USAGE_Z, norm((d["brake"] + 1)))
    return ws

async def index(req):
    return web.FileResponse("wheel.html")

app = web.Application()
app.add_routes([web.get("/", index), web.get("/", handler)])

ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain("cert.pem", "key.pem")
web.run_app(app, port=8443, ssl_context=ctx)

#winget install ShiningLight.OpenSSL.Light
# openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 365 -subj "/CN=pc"

