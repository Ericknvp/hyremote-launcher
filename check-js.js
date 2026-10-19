const fs = require('fs');
const html = fs.readFileSync('www/index.html','utf8');
const m = html.match(/<script>([\s\S]*?)<\/script>/);
if (!m) { console.log('NO HAY SCRIPT'); process.exit(1); }
try {
  new Function(m[1]);
  console.log('JS OK - ' + m[1].length + ' chars');
} catch (e) {
  console.log('JS ERROR: ' + e.message);
}
