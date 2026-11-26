package com.thebrowser.core.shield

import com.thebrowser.core.util.TrackerBlocklist
import kotlinx.serialization.Serializable
import java.net.IDN
import java.net.URI
import java.net.URLDecoder

// ───────────────────────── settings ─────────────────────────

/** Individually switchable protections. [referrer]: default | strict-origin-when-cross-origin | same-origin | no-referrer. */
@Serializable
data class ShieldToggles(
    val trackers: Boolean = true,
    val ads: Boolean = true,
    val social: Boolean = true,
    val thirdPartyCookies: Boolean = true,
    val fpCanvas: Boolean = true,
    val fpWebgl: Boolean = true,
    val fpAudio: Boolean = true,
    val fpFonts: Boolean = false,
    val fpApis: Boolean = false,
    val stripParams: Boolean = true,
    val bounce: Boolean = true,
    val cname: Boolean = true,
    val consent: Boolean = false,
    val cosmeticAds: Boolean = false,
    val referrer: String = "strict-origin-when-cross-origin",
) {
    companion object {
        val STANDARD = ShieldToggles()
        val AGGRESSIVE = ShieldToggles(
            fpFonts = true, fpApis = true, consent = true, cosmeticAds = true, referrer = "same-origin",
        )
    }
}

@Serializable
data class FilterListRef(
    val id: String,
    val name: String,
    val url: String = "",          // empty for pasted/custom text lists
    val category: String = "ads",  // ads | trackers
    val enabled: Boolean = true,
    val lastUpdated: Long = 0L,
    val etag: String = "",
    val hostCount: Int = 0,
)

/** level: standard | aggressive | custom. Protection is ON by default. */
@Serializable
data class ShieldSettings(
    val enabled: Boolean = true,
    val level: String = "standard",
    val custom: ShieldToggles = ShieldToggles.STANDARD,
    val httpsMode: String = "first",   // off | first | strict
    val webRtc: String = "default",    // default | relay | off
    val gpc: Boolean = true,
    val dnt: Boolean = true,
    val siteOff: List<String> = emptyList(),
    val lists: List<FilterListRef> = emptyList(),
    val autoUpdateLists: Boolean = true,
    val aggressiveFirstPartyAds: Boolean = false,
) {
    fun effective(): ShieldToggles = when (level) {
        "aggressive" -> ShieldToggles.AGGRESSIVE
        "custom" -> custom
        else -> ShieldToggles.STANDARD
    }
}

/** Immutable runtime snapshot handed to the engine. */
data class ShieldConfig(
    val enabled: Boolean,
    val toggles: ShieldToggles,
    val httpsMode: String,
    val webRtc: String,
    val gpc: Boolean,
    val dnt: Boolean,
    val siteOff: Set<String>,
    val filters: FilterEngine,
    val seed: String,
    val isPrivate: Boolean,
    val jsBlockedHosts: Set<String> = emptySet(),
    val thirdPartyCookiesAllowedHosts: Set<String> = emptySet(),
    val popupAllowedHosts: Set<String> = emptySet(),
    val captureCredentials: Boolean = false,
    val strictHttpsAllowedHosts: Set<String> = emptySet(),
) {
    fun activeFor(host: String?): Boolean = enabled && (host == null || host.lowercase() !in siteOff)
}

// ───────────────────────── events + interstitials ─────────────────────────

/** Real, event-based counters: one is only reported when the browser actually did the thing. */
enum class ShieldEventKind(val label: String) {
    Tracker("Trackers blocked"), Ad("Ads blocked"), Social("Social trackers blocked"),
    Cname("CNAME-cloaked trackers blocked"), ParamStripped("Tracking parameters removed"),
    BounceUnwrapped("Tracking redirects skipped"), HttpsUpgrade("Connections upgraded to HTTPS"),
    Dangerous("Dangerous pages blocked"), CertError("Certificate errors stopped"),
    SuspiciousRedirect("Suspicious redirect chains flagged"), Lookalike("Lookalike domains flagged"),
    FpCanvas("Canvas fingerprinting altered"), FpWebgl("WebGL fingerprinting altered"),
    FpAudio("Audio fingerprinting altered"), FpFonts("Font fingerprinting altered"),
    FpApi("Browser-API fingerprinting altered"), WebRtc("WebRTC IP exposure limited"),
    ConsentHidden("Cookie banners hidden"), AdsHidden("Ad slots hidden"), DangerousDownload("Risky downloads warned"),
}

