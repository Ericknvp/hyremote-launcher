const fs=require('fs');
const html=fs.readFileSync(process.argv[2],'utf8');
const s=html.match(/<script>([\s\S]*?)<\/script>/)[1];
// simular el DOM minimo que usa el script
const ids=['apps','editor','editorList','editBtn','editDone','track','dot','coords','homeBtn','mute','muteIcon','vfill','vnum','status','statusText','lat','text','send','mouseToggle','mouseLabel','mouseHint','mouseBar'];
const store={};
global.document={
  getElementById:(id)=>store[id]||null,
  querySelectorAll:()=>[],
  createElement:()=>({style:{},dataset:{},classList:{add(){},remove(){},toggle(){}},
    addEventListener(){},appendChild(){},setAttribute(){},querySelector:()=>null,
    get firstChild(){return null},scrollIntoView(){}}),
  addEventListener(){},
};
global.localStorage={getItem:()=>null,setItem(){}};
global.fetch=async()=>({text:async()=>''});
global.navigator={};
global.performance={now:()=>0};
global.setTimeout=()=>0; global.clearTimeout=()=>{};
global.setInterval=()=>0; global.clearInterval=()=>{};
try{ new Function(s)(); console.log('EJECUTO OK'); }
catch(e){ console.log('ERROR EN EJECUCION:', e.message); console.log(e.stack.split('\n').slice(0,4).join('\n')); }
