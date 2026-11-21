// Calls in the browser: WebRTC, the same as the phones (Opus, one picture slot kept in voice calls so a camera can
// come on later). The signalling (offer, answer, candidates) goes through the database from the Kotlin side.

const calls = new Map();
let nextCall = 1;

/**
 * A call's connection. [ice] is the relay list ({urls, username, credential}[] as JSON); [onEvent] hears
 * "ice:<json>", "state:<connected|disconnected|failed|closed>", "track:video", "level:<0-100>".
 */
export async function rtcNew(ice, video, caller, onEvent) {
  const pc = new RTCPeerConnection({ iceServers: JSON.parse(ice), bundlePolicy: 'max-bundle', rtcpMuxPolicy: 'require' });
  const id = nextCall++;
  const c = { pc, local: null, remote: new MediaStream(), videoSender: null, camera: null, levelTimer: 0 };
  calls.set(id, c);
  pc.onicecandidate = (e) => {
    if (e.candidate) onEvent('ice:' + JSON.stringify({ mid: e.candidate.sdpMid, index: e.candidate.sdpMLineIndex, sdp: e.candidate.candidate }));
  };
  pc.onconnectionstatechange = () => onEvent('state:' + pc.connectionState);
  pc.ontrack = (e) => {
    c.remote.addTrack(e.track);
    if (e.track.kind === 'audio') playRemote(c);
    if (e.track.kind === 'video') onEvent('track:video');
  };
  const media = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    video: video ? { width: { ideal: 1280 }, height: { ideal: 720 }, facingMode: 'user' } : false,
  });
  c.local = media;
  for (const t of media.getAudioTracks()) pc.addTrack(t, media);
  if (video) {
    const v = media.getVideoTracks()[0];
    c.camera = v;
    c.videoSender = pc.addTrack(v, media);
  } else if (caller) {
    c.videoSender = pc.addTransceiver('video', { direction: 'sendrecv' }).sender;
  }
  // How loud the other person is, for the ring around their picture.
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    c.ctx = ctx;
    c.levelTimer = setInterval(() => {
      if (!c.analyser && c.remote.getAudioTracks().length) {
        const src = ctx.createMediaStreamSource(new MediaStream(c.remote.getAudioTracks()));
        c.analyser = ctx.createAnalyser();
        c.analyser.fftSize = 256;
        src.connect(c.analyser);
      }
      if (!c.analyser) return;
      const d = new Uint8Array(c.analyser.fftSize);
      c.analyser.getByteTimeDomainData(d);
      let p = 0;
      for (const x of d) p = Math.max(p, Math.abs(x - 128));
      onEvent('level:' + Math.min(100, Math.round((p / 128) * 160)));
    }, 150);
  } catch { /* no levels */ }
  return id;
}

function playRemote(c) {
  if (!c.audioEl) {
    c.audioEl = new Audio();
    c.audioEl.autoplay = true;
  }
  c.audioEl.srcObject = c.remote;
  c.audioEl.play().catch(() => {});
}

export async function rtcOffer(id) {
  const c = calls.get(id);
  const o = await c.pc.createOffer();
  await c.pc.setLocalDescription(o);
  return c.pc.localDescription.sdp;
}

export async function rtcAnswer(id) {
  const c = calls.get(id);
  // A voice call: the caller's picture slot is taken both ways (either camera can come on later).
  if (!c.videoSender) {
    const t = c.pc.getTransceivers().find((x) => x.receiver.track && x.receiver.track.kind === 'video');
    if (t) {
      t.direction = 'sendrecv';
      c.videoSender = t.sender;
    }
  }
  const a = await c.pc.createAnswer();
  await c.pc.setLocalDescription(a);
  return c.pc.localDescription.sdp;
}

export async function rtcRemote(id, type, sdp) {
  await calls.get(id).pc.setRemoteDescription({ type, sdp });
  return null;
}

export function rtcState(id) {
  const c = calls.get(id);
  return c ? c.pc.signalingState : 'closed';
}

