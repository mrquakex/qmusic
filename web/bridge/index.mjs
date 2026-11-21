// The browser's half of Nuvelo Web: Firebase (sign-in and the Realtime Database), the cryptography of the end-to-end
// encryption, and the files (picked, made smaller, sealed, sent up, fetched). The Kotlin side calls these with plain
// strings and numbers; big data stays here as Blobs, known to Kotlin by a number.

import { initializeApp } from 'firebase/app';
import {
  initializeAuth, indexedDBLocalPersistence, browserLocalPersistence, browserPopupRedirectResolver,
  onAuthStateChanged, GoogleAuthProvider, signInWithPopup, signInWithRedirect, getRedirectResult, signOut, signInWithCustomToken,
} from 'firebase/auth';
import {
  getDatabase, ref, onValue, get, set, update, remove, push, query, orderByChild, orderByKey, orderByValue, startAt, endAt,
  equalTo, limitToFirst, limitToLast, runTransaction, onDisconnect, onChildAdded, onChildChanged, onChildRemoved,
} from 'firebase/database';
import { getMessaging, getToken, deleteToken, isSupported as messagingSupported } from 'firebase/messaging';
import { p256 } from '@noble/curves/p256';
import { gcm } from '@noble/ciphers/aes';
import { sha256 as nobleSha256, sha512 as nobleSha512 } from '@noble/hashes/sha2';
import { hmac } from '@noble/hashes/hmac';
import qrcode from 'qrcode-generator';

// ------------------------------------------------------------------ Firebase

const app = initializeApp({
  apiKey: 'AIzaSyB7YBpZE8VGH3Bra3-JP-H30ncrWCwarOU',
  authDomain: 'qmusic-2552b.firebaseapp.com',
  databaseURL: 'https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app',
  projectId: 'qmusic-2552b',
  storageBucket: 'qmusic-2552b.firebasestorage.app',
  messagingSenderId: '750114797526',
});
const auth = initializeAuth(app, {
  persistence: [indexedDBLocalPersistence, browserLocalPersistence],
  popupRedirectResolver: browserPopupRedirectResolver,
});
const db = getDatabase(app);

/** A place in the database: the root when [path] is empty (Firebase's web SDK does not take "" for it). */
const at = (path) => (path ? ref(db, path) : ref(db));
getRedirectResult(auth).catch(() => {});

/** A snapshot as JSON that keeps the order of its children: a leaf is its value, a parent {"c": [[key, child], ...]}. */
function tree(snap) {
  if (!snap.exists()) return null;
  if (!snap.hasChildren()) return snap.val();
  const c = [];
  snap.forEach((ch) => { c.push([ch.key, tree(ch)]); });
  return { c };
}

function q(path, spec) {
  const r = at(path);
  if (!spec) return r;
  const parts = JSON.parse(spec).map(([op, a]) => {
    switch (op) {
      case 'orderByChild': return orderByChild(a);
      case 'orderByKey': return orderByKey();
      case 'orderByValue': return orderByValue();
      case 'startAt': return startAt(a);
      case 'endAt': return endAt(a);
      case 'equalTo': return equalTo(a);
      case 'limitToFirst': return limitToFirst(a);
      case 'limitToLast': return limitToLast(a);
      default: throw new Error('query ' + op);
    }
  });
  return parts.length ? query(r, ...parts) : r;
}

const listeners = new Map();
let nextListener = 1;

export function dbOn(path, spec, cb, err) {
  const id = nextListener++;
  const off = onValue(q(path, spec), (s) => cb(JSON.stringify(tree(s))), (e) => err(String(e && e.message || e)));
  listeners.set(id, off);
  return id;
}

export function dbChildOn(path, cb) {
  const id = nextListener++;
  const r = at(path);
  const a = onChildAdded(r, (s) => cb('added', s.key, JSON.stringify(tree(s))));
  const c = onChildChanged(r, (s) => cb('changed', s.key, JSON.stringify(tree(s))));
  const d = onChildRemoved(r, (s) => cb('removed', s.key, JSON.stringify(tree(s))));
  listeners.set(id, () => { a(); c(); d(); });
  return id;
}

