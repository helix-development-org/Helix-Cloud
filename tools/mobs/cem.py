"""Builds OptiFine/EMF Custom Entity Models (.jem) for every mob from the CEM templates, per design concept §13:
the head grows (cartoon proportion) and small facial features (nose, snout, ears, horns, beak, eyes, jaw) are
exaggerated. Part names, pivots and UVs stay vanilla, so vanilla animations and all texture variants keep working."""
import json, os, re, sys
TEMPLATES, OUT = sys.argv[1], sys.argv[2]
T = json.load(open(TEMPLATES))
models = {k: v['model'] for k, v in T['models'].items() if isinstance(v, dict) and 'model' in v}
SKIP = re.compile(r'^(player|ender_chest|.*_wall_sign|.*_boat_patch|.*_\d+(\.\d+)?$|trapped_chest|armor|arrow|banner|bed|bell|boat|chest|book|cape|conduit|decorated_pot|elytra|end_crystal|evoker_fangs|hanging_sign|head|lead_knot|llama_spit|minecart|raft|shield|shulker_bullet|shulker_box|sign|wall_sign|trident|wind_charge|wither_skull|copper_golem_statue|iron_golem_destroy|mooshroom_mushrooms|.*_(saddle|decor|harness|armor|ropes)|.*_old_uv)')
HEAD = re.compile(r'^(torso|head|headwear|hat|head\d|head_[a-z]+|left_head|right_head|center_head|skull|jaw_top)$')
FEATURE = re.compile(r'(nose|snout|ear|horn|beak|bill|chin|eye|jaw|mouth|antenna|whisker|tusk|fin_top|crest|wattle)')

def _ver(s):
    return tuple(int(x) for x in s.split('.'))
TARGET = (21, 11)
def resolve(mid, keys):
    """Template for Minecraft 1.21.11: '<id>_<ver>' means 'valid up to <ver>' – take the smallest such version that
    is still >= 1.21.11, else the unsuffixed (newest) template."""
    cands = []
    for k in keys:
        if k.startswith(mid + '_'):
            suf = k[len(mid) + 1:]
            if all(p.isdigit() for p in suf.split('.')) and _ver(suf) >= TARGET: cands.append((_ver(suf), k))
    return min(cands)[1] if cands else mid

def _faces(m):
    out = []
    def walk(parts):
        for p in parts:
            for b in p.get('boxes', []):
                if 'textureOffset' in b:
                    u, v = b['textureOffset']; w, h, d = [int(x) for x in b['coordinates'][3:6]]
                    out.extend([('up', u+d, v, w, d), ('down', u+d+w, v, w, d), ('side', u, v+d, d, h), ('front', u+d, v+d, w, h), ('side', u+d+w, v+d, d, h), ('back', u+2*d+w, v+d, w, h)])
                for k in ('uvUp', 'uvDown', 'uvNorth', 'uvSouth', 'uvEast', 'uvWest'):
                    if k in b:
                        x1, y1, x2, y2 = b[k]; out.append((k, min(x1, x2), min(y1, y2), abs(x2 - x1), abs(y2 - y1)))
            walk(p.get('submodels', []))
    walk(m['models'])
    return out
LAYOUTS = {}
for _k, _v in models.items():
    try: LAYOUTS[_k] = _faces(json.loads(_v))
    except Exception: pass
entities = {}
def collect(entries, baby):
    for e in entries:
        if not isinstance(e, dict) or 'id' not in e: continue
        mid = e.get('model', e['id'])
        if mid in models and not SKIP.match(e['id']): entities[e['id']] = (mid, baby)
        for v in e.get('variants', []):
            vid = v.get('id'); vm = v.get('model', vid)
            if vid and vm in models and not SKIP.match(vid): entities[vid] = (vm, baby)
for c in T['categories']:
    if c.get('type') == 'heading': continue
    collect(c.get('entities', []), c.get('name') == 'Babies')

def inflate(box, amount):
    box['sizeAdd'] = round(box.get('sizeAdd', 0) + amount, 3)

def dims(box):
    return box['coordinates'][3:6]

def walk_boxes(parts, root=None):
    """Yields (head root part name or None, part name, box) for every box, depth first; the head root is the
    outermost part whose name marks a head (its submodels belong to it)."""
    for p in parts:
        name = (p.get('part') or p.get('id') or '').lower()
        here = root or (name if HEAD.match(name) else None)
        for b in p.get('boxes', []):
            if 'coordinates' in b: yield here, name, b
        yield from walk_boxes(p.get('submodels', []), here)

def find_part(parts, name):
    for p in parts:
        if (p.get('part') or p.get('id') or '').lower() == name: return p
        hit = find_part(p.get('submodels', []), name)
        if hit is not None: return hit
    return None