export function rtcIce(id, json) {
  const c = calls.get(id);
  if (!c) return;
  const x = JSON.parse(json);
  c.pc.addIceCandidate({ candidate: x.sdp, sdpMid: x.mid, sdpMLineIndex: x.index }).catch(() => {});
}

export function rtcMute(id, muted) {
  const c = calls.get(id);
  if (c && c.local) c.local.getAudioTracks().forEach((t) => { t.enabled = !muted; });
}

/** The camera on or off; turned on for the first time in a voice call, it goes through the picture slot. */
export async function rtcCamera(id, on) {
  const c = calls.get(id);
  if (!c) return false;
  if (on && !c.camera) {
    const s = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1280 }, height: { ideal: 720 }, facingMode: 'user' } });
    c.camera = s.getVideoTracks()[0];
    c.local.addTrack(c.camera);
    if (c.videoSender) await c.videoSender.replaceTrack(c.camera);
    return true;
  }
  if (c.camera) c.camera.enabled = on;
  return true;
}

export function rtcRestart(id) {
  const c = calls.get(id);
  if (c) c.pc.restartIce();
}

export function rtcClose(id) {
  const c = calls.get(id);
  if (!c) return;
  calls.delete(id);
  clearInterval(c.levelTimer);
  try { if (c.ctx) c.ctx.close(); } catch { /* closed */ }
  if (c.local) c.local.getTracks().forEach((t) => t.stop());
  if (c.audioEl) c.audioEl.srcObject = null;
  try { c.pc.close(); } catch { /* closed */ }
  document.querySelectorAll('video[data-call="' + id + '"]').forEach((v) => v.remove());
}

/** A picture of the call (theirs or ours) laid over the page at the given place (CSS pixels of the app box). */
export function rtcVideo(id, remote, x, y, w, h, mirror) {
  const c = calls.get(id);
  if (!c) return;
  const key = id + (remote ? 'r' : 'l');
  let v = document.querySelector('video[data-key="' + key + '"]');
  if (!v) {
    v = document.createElement('video');
    v.dataset.key = key;
    v.dataset.call = String(id);
    v.autoplay = true;
    v.playsInline = true;
    v.muted = true;
    v.setAttribute('playsinline', '');
    v.style.cssText = 'position:fixed;z-index:6;object-fit:cover;background:#000;border-radius:' + (remote ? '0' : '14px');
    document.body.appendChild(v);
  }
  const s = remote ? c.remote : c.local;
  if (v.srcObject !== s) v.srcObject = s;
  v.play().catch(() => {});
  v.style.transform = mirror ? 'scaleX(-1)' : '';
  const app = document.getElementById('app').getBoundingClientRect();
  v.style.left = app.left + x + 'px';
  v.style.top = app.top + y + 'px';
  v.style.width = w + 'px';
  v.style.height = h + 'px';
}

export function rtcVideoDrop(id, remote) {
  document.querySelectorAll('video[data-key="' + id + (remote ? 'r' : 'l') + '"]').forEach((v) => v.remove());
}

// A ring and a ringback made with the browser's own sound (no files).
let tone = null;

export function toneStart(kind) {
  toneStop();
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const gain = ctx.createGain();
    gain.gain.value = 0;
    gain.connect(ctx.destination);
    const osc = ctx.createOscillator();
    osc.frequency.value = kind === 'ring' ? 660 : 425;
    osc.connect(gain);
    osc.start();
    const loudness = kind === 'ring' ? 0.18 : 0.08;
    let t = 0;
    const timer = setInterval(() => {
      // Ring: two short beeps, then quiet; ringback: one second on, three off.
      const phase = t % (kind === 'ring' ? 20 : 40);
      const loud = kind === 'ring' ? (phase < 3 || (phase >= 5 && phase < 8)) : phase < 10;
      gain.gain.setTargetAtTime(loud ? loudness : 0, ctx.currentTime, 0.01);
      t++;
    }, 100);
    tone = { ctx, timer };
  } catch { /* no sound */ }
}

export function toneStop() {
  if (tone) {
    clearInterval(tone.timer);
    try { tone.ctx.close(); } catch { /* closed */ }
    tone = null;
  }
}