export function dbOff(id) {
  const off = listeners.get(id);
  if (off) { listeners.delete(id); off(); }
}

export async function dbGet(path, spec) {
  return JSON.stringify(tree(await get(q(path, spec))));
}

export function dbSet(path, json) { return set(at(path), JSON.parse(json)).then(() => null); }
export function dbUpdate(path, json) { return update(at(path), JSON.parse(json)).then(() => null); }
export function dbRemove(path) { return remove(at(path)).then(() => null); }
export function dbPushKey(path) { return push(at(path)).key; }

const ABORT = '\u0000abort';

export async function dbTransaction(path, fn) {
  const r = await runTransaction(at(path), (cur) => {
    const out = fn(JSON.stringify(cur === undefined ? null : cur));
    return out === ABORT ? undefined : JSON.parse(out);
  });
  return JSON.stringify({ committed: r.committed, v: tree(r.snapshot) });
}

export function dbOnDisconnect(path, op, json) {
  const d = onDisconnect(at(path));
  const p = op === 'set' ? d.set(JSON.parse(json)) : op === 'update' ? d.update(JSON.parse(json)) : op === 'remove' ? d.remove() : d.cancel();
  return p.then(() => null);
}

// ------------------------------------------------------------------ sign-in

export function authListen(cb) {
  onAuthStateChanged(auth, (u) => cb(u ? JSON.stringify({ uid: u.uid, name: u.displayName || '', photo: u.photoURL || '' }) : null));
}

/** Google's account picker: a popup, or the whole page going there where a popup cannot open (the result comes back on load). */
export async function authGoogle() {
  const p = new GoogleAuthProvider();
  p.setCustomParameters({ prompt: 'select_account' });
  try {
    await signInWithPopup(auth, p);
    return 'ok';
  } catch (e) {
    const code = e && e.code;
    if (code === 'auth/popup-closed-by-user' || code === 'auth/cancelled-popup-request' || code === 'auth/user-cancelled') return 'cancel';
    if (code === 'auth/popup-blocked' || code === 'auth/operation-not-supported-in-this-environment' || code === 'auth/web-storage-unsupported') {
      await signInWithRedirect(auth, p);
      return 'redirect';
    }
    throw new Error(code || String(e));
  }
}

/** A sign-in made by Nuvelo's server (used to try the page with the test account). */
export function authCustom(token) { return signInWithCustomToken(auth, token).then(() => null); }

export async function authToken(force) {
  const u = auth.currentUser;
  if (!u) throw new Error('not signed in');
  return await u.getIdToken(!!force);
}

export function authOut() { return signOut(auth).then(() => null); }
export function authDeleteUser() { return auth.currentUser ? auth.currentUser.delete().then(() => null) : Promise.resolve(null); }

// ------------------------------------------------------------------ bytes between here and Kotlin (base64)

function b64(u8) {
  let s = '';
  for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(s);
}

