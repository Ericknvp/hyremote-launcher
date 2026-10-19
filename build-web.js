// Generates www/index.html from www/src.js as UTF-8, no BOM.
// Do NOT use PowerShell `node src.js > index.html`: PowerShell 5 redirects
// through the console codepage and writes UTF-16, which mangles accents.
const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(path.join(__dirname, 'www', 'src.js'), 'utf8');
// src.js is a template literal that documents the full page.
const start = src.indexOf('<!DOCTYPE');
const end = src.lastIndexOf('</html>');
if (start < 0 || end < 0) {
  console.error('No encontro <!DOCTYPE ... </html> en src.js');
  process.exit(1);
}
const html = src.slice(start, end + '</html>'.length) + '\n';

// Inline the brand mark so the page is one self-contained file: the bridge
// serves it straight from /system, with no extra request and no asset server.
const logoPath = path.join(__dirname, 'res', 'drawable', 'logo_web.png');
if (!fs.existsSync(logoPath)) {
  console.error('Falta res/drawable/logo_web.png (generá el logo con tools/make-logo.py)');
  process.exit(1);
}
const logoDataUri = 'data:image/png;base64,' + fs.readFileSync(logoPath).toString('base64');
if (!html.includes('__LOGO_DATA_URI__')) {
  console.error('ERROR: no hay __LOGO_DATA_URI__ en src.js');
  process.exit(1);
}
const withLogo = html.split('__LOGO_DATA_URI__').join(logoDataUri);

const out = path.join(__dirname, 'www', 'index.html');
fs.writeFileSync(out, Buffer.from(withLogo, 'utf8'));

// Verify there is no BOM and the accents survived.
const check = fs.readFileSync(out);
const bom = check[0] === 0xFF && check[1] === 0xFE;
const text = check.toString('utf8');
const accents = ['línea', 'Atrás', 'Enviar', 'Escribir en el proyector'];
const missing = accents.filter((a) => !text.includes(a));

console.log('index.html generado:', check.length, 'bytes');
console.log('BOM UTF-16:', bom ? 'SI (mal)' : 'no');
console.log('acentos presentes:', missing.length === 0 ? 'todos' : 'FALTAN ' + missing.join(', '));

// Syntax-check the inline script. A runtime error here renders a perfectly
// good-looking remote where no button works, which is very hard to spot.
const scriptMatch = text.match(/<script>([\s\S]*?)<\/script>/);
if (!scriptMatch) {
  console.error('ERROR: no hay <script> en el HTML');
  process.exit(1);
}
try {
  new Function(scriptMatch[1]);
  console.log('JavaScript: OK');
} catch (e) {
  console.error('ERROR de JavaScript:', e.message);
  process.exit(1);
}

// No stray Node wrapper should ever reach the page again.
if (/const DOC|process\.stdout/.test(text)) {
  console.error('ERROR: la plantilla de Node se coló en el HTML');
  process.exit(1);
}