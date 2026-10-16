"""Generates the ten CityBuild villagers (design concept §12 "CityBuild-Figuren") as Blockbench projects for the
Helix-NPC pack, plus their 16/32 px speaker heads for Helix-GUIs.

The body is the shared model sheet of the cast (geometry and part pivots taken from an existing villager, so rigs and
animations stay identical); each character gets its own colours, props and face tweaks. Every face is painted flat in
the Kronland style (§4): base colour on the sides, the light step on top, the dark step below, a 1 px darker edge.

Existing projects are never overwritten: after the first run the .bbmodel files are hand-maintained in Blockbench,
so the script only creates missing villagers (pass --force to regenerate everything from scratch). The speaker heads
are always re-rendered from the (possibly hand-painted) projects via heads.py.

Usage: python3 tools/villagers/citybuild.py <villagers dir (npc-paper)> <heads dir (guis-paper)> [--force]
"""
import base64, copy, json, os, struct, sys, uuid, zlib

from heads import render as render_head

VILLAGERS, HEADS = sys.argv[1], sys.argv[2]
FORCE = "--force" in sys.argv[3:]
INK = (0x2A, 0x16, 0x0A)
SKIN = {"light": "#e0b48e", "mid": "#c99872", "dark": "#b9835e"}
BASE_PARTS = {"belt", "belt_buckle", "brow", "collar", "cuffs", "eye_iris_l", "eye_iris_r", "eye_pupil_l",
              "eye_pupil_r", "eye_white_l", "eye_white_r", "forearms", "hands", "head", "leg_l", "leg_r", "lid_l",
              "lid_r", "mouth", "nose", "robe", "robe_placket", "shoe_l", "shoe_r", "sleeve_l", "sleeve_r"}


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def light(c):
    return mix(c, (255, 255, 255), 0.22)


def dark(c):
    return mix(c, (0, 0, 0), 0.3)