def process(parts, baby, stats):
    boxes = list(walk_boxes(parts))
    # main head boxes: the biggest box of every head part (wither: three heads)
    heads = {}
    for root, name, b in boxes:
        if root:
            w, h, d = dims(b)
            if root not in heads or w * h * d > heads[root][0]: heads[root] = (w * h * d, b)
    mains = []
    for _, b in heads.values():
        w, h, d = dims(b)
        if w * h * d < 6: continue
        grow = max(0.25, min(1.8, 0.2 * min(w, h, d))) * (0.55 if baby else 1.0)
        mains.append((b, grow, list(b['coordinates'])))
    main_ids = {id(b) for b, _, _ in mains}
    stats['heads'] = [(n, b, g) for n, (_, b) in heads.items() for hb, g, _ in mains if hb is b]
    def nearest(box):
        c = [box['coordinates'][i] + box['coordinates'][i + 3] / 2 for i in range(3)]
        best = None
        for hb, grow, hc in mains:
            hcen = [hc[i] + hc[i + 3] / 2 for i in range(3)]
            dist = sum((c[i] - hcen[i]) ** 2 for i in range(3))
            if best is None or dist < best[0]: best = (dist, hc, grow)
        return best
    for root, name, b in boxes:
        head = root is not None
        w, h, d = dims(b)
        small = max(w, h, d) <= 4 and w * h * d <= 32
        if id(b) in main_ids:
            inflate(b, next(g for hb, g, _ in mains if hb is b)); stats['head'] += 1
            continue
        feature = FEATURE.search(name) or (head and small)
        if not (feature or head): continue
        near = nearest(b)
        if feature: inflate(b, 0.55 if not baby else 0.35); stats['feature'] += 1
        else: inflate(b, near[2] if near else 0.4)
        if near and feature:
            # move it out by the head's growth along every axis where it sits outside the head box
            _, hc, grow = near
            co = b['coordinates']
            for i in range(3):
                lo, hi = hc[i], hc[i] + hc[i + 3]
                cen = co[i] + co[i + 3] / 2
                if cen < lo: co[i] = round(co[i] - grow, 3)
                elif cen > hi: co[i] = round(co[i] + grow, 3)

NO_EYES = re.compile(r'^(?:cod|salmon|tropical|puffer|squid|glow_squid|warden|enderman|wither|dragon|guardian|elder_guardian|shulker|slime|magma_cube|snow_golem|frog|tadpole|breeze|creaking|ghast|happy_ghast|endermite|silverfish|sulfur|nautilus|zombie_nautilus|dolphin|turtle|bee_stinger|.*_outer|.*_wool.*|.*_eyes|breeze_wind|creeper_charge|elder_guardian_ghost|giant)$')
TAIL = re.compile(r'tail')
EAR = re.compile(r'ear')
LEG = re.compile(r'leg|foot|thigh|paw')

def free_rect(layout, tex, w, h):
    """Top-most free texture rectangle of w×h texels that no face of the model samples."""
    if not layout: return None
    tw, th = tex
    used = [[False] * tw for _ in range(th)]
    for _, u, v, fw, fh in layout:
        for y in range(max(0, int(v)), min(th, int(v + fh + 0.999))):
            for x in range(max(0, int(u)), min(tw, int(u + fw + 0.999))): used[y][x] = True
    for y in range(th - h + 1):
        for x in range(tw - w + 1):
            if all(not used[y + j][x + i] for j in range(h) for i in range(w)): return (x, y)
    return None

EYE_W, EYE_H = 10, 4   # strip: white 4x4 (ink rim) | pupil 2x2 | lid 3x3 (+1 gap)

def add_eyes(model, eid, head_part, head_box, grow, uv, strip):
    """Cartoon eyes (design §13) on the front of the main head box: white with ink rim, pupil with glint, a lid
    that blinks. Returned ids are animated."""
    x0, y0, z0, w, h, d = head_box['coordinates']
    size = round(max(1.6, min(3.6, w * 0.3)), 2)
    pupil = round(size * 0.5, 2)
    zf = z0 - grow - 0.06
    cy = y0 + h * 0.56
    ux, uy = uv
    rect = lambda a, b, c, e: [ux + a, uy + b, ux + c, uy + e]
    # strip layouts: full (white 4 with rim | pupil 2 | lid 3), compact (white 3 | pupil 2 | lid 2), tiny (2|1|2)
    W, P, L = {10: ((0, 0, 4, 4), (4, 0, 6, 2), (6, 0, 9, 3)), 7: ((0, 0, 3, 3), (3, 0, 5, 2), (5, 0, 7, 2)), 5: ((0, 0, 2, 2), (2, 0, 3, 1), (3, 0, 5, 2))}[strip[0]]
    subs = []
    for side, sx in (('l', -1), ('r', 1)):
        cx = sx * w * 0.24 + x0 + w / 2
        subs.append({'id': f'kl_eye_{side}', 'boxes': [
            {'coordinates': [round(cx - size / 2, 3), round(cy - size / 2, 3), round(zf - 0.3, 3), size, size, 0.3], 'uvNorth': rect(*W)},
            {'coordinates': [round(cx - pupil / 2 - sx * 0.15 * size, 3), round(cy - pupil / 2 - 0.1 * size, 3), round(zf - 0.42, 3), pupil, pupil, 0.1], 'uvNorth': rect(*P)}]})
        subs.append({'id': f'kl_lid_{side}', 'boxes': [
            {'coordinates': [round(cx - size / 2 - 0.05, 3), round(cy - size / 2 - 0.05, 3), round(zf - 0.5, 3), round(size + 0.1, 3), round(size + 0.1, 3), 0.06], 'uvNorth': rect(*L)}]})
    head_part.setdefault('submodels', []).extend(subs)
    return [s['id'] for s in subs]

