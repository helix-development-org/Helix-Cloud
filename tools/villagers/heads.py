"""Renders the 16/32 px speaker heads of villagers from their Blockbench projects (the painted texture, not flat
colours), so the heads always match the model after hand work in Blockbench.

The view is orthographic from the front (low z): every pixel samples the north face of the frontmost head cube
(plus collar/robe below the chin), the outermost pixels become an ink silhouette. Lids are skipped because they rest
almost closed-to-zero in game, so the icon shows open eyes.

Usage: python3 tools/villagers/heads.py <villagers dir (npc-paper)> <heads dir (guis-paper)> <id> [<id> ...]
"""
import base64, json, struct, sys, zlib

INK = (0x2A, 0x16, 0x0A)


def read_png(data):
    """Decodes an 8-bit RGB/RGBA, non-interlaced PNG into rows of RGBA tuples."""
    pos, chunks = 8, {}
    idat = b""
    while pos < len(data):
        length, tag = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if tag == b"IHDR":
            chunks["ihdr"] = body
        elif tag == b"IDAT":
            idat += body
        pos += 12 + length
    w, h, depth, ctype = struct.unpack(">IIBB", chunks["ihdr"][:10])
    if depth != 8 or ctype not in (2, 6):
        raise ValueError(f"unsupported PNG (depth {depth}, colour type {ctype})")
    bpp = 4 if ctype == 6 else 3
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev = [], bytearray(stride)
    for y in range(h):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                line[i] = (line[i] + a) & 0xFF
            elif f == 2:
                line[i] = (line[i] + b) & 0xFF
            elif f == 3:
                line[i] = (line[i] + (a + b) // 2) & 0xFF
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 0xFF
        prev = line
        rows.append([tuple(line[x * bpp:x * bpp + bpp]) + ((255,) if bpp == 3 else ()) for x in range(w)])
    return w, h, rows


def write_png(w, h, raw):
    def chunk(tag, body):
        return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def render(model, px):
    """Renders the front view of a villager head as PNG bytes of size px × px."""
    tex = model["textures"][0]
    tw, th, pixels = read_png(base64.b64decode(tex["source"].split(",", 1)[1]))
    uvw = tex.get("uv_width") or model["resolution"]["width"]
    uvh = tex.get("uv_height") or model["resolution"]["height"]
    groups = {g["uuid"]: g["name"] for g in model["groups"]}
    elements = {e["uuid"]: e for e in model["elements"]}
    head_ids = set()

    def walk(nodes, inside):
        for n in nodes:
            if isinstance(n, str):
                if inside:
                    head_ids.add(n)
            else:
                walk(n["children"], inside or groups[n["uuid"]] == "head")
    walk(model["outliner"], False)
    cubes = [elements[u] for u in head_ids if not elements[u]["name"].startswith("lid_")]
    cubes += [e for e in model["elements"] if e["name"] in ("collar", "robe")]
    top = min(max(e["to"][1] for e in cubes), 36.0)
    unit = 16.0 / px
    img = [[None] * px for _ in range(px)]
    for j in range(px):
        wy = top - (j + 0.5) * unit
        for i in range(px):
            wx = 16.0 - (i + 0.5) * unit
            hits = sorted((e for e in cubes if e["from"][0] <= wx <= e["to"][0] and e["from"][1] <= wy <= e["to"][1]
                           and "north" in e.get("faces", {})), key=lambda e: e["from"][2])
            for e in hits:
                f, t, uv = e["from"], e["to"], e["faces"]["north"]["uv"]
                fu = (t[0] - wx) / max(t[0] - f[0], 1e-6)
                fv = (t[1] - wy) / max(t[1] - f[1], 1e-6)
                u = (uv[0] + fu * (uv[2] - uv[0])) * tw / uvw
                v = (uv[1] + fv * (uv[3] - uv[1])) * th / uvh
                x, y = min(tw - 1, max(0, int(u))), min(th - 1, max(0, int(v)))
                p = pixels[y][x]
                if p[3] >= 128:
                    img[j][i] = p[:3]
                    break
    out = [row[:] for row in img]
    for j in range(px):
        for i in range(px):
            if img[j][i] is None:
                continue
            if any(not (0 <= j + dj < px and 0 <= i + di < px) or img[j + dj][i + di] is None
                   for dj, di in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                out[j][i] = INK
    raw = b"".join(b"\0" + b"".join(bytes((*p, 255)) if p else b"\0\0\0\0" for p in row) for row in out)
    return write_png(px, px, raw)


if __name__ == "__main__":
    villagers, heads, ids = sys.argv[1], sys.argv[2], sys.argv[3:]
    for vid in ids:
        model = json.load(open(f"{villagers}/{vid}.bbmodel"))
        for size in (16, 32):
            with open(f"{heads}/{vid}_{size}.png", "wb") as fh:
                fh.write(render(model, size))
        print(f"{vid}: heads rendered")
