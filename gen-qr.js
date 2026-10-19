// Static QR generator - a debugging aid.
//
// The launcher no longer ships a fixed QR: it encodes the projector's own
// WiFi address and the port the bridge actually bound, at runtime, so the
// same APK works on any projector in any home. This script is only here to
// produce a PNG you can read with a phone while debugging the bridge.
//
//   node gen-qr.js [url]        (default: http://192.168.40.14:8080)
const QR = require("qrcode");
const url = process.argv[2] || "http://192.168.40.14:8080";
const out = require("path").join(__dirname, "build", "qr-static.png");
QR.toFile(out, url, {
  errorCorrectionLevel: "M",
  margin: 2,
  width: 512,
  color: { dark: "#05070C", light: "#FFFFFF" }
}).then(() => console.log("QR generado para", url, "->", out));
