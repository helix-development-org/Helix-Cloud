
const stage = document.querySelector('three-d-stage');
const { THREE } = await stage.ready;
const P = 1/16;

const ROLES = {
  mayor:   { label:'Bürgermeister', name:'Bertold Knubbel', quote:'Ich habe diesen Brunnen persönlich eröffnet. Zweimal.', robe:'#8e2a1a' },
  merchant:{ label:'Händlerin', name:'Mira Feilsch', quote:'Für dich? Sonderpreis. Also der normale.', robe:'#3a8a4a' },
  smith:   { label:'Schmied', name:'Ambo Hammerl', quote:'Schnellere Schlösser? Klong. Erledigt.', robe:'#5e5e66' },
  miner:   { label:'Minenarbeiter', name:'Pitt Stollen', quote:'Heute glitzert’s! Oder ich hab Staub im Auge.', robe:'#7a5a3a' },
};

const mat = (name, color, o={}) => new THREE.MeshStandardMaterial({ name, color, roughness:o.r??0.85, metalness:o.m??0, emissive:o.e??'#000000', emissiveIntensity:o.ei??0 });
const M = {
  skin: mat('skin','#c99872'), nose: mat('skin_nose','#b9835e'), white: mat('eye_white','#f4efe6',{r:.5}),
  iris: mat('eye_iris','#3fae4a',{r:.4}), pupil: mat('eye_pupil','#141010',{r:.3}), brow: mat('brow','#4a2e1c'),
  mouth: mat('mouth','#3a1a12'), gold: mat('gold','#f2c14e',{r:.35,m:.35}), shoe: mat('shoe','#3b2617'), trousers: mat('trousers','#4a3a2c'),
  wood: mat('wood','#8a5a30'), iron: mat('iron','#a8adb6',{r:.4,m:.3}), leather: mat('leather','#6b4a2e'),
  dark: mat('hat_dark','#2a1a14'), scarf: mat('scarf','#7a3aa0'), helmet: mat('helmet','#e0b030',{r:.5,m:.1}),
  lamp: mat('lamp','#fff2b0',{e:'#ffd968',ei:1.4,r:.3}),
};

function box(name,w,h,d,m,x,y,z,parent){ const o=new THREE.Mesh(new THREE.BoxGeometry(w*P,h*P,d*P),m); o.name=name; o.position.set(x*P,y*P,z*P); parent.add(o); return o; }
function piv(name,x,y,z,parent){ const g=new THREE.Group(); g.name=name; g.position.set(x*P,y*P,z*P); parent.add(g); return g; }

