// Drive headless Chromium over CDP. Usage: S=<dir> STEPS='[[kind,arg],...]' node cdp.mjs <w> <h> <prefix>
// kinds: click (button text or selector), clickfast, shot, eval, reload,
//        type [selector, text], key <name>, drag [selector, dy],
//        save [expr, path], file [inputSelector, path]
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
const [w, h, prefix] = process.argv.slice(2);
const port = 9333 + Math.floor(Math.random() * 500);
const chrome = spawn('chromium', ['--headless=new', `--remote-debugging-port=${port}`, '--no-first-run', `--user-data-dir=${process.env.S}/profile-${port}`, 'about:blank'], { stdio: 'ignore' });
const sleep = ms => new Promise(r => setTimeout(r, ms));
let ws, id = 0; const pending = new Map();
for (let i = 0; i < 150; i++) { try { const t = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(t => t.type === 'page'); ws = new WebSocket(t.webSocketDebuggerUrl); break; } catch { await sleep(200); } }
await new Promise(r => ws.onopen = r);
ws.onmessage = e => { const m = JSON.parse(e.data); if (pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } else if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') console.log('CONSOLE ERROR', JSON.stringify(m.params.args.map(a => a.value ?? a.description)).slice(0, 400)); else if (m.method === 'Runtime.exceptionThrown') console.log('EXCEPTION', m.params.exceptionDetails.exception?.description); };
const send = (method, params = {}) => new Promise(r => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const evalJs = async expr => (await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result?.result?.value;
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: +w, height: +h, deviceScaleFactor: 1, mobile: false });
await send('Page.navigate', { url: 'http://127.0.0.1:8391/' });
await sleep(3000);
const find = sel => `([...document.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(sel)}) || document.querySelector(${JSON.stringify(sel)}))`;
const shot = async name => { await evalJs("document.getElementById('--re-frame-10x--')?.remove()"); const r = await send('Page.captureScreenshot', { format: 'png' }); writeFileSync(`${process.env.S}/shots/${prefix}-${name}.png`, Buffer.from(r.result.data, 'base64')); };
const keys = { Enter: [13, '\r'], ArrowDown: [40], ArrowUp: [38], Escape: [27] };
for (const [kind, arg] of JSON.parse(process.env.STEPS)) {
  if (kind === 'click') { await evalJs(`${find(arg)}?.click()`); await sleep(900); }
  else if (kind === 'clickfast') { await evalJs(`${find(arg)}?.click()`); await sleep(300); }
  else if (kind === 'shot') await shot(arg);
  else if (kind === 'reload') { await send('Page.reload'); await sleep(3000); }
  else if (kind === 'type') { const [sel, text] = arg; await evalJs(`(() => { const el = document.querySelector(${JSON.stringify(sel)}); el.focus(); el.select && el.select(); })()`); await send('Input.insertText', { text }); await sleep(150); }
  else if (kind === 'key') { const [code, text] = keys[arg]; await send('Input.dispatchKeyEvent', { type: 'keyDown', key: arg, code: arg, windowsVirtualKeyCode: code, text }); await send('Input.dispatchKeyEvent', { type: 'keyUp', key: arg, code: arg, windowsVirtualKeyCode: code }); await sleep(300); }
  else if (kind === 'drag') { const [sel, dy] = arg; const [x, y] = await evalJs(`(() => { const b = document.querySelector(${JSON.stringify(sel)}).getBoundingClientRect(); return [b.left + b.width / 2, b.top + b.height / 2]; })()`); await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', buttons: 1, clickCount: 1 }); for (let i = 1; i <= 12; i++) { await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y: y + dy * i / 12, button: 'left', buttons: 1 }); await sleep(30); } await sleep(200); await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y: y + dy, button: 'left', buttons: 0, clickCount: 1 }); await sleep(400); }
  else if (kind === 'save') { const [expr, path] = arg; writeFileSync(path, String(await evalJs(expr))); console.log('saved', path); }
  else if (kind === 'file') { const [sel, path] = arg; const doc = await send('DOM.getDocument', { depth: -1 }); const q = await send('DOM.querySelector', { nodeId: doc.result.root.nodeId, selector: sel }); await send('DOM.setFileInputFiles', { files: [path], nodeId: q.result.nodeId }); await sleep(900); }
  else if (kind === 'eval') console.log(arg.length > 80 ? arg.slice(0, 77) + '...' : arg, '=>', JSON.stringify(await evalJs(arg)));
}
ws.close(); chrome.kill();