function unb64(s) {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

const utf8 = new TextEncoder();

// ------------------------------------------------------------------ cryptography (the same as the phone's: P-256, AES-GCM, SHA-2)

export function randomB64(n) { return b64(crypto.getRandomValues(new Uint8Array(n))); }

/** A new P-256 key pair: the 32-byte private number and the 65-byte public point (uncompressed). */
export function ecNew() {
  const priv = p256.utils.randomPrivateKey();
  return JSON.stringify({ priv: b64(priv), pub: b64(p256.getPublicKey(priv, false)) });
}

export function ecPublicOf(privB64) { return b64(p256.getPublicKey(unb64(privB64), false)); }

/** ECDH as Java's KeyAgreement gives it: the shared point's x. */
export function ecdh(privB64, pubB64) { return b64(p256.getSharedSecret(unb64(privB64), unb64(pubB64), true).subarray(1)); }

export function ecValid(pubB64) {
  try { p256.ProjectivePoint.fromHex(unb64(pubB64)).assertValidity(); return true; } catch { return false; }
}

export function gcmSeal(keyB64, nonceB64, plainB64, aadB64) {
  const aad = aadB64 ? unb64(aadB64) : undefined;
  return b64(gcm(unb64(keyB64), unb64(nonceB64), aad).encrypt(unb64(plainB64)));
}

export function gcmOpen(keyB64, nonceB64, sealedB64, aadB64) {
  const aad = aadB64 ? unb64(aadB64) : undefined;
  return b64(gcm(unb64(keyB64), unb64(nonceB64), aad).decrypt(unb64(sealedB64)));
}

export function sha256B64(b) { return b64(nobleSha256(unb64(b))); }
export function hmac256(keyB64, dataB64) { return b64(hmac(nobleSha256, unb64(keyB64), unb64(dataB64))); }

/** PBKDF2-HMAC-SHA256 with the browser's own (fast) implementation. */
export async function pbkdf2(password, saltB64, rounds, length) {
  const key = await crypto.subtle.importKey('raw', utf8.encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: unb64(saltB64), iterations: rounds }, key, length * 8);
  return b64(new Uint8Array(bits));
}

/** One half of the safety number (as the phone works it out: SHA-512, 5200 rounds). */
export function safetyHalf(uid, keyB64) {
  const key = unb64(keyB64);
  const cat = (...parts) => { const n = parts.reduce((a, p) => a + p.length, 0); const o = new Uint8Array(n); let i = 0; for (const p of parts) { o.set(p, i); i += p.length; } return o; };
  let h = nobleSha512(cat(new Uint8Array([0, 0]), key, utf8.encode(uid)));
  for (let r = 0; r < 5200; r++) h = nobleSha512(cat(h, key));
  let out = '';
  for (let i = 0; i < 6; i++) {
    const n = h[i * 5] * 2 ** 32 + h[i * 5 + 1] * 2 ** 24 + h[i * 5 + 2] * 2 ** 16 + h[i * 5 + 3] * 2 ** 8 + h[i * 5 + 4];
    out += String(n % 100000).padStart(5, '0');
  }
  return out;
}

// ------------------------------------------------------------------ the device's own lock (a key that cannot be read out)

function idb() {
  return new Promise((ok, fail) => {
    const r = indexedDB.open('nuvelo', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('keys');
    r.onsuccess = () => ok(r.result);
    r.onerror = () => fail(r.error);
  });
}

async function deviceKey() {
  const d = await idb();
  const got = await new Promise((ok) => {
    const t = d.transaction('keys', 'readonly').objectStore('keys').get('device');
    t.onsuccess = () => ok(t.result);
    t.onerror = () => ok(null);
  });
  if (got) return got;
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  await new Promise((ok, fail) => {
    const t = d.transaction('keys', 'readwrite').objectStore('keys').put(key, 'device');
    t.onsuccess = () => ok();
    t.onerror = () => fail(t.error);
  });
  return key;
}

export async function deviceSeal(plainB64) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const c = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await deviceKey(), unb64(plainB64)));
  const out = new Uint8Array(12 + c.length);
  out.set(iv); out.set(c, 12);
  return b64(out);
}

export async function deviceOpen(sealedB64) {
  try {
    const b = unb64(sealedB64);
    const p = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: b.subarray(0, 12) }, await deviceKey(), b.subarray(12));
    return b64(new Uint8Array(p));
  } catch { return null; }
}

// ------------------------------------------------------------------ files: kept here, known by a number

const blobs = new Map();
let nextBlob = 1;
function keep(b) { const id = nextBlob++; blobs.set(id, b); return id; }
export function blobDrop(id) { const b = blobs.get(id); if (b && b.__url) URL.revokeObjectURL(b.__url); blobs.delete(id); }
export function blobSize(id) { const b = blobs.get(id); return b ? b.size : 0; }
export function blobUrl(id) { const b = blobs.get(id); if (!b) return ''; if (!b.__url) b.__url = URL.createObjectURL(b); return b.__url; }
export async function blobBytes(id) { const b = blobs.get(id); return b ? new Int8Array(await b.arrayBuffer()) : new Int8Array(0); }
export function blobFromBytes(bytes, type) { return keep(new Blob([bytes], { type: type || 'application/octet-stream' })); }

