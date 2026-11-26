package com.thebrowser.core.reader

import com.thebrowser.core.model.Settings
import org.json.JSONObject
import org.json.JSONTokener

data class ReaderArticle(val title: String, val html: String, val text: String, val baseUrl: String) {
    val wordCount: Int get() = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    val minutes: Int get() = maxOf(1, Math.round(wordCount / 230.0).toInt())
}

object Reader {
    /** Heuristic article extraction run inside the page. Returns a JSON string. */
    const val EXTRACT_JS = """
(function(){
  function score(el){
    var s=0, ps=el.querySelectorAll('p');
    for (var i=0;i<ps.length;i++){ var t=(ps[i].innerText||'').trim(); if(t.length>60) s+=Math.min(t.length,300); }
    return s;
  }
  var best=document.querySelector('article'), bs=best?score(best):0;
  if(!best||bs<300){
    var c=document.querySelectorAll('main,[role=main],section,div'); best=document.body; bs=0;
    for (var i=0;i<c.length;i++){ var sc=score(c[i]); if(sc>bs){bs=sc;best=c[i];} }
  }
  var n=best.cloneNode(true);
  var drop='script,style,noscript,iframe,form,nav,aside,footer,header,button,input,select,textarea,svg,canvas,video,audio,object,embed,[role=navigation],[aria-hidden=true]';
  n.querySelectorAll(drop).forEach(function(e){e.remove();});
  n.querySelectorAll('*').forEach(function(e){
    Array.prototype.slice.call(e.attributes).forEach(function(a){
      var k=a.name.toLowerCase();
      if(k.indexOf('on')===0||k==='style'||k==='class'||k==='id') e.removeAttribute(a.name);
    });
  });
  n.querySelectorAll('img').forEach(function(im){
    var src=im.currentSrc||im.src; if(src) im.setAttribute('src',src);
    im.removeAttribute('srcset'); im.removeAttribute('loading');
  });
  n.querySelectorAll('a').forEach(function(a){ if(a.href) a.setAttribute('href',a.href); });
  var h1=document.querySelector('h1');
  return JSON.stringify({title:(h1&&h1.innerText.trim())||document.title,html:n.innerHTML,text:(best.innerText||'').trim(),base:location.href});
})()
"""

    /** `evaluateJavascript` returns a JSON-encoded string; decode it. */
    fun parse(raw: String?): ReaderArticle? = runCatching {
        if (raw.isNullOrBlank() || raw == "null") return null
        val inner = JSONTokener(raw).nextValue() as? String ?: return null
        val o = JSONObject(inner)
        val article = ReaderArticle(o.optString("title"), o.optString("html"), o.optString("text"), o.optString("base"))
        article.takeIf { it.wordCount >= 20 }
    }.getOrNull()

    fun pageText(raw: String?, limit: Int = 30_000): String =
        runCatching { (JSONTokener(raw ?: "null").nextValue() as? String).orEmpty().take(limit) }.getOrDefault("")

    fun toJson(a: ReaderArticle): String =
        JSONObject().put("title", a.title).put("html", a.html).put("text", a.text).put("base", a.baseUrl).toString()

    fun fromJson(json: String): ReaderArticle? = runCatching {
        val o = JSONObject(json)
        ReaderArticle(o.optString("title"), o.optString("html"), o.optString("text"), o.optString("base"))
    }.getOrNull()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun html(a: ReaderArticle, s: Settings): String {
        val (bg, fg, link) = when (s.readerTheme) {
            "dark" -> Triple("#16161a", "#e6e6ea", "#9d8cff")
            "sepia" -> Triple("#f4ecd8", "#433422", "#8a4b08")
            else -> Triple("#ffffff", "#1c1c1e", "#4a3fd1")
        }
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'">
<style>
body{margin:0;padding:${s.readerMarginDp}px;background:$bg;color:$fg;font:${s.readerFontSp}px/${s.readerLineHeight} Georgia,serif;
  max-width:42em;margin-left:auto;margin-right:auto;word-wrap:break-word}
h1{font-size:1.5em;line-height:1.25}a{color:$link}img{max-width:100%;height:auto}
.meta{opacity:.65;font:0.7em sans-serif;margin-bottom:1.2em}pre,code{white-space:pre-wrap}
blockquote{border-left:3px solid $link;margin-left:0;padding-left:1em;opacity:.9}
</style></head><body><h1>${esc(a.title)}</h1><div class="meta">${a.minutes} min read · ${a.wordCount} words</div>
${a.html}</body></html>"""
    }
}
