package com.thebrowser.core.page

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Decodes what WebView.evaluateJavascript hands back (a JSON value) into a plain string. */
object JsValue {
    fun string(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }
}

/**
 * "Hide Distracting Items": the user taps page elements and they are hidden, per site, and stay hidden on later visits.
 * Works by injecting a style sheet with `display:none` rules built from CSS selectors. Needs an engine that can run page scripts.
 */
object DistractionJs {
    private const val MAX_SELECTOR = 600

    /** Only plain selectors are accepted back from storage, so a damaged or edited state file can't inject other CSS. */
    fun safe(selectors: List<String>): List<String> =
        selectors.filter { it.isNotBlank() && it.length <= MAX_SELECTOR && it.none { c -> c in "{};@<" || c < ' ' } }.distinct()

    /** Installs the saved rules for this page. Safe to run repeatedly. */
    fun apply(selectors: List<String>): String {
        val arr = JSONArray(safe(selectors)).toString()
        return "(function(){try{var S=$arr;window.__tbHiddenAll=S;var st=document.getElementById('tb-hide-style');" +
            "if(!st){st=document.createElement('style');st.id='tb-hide-style';(document.head||document.documentElement).appendChild(st);}" +
            "var sh=st.sheet;if(!sh)return 0;while(sh.cssRules.length)sh.deleteRule(0);var n=0;" +
            "S.forEach(function(s){try{sh.insertRule(s+'{display:none !important}',sh.cssRules.length);n++}catch(e){}});return n}catch(e){return -1}})()"
    }

    /** Starts tap-to-select. Taps are caught so the page does not react; each tap outlines the element under the finger. */
    val START: String = """
(function(){
 if(window.__tbPick&&window.__tbPick.active){return 'already';}
 var P=window.__tbPick={active:true,cur:null,hidden:[]};
 var box=document.createElement('div');box.id='tb-pick-box';
 box.style.cssText='position:fixed;z-index:2147483647;pointer-events:none;border:2px solid #e84118;background:rgba(232,65,24,.18);border-radius:4px;display:none;box-sizing:border-box';
 document.documentElement.appendChild(box);
 function ok(el){return !!el&&el.nodeType===1&&el!==document.documentElement&&el!==document.body&&el.id!=='tb-pick-box';}
 function esc(s){return(window.CSS&&CSS.escape)?CSS.escape(s):String(s).replace(/[^a-zA-Z0-9_-]/g,'\\$&');}
 function uniq(sel){try{return document.querySelectorAll(sel).length===1}catch(e){return false}}
 function stableId(id){return !!id&&id.length<40&&!/\d{4,}/.test(id)&&!/[0-9a-f]{8,}/i.test(id);}
 function part(el){
  if(stableId(el.id))return '#'+esc(el.id);
  var cls=[].slice.call(el.classList).filter(function(c){return c.length<30&&!/\d{3,}/.test(c)}).slice(0,2);
  var s=el.tagName.toLowerCase()+cls.map(function(c){return '.'+esc(c)}).join('');
  var p=el.parentElement;
  if(p){var same=[].filter.call(p.children,function(x){return x.tagName===el.tagName});
   if(same.length>1)s+=':nth-of-type('+(same.indexOf(el)+1)+')';}
  return s;
 }
 function selector(el){
  var s=part(el);if(uniq(s))return s;
  var path=[s],node=el,full;
  while(node.parentElement&&node.parentElement!==document.body&&node.parentElement!==document.documentElement){
   node=node.parentElement;path.unshift(part(node));full=path.join(' > ');if(uniq(full))return full;
  }
  return 'body > '+path.join(' > ');
 }
 function sheet(){var st=document.getElementById('tb-hide-style');
  if(!st){st=document.createElement('style');st.id='tb-hide-style';(document.head||document.documentElement).appendChild(st);}return st.sheet;}
 function rebuild(){var sh=sheet();if(!sh)return;while(sh.cssRules.length)sh.deleteRule(0);
  (window.__tbHiddenAll||[]).concat(P.hidden).forEach(function(s){try{sh.insertRule(s+'{display:none !important}',sh.cssRules.length)}catch(e){}});}
 function show(el){var b=document.getElementById('tb-pick-box');if(!b)return;
  if(!el){b.style.display='none';return;}
  var r=el.getBoundingClientRect();b.style.display='block';b.style.left=r.left+'px';b.style.top=r.top+'px';b.style.width=r.width+'px';b.style.height=r.height+'px';}
 function stopOnly(e){if(P.active)e.stopPropagation();}
 ['pointerdown','pointerup','mousedown','mouseup','touchstart','touchend'].forEach(function(ev){window.addEventListener(ev,stopOnly,{capture:true,passive:true});});
 function block(e){e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation();}
 window.addEventListener('click',function(e){if(!P.active)return;block(e);if(ok(e.target)){P.cur=e.target;show(P.cur);}},true);
 ['auxclick','dblclick','contextmenu','submit'].forEach(function(ev){window.addEventListener(ev,function(e){if(P.active)block(e);},true);});
 P.larger=function(){if(P.cur&&ok(P.cur.parentElement)){P.cur=P.cur.parentElement;show(P.cur);}return P.cur?1:0;};
 P.hide=function(){var el=P.cur;if(!ok(el))return '';var s=selector(el);P.hidden.push(s);rebuild();show(null);P.cur=null;return s;};
 P.undo=function(){var s=P.hidden.pop();rebuild();return s||'';};
 P.state=function(){return JSON.stringify({sel:P.cur?1:0,n:P.hidden.length});};
 P.stop=function(){P.active=false;var b=document.getElementById('tb-pick-box');if(b)b.remove();return JSON.stringify(P.hidden);};
 return 'started';
})()
""".trimIndent()

    const val LARGER = "(function(){return window.__tbPick?window.__tbPick.larger():0})()"
    const val HIDE = "(function(){return window.__tbPick?window.__tbPick.hide():''})()"
    const val UNDO = "(function(){return window.__tbPick?window.__tbPick.undo():''})()"
    const val STATE = "(function(){return window.__tbPick&&window.__tbPick.active?window.__tbPick.state():null})()"
    const val STOP = "(function(){return window.__tbPick?window.__tbPick.stop():'[]'})()"

    data class PickState(val selected: Boolean, val hiddenCount: Int)
    fun parseState(raw: String?): PickState? {
        val j = JsValue.string(raw) ?: return null
        return runCatching { JSONObject(j).let { PickState(it.optInt("sel") == 1, it.optInt("n")) } }.getOrNull()
    }
}

/** Per-tab memory the page itself can report. Android gives no per-tab process memory, so this is the page's JavaScript heap. */
data class TabMemory(val jsHeapBytes: Long?, val domNodes: Int, val frames: Int)

object TabMemoryJs {
    const val SCRIPT = "(function(){try{var m=performance&&performance.memory;return JSON.stringify({js:m?m.usedJSHeapSize:-1,dom:document.getElementsByTagName('*').length,fr:window.frames.length})}catch(e){return null}})()"
    fun parse(raw: String?): TabMemory? {
        val j = JsValue.string(raw) ?: return null
        return runCatching { JSONObject(j).let { TabMemory(it.optLong("js", -1).takeIf { v -> v >= 0 }, it.optInt("dom"), it.optInt("fr")) } }.getOrNull()
    }
    fun format(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%d KB".format(bytes / 1024)
    }
}
