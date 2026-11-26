package com.thebrowser.core.ext

import com.thebrowser.core.engine.ContentScript
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chrome-extension compatibility layer for Android WebView.
 *
 * HONEST SCOPE: WebView has no extension host. This implements ONLY content scripts (JS + CSS
 * injected into matching pages) plus a tiny `chrome.storage.local` / `chrome.runtime` shim.
 * Background service workers, popups, options pages, `tabs`, `webRequest`,
 * `declarativeNetRequest`, `scripting`, `cookies`, etc. are NOT implemented, and every extension
 * is checked and told so before it can be enabled. Scripts run in the page's main world
 * (WebView has no isolated worlds).
 */
data class ContentScriptDef(
    val matches: List<String>,
    val excludeMatches: List<String>,
    val js: List<String>,
    val css: List<String>,
    val runAt: String,
)

data class ManifestInfo(
    val name: String,
    val version: String,
    val manifestVersion: Int,
    val publisher: String,
    val homepage: String,
    val optionalPermissions: List<String>,
    val permissions: List<String>,
    val hostPatterns: List<String>,
    val contentScripts: List<ContentScriptDef>,
    val unsupported: List<String>,
    val raw: String,
)

object MatchPattern {
    /** Converts a Chrome match pattern to a regex source string, or null if invalid. */
    fun toRegex(pattern: String): String? {
        if (pattern == "<all_urls>") return "^https?://.*$"
        val m = Regex("""^(\*|https?)://([^/]+)(/.*)$""").matchEntire(pattern) ?: return null
        val (scheme, host, path) = m.destructured
        val schemeRe = if (scheme == "*") "https?" else scheme
        val hostRe = when {
            host == "*" -> "[^/]+"
            host.startsWith("*.") -> "([^/]+\\.)?" + Regex.escape(host.removePrefix("*."))
            else -> Regex.escape(host)
        }
        val pathRe = path.split("*").joinToString(".*") { Regex.escape(it) }
        return "^$schemeRe://$hostRe$pathRe$"
    }

    /** WebView origin rules: scheme://host with an optional leading `*.` wildcard. */
    fun originRules(pattern: String): Set<String> {
        if (pattern == "<all_urls>") return setOf("*")
        val m = Regex("""^(\*|https?)://([^/:]+)(:\d+)?/.*$""").matchEntire(pattern) ?: return emptySet()
        val (scheme, host) = m.destructured
        val hostPart = if (host == "*") return setOf("*") else host
        val schemes = if (scheme == "*") listOf("http", "https") else listOf(scheme)
        return schemes.map { "$it://$hostPart" }.toSet()
    }
}

object ExtensionManifest {
    private val SUPPORTED_PERMS = setOf("storage", "activeTab", "unlimitedStorage")