let rig = null;
function build(key){
  const r = ROLES[key];
  const robe = mat('robe', r.robe), robeDark = mat('robe_trim', new THREE.Color(r.robe).multiplyScalar(.7));
  const root = new THREE.Group(); root.name = 'villager_'+key;
  const bob = piv('bob',0,0,0,root);

  box('shoe_l',4,2,5,M.shoe,-2.3,1,0.6,root); box('shoe_r',4,2,5,M.shoe,2.3,1,0.6,root);
  box('leg_l',3.6,7,3.6,M.trousers,-2.1,5.5,0,bob); box('leg_r',3.6,7,3.6,M.trousers,2.1,5.5,0,bob);
  box('robe',9,12,6,robe,0,14,0,bob);
  box('robe_placket',1.6,12,0.2,robeDark,0,14,3.05,bob);
  box('collar',7,1.4,6.4,robeDark,0,19.9,0,bob);
  box('belt',9.2,1.2,6.2,M.leather,0,12,0,bob);
  box('belt_buckle',1.8,1.6,0.3,M.iron,0,12,3.2,bob);
  box('sleeve_l',3,3.4,5,robe,-4.4,17,2.2,bob); box('sleeve_r',3,3.4,5,robe,4.4,17,2.2,bob);
  box('forearms',11.2,3.2,3.4,robe,0,16.1,5.6,bob);
  box('cuffs',11.4,3.4,0.6,robeDark,0,16.1,7.1,bob);
  box('hands',3.6,2.8,3.6,M.skin,0,16.1,5.8,bob);

  const head = piv('head_pivot',0,20,0,bob);
  box('head',12,12,11,M.skin,0,6,0,head);
  const brow = piv('brow_pivot',0,8.6,5.55,head);
  box('brow',9.4,1.7,1,M.brow,0,0,0.3,brow);
  const lids = [];
  for (const s of [-1,1]){
    box('eye_white_'+(s<0?'l':'r'),3.2,2.4,0.3,M.white,s*2.5,6.9,5.6,head);
    const iris = box('eye_iris_'+(s<0?'l':'r'),1.7,2.1,0.3,M.iris,s*2.1,6.9,5.75,head);
    box('eye_pupil_'+(s<0?'l':'r'),0.8,1.1,0.2,M.pupil,0,-0.2,0.2,iris);
    const lid = piv('lid_pivot_'+(s<0?'l':'r'),s*2.5,8.15,5.95,head);
    box('lid_'+(s<0?'l':'r'),3.4,2.6,0.3,M.skin,0,-1.3,0,lid);
    lid.scale.y = 0.05; lids.push(lid);
  }
  const mouth = piv('mouth_pivot',0,2.2,5.52,head);
  box('mouth',6.4,1.6,0.3,M.mouth,0,0,0,mouth);
  const nose = piv('nose_pivot',0,6.2,5.5,head);
  box('nose',3.4,6.2,3.4,M.nose,0,-2.9,1.6,nose);

  if (key==='mayor'){
    box('hat_brim',13.4,1,12.4,M.dark,0,12.5,0,head);
    box('hat_top',9,6,8.5,M.dark,0,16,0,head);
    box('hat_band',9.2,1.4,8.7,M.gold,0,13.8,0,head);
    box('chain',8,0.8,0.4,M.gold,0,19.3,3.12,bob);
    box('medallion',2.6,2.6,0.6,M.gold,0,18.6,3.25,bob);
    box('robe_hem',9.1,0.8,6.1,M.gold,0,8.4,0,bob);
  }
  if (key==='merchant'){
    box('scarf_top',12.6,4,11.6,M.scarf,0,10.4,-0.1,head);
    box('scarf_tail',4,5,1,M.scarf,-3,9,-5.9,head);
    box('pouch',3,3.4,2,M.leather,5.4,10.6,1,bob);
    box('pouch_coin',1.4,1.4,0.4,M.gold,6.05,11,1,bob);
  }
  if (key==='smith'){
    box('apron',7,9,0.4,M.leather,0,10.5,3.25,bob);
    box('brow_thick',9.8,0.8,1,M.brow,0,-1,0.3,brow);
    const ham = piv('hammer',3.5,16.5,6.8,bob); ham.rotation.z = -0.35;
    box('hammer_handle',1.2,10,1.2,M.wood,0,3.5,0,ham);
    box('hammer_head',4.6,2.8,2.8,M.iron,0,8.8,0,ham);
  }
  if (key==='miner'){
    box('helmet',12.8,3.4,11.8,M.helmet,0,11.4,0,head);
    box('helmet_brim',13.6,0.8,12.6,M.helmet,0,10,0,head);
    box('lamp_case',3,2.4,1,M.iron,0,11.6,6.2,head);
    box('lamp',2,1.6,0.4,M.lamp,0,11.6,6.75,head);
    box('soot',2.2,1.2,0.2,M.brow,3.6,4.2,5.56,head);
  }
  stage.setObject(root);
  rig = { bob, head, brow, lids, mouth, nose, browY: brow.position.y };
  document.getElementById('who').innerHTML = `<b>${r.name}</b><span class="eyebrow">${r.label}</span><i>„${r.quote}“</i>`;
  document.querySelectorAll('#roles button').forEach(b=>b.classList.toggle('on', b.dataset.k===key));
}

const rolesEl = document.getElementById('roles');
for (const [k,r] of Object.entries(ROLES)){
  const b = document.createElement('button'); b.textContent = r.label; b.dataset.k = k;
  b.onclick = () => build(k); rolesEl.appendChild(b);
}
let animOn = true;
const tog = document.getElementById('anim');
tog.onclick = () => { animOn = !animOn; tog.textContent = animOn ? 'Animation an' : 'Animation aus'; tog.classList.toggle('off', !animOn);
  if (!animOn && rig){ rig.bob.position.y=0; rig.head.rotation.set(0,0,0); rig.nose.rotation.set(0,0,0); rig.mouth.scale.y=1; rig.brow.position.y=rig.browY; rig.lids.forEach(l=>l.scale.y=.05);} };

build('mayor');

let nextBlink = 2, nextBrow = 4;
const clock = new THREE.Clock();
(function tick(){
  requestAnimationFrame(tick);
  if (!animOn || !rig) return;
  const t = clock.getElapsedTime();
  rig.bob.position.y = Math.abs(Math.sin(t*Math.PI*0.5))*0.35*P;
  rig.head.rotation.y = Math.sin(t*0.45)*0.35;
  rig.head.rotation.z = Math.sin(t*0.9)*0.04;
  const talking = (t % 6) < 2.6;
  rig.mouth.scale.y = talking ? 0.6 + Math.abs(Math.sin(t*9))*1.4 : 1;
  rig.nose.rotation.x = talking ? Math.sin(t*9)*0.12 : Math.sin(t*1.2)*0.03;
  rig.nose.rotation.z = Math.sin(t*2.1)*0.05;
  if (t > nextBlink){ const k=(t-nextBlink); rig.lids.forEach(l=>l.scale.y = k<0.14 ? 1 : 0.05); if (k>0.14) nextBlink = t + 2.5 + Math.random()*3.5; }
  if (t > nextBrow){ const k=(t-nextBrow); rig.brow.position.y = rig.browY + (k<0.9 ? Math.sin(k/0.9*Math.PI)*0.9*P : 0); if (k>0.9) nextBrow = t + 4 + Math.random()*4; }
})();
