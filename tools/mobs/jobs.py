"""Maps every vanilla mob texture to its CEM template model and lists the box-UV face rectangles, so the
Blockbench toon pass can paint ink edges and top/bottom light per face (design concept §4)."""
import json, re, glob, sys
TEMPLATES, VANILLA, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
T = json.load(open(TEMPLATES))
models = {k: json.loads(v['model']) for k, v in T['models'].items() if isinstance(v, dict) and 'model' in v}
# entity ids that reuse another template (husk -> zombie, evoker -> illager …)
def _aliases(entries, acc):
    for e in entries:
        if isinstance(e, dict) and 'id' in e:
            if 'model' in e and e['model'] in models: acc.setdefault(e['id'], e['model'])
            _aliases(e.get('variants', []), acc)
    return acc
alias = {}
for c in T['categories']: _aliases(c.get('entities', []), alias)
for k, v in alias.items(): models.setdefault(k, models[v])
base = VANILLA + '/assets/minecraft/textures/entity/'
files = sorted(f[len(base):] for f in glob.glob(base + '**/*.png', recursive=True))
rules = [(r'^cow/cold_cow','cold_cow'),(r'^cow/warm_cow','warm_cow'),(r'^chicken/cold_chicken','cold_chicken'),(r'^pig/cold_pig','cold_pig'),(r'^allay/','allay'),(r'^armadillo\.png','armadillo'),(r'^axolotl/','axolotl'),(r'^bat\.png','bat'),(r'^bear/','polar_bear'),(r'^bee/bee(_angry)?(_nectar)?\.png','bee'),(r'^blaze','blaze'),(r'^breeze/breeze\.png','breeze'),(r'^camel/camel\.png','camel'),(r'^camel/camel_husk','camel_husk'),(r'^cat/ocelot','ocelot'),(r'^cat/(?!cat_collar)','cat'),(r'^chicken/','chicken'),(r'^copper_golem/(?!.*eyes)','copper_golem'),(r'^cow/.*mooshroom','mooshroom'),(r'^cow/','cow'),(r'^creaking/creaking\.png','creaking'),(r'^creeper/creeper\.png','creeper'),(r'^dolphin','dolphin'),(r'^enderdragon/dragon(_exploding)?\.png','dragon'),(r'^enderman/enderman\.png','enderman'),(r'^endermite','endermite'),(r'^fish/cod','cod'),(r'^fish/salmon','salmon'),(r'^fish/tropical_a\.png','tropical_fish_a'),(r'^fox/','fox'),(r'^frog/','frog'),(r'^ghast/ghast','ghast'),(r'^ghast/happy_ghast_baby','happy_ghast_baby'),(r'^ghast/happy_ghast\.png','happy_ghast'),(r'^goat/','goat'),(r'^guardian\.png','guardian'),(r'^guardian_elder','elder_guardian'),(r'^hoglin/hoglin','hoglin'),(r'^hoglin/zoglin','zoglin'),(r'^horse/horse_(black|brown|chestnut|creamy|darkbrown|gray|white)\.png','horse'),(r'^horse/donkey','donkey'),(r'^horse/mule','mule'),(r'^horse/horse_skeleton','skeleton_horse'),(r'^horse/horse_zombie','zombie_horse'),(r'^illager/evoker\.png','evoker'),(r'^illager/illusioner','illusioner'),(r'^illager/pillager','pillager'),(r'^illager/ravager','ravager'),(r'^illager/vex','vex'),(r'^illager/vindicator','vindicator'),(r'^iron_golem/iron_golem\.png','iron_golem'),(r'^llama/(?!spit)','llama'),(r'^nautilus/nautilus\.png','nautilus'),(r'^nautilus/nautilus_baby','nautilus_baby'),(r'^nautilus/zombie_nautilus','zombie_nautilus'),(r'^panda/','panda'),(r'^parrot/','parrot'),(r'^phantom\.png','phantom'),(r'^pig/','pig'),(r'^piglin/piglin\.png','piglin'),(r'^piglin/piglin_brute','piglin_brute'),(r'^piglin/zombified','zombified_piglin'),(r'^rabbit/','rabbit'),(r'^sheep/sheep\.png','sheep'),(r'^silverfish','silverfish'),(r'^skeleton/skeleton','skeleton'),(r'^skeleton/stray\.png','stray'),(r'^skeleton/bogged\.png','bogged'),(r'^skeleton/parched','parched'),(r'^skeleton/wither_skeleton','wither_skeleton'),(r'^slime/slime','slime'),(r'^slime/magmacube','magma_cube'),(r'^sniffer/','sniffer'),(r'^snow_golem','snow_golem'),(r'^spider/spider','spider'),(r'^spider/cave_spider','cave_spider'),(r'^squid/squid','squid'),(r'^squid/glow_squid','glow_squid'),(r'^strider/','strider'),(r'^tadpole/','tadpole'),(r'^turtle/','turtle'),(r'^villager/','villager'),(r'^wandering_trader','wandering_trader'),(r'^warden/warden\.png','warden'),(r'^witch','witch'),(r'^wither/wither(_invulnerable)?\.png','wither'),(r'^wolf/wolf(_[a-z]+)?(_angry|_tame)?\.png','wolf'),(r'^zombie/zombie','zombie'),(r'^zombie/husk','husk'),(r'^zombie/drowned\.png','drowned'),(r'^zombie_villager/','zombie_villager')]
generic = re.compile(r'eyes|_heart|pulsating|bioluminescent|collar|markings|wool|overlay|crackiness|armor|ropes|wind|spit|fireball|fangs|stinger|pattern|tropical_b|pufferfish|outer_layer|shooting|charging')
def faces(m):
    out = []
    def walk(parts):
        for p in parts:
            for b in p.get('boxes', []):
                if 'textureOffset' in b:
                    u, v = b['textureOffset']; w, h, d = [int(x) for x in b['coordinates'][3:6]]
                    out.extend([('up', u+d, v, w, d), ('down', u+d+w, v, w, d), ('side', u, v+d, d, h), ('front', u+d, v+d, w, h), ('side', u+d+w, v+d, d, h), ('back', u+2*d+w, v+d, w, h)])
                else:
                    for k, f in (('uvUp','up'),('uvDown','down'),('uvNorth','front'),('uvSouth','back'),('uvEast','side'),('uvWest','side')):
                        if k in b:
                            x1, y1, x2, y2 = b[k]; out.append((f, min(x1,x2), min(y1,y2), abs(x2-x1), abs(y2-y1)))
            walk(p.get('submodels', []))
    walk(m['models'])
    return [f for f in out if f[3] > 0 and f[4] > 0]

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

jobs, unmapped = [], []
for f in files:
    mid = next((m for r, m in rules if re.search(r, f)), None)
    if mid is None: continue
    j = {'file': f, 'model': mid, 'mode': 'generic' if generic.search(f) else 'faces'}
    if j['mode'] == 'faces':
        if mid in models:
            mm = models[resolve(mid, models.keys())] if resolve(mid, models.keys()) in models else models[mid]
            j['tex'] = mm['textureSize']; j['faces'] = faces(mm); j['template'] = resolve(mid, models.keys())
        else: j['mode'] = 'generic'; unmapped.append(f)
    jobs.append(j)
json.dump(jobs, open(OUT, 'w'))
print(len(jobs), 'jobs,', sum(1 for j in jobs if j['mode'] == 'faces'), 'with face layout; no template:', unmapped)
