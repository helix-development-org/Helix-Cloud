"""Builds OptiFine/EMF Custom Entity Models (.jem) for every mob from the CEM templates, per design concept §13:
the head grows (cartoon proportion) and small facial features (nose, snout, ears, horns, beak, eyes, jaw) are
exaggerated. Part names, pivots and UVs stay vanilla, so vanilla animations and all texture variants keep working."""
import json, os, re, sys
TEMPLATES, OUT = sys.argv[1], sys.argv[2]
T = json.load(open(TEMPLATES))
models = {k: v['model'] for k, v in T['models'].items() if isinstance(v, dict) and 'model' in v}
SKIP = re.compile(r'^(player|ender_chest|.*_wall_sign|.*_boat_patch|.*_\d+(\.\d+)?$|trapped_chest|armor|arrow|banner|bed|bell|boat|chest|book|cape|conduit|decorated_pot|elytra|end_crystal|evoker_fangs|hanging_sign|head|lead_knot|llama_spit|minecart|raft|shield|shulker_bullet|shulker_box|sign|wall_sign|trident|wind_charge|wither_skull|copper_golem_statue|iron_golem_destroy|mooshroom_mushrooms|.*_(saddle|decor|harness|armor|ropes)|.*_old_uv)')
HEAD = re.compile(r'^(head|headwear|hat|head\d|head_[a-z]+|left_head|right_head|center_head|skull|jaw_top)$')
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

def walk_boxes(parts, in_head=False):
    """Yields (part name, box, belongs to a head) for every box, depth first."""
    for p in parts:
        name = (p.get('part') or p.get('id') or '').lower()
        head = in_head or bool(HEAD.match(name))
        for b in p.get('boxes', []):
            if 'coordinates' in b: yield name, b, head
        yield from walk_boxes(p.get('submodels', []), head)

def process(parts, baby, stats):
    boxes = list(walk_boxes(parts))
    # main head boxes: the biggest box of every head part (wither: three heads)
    heads = {}
    for name, b, head in boxes:
        if head and HEAD.match(name):
            w, h, d = dims(b)
            if name not in heads or w * h * d > heads[name][0]: heads[name] = (w * h * d, b)
    mains = []
    for _, b in heads.values():
        w, h, d = dims(b)
        if max(w, h, d) <= 4 and w * h * d <= 32: continue
        grow = max(0.4, min(1.8, 0.2 * min(w, h, d))) * (0.55 if baby else 1.0)
        mains.append((b, grow, list(b['coordinates'])))
    main_ids = {id(b) for b, _, _ in mains}
    def nearest(box):
        c = [box['coordinates'][i] + box['coordinates'][i + 3] / 2 for i in range(3)]
        best = None
        for hb, grow, hc in mains:
            hcen = [hc[i] + hc[i + 3] / 2 for i in range(3)]
            dist = sum((c[i] - hcen[i]) ** 2 for i in range(3))
            if best is None or dist < best[0]: best = (dist, hc, grow)
        return best
    for name, b, head in boxes:
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

os.makedirs(OUT, exist_ok=True)
count = 0
for eid, (mid, baby) in sorted(entities.items()):
    m = json.loads(models[resolve(mid, models.keys())])
    stats = {'head': 0, 'feature': 0}
    process(m['models'], baby, stats)
    m['credit'] = 'Kronland design concept §13 (generated by tools/mobs/cem.py from the CEM templates)'
    json.dump(m, open(os.path.join(OUT, eid + '.jem'), 'w'), indent=1)
    count += 1
print(count, 'jem models written to', OUT)