/** A full-page warning. [proceed] is null when bypassing is not allowed. */
class SecurityBlock(
    val kind: String,        // cert | malware | phishing | unwanted | billing | https_only | lookalike | download | redirect
    val url: String,
    val host: String,
    val title: String,
    val explanation: String,
    val details: List<String>,
    val proceed: (() -> Unit)?,
    val back: () -> Unit,
)

// ───────────────────────── filter engine ─────────────────────────

enum class FilterCategory { Tracker, Ad, Social }

/** Domain-level filter matching (hosts files + `||domain^` rules). No regex/cosmetic rules from lists. */
class FilterEngine {
    @Volatile private var userTrackers: Set<String> = emptySet()
    @Volatile private var userAds: Set<String> = emptySet()
    @Volatile private var userAllow: Set<String> = emptySet()

    fun update(trackers: Set<String>, ads: Set<String>, allow: Set<String>) {
        userTrackers = trackers; userAds = ads; userAllow = allow
    }

    private fun walk(host: String, set: Set<String>): Boolean {
        var h = host.lowercase()
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun categoryOf(host: String?): FilterCategory? {
        host ?: return null
        if (walk(host, userAllow)) return null
        if (walk(host, SOCIAL)) return FilterCategory.Social
        if (TrackerBlocklist.isTracker(host) || walk(host, userTrackers)) return FilterCategory.Tracker
        if (walk(host, ADS) || walk(host, userAds)) return FilterCategory.Ad
        return null
    }

    companion object {
        val ADS = setOf(
            "adservice.google.com", "pagead2.googlesyndication.com", "tpc.googlesyndication.com", "ads.yahoo.com",
            "advertising.com", "adform.net", "adsafeprotected.com", "serving-sys.com", "smartadserver.com",
            "yieldmo.com", "sharethrough.com", "teads.tv", "media.net", "revcontent.com", "mgid.com", "zedo.com",
            "adcolony.com", "unityads.unity3d.com", "applovin.com", "inmobi.com", "ads.pubmatic.com", "indexww.com",
            "33across.com", "bidswitch.net", "contextweb.com", "lijit.com", "sovrn.com", "spotxchat.com", "spotx.tv",
            "adroll.com", "amazon-adsystem.com", "advertising.amazon.com", "ib.adnxs.com", "popads.net", "propellerads.com",
            "exoclick.com", "juicyads.com", "trafficjunky.net", "adsterra.com",
        )
        val SOCIAL = setOf(
            "connect.facebook.net", "facebook.net", "pixel.facebook.com", "platform.twitter.com", "syndication.twitter.com",
            "platform.linkedin.com", "snap.licdn.com", "assets.pinterest.com", "ct.pinterest.com", "log.pinterest.com",
            "analytics.tiktok.com", "business-api.tiktok.com", "sc-static.net", "tr.snapchat.com", "platform.instagram.com",
            "widgets.sharethis.com", "static.addtoany.com", "s7.addthis.com", "addthis.com", "disqus.com",
        )
    }
}

object FilterLists {
    data class Parsed(val blocked: Set<String>, val allowed: Set<String>)

    private val HOST = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")

    /** Understands hosts-file lines and ABP `||domain^` network rules. Everything else is skipped (and counted by the caller). */
    fun parse(text: String): Parsed {
        val blocked = HashSet<String>(); val allowed = HashSet<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("#") || line.startsWith("[")) return@forEach
            when {
                line.startsWith("@@||") -> domainOf(line.removePrefix("@@||"))?.let(allowed::add)
                line.startsWith("||") -> {
                    val rule = line.removePrefix("||")
                    // Only plain domain rules (optionally `$third-party`); path/regex/option-heavy rules are not domain-level.
                    val opts = rule.substringAfter('$', "")
                    if (opts.isEmpty() || opts.split(',').all { it == "third-party" || it == "3p" || it == "important" }) domainOf(rule.substringBefore('$'))?.let(blocked::add)
                }
                line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ") -> {
                    val h = line.split(Regex("\\s+")).getOrNull(1)?.lowercase()
                    if (h != null && h != "localhost" && HOST.matches(h)) blocked.add(h)
                }
            }
        }
        return Parsed(blocked, allowed)
    }

    private fun domainOf(rule: String): String? {
        val d = rule.substringBefore('^').substringBefore('/').substringBefore(':').lowercase()
        return d.takeIf { it.contains('.') && HOST.matches(it) && !it.contains('*') }
    }
}