/** Opens the browser's file picker: the files picked, as [{id, name, type, size}] (empty when none). */
export function pickFiles(accept, multiple, capture) {
  return new Promise((ok) => {
    const input = document.createElement('input');
    input.type = 'file';
    if (accept) input.accept = accept;
    if (multiple) input.multiple = true;
    if (capture) input.setAttribute('capture', capture);
    input.style.display = 'none';
    let done = false;
    const finish = (list) => { if (done) return; done = true; input.remove(); ok(JSON.stringify(list)); };
    input.onchange = () => finish(Array.from(input.files || []).map((f) => ({ id: keep(f), name: f.name, type: f.type || '', size: f.size })));
    input.oncancel = () => finish([]);
    document.body.appendChild(input);
    input.click();
  });
}

async function bitmapOf(blob) {
  try { return await createImageBitmap(blob, { imageOrientation: 'from-image' }); } catch { /* older Safari */ }
  const url = URL.createObjectURL(blob);
  try {
    const img = new Image();
    img.src = url;
    await img.decode();
    return img;
  } finally { setTimeout(() => URL.revokeObjectURL(url), 1000); }
}

function canvasOf(src, w, h) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const g = c.getContext('2d');
  g.imageSmoothingQuality = 'high';
  g.drawImage(src, 0, 0, w, h);
  return c;
}

const jpeg = (c, quality) => new Promise((ok) => c.toBlob((b) => ok(b), 'image/jpeg', quality));

/**
 * A photo made ready to send, as the phone does it: at most [maxSide] on its long side, JPEG; and a tiny blurred
 * preview (base64) that shows at once. Returns {id, w, h, thumb}.
 */
export async function prepareImage(id, maxSide, quality) {
  const src = await bitmapOf(blobs.get(id));
  const w0 = src.width, h0 = src.height;
  const s = Math.min(1, maxSide / Math.max(w0, h0));
  const w = Math.max(1, Math.round(w0 * s)), h = Math.max(1, Math.round(h0 * s));
  const out = await jpeg(canvasOf(src, w, h), quality);
  const ts = Math.min(1, 24 / Math.max(w0, h0));
  const thumbBlob = await jpeg(canvasOf(src, Math.max(1, Math.round(w0 * ts)), Math.max(1, Math.round(h0 * ts))), 0.5);
  const thumb = b64(new Uint8Array(await thumbBlob.arrayBuffer()));
  return JSON.stringify({ id: keep(out), w, h, thumb });
}

/** A video's size, length and a tiny preview of its first moment: {w, h, ms, thumb}. */
export function videoInfo(id) {
  return new Promise((ok) => {
    const v = document.createElement('video');
    v.muted = true; v.playsInline = true; v.preload = 'metadata';
    v.src = blobUrl(id);
    const fail = () => ok(JSON.stringify({ w: 0, h: 0, ms: 0, thumb: '' }));
    v.onerror = fail;
    v.onloadedmetadata = () => { v.currentTime = Math.min(0.5, (v.duration || 1) / 2); };
    v.onseeked = async () => {
      try {
        const w0 = v.videoWidth, h0 = v.videoHeight;
        const ts = Math.min(1, 24 / Math.max(w0, h0, 1));
        const b = await jpeg(canvasOf(v, Math.max(1, Math.round(w0 * ts)), Math.max(1, Math.round(h0 * ts))), 0.5);
        ok(JSON.stringify({ w: w0, h: h0, ms: Math.round((v.duration || 0) * 1000), thumb: b64(new Uint8Array(await b.arrayBuffer())) }));
      } catch { fail(); }
    };
    setTimeout(fail, 8000);
  });
}