# ---------------------------------------------------------------------------------------------------------------
# Characters: robe/trim/hair/eyes/skin, props as (name, group, from, to, colour), face tweaks.
# Coordinates follow the cast's model sheet (head 2..14 × 20..32 × 2.5..13.5, front = low z).
# ---------------------------------------------------------------------------------------------------------------
CAST = [
    dict(id="lotte", robe="#5aa8e0", trim="#f1e2c2", hair="#d8a040", iris="#3a7ac0", skin="light",
         mouth=([4.3, 21.2, 2.33], [11.7, 23.0, 2.63]),
         props=[("hair_cap", "head", [1.8, 31.0, 2.3], [14.2, 32.8, 13.7], "#d8a040"),
                ("plait_l", "head", [12.6, 24.0, 12.6], [14.0, 31.0, 14.0], "#d8a040"),
                ("plait_r", "head", [2.0, 24.0, 12.6], [3.4, 31.0, 14.0], "#d8a040"),
                ("staff", "body", [0.6, 0.0, 1.6], [1.6, 27.0, 2.6], "#8a5a2e"),
                ("lantern_cap", "body", [0.0, 30.4, 1.0], [2.2, 31.2, 3.2], "#5e5e66"),
                ("lantern_glow", "body", [0.2, 27.6, 1.2], [2.0, 30.4, 3.0], "#ffcf4a"),
                ("lantern_base", "body", [0.0, 27.0, 1.0], [2.2, 27.6, 3.2], "#5e5e66")]),
    dict(id="gundula", robe="#5a6470", trim="#e8e0d0", hair="#9aa0a8", iris="#5a7a9a", skin="light",
         props=[("hair", "head", [1.8, 30.2, 2.3], [14.2, 32.2, 13.7], "#9aa0a8"),
                ("bun", "head", [5.5, 31.1, 9.0], [10.5, 34.1, 13.0], "#9aa0a8"),
                ("glass_top_a", "head", [8.6, 26.6, 0.9], [12.0, 26.9, 1.1], "#2a160a"),
                ("glass_bot_a", "head", [8.6, 24.4, 0.9], [12.0, 24.7, 1.1], "#2a160a"),
                ("glass_top_b", "head", [4.0, 26.6, 0.9], [7.4, 26.9, 1.1], "#2a160a"),
                ("glass_bot_b", "head", [4.0, 24.4, 0.9], [7.4, 24.7, 1.1], "#2a160a"),
                ("glass_bridge", "head", [7.4, 25.9, 0.9], [8.6, 26.2, 1.1], "#2a160a"),
                ("folio", "body", [12.6, 10.0, 4.0], [14.8, 17.6, 10.4], "#7a2a1a"),
                ("folio_pages", "body", [12.4, 10.4, 4.4], [12.6, 17.2, 10.0], "#f1e2c2"),
                ("tape", "body", [2.9, 10.3, 4.6], [4.5, 11.9, 6.2], "#ffcf4a")]),
    dict(id="fritz", robe="#e07a2a", trim="#6b4a2e", hair="#6b3a1a", iris="#4a7a3a", skin="mid",
         mouth=([5.0, 20.9, 2.33], [11.0, 23.4, 2.63]),
         props=[("apron", "body", [4.5, 6.0, 4.55], [11.5, 15.0, 4.95], "#f1e2c2"),
                ("apron_bib", "body", [5.6, 15.0, 4.6], [10.4, 18.6, 4.95], "#f1e2c2"),
                ("scale_beam", "body", [3.8, 18.2, -0.6], [12.2, 18.7, 0.0], "#e0a420"),
                ("scale_post", "body", [7.7, 14.6, -0.5], [8.3, 18.2, 0.1], "#e0a420"),
                ("scale_pan_l", "body", [10.4, 16.8, -1.4], [12.6, 17.4, 0.8], "#e0a420"),
                ("scale_pan_r", "body", [3.4, 16.8, -1.4], [5.6, 17.4, 0.8], "#e0a420"),
                ("pencil", "head", [13.8, 27.0, 5.0], [14.6, 27.8, 10.0], "#ffcf4a")]),
    dict(id="ludwig", robe="#2e6a40", trim="#e0a420", hair="#3a2a1a", iris="#6a4a2a", skin="dark",
         props=[("vest", "body", [4.2, 12.6, 4.75], [11.8, 19.4, 4.95], "#8e2a1a"),
                ("vest_button_a", "body", [7.6, 17.2, 4.55], [8.4, 18.0, 4.75], "#ffcf4a"),
                ("vest_button_b", "body", [7.6, 14.6, 4.55], [8.4, 15.4, 4.75], "#ffcf4a"),
                ("hair", "head", [1.8, 30.6, 2.3], [14.2, 32.2, 13.7], "#3a2a1a"),
                ("hammer_handle", "body", [13.4, 14.0, 1.0], [14.2, 24.0, 1.8], "#8a5a2e"),
                ("hammer_head", "body", [12.6, 24.0, 0.2], [15.0, 26.2, 2.6], "#5a3a1a"),
                ("hammer_band", "body", [12.5, 24.8, 0.1], [15.1, 25.4, 2.7], "#e0a420")]),
    dict(id="henni", robe="#9a4a32", trim="#5a2a1a", hair="#b04a20", iris="#3a6a3a", skin="mid",
         forearms="skin",
         props=[("kerchief", "head", [1.7, 28.4, 2.3], [14.3, 32.4, 13.9], "#c8a46a"),
                ("kerchief_knot", "head", [6.5, 26.5, 13.4], [9.5, 29.5, 14.4], "#c8a46a"),
                ("pick_handle", "body", [2.6, 14.0, 9.4], [3.4, 31.0, 10.2], "#8a5a2e"),
                ("pick_head", "body", [-0.4, 29.4, 9.2], [6.4, 30.8, 10.4], "#9aa0a8")]),
    dict(id="waldemar", robe="#5a7a3a", trim="#3a5a2a", hair="#e8e0d0", iris="#4a5a3a", skin="mid",
         props=[("beard", "head", [4.4, 19.6, 2.15], [11.6, 21.3, 2.6], "#e8e0d0"),
                ("hat_brim", "head", [0.8, 31.6, 1.3], [15.2, 32.4, 14.7], "#6a5a2a"),
                ("hat_top", "head", [2.8, 32.4, 3.3], [13.2, 35.4, 12.7], "#5a4a24"),
                ("nest", "head", [5.0, 35.4, 5.0], [11.0, 36.6, 11.0], "#8a6a3a"),
                ("egg_a", "head", [6.4, 36.0, 7.0], [7.8, 37.4, 8.4], "#a8dcff"),
                ("egg_b", "head", [8.4, 36.0, 7.6], [9.8, 37.4, 9.0], "#a8dcff"),
                ("axe_handle", "body", [13.6, 3.0, 3.0], [14.4, 15.0, 3.8], "#8a5a2e"),
                ("axe_blade", "body", [14.4, 12.0, 2.2], [15.8, 15.0, 4.6], "#b8c0cc")]),
    dict(id="rupert", robe="#a8783a", trim="#6b4a2e", hair="#6b4a2e", iris="#5a4a2a", skin="light",
         props=[("hair", "head", [1.8, 30.6, 2.3], [14.2, 32.2, 13.7], "#6b4a2e"),
                ("pack", "body", [3.0, 8.5, 11.0], [13.0, 21.5, 16.5], "#7a5a3a"),
                ("pack_flap", "body", [2.8, 18.0, 10.8], [13.2, 21.8, 16.7], "#5a3a1a"),
                ("bedroll", "body", [2.4, 21.8, 12.0], [13.6, 23.8, 15.6], "#5a6470"),
                ("strap_l", "body", [10.4, 12.0, 4.85], [11.4, 20.0, 5.05], "#5a3a1a"),
                ("strap_r", "body", [4.6, 12.0, 4.85], [5.6, 20.0, 5.05], "#5a3a1a"),
                ("clipboard", "body", [5.6, 17.4, 0.0], [10.4, 22.0, 0.5], "#8a5a2e"),
                ("clip_paper", "body", [6.0, 17.8, -0.15], [10.0, 21.4, 0.05], "#f1e2c2")]),
    dict(id="paula", robe="#f0c030", trim="#d8382e", hair="#8a5a2e", iris="#3a7ac0", skin="light",
         props=[("cap", "head", [2.6, 32.0, 2.8], [13.4, 34.2, 13.2], "#f0c030"),
                ("cap_band", "head", [2.5, 31.4, 2.7], [13.5, 32.2, 13.3], "#d8382e"),
                ("cap_visor", "head", [3.6, 31.4, 0.6], [12.4, 32.0, 2.7], "#5a3a1a"),
                ("bag", "body", [12.6, 8.0, 4.5], [15.4, 12.6, 10.5], "#8a3a2a"),
                ("letters", "body", [13.0, 12.6, 6.0], [15.0, 13.8, 9.0], "#f1e2c2"),
                ("bag_strap", "body", [10.4, 12.6, 4.8], [11.2, 19.8, 5.05], "#8a3a2a"),
                ("cheek_l", "head", [10.8, 23.4, 2.25], [12.6, 24.6, 2.5], "#ff9aa8"),
                ("cheek_r", "head", [3.4, 23.4, 2.25], [5.2, 24.6, 2.5], "#ff9aa8"),
                ("sweat", "head", [12.0, 29.6, 2.2], [12.9, 31.0, 2.45], "#a8dcff")]),
    dict(id="brunhilde", robe="#7a7f88", trim="#d8382e", hair="#e0b040", iris="#4a6a8a", skin="mid",
         props=[("hair_cap", "head", [1.8, 31.0, 2.3], [14.2, 33.0, 13.7], "#e0b040"),
                ("braid_l", "head", [12.8, 18.0, 6.0], [14.4, 31.0, 7.8], "#e0b040"),
                ("braid_r", "head", [1.6, 18.0, 6.0], [3.2, 31.0, 7.8], "#e0b040"),
                ("scar", "head", [9.6, 26.6, 2.2], [10.2, 29.8, 2.45], "#e8c8a8"),
                ("shield", "body", [13.8, 8.0, 1.5], [14.8, 19.0, 11.5], "#8a5a2e"),
                ("shield_rim", "body", [13.7, 7.6, 1.1], [14.6, 19.4, 11.9], "#5e5e66"),
                ("shield_boss", "body", [14.8, 12.2, 5.2], [15.6, 14.8, 7.8], "#b8c0cc"),
                ("sword_blade", "body", [3.2, 16.6, 0.2], [4.4, 25.6, 1.0], "#c8a46a"),
                ("sword_guard", "body", [3.1, 16.7, 0.1], [6.5, 17.5, 1.1], "#6b4a2e")]),
    dict(id="greta", robe="#6e3a4a", trim="#e0a420", hair="#2a160a", iris="#6a3a9a", skin="dark",
         props=[("hair", "head", [1.8, 30.4, 2.3], [14.2, 32.4, 13.7], "#2a160a"),
                ("button_a", "body", [7.5, 18.0, 4.55], [8.5, 19.0, 4.85], "#ffcf4a"),
                ("button_b", "body", [7.5, 15.6, 4.55], [8.5, 16.6, 4.85], "#ffcf4a"),
                ("button_c", "body", [7.5, 13.2, 4.55], [8.5, 14.2, 4.85], "#ffcf4a"),
                ("gold_tooth", "head", [8.6, 21.6, 2.18], [9.6, 22.4, 2.33], "#ffcf4a"),
                ("keyring", "body", [10.9, 7.9, 5.3], [14.3, 11.3, 5.9], "#e0a420"),
                ("key_a", "body", [12.8, 5.7, 5.2], [13.6, 8.7, 5.6], "#ffcf4a"),
                ("key_b", "body", [11.6, 6.1, 5.2], [12.4, 8.7, 5.6], "#e0a420")]),
]


