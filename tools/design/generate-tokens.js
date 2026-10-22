const fs=require('fs'); global.DCLogic=class{};
eval(fs.readFileSync('../../docs/design/concept-data.js','utf8')+';global.V=new Component().renderVals();');
const t={}; V.palettes.forEach(g=>g.items.forEach(c=>{t[c.tok]={name:c.n,light:c.l,base:c.b,dark:c.d,use:c.use};}));
const ranks=V.ranks.map(r=>({tag:r.tag,color:r.c,weight:r.w}));
fs.writeFileSync('tokens.json',JSON.stringify({version:'1.0',tokens:t,ranks},null,2)+'\n');
const camel=s=>s.replace(/\.(\w)/g,(m,c)=>c.toUpperCase());
const hex=h=>'0x'+h.slice(1).toUpperCase();
let k=`package org.helix.api.design

/**
 * One colour token of the network design concept (docs/design/DESIGN.md §3): three steps, used identically in the
 * resource pack, MiniMessage, the web panel and LabyMod.
 *
 * @property id token id (\`gold\`, \`rarity.elite\` …).
 * @property light highlight step (top edge).
 * @property base surface step.
 * @property dark shade step (bottom edge, shadow).
 */
data class ColorToken(val id: String, val light: Int, val base: Int, val dark: Int) {
    /** MiniMessage hex tag of the base step, e.g. \`<#ffcf4a>\`. */
    val mm: String get() = "<#%06x>".format(base)

    /** Closing MiniMessage tag of [mm]. */
    val mmClose: String get() = "</#%06x>".format(base)

    /**
     * Wraps [text] in the base colour.
     *
     * @param text MiniMessage text.
     * @return the coloured text.
     */
    fun wrap(text: String): String = "$mm$text$mmClose"
}

/** Generated from docs/design/tokens.json — every colour of the network design concept. Add colours there first. */
object Palette {
`;
for (const [id,c] of Object.entries(t)) k+=`    /** ${c.name}: ${c.use}. */\n    val ${camel(id).toUpperCase()==='X'?'':camel(id)} = ColorToken("${id}", ${hex(c.light)}, ${hex(c.base)}, ${hex(c.dark)})\n\n`;
k+=`    /** All tokens by id. */\n    val ALL: Map<String, ColorToken> = listOf(${Object.keys(t).map(camel).join(', ')}).associateBy { it.id }\n}\n\n`;
k+=`/**
 * A staff rank of the design concept (§9).
 *
 * @property tag chat tag including brackets.
 * @property color rank colour (RGB).
 * @property weight sort weight (higher = more important).
 */
data class StaffRank(val tag: String, val color: Int, val weight: Int)

/** The staff ranks of the design concept, highest first. */
object StaffRanks {
    /** Ranks in display order. */
    val ALL: List<StaffRank> = listOf(
${ranks.map(r=>`        StaffRank("${r.tag}", ${hex(r.color)}, ${r.weight}),`).join('\n')}
    )
}
`;
fs.writeFileSync('Palette.kt',k);