// ------------------------------------------------------------------ the sealed files (the phone's E2eStream: 64 KB pieces)

const PIECE = 64 * 1024;
const nonceOf = (prefix, i) => { const n = new Uint8Array(12); n.set(prefix); new DataView(n.buffer).setUint32(8, i); return n; };
const aadOf = (i, last) => { const a = new Uint8Array(5); new DataView(a.buffer).setUint32(0, i); a[4] = last ? 1 : 0; return a; };

function sealBytes(key, plain) {
  const prefix = crypto.getRandomValues(new Uint8Array(8));
  const pieces = Math.max(1, Math.ceil(plain.length / PIECE));
  const out = new Uint8Array(8 + plain.length + 16 * pieces);
  out.set(prefix);
  let o = 8;
  for (let i = 0; i < pieces; i++) {
    const part = plain.subarray(i * PIECE, Math.min(plain.length, (i + 1) * PIECE));
    const c = gcm(key, nonceOf(prefix, i), aadOf(i, i === pieces - 1)).encrypt(part);
    out.set(c, o); o += c.length;
  }
  return out;
}

function openBytes(key, sealed) {
  if (sealed.length < 24) throw new Error('short');
  const prefix = sealed.subarray(0, 8);
  const body = sealed.subarray(8);
  const pieces = Math.max(1, Math.ceil(body.length / (PIECE + 16)));
  const out = new Uint8Array(body.length - 16 * pieces);
  let o = 0;
  for (let i = 0; i < pieces; i++) {
    const part = body.subarray(i * (PIECE + 16), Math.min(body.length, (i + 1) * (PIECE + 16)));
    const p = gcm(key, nonceOf(prefix, i), aadOf(i, i === pieces - 1)).decrypt(part);
    out.set(p, o); o += p.length;
  }
  return out;
}

/** The file [id] sealed with a fresh key: {id, key}. */
export async function sealBlob(id) {
  const key = crypto.getRandomValues(new Uint8Array(32));
  const plain = new Uint8Array(await blobs.get(id).arrayBuffer());
  return JSON.stringify({ id: keep(new Blob([sealBytes(key, plain)], { type: 'application/octet-stream' })), key: b64(key) });
}

export function openSealedB64(keyB64, sealedB64) { return b64(openBytes(unb64(keyB64), unb64(sealedB64))); }

const opened = new Map();

/** While developing on the computer, the media server's files are asked of the dev server (which passes them on). */
const local = (url) => location.hostname === 'localhost' ? url.replace('https://qeyf.159.195.147.140.sslip.io', '') : url;

/** The file at [url] fetched (and opened when [keyB64] is given), as a link this page can play or show. */
export async function mediaUrl(url, keyB64, type) {
  const k = url + '|' + (keyB64 || '');
  if (opened.has(k)) return opened.get(k);
  const r = await fetch(local(url));
  if (!r.ok) throw new Error('http ' + r.status);
  let bytes = new Uint8Array(await r.arrayBuffer());
  if (keyB64) bytes = openBytes(unb64(keyB64), bytes);
  const u = URL.createObjectURL(new Blob([bytes], { type: type || '' }));
  opened.set(k, u);
  return u;
}

/** The bytes at [url] (opened with [keyB64] when given). */
export async function fetchBytes(url, keyB64) {
  const r = await fetch(local(url));
  if (!r.ok) return null;
  let bytes = new Uint8Array(await r.arrayBuffer());
  if (keyB64) bytes = openBytes(unb64(keyB64), bytes);
  return new Int8Array(bytes.buffer, bytes.byteOffset, bytes.length);
}