def base_colours(c):
    skin = rgb(SKIN[c["skin"]])
    robe, trim, hair = rgb(c["robe"]), rgb(c["trim"]), rgb(c["hair"])
    return {
        "robe": robe, "robe_placket": dark(robe), "collar": trim, "cuffs": trim, "belt": dark(trim),
        "belt_buckle": rgb("#ffcf4a"), "sleeve_l": robe, "sleeve_r": robe,
        "forearms": skin if c.get("forearms") == "skin" else robe, "hands": skin, "head": skin,
        "nose": mix(skin, (0, 0, 0), 0.08), "lid_l": mix(skin, (0, 0, 0), 0.12), "lid_r": mix(skin, (0, 0, 0), 0.12),
        "brow": hair, "mouth": rgb("#5a2a1a"), "eye_white_l": rgb("#f4f0e6"), "eye_white_r": rgb("#f4f0e6"),
        "eye_iris_l": rgb(c["iris"]), "eye_iris_r": rgb(c["iris"]), "eye_pupil_l": INK, "eye_pupil_r": INK,
        "leg_l": rgb("#4a3a2e"), "leg_r": rgb("#4a3a2e"), "shoe_l": rgb("#3a2a1a"), "shoe_r": rgb("#3a2a1a"),
    }


# ---------------------------------------------------------------------------------------------------------------
# Texture atlas
# ---------------------------------------------------------------------------------------------------------------
FACES = ("north", "east", "south", "west", "up", "down")


