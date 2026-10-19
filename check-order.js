const fs=require('fs');
const html=fs.readFileSync(process.argv[2],'utf8');
const s=html.match(/<script>([\s\S]*?)<\/script>/)[1];
// orden de las sentencias clave
const lines=s.split('\n');
['loadApps();','renderApps();','const editor','editBtn','appsRow','renderEditor','currentOrdered','function renderApps','function loadApps'].forEach(k=>{
  const i=lines.findIndex(l=>l.includes(k));
  console.log(String(i).padStart(4), k);
});