/** Sends the file [id] to Nuvelo's media server; [progress] hears the bytes sent. Resolves to the file's link. */
export function upload(id, url, token, mime, progress) {
  return new Promise((ok, fail) => {
    const x = new XMLHttpRequest();
    x.open('POST', url);
    x.setRequestHeader('Authorization', 'Bearer ' + token);
    x.setRequestHeader('Content-Type', mime);
    x.upload.onprogress = (e) => progress(e.loaded);
    x.onload = () => {
      if (x.status < 200 || x.status >= 300) return fail(new Error('upload ' + x.status));
      try { ok(JSON.parse(x.responseText).url); } catch (e) { fail(e); }
    };
    x.onerror = () => fail(new Error('upload network'));
    x.send(blobs.get(id));
  });
}

/** A request to Nuvelo's server with the person's sign-in: {status, body} (body as text). */
export async function api(method, url, token, json) {
  const r = await fetch(url, {
    method,
    headers: Object.assign({ Authorization: 'Bearer ' + token }, json != null ? { 'Content-Type': 'application/json' } : {}),
    body: json != null ? json : undefined,
  });
  return JSON.stringify({ status: r.status, body: await r.text() });
}

/** The same, with a file as the body (a voice message to be written out): {status, body}. */
export async function apiBlob(url, token, id) {
  const r = await fetch(url, { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/octet-stream' }, body: blobs.get(id) });
  return JSON.stringify({ status: r.status, body: await r.text() });
}

// ------------------------------------------------------------------ sound: voice messages, reading aloud

let recorder = null;
let chunks = [];
let recStart = 0;
let analyser = null;
let levels = [];
let levelTimer = 0;

export async function recStartNow() {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
  const types = ['audio/mp4;codecs=mp4a.40.2', 'audio/mp4', 'audio/webm;codecs=opus', 'audio/webm'];
  const type = types.find((t) => window.MediaRecorder && MediaRecorder.isTypeSupported(t)) || '';
  recorder = new MediaRecorder(stream, type ? { mimeType: type, audioBitsPerSecond: 48000 } : undefined);
  chunks = [];
  levels = [];
  recorder.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
  recorder.start(250);
  recStart = performance.now();
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const src = ctx.createMediaStreamSource(stream);
    analyser = ctx.createAnalyser();
    analyser.fftSize = 512;
    src.connect(analyser);
    const data = new Uint8Array(analyser.fftSize);
    levelTimer = setInterval(() => {
      analyser.getByteTimeDomainData(data);
      let peak = 0;
      for (const v of data) peak = Math.max(peak, Math.abs(v - 128));
      levels.push(Math.min(100, Math.round(peak / 128 * 140)));
    }, 100);
    recorder.__ctx = ctx;
  } catch { /* no levels */ }
  return null;
}

/** The newest loudness (0-100), for the bars while recording. */
export function recLevel() { return levels.length ? levels[levels.length - 1] : 0; }

/** Stops: {id, type, ms, wave} (wave: 0-100 per bar), or null when it was cancelled ([keep] false). */
export function recStop(keepIt) {
  return new Promise((ok) => {
    if (!recorder) return ok(null);
    const r = recorder;
    recorder = null;
    clearInterval(levelTimer);
    r.onstop = () => {
      r.stream.getTracks().forEach((t) => t.stop());
      try { r.__ctx && r.__ctx.close(); } catch { /* closed */ }
      if (!keepIt) return ok(null);
      const ms = Math.round(performance.now() - recStart);
      const blob = new Blob(chunks, { type: r.mimeType || 'audio/mp4' });
      const bars = 48;
      const wave = [];
      for (let i = 0; i < bars; i++) {
        const a = Math.floor(i * levels.length / bars), b = Math.max(a + 1, Math.floor((i + 1) * levels.length / bars));
        let m = 0;
        for (let j = a; j < b && j < levels.length; j++) m = Math.max(m, levels[j]);
        wave.push(m);
      }
      ok(JSON.stringify({ id: keep(blob), type: (r.mimeType || 'audio/mp4').split(';')[0], ms, wave }));
    };
    r.stop();
  });
}

const players = new Map();
let nextPlayer = 1;

/** An audio element playing [url]; [onEvent] hears "time:<ms>", "duration:<ms>", "ended", "error". */
export function audioNew(url, onEvent) {
  const a = new Audio();
  a.preload = 'auto';
  a.src = url;
  const id = nextPlayer++;
  players.set(id, a);
  a.ontimeupdate = () => onEvent('time:' + Math.round(a.currentTime * 1000));
  a.ondurationchange = () => { if (isFinite(a.duration)) onEvent('duration:' + Math.round(a.duration * 1000)); };
  a.onended = () => onEvent('ended');
  a.onerror = () => onEvent('error');
  a.onplaying = () => onEvent('playing');
  a.onpause = () => onEvent('paused');
  return id;
}

export function audioPlay(id) { const a = players.get(id); if (a) a.play().catch(() => {}); }
export function audioPause(id) { const a = players.get(id); if (a) a.pause(); }
export function audioSeek(id, ms) { const a = players.get(id); if (a) a.currentTime = ms / 1000; }
export function audioRate(id, r) { const a = players.get(id); if (a) a.playbackRate = r; }
export function audioDrop(id) { const a = players.get(id); if (a) { a.pause(); a.removeAttribute('src'); a.load(); players.delete(id); } }

// ------------------------------------------------------------------ small things

/** The modules of a QR code for [text]: its size, then '1' and '0' row by row. */
export function qrMatrix(text) {
  const qr = qrcode(0, 'M');
  qr.addData(text, 'Byte');
  qr.make();
  const n = qr.getModuleCount();
  let s = n + ':';
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) s += qr.isDark(r, c) ? '1' : '0';
  return s;
}