def face_size(f, t, face):
    dx, dy, dz = (max(1, round(t[i] - f[i] + 0.49)) for i in range(3))
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}[face]


class Atlas:
    def __init__(self, size):
        self.size, self.x, self.y, self.row = size, 0, 0, 0
        self.px = [[None] * size for _ in range(size)]

    def place(self, w, h):
        if self.x + w > self.size:
            self.x, self.y, self.row = 0, self.y + self.row, 0
        if self.y + h > self.size:
            raise OverflowError
        at = (self.x, self.y)
        self.x += w
        self.row = max(self.row, h)
        return at

    def paint(self, x, y, w, h, colour):
        edge = mix(colour, INK, 0.35)
        for j in range(h):
            for i in range(w):
                border = w >= 3 and h >= 3 and (i in (0, w - 1) or j in (0, h - 1))
                self.px[y + j][x + i] = edge if border else colour

    def png(self):
        raw = b"".join(b"\0" + b"".join(bytes((*p, 255)) if p else b"\0\0\0\0" for p in row) for row in self.px)
        return write_png(self.size, self.size, raw)


def write_png(w, h, raw):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def shade(colour, face):
    return light(colour) if face == "up" else dark(colour) if face == "down" else colour


# ---------------------------------------------------------------------------------------------------------------
# Model assembly
# ---------------------------------------------------------------------------------------------------------------
def det_uuid(*parts):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "helix-villager:" + ":".join(parts)))