    fun parse(json: String): ManifestInfo {
        val o = JSONObject(json)
        val mv = o.optInt("manifest_version", 0)
        val perms = o.optJSONArray("permissions").toStrings()
        val hosts = (o.optJSONArray("host_permissions").toStrings() + perms.filter { it.contains("://") || it == "<all_urls>" }).distinct()
        val cs = mutableListOf<ContentScriptDef>()
        o.optJSONArray("content_scripts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                cs += ContentScriptDef(
                    c.optJSONArray("matches").toStrings(), c.optJSONArray("exclude_matches").toStrings(),
                    c.optJSONArray("js").toStrings(), c.optJSONArray("css").toStrings(), c.optString("run_at", "document_idle"),
                )
            }
        }
        val unsupported = mutableListOf<String>()
        if (o.has("background")) unsupported += "background service worker / scripts"
        val action = o.optJSONObject("action") ?: o.optJSONObject("browser_action")
        if (action?.has("default_popup") == true) unsupported += "toolbar popup"
        if (o.has("options_page") || o.has("options_ui")) unsupported += "options page"
        if (o.has("declarative_net_request")) unsupported += "declarativeNetRequest rules"
        if (o.has("commands")) unsupported += "keyboard commands"
        if (o.has("side_panel")) unsupported += "side panel"
        perms.filter { !it.contains("://") && it != "<all_urls>" && it !in SUPPORTED_PERMS }.forEach { unsupported += "API: $it" }
        if (mv != 3) unsupported += "manifest_version $mv (MV3 expected)"
        if (cs.isEmpty()) unsupported += "no content scripts (nothing this runtime can run)"
        val publisher = o.optJSONObject("developer")?.optString("name").orEmpty()
            .ifBlank { o.optString("author").takeIf { it.isNotBlank() } ?: o.optJSONObject("author")?.optString("email").orEmpty() }
        val optional = (o.optJSONArray("optional_permissions").toStrings() + o.optJSONArray("optional_host_permissions").toStrings()).distinct()
        return ManifestInfo(o.optString("name", "Unnamed"), o.optString("version", "0"), mv, publisher, o.optString("homepage_url"), optional, perms, hosts, cs, unsupported, json)
    }

    /** Patterns that give a script access to (almost) every website. These are OFF until the user explicitly allows them. */
    fun isBroad(pattern: String): Boolean =
        pattern == "<all_urls>" || Regex("""^(\*|https?)://(\*|\*\.\*)/.*$""").matches(pattern) || pattern.endsWith("://*/*")

    private val POWERFUL = setOf("cookies", "webRequest", "webRequestBlocking", "history", "bookmarks", "management", "nativeMessaging", "debugger", "proxy", "privacy", "downloads", "clipboardRead", "tabs", "scripting", "declarativeNetRequest")

    /**
     * Static checks (heuristic, NOT a malware scan): powerful permissions, dynamic-code patterns,
     * remote script loading, obfuscation and external network calls in the content scripts.
     */
    fun scan(info: ManifestInfo, readFile: (String) -> String?): List<String> {
        val out = mutableListOf<String>()
        info.permissions.filter { it in POWERFUL }.forEach { out += "Requests powerful permission “$it”" }
        if (info.hostPatterns.any(::isBroad)) out += "Asks to read and change EVERY website"
        val checks = listOf(
            Regex("""\beval\s*\(""") to "uses eval() (runs text as code)",
            Regex("""new\s+Function\s*\(""") to "builds code at runtime (new Function)",
            Regex("""document\.write\s*\(""") to "uses document.write",
            Regex("""createElement\(['"]script['"]\)""") to "can inject script elements",
            Regex("""['"]https?://[^'"]+\.js['"]""") to "references remote JavaScript files",
            Regex("""atob\s*\(\s*['"][A-Za-z0-9+/=]{200,}""") to "contains a long encoded blob (possible obfuscation)",
            Regex("""\b(XMLHttpRequest|fetch\s*\()""") to "makes network requests from the page",
            Regex("""navigator\.sendBeacon""") to "sends beacons to servers",
        )
        info.contentScripts.flatMap { it.js }.distinct().forEach { path ->
            val src = readFile(path) ?: return@forEach
            checks.forEach { (re, msg) -> if (re.containsMatchIn(src)) out += "$path $msg" }
        }
        return out.distinct()
    }

    private fun JSONArray?.toStrings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun js(s: String) = JSONObject.quote(s)

    /** Builds one injectable script per content-script definition. */
    fun buildScripts(extensionId: String, info: ManifestInfo, allowedPatterns: Set<String>, readFile: (String) -> String?): List<ContentScript> =
        info.contentScripts.mapNotNull { def ->
            val allowed = def.matches.filter { it in allowedPatterns }
            val matchRes = allowed.mapNotNull(MatchPattern::toRegex)
            if (matchRes.isEmpty()) return@mapNotNull null
            val origins = allowed.flatMap { MatchPattern.originRules(it) }.toSet()
            if (origins.isEmpty()) return@mapNotNull null
            val exclRes = def.excludeMatches.mapNotNull(MatchPattern::toRegex)
            val css = def.css.mapNotNull(readFile).joinToString("\n")
            val bodies = def.js.mapNotNull(readFile).joinToString("\n") { "(function(chrome,browser){\n$it\n})(__tbChrome,__tbChrome);" }
            val run = when (def.runAt) {
                "document_start" -> "__tbRun();"
                "document_end" -> "if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',__tbRun);}else{__tbRun();}"
                else -> "if(document.readyState==='complete'){__tbRun();}else{window.addEventListener('load',__tbRun);}"
            }
            val script = """
(function(){
try{
var href=location.href;
var inc=${matchRes.joinToString(",", "[", "]") { js(it) }}, exc=${exclRes.joinToString(",", "[", "]") { js(it) }};
if(!inc.some(function(r){return new RegExp(r).test(href)})) return;
if(exc.some(function(r){return new RegExp(r).test(href)})) return;
var KEY='tbext:'+${js(extensionId)}+':';
function load(){try{return JSON.parse(localStorage.getItem(KEY)||'{}')}catch(e){return {}}}
function save(o){try{localStorage.setItem(KEY,JSON.stringify(o))}catch(e){}}
function wrap(fn){return function(){var a=[].slice.call(arguments),cb=typeof a[a.length-1]==='function'?a.pop():null;
 var p=new Promise(function(res){res(fn.apply(null,a))});if(cb){p.then(cb);return;}return p;}}
var __tbChrome={
 runtime:{id:${js(extensionId)},getManifest:function(){return {name:${js(info.name)},version:${js(info.version)},manifest_version:${info.manifestVersion}}}},
 storage:{local:{
  get:wrap(function(keys){var o=load(),r={};if(keys==null)return o;
    if(typeof keys==='string')keys=[keys];
    if(Array.isArray(keys)){keys.forEach(function(k){if(k in o)r[k]=o[k]});return r;}
    Object.keys(keys).forEach(function(k){r[k]=(k in o)?o[k]:keys[k]});return r;}),
  set:wrap(function(items){var o=load();Object.keys(items).forEach(function(k){o[k]=items[k]});save(o);}),
  remove:wrap(function(keys){var o=load();[].concat(keys).forEach(function(k){delete o[k]});save(o);}),
  clear:wrap(function(){save({});})
 }}
};
function __tbRun(){
 try{var css=${js(css)};if(css){var s=document.createElement('style');s.textContent=css;(document.head||document.documentElement).appendChild(s);}}catch(e){}
 try{
$bodies
 }catch(e){console.error('[TheBrowser extension ${info.name.replace("'", "")}]',e)}
}
$run
}catch(e){}
})();
""".trimIndent()
            ContentScript(extensionId, script, origins)
        }
}