// ───────────────────────── URL helpers ─────────────────────────

object Registrable {
    private val TWO_LEVEL = setOf("co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.in", "net.in", "org.in", "co.jp", "com.br", "co.za", "com.cn", "com.tr", "co.nz", "com.sg", "com.hk", "com.pk", "net.pk", "org.pk", "edu.pk", "gov.pk", "com.mx", "com.ar", "co.kr", "com.my", "com.ph", "com.sa", "com.eg", "co.id")

    /** Approximation of eTLD+1 without the full Public Suffix List. Good enough for third-party checks, not for security boundaries. */
    fun of(host: String): String {
        val h = host.lowercase().trimEnd('.')
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val last2 = parts.takeLast(2).joinToString(".")
        return if (last2 in TWO_LEVEL && parts.size >= 3) parts.takeLast(3).joinToString(".") else last2
    }

    fun isThirdParty(requestHost: String?, topHost: String?): Boolean {
        if (requestHost == null || topHost == null) return false
        return of(requestHost) != of(topHost)
    }
}

object UrlCleaner {
    private val PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "utm_name", "fbclid", "gclid", "gclsrc",
        "dclid", "gbraid", "wbraid", "msclkid", "mc_eid", "mc_cid", "igshid", "yclid", "_hsenc", "_hsmi", "mkt_tok", "ttclid",
        "twclid", "vero_id", "ref_src", "ref_url", "oly_enc_id", "oly_anon_id", "_openstat", "s_cid", "icid",
    )

    /** Returns the URL without tracking parameters, or null when nothing was removed. */
    fun clean(url: String): String? {
        val q = url.indexOf('?')
        if (q < 0) return null
        val hash = url.indexOf('#').let { if (it < 0) url.length else it }.coerceAtLeast(q)
        val query = url.substring(q + 1, hash)
        val kept = query.split('&').filter { it.isNotEmpty() && it.substringBefore('=').lowercase() !in PARAMS }
        if (kept.size == query.split('&').filter { it.isNotEmpty() }.size) return null
        return url.substring(0, q) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + url.substring(hash)
    }

    private data class Redirector(val host: String, val path: String, val params: List<String>)
    private val REDIRECTORS = listOf(
        Redirector("l.facebook.com", "/l.php", listOf("u")), Redirector("lm.facebook.com", "/l.php", listOf("u")),
        Redirector("l.instagram.com", "/", listOf("u")), Redirector("www.google.com", "/url", listOf("q", "url")),
        Redirector("google.com", "/url", listOf("q", "url")), Redirector("out.reddit.com", "/", listOf("url")),
        Redirector("www.youtube.com", "/redirect", listOf("q")), Redirector("slack-redir.net", "/link", listOf("url")),
        Redirector("steamcommunity.com", "/linkfilter", listOf("u", "url")),
    )

    /** Known link-wrapper "bounce" pages that carry the real destination in a parameter. Returns the destination or null. */
    fun unwrapRedirector(url: String): String? = runCatching {
        val u = URI(url)
        val host = u.host?.lowercase() ?: return null
        val r = REDIRECTORS.firstOrNull { it.host == host && (u.path ?: "/").startsWith(it.path) } ?: return null
        val query = u.rawQuery ?: return null
        for (p in r.params) {
            val v = query.split('&').firstOrNull { it.startsWith("$p=") }?.substringAfter('=') ?: continue
            val dest = URLDecoder.decode(v, "UTF-8")
            if (dest.startsWith("http://") || dest.startsWith("https://")) return dest
        }
        null
    }.getOrNull()
}

object UrlSafety {
    data class Finding(val kind: String, val message: String)

    private val BRANDS = listOf(
        "google", "youtube", "facebook", "instagram", "whatsapp", "twitter", "linkedin", "microsoft", "outlook", "office",
        "apple", "icloud", "amazon", "paypal", "netflix", "github", "dropbox", "adobe", "ebay", "yahoo", "wikipedia",
        "reddit", "spotify", "binance", "coinbase", "tiktok", "telegram", "discord", "steam", "chase", "wellsfargo",
        "bankofamerica", "hbl", "meezan", "easypaisa", "jazzcash", "alfalah", "daraz", "foodpanda", "anthropic", "openai",
    )