def build(c, template):
    d = copy.deepcopy(template)
    groups = {g["uuid"]: g for g in d["groups"]}
    by_name = {g["name"]: g["uuid"] for g in d["groups"]}
    proto = copy.deepcopy(d["elements"][0])
    d["elements"] = [e for e in d["elements"] if e["name"] in BASE_PARTS]
    keep = {e["uuid"] for e in d["elements"]}

    def prune(children):
        out = []
        for ch in children:
            if isinstance(ch, str):
                if ch in keep:
                    out.append(ch)
            else:
                ch["children"] = prune(ch["children"])
                out.append(ch)
        return out
    d["outliner"] = prune(d["outliner"])

    def outliner_node(name, nodes):
        for n in nodes:
            if isinstance(n, dict):
                if groups[n["uuid"]]["name"] == name:
                    return n
                hit = outliner_node(name, n["children"])
                if hit:
                    return hit
        return None

    colours = base_colours(c)
    if "mouth" in c:
        m = next(e for e in d["elements"] if e["name"] == "mouth")
        m["from"], m["to"] = c["mouth"][0], c["mouth"][1]
    for name, group, f, t, col in c["props"]:
        e = copy.deepcopy(proto)
        e.update(name=name, uuid=det_uuid(c["id"], name), **{"from": f, "to": t},
                 origin=[round((f[i] + t[i]) / 2, 3) for i in range(3)])
        d["elements"].append(e)
        outliner_node(group, d["outliner"])["children"].append(e["uuid"])
        colours[name] = rgb(col)

    for size in (128, 256):
        atlas = Atlas(size)
        try:
            for e in d["elements"]:
                for face in FACES:
                    w, h = face_size(e["from"], e["to"], face)
                    x, y = atlas.place(w, h)
                    atlas.paint(x, y, w, h, shade(colours[e["name"]], face))
                    e["faces"][face] = {"uv": [x, y, x + w, y + h], "texture": 0}
            break
        except OverflowError:
            continue
    tex = d["textures"][0]
    tex.update(name=f"{c['id']}.png", uv_width=size, uv_height=size, uuid=det_uuid(c["id"], "texture"),
               source="data:image/png;base64," + base64.b64encode(atlas.png()).decode())
    d["resolution"] = {"width": size, "height": size}
    d["name"] = c["id"]
    return d, colours


template = json.load(open(f"{VILLAGERS}/mira.bbmodel"))
for c in CAST:
    path = f"{VILLAGERS}/{c['id']}.bbmodel"
    if FORCE or not os.path.exists(path):
        model, _ = build(c, template)
        with open(path, "w") as fh:
            json.dump(model, fh, separators=(",", ":"))
        print(f"{c['id']}: generated, {len(model['elements'])} cubes")
    else:
        model = json.load(open(path))
        print(f"{c['id']}: kept (hand-maintained)")
    for px in (16, 32):
        with open(f"{HEADS}/{c['id']}_{px}.png", "wb") as fh:
            fh.write(render_head(model, px))