def lively(model, eid, eyes, head_name, has_head):
    """Cartoon life on top of the vanilla animation (OptiFine/EMF CEM expressions; rotations in radians). Only
    relative rotations and scales are used, so parts that vanilla nests (nose, hat, ears) stay attached:
    breathing body, rocking walk, head wobble with squash & stretch, tail wag, ear twitch, blinking, a flinch when hurt."""
    seed = sum(map(ord, eid)) % 97
    a = {}
    tops = [p.get('part') for p in model['models'] if p.get('part')]
    walk = 'sin(limb_swing * 0.6662) * min(limb_speed, 1)'
    breathe = f'sin((age + {seed}) * 0.09)'
    body = next((t for t in tops if t in ('body', 'torso')), None)
    if body:
        a[f'{body}.sy'] = f'1 + 0.025 * {breathe}'
        a[f'{body}.sx'] = f'1 + 0.012 * {breathe}'
        a[f'{body}.rz'] = f'{body}.rz + {walk} * 0.06'
    if has_head and head_name in tops:
        h = head_name
        a[f'{h}.rz'] = f'{h}.rz + {walk} * 0.16 + sin((age + {seed}) * 0.045) * 0.05'
        a[f'{h}.rx'] = f'{h}.rx + if(hurt_time > 0, -0.3 * hurt_time / 10, sin((age + {seed}) * 0.06) * 0.03)'
        a[f'{h}.sy'] = f'1 + abs({walk}) * 0.06'
        a[f'{h}.sx'] = f'1 - abs({walk}) * 0.03'
    for part in tops:
        if TAIL.search(part):
            a[f'{part}.ry'] = f'{part}.ry + sin((age + {seed}) * 0.32) * 0.35'
        elif EAR.search(part):
            a[f'{part}.rz'] = f'{part}.rz + if(frac((age + {seed * 3}) / 70) < 0.06, sin(age * 1.8) * 0.3, 0)'
    for e in eyes:
        if e.startswith('kl_lid_'):
            a[f'{e}.visible'] = f'frac((age + {seed * 7}) / {80 + seed % 40}) > 0.955 || hurt_time > 0'
    if a: model.setdefault('animations', []).append(a)

EYE_PLAN = {}
MISSING = []
os.makedirs(OUT, exist_ok=True)
count = 0
for eid, (mid, baby) in sorted(entities.items()):
    m = json.loads(models[resolve(mid, models.keys())])
    stats = {'head': 0, 'feature': 0}
    process(m['models'], baby, stats)
    heads = stats.get('heads', [])
    head_name = heads[0][0] if heads else None
    eyes = []
    if heads and not NO_EYES.match(eid) and not NO_EYES.match(mid):
        name, box, grow = max(heads, key=lambda t: t[1]['coordinates'][3] * t[1]['coordinates'][4])
        part = find_part(m['models'], name)
        layout = LAYOUTS.get(resolve(mid, models.keys()))
        # babies share the adult texture: the strip must be free in both layouts
        if layout is not None and baby:
            adult = entities.get(re.sub(r'_baby.*$', '', eid))
            if adult: layout = layout + (LAYOUTS.get(resolve(adult[0], models.keys())) or [])
        uv, strip = None, None
        for sw, sh in ((EYE_W, EYE_H), (7, 3), (5, 2)):
            uv = free_rect(layout, m['textureSize'], sw, sh) if layout is not None else None
            if uv: strip = (sw, sh); break
        if part is not None and uv is not None:
            eyes = add_eyes(m, eid, part, box, grow, uv, strip)
            EYE_PLAN[eid] = {'adult': re.sub(r'_baby.*$', '', eid) if baby else eid, 'template': resolve(mid, models.keys()), 'uv': uv, 'strip': strip, 'tex': m['textureSize'], 'head': box['coordinates'], 'headUv': box.get('textureOffset')}
        else:
            MISSING.append((eid, 'no free texels' if part is not None else 'head is nested'))
    lively(m, eid, eyes, head_name, bool(heads))
    m['credit'] = 'Kronland design concept §13 (generated by tools/mobs/cem.py from the CEM templates)'
    json.dump(m, open(os.path.join(OUT, eid + '.jem'), 'w'), indent=1)
    count += 1
json.dump(EYE_PLAN, open(os.path.join(OUT, '..', 'eye_plan.json'), 'w'), indent=1)
print(count, 'jem models written to', OUT, '·', len(EYE_PLAN), 'with cartoon eyes'); print('without eyes:', MISSING)