export function saveText(name, text) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
  a.download = name;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000);
}

export function openUrl(url) { window.open(url, '_blank', 'noopener'); }

export function storeGet(k) { try { return localStorage.getItem(k); } catch { return null; } }
export function storeSet(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch { /* full or blocked */ } }

/** Where the person is (once), for sending a place: "lat,lng" or an error word. */
export function locate() {
  return new Promise((ok) => {
    if (!navigator.geolocation) return ok('none');
    navigator.geolocation.getCurrentPosition((p) => ok(p.coords.latitude + ',' + p.coords.longitude), (e) => ok(e.code === 1 ? 'denied' : 'failed'), { enableHighAccuracy: true, timeout: 15000 });
  });
}

// ------------------------------------------------------------------ notifications (Firebase Cloud Messaging, web push)

/** "unsupported" (this browser cannot; an iPhone only from the home screen), "default", "granted" or "denied". */
export async function pushState() {
  try {
    if (!('Notification' in window) || !('serviceWorker' in navigator) || !(await messagingSupported())) return 'unsupported';
    return Notification.permission;
  } catch { return 'unsupported'; }
}

async function token() {
  const reg = await navigator.serviceWorker.ready;
  return await getToken(getMessaging(app), { serviceWorkerRegistration: reg });
}

/** Asks for the permission (must be called from a tap) and gives this browser's push address, or null. */
export async function pushEnable() {
  if ((await pushState()) === 'unsupported') return null;
  const p = await Notification.requestPermission();
  if (p !== 'granted') return null;
  return await token();
}

/** The push address when notifications are allowed already, else null. */
export async function pushToken() {
  if ((await pushState()) !== 'granted') return null;
  try { return await token(); } catch { return null; }
}

export async function pushForget() { try { await deleteToken(getMessaging(app)); } catch { /* none */ } return null; }

/** A notification tapped while the page was open: [cb] gets its JSON. */
export function onPushOpen(cb) {
  if (!('serviceWorker' in navigator)) return;
  navigator.serviceWorker.addEventListener('message', (e) => { if (e.data && e.data.nuvelo === 'open') cb(JSON.stringify(e.data)); });
}

/** On an iPhone or iPad, outside the home screen (notifications only come once it is added there). */
export function iosBrowser() {
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const standalone = window.navigator.standalone === true || window.matchMedia('(display-mode: standalone)').matches;
  return ios && !standalone;
}

// Calls (WebRTC): see calls.mjs.
export * from './calls.mjs';
