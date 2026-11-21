@file:JsModule("nuvelo-bridge")

package com.nuvelo.web

import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise

// The browser's half (web/bridge/index.mjs): see there for what each does.

external fun dbOn(path: String, spec: String?, cb: (String) -> Unit, err: (String) -> Unit): Int
external fun dbChildOn(path: String, cb: (String, String, String) -> Unit): Int
external fun dbOff(id: Int)
external fun dbGet(path: String, spec: String?): Promise<JsString>
external fun dbSet(path: String, json: String): Promise<JsAny?>
external fun dbUpdate(path: String, json: String): Promise<JsAny?>
external fun dbRemove(path: String): Promise<JsAny?>
external fun dbPushKey(path: String): String
external fun dbTransaction(path: String, fn: (String) -> String): Promise<JsString>
external fun dbOnDisconnect(path: String, op: String, json: String?): Promise<JsAny?>

external fun authListen(cb: (String?) -> Unit)
external fun authGoogle(): Promise<JsString>
external fun authCustom(token: String): Promise<JsAny?>
external fun authToken(force: Boolean): Promise<JsString>
external fun authOut(): Promise<JsAny?>
external fun authDeleteUser(): Promise<JsAny?>

external fun randomB64(n: Int): String
external fun ecNew(): String
external fun ecPublicOf(privB64: String): String
external fun ecdh(privB64: String, pubB64: String): String
external fun ecValid(pubB64: String): Boolean
external fun gcmSeal(keyB64: String, nonceB64: String, plainB64: String, aadB64: String?): String
external fun gcmOpen(keyB64: String, nonceB64: String, sealedB64: String, aadB64: String?): String
external fun sha256B64(b: String): String
external fun hmac256(keyB64: String, dataB64: String): String
external fun pbkdf2(password: String, saltB64: String, rounds: Int, length: Int): Promise<JsString>
external fun safetyHalf(uid: String, keyB64: String): String
external fun deviceSeal(plainB64: String): Promise<JsString>
external fun deviceOpen(sealedB64: String): Promise<JsString?>

external fun blobDrop(id: Int)
external fun blobSize(id: Int): Double
external fun blobUrl(id: Int): String
external fun blobBytes(id: Int): Promise<JsAny>
external fun blobFromBytes(bytes: JsAny, type: String): Int
external fun pickFiles(accept: String, multiple: Boolean, capture: String?): Promise<JsString>
external fun prepareImage(id: Int, maxSide: Int, quality: Double): Promise<JsString>
external fun videoInfo(id: Int): Promise<JsString>
external fun sealBlob(id: Int): Promise<JsString>
external fun openSealedB64(keyB64: String, sealedB64: String): String
external fun mediaUrl(url: String, keyB64: String?, type: String?): Promise<JsString>
external fun fetchBytes(url: String, keyB64: String?): Promise<JsAny?>
external fun upload(id: Int, url: String, token: String, mime: String, progress: (Double) -> Unit): Promise<JsString>
external fun api(method: String, url: String, token: String, json: String?): Promise<JsString>
external fun apiBlob(url: String, token: String, id: Int): Promise<JsString>

external fun recStartNow(): Promise<JsAny?>
external fun recLevel(): Int
external fun recStop(keepIt: Boolean): Promise<JsString?>
external fun audioNew(url: String, onEvent: (String) -> Unit): Int
external fun audioPlay(id: Int)
external fun audioPause(id: Int)
external fun audioSeek(id: Int, ms: Double)
external fun audioRate(id: Int, r: Double)
external fun audioDrop(id: Int)

external fun qrMatrix(text: String): String
external fun saveText(name: String, text: String)
external fun openUrl(url: String)
external fun storeGet(k: String): String?
external fun storeSet(k: String, v: String?)
external fun locate(): Promise<JsString>

external fun pushState(): Promise<JsString>
external fun pushEnable(): Promise<JsString?>
external fun pushToken(): Promise<JsString?>
external fun pushForget(): Promise<JsAny?>
external fun onPushOpen(cb: (String) -> Unit)
external fun iosBrowser(): Boolean

external fun rtcNew(ice: String, video: Boolean, caller: Boolean, onEvent: (String) -> Unit): Promise<JsAny>
external fun rtcOffer(id: Int): Promise<JsString>
external fun rtcAnswer(id: Int): Promise<JsString>
external fun rtcRemote(id: Int, type: String, sdp: String): Promise<JsAny?>
external fun rtcState(id: Int): String
external fun rtcIce(id: Int, json: String)
external fun rtcMute(id: Int, muted: Boolean)
external fun rtcCamera(id: Int, on: Boolean): Promise<JsAny?>
external fun rtcRestart(id: Int)
external fun rtcClose(id: Int)
external fun rtcVideo(id: Int, remote: Boolean, x: Double, y: Double, w: Double, h: Double, mirror: Boolean)
external fun rtcVideoDrop(id: Int, remote: Boolean)
external fun toneStart(kind: String)
external fun toneStop()