    // Common Cyrillic/Greek look-alikes → Latin skeleton.
    private val CONFUSABLE = mapOf(
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'х' to 'x', 'у' to 'y', 'і' to 'i', 'ј' to 'j', 'ѕ' to 's',
        'ɡ' to 'g', 'ο' to 'o', 'ν' to 'v', 'α' to 'a', 'ρ' to 'p', 'τ' to 't', 'ι' to 'i', 'κ' to 'k', 'ı' to 'i',
        '0' to 'o', '1' to 'l', '5' to 's',
    )

    private fun skeleton(s: String) = s.lowercase().map { CONFUSABLE[it] ?: it }.joinToString("").replace("-", "")

    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    fun analyseHost(host: String): List<Finding> {
        val out = mutableListOf<Finding>()
        val unicode = runCatching { IDN.toUnicode(host) }.getOrDefault(host)
        val scripts = unicode.filter { it.isLetter() }.map { Character.UnicodeScript.of(it.code) }.filter { it != Character.UnicodeScript.COMMON }.toSet()
        if (host.split('.').any { it.startsWith("xn--") }) out += Finding("punycode", "This address uses international characters (shown as “$unicode”). Attackers use look-alike letters to imitate real sites.")
        if (scripts.size > 1) out += Finding("mixed_script", "The address mixes writing systems (${scripts.joinToString { it.name.lowercase() }}), a common sign of an imitation site.")
        val label = Registrable.of(unicode).substringBefore('.')
        val sk = skeleton(label)
        for (brand in BRANDS) {
            if (label == brand) break // real brand label, any TLD: not flagged here
            val similar = sk == brand || (brand.length >= 7 && sk.length >= 7 && lev(sk, brand) == 1)
            if (similar) { out += Finding("lookalike", "“$unicode” looks like “$brand”. Check the address carefully before entering anything."); break }
        }
        return out
    }

    private val RISKY = setOf("apk", "exe", "msi", "bat", "cmd", "scr", "com", "pif", "jar", "vbs", "vbe", "js", "jse", "wsf", "ps1", "dmg", "pkg", "app", "sh", "lnk", "iso", "reg", "hta", "xapk", "apks")

    fun riskyDownload(fileName: String, url: String): List<String> {
        val out = mutableListOf<String>()
        val parts = fileName.lowercase().split('.')
        val ext = parts.lastOrNull().orEmpty()
        if (ext in RISKY) out += "“.$ext” files can run code on your device."
        if (parts.size > 2 && parts[parts.size - 2] in setOf("pdf", "doc", "docx", "jpg", "png", "txt") && ext in RISKY) out += "The name pretends to be a document but is really an app file (double extension)."
        if (url.startsWith("http://")) out += "The download is not encrypted (HTTP), so it could be altered in transit."
        return out
    }
}

// ───────────────────────── injected page scripts ─────────────────────────

object ShieldScripts {
    private fun q(s: String) = org.json.JSONObject.quote(s)

    /** Hooks run at document start. Each reports to native ONLY when it actually changed an API result. */
    fun pageScript(c: ShieldConfig): String {
        val t = c.toggles
        val cfg = """{"seed":${q(c.seed)},"off":${c.siteOff.joinToString(",", "[", "]") { q(it) }},"canvas":${t.fpCanvas},"webgl":${t.fpWebgl},"audio":${t.fpAudio},"fonts":${t.fpFonts},"apis":${t.fpApis},"gpc":${c.gpc},"dnt":${c.dnt},"rtc":${q(c.webRtc)},"ref":${q(t.referrer)},"consent":${t.consent},"cosmetic":${t.cosmeticAds}}"""
        return SCRIPT.replace("__CONFIG__", cfg)
    }

    /** Password-form capture, injected only for non-private pages when the vault is enabled. */
    const val CREDENTIAL_SCRIPT = """
(function(){try{
if(window.top!==window) return;
function send(f){try{var pw=f.querySelector('input[type=password]'); if(!pw||!pw.value) return;
 var u=f.querySelector('input[type=email],input[autocomplete=username],input[name*=user i],input[name*=login i],input[type=text]');
 window.__tbShield.postMessage('cred|'+JSON.stringify({origin:location.origin,user:u?u.value:'',pass:pw.value}));}catch(e){}}
document.addEventListener('submit',function(e){send(e.target)},true);
document.addEventListener('click',function(e){var b=e.target&&e.target.closest&&e.target.closest('button,input[type=submit]'); if(b&&b.form) send(b.form)},true);
}catch(e){}})();
"""

    /** Fills the visible login form. Only call after authentication and an exact origin match. */
    fun fillScript(user: String, pass: String): String = """
(function(){try{
function set(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
var pw=[].slice.call(document.querySelectorAll('input[type=password]')).filter(function(e){return e.offsetParent!==null})[0]; if(!pw) return 'nofield';
var scope=pw.form||document; var us=[].slice.call(scope.querySelectorAll('input[type=email],input[autocomplete=username],input[type=text]')).filter(function(e){return e.offsetParent!==null});
var u=us.filter(function(e){return e.compareDocumentPosition(pw)&Node.DOCUMENT_POSITION_FOLLOWING}).pop()||us[0];
if(u&&${q(user)}) set(u,${q(user)}); set(pw,${q(pass)}); return 'ok';}catch(e){return 'error'}})()
"""

    private val SCRIPT = """
(function(){try{
var C=__CONFIG__; var H=location.hostname; if(C.off.indexOf(H)>=0) return;
function post(k){try{window.__tbShield&&window.__tbShield.postMessage('ev|'+k);}catch(e){}}
var done={}; function once(k){if(!done[k]){done[k]=1;post(k);}}
function hash(s){var x=2166136261;for(var i=0;i<s.length;i++){x^=s.charCodeAt(i);x=Math.imul(x,16777619);}return x>>>0;}
var st=hash(C.seed+'|'+H); function rnd(){st=(Math.imul(st,1664525)+1013904223)>>>0;return st/4294967296;}
function def(o,p,g){try{Object.defineProperty(o,p,{get:g,configurable:true});}catch(e){}}

if(C.gpc){def(Navigator.prototype,'globalPrivacyControl',function(){return true;});}
if(C.dnt){def(Navigator.prototype,'doNotTrack',function(){return '1';});}

if(C.canvas){
 var gid=CanvasRenderingContext2D.prototype.getImageData;
 var noisy=function(im){var d=im.data;for(var i=0;i<d.length;i+=4){if(rnd()<0.02){d[i]=(d[i]+(rnd()<0.5?1:-1))&255;}}return im;};
 CanvasRenderingContext2D.prototype.getImageData=function(){var r=gid.apply(this,arguments);once('fp_canvas');return noisy(r);};
 var copy=function(cv){var c=document.createElement('canvas');c.width=cv.width;c.height=cv.height;var x=c.getContext('2d');x.drawImage(cv,0,0);var im=gid.call(x,0,0,c.width||1,c.height||1);noisy(im);x.putImageData(im,0,0);return c;};
 var tdu=HTMLCanvasElement.prototype.toDataURL;
 HTMLCanvasElement.prototype.toDataURL=function(){try{if(this.width*this.height>4000000)return tdu.apply(this,arguments);var c=copy(this);once('fp_canvas');return tdu.apply(c,arguments);}catch(e){return tdu.apply(this,arguments);}};
 var tb=HTMLCanvasElement.prototype.toBlob;
 HTMLCanvasElement.prototype.toBlob=function(){try{if(this.width*this.height>4000000)return tb.apply(this,arguments);var c=copy(this);once('fp_canvas');return tb.apply(c,arguments);}catch(e){return tb.apply(this,arguments);}};
}
if(C.webgl){
 [window.WebGLRenderingContext,window.WebGL2RenderingContext].forEach(function(G){ if(!G) return;
  var gp=G.prototype.getParameter;
  G.prototype.getParameter=function(p){if(p===37445){once('fp_webgl');return 'Google Inc.';} if(p===37446){once('fp_webgl');return 'ANGLE (Generic Renderer)';} return gp.apply(this,arguments);};
  var rp=G.prototype.readPixels;
  G.prototype.readPixels=function(){var r=rp.apply(this,arguments);try{var b=arguments[6];if(b&&b.length){for(var i=0;i<b.length;i+=4){if(rnd()<0.02){b[i]=(b[i]+1)&255;}}once('fp_webgl');}}catch(e){}return r;};
 });
}
if(C.audio){
 if(window.AnalyserNode){['getFloatFrequencyData','getFloatTimeDomainData'].forEach(function(m){var o=AnalyserNode.prototype[m];AnalyserNode.prototype[m]=function(a){o.apply(this,arguments);try{for(var i=0;i<a.length;i++){a[i]+=(rnd()-0.5)*1e-5;}once('fp_audio');}catch(e){}};});}
 if(window.AudioBuffer){var gc=AudioBuffer.prototype.getChannelData;var seen=new WeakSet();
  AudioBuffer.prototype.getChannelData=function(){var d=gc.apply(this,arguments);try{if(!seen.has(d)){seen.add(d);for(var i=0;i<d.length;i+=50){d[i]+=(rnd()-0.5)*1e-7;}once('fp_audio');}}catch(e){}return d;};}
}
if(C.fonts){
 try{if(document.fonts){var chk=document.fonts.check.bind(document.fonts);document.fonts.check=function(){once('fp_fonts');return true;};}}catch(e){}
 try{var mt=CanvasRenderingContext2D.prototype.measureText;CanvasRenderingContext2D.prototype.measureText=function(){var m=mt.apply(this,arguments);once('fp_fonts');var w=m.width+(rnd()-0.5)*0.02;return {width:w,actualBoundingBoxLeft:m.actualBoundingBoxLeft,actualBoundingBoxRight:m.actualBoundingBoxRight,actualBoundingBoxAscent:m.actualBoundingBoxAscent,actualBoundingBoxDescent:m.actualBoundingBoxDescent,fontBoundingBoxAscent:m.fontBoundingBoxAscent,fontBoundingBoxDescent:m.fontBoundingBoxDescent};};}catch(e){}
}
if(C.apis){
 def(Navigator.prototype,'hardwareConcurrency',function(){once('fp_api');return 4;});
 def(Navigator.prototype,'deviceMemory',function(){once('fp_api');return 8;});
 def(Navigator.prototype,'languages',function(){once('fp_api');return Object.freeze([navigator.language]);});
 try{if(navigator.getBattery){navigator.getBattery=function(){once('fp_api');return Promise.reject(new DOMException('Not allowed','NotAllowedError'));};}}catch(e){}
 def(Navigator.prototype,'connection',function(){once('fp_api');return undefined;});
}
if(C.rtc!=='default'){
 var Orig=window.RTCPeerConnection;
 if(Orig){
  if(C.rtc==='off'){window.RTCPeerConnection=undefined;window.webkitRTCPeerConnection=undefined;once('webrtc');}
  else{var P=function(cfg,c){cfg=cfg||{};cfg.iceTransportPolicy='relay';return new Orig(cfg,c);};P.prototype=Orig.prototype;P.generateCertificate=Orig.generateCertificate;window.RTCPeerConnection=P;window.webkitRTCPeerConnection=P;once('webrtc');}
 }
}
function onHead(fn){if(document.head){fn(document.head);return;}var mo=new MutationObserver(function(){if(document.head){mo.disconnect();fn(document.head);}});mo.observe(document.documentElement,{childList:true});}
if(C.ref&&C.ref!=='default'){onHead(function(h){var m=document.createElement('meta');m.name='referrer';m.content=C.ref;h.insertBefore(m,h.firstChild);});}
var CONSENT='#onetrust-banner-sdk,#onetrust-consent-sdk,#CybotCookiebotDialog,#CybotCookiebotDialogBodyUnderlay,.qc-cmp2-container,#truste-consent-track,.cc-window,.cc-banner,#cookie-law-info-bar,#didomi-host,.fc-consent-root,#usercentrics-root,.osano-cm-window,#cookiescript_injected,.cmplz-cookiebanner,#cookie-notice,.cookie-consent,#gdpr-cookie-message,.truste_overlay,#sp_message_container_,div[id^=sp_message_container],#cmpbox,#cmpwrapper,.iubenda-cs-container';
var ADS='ins.adsbygoogle,.adsbygoogle,[id^=google_ads_iframe],[id^=div-gpt-ad],.ad-banner,.ad-container,.advert,[aria-label=Advertisement],[data-ad-slot],.sponsored-ad';
function hideStyle(sel,key){onHead(function(h){var s=document.createElement('style');s.textContent=sel+'{display:none!important}';h.appendChild(s);});
 function chk(){try{if(document.querySelector(sel)){once(key);}}catch(e){}}
 document.addEventListener('DOMContentLoaded',chk);window.addEventListener('load',function(){setTimeout(chk,800);});}
if(C.consent){hideStyle(CONSENT,'consent');onHead(function(h){var s=document.createElement('style');s.textContent='html,body{overflow:auto!important;position:static!important}';h.appendChild(s);});}
if(C.cosmetic){hideStyle(ADS,'ads_hidden');}
}catch(e){}})();
"""
}
