package com.thebrowser.core.agent

import org.json.JSONObject

/**
 * Page-side helper injected on demand (never globally, so sites can't detect or call it ahead of time).
 * It numbers the interactive elements, reports risk flags BEFORE an action, and never returns password
 * or card-field values. The decision to act is made outside the page, in code.
 */
object AgentJs {
    private const val RUNTIME = """
window.__hy=(function(){
 var seq=0;
 function vis(el){var r=el.getBoundingClientRect();var s=getComputedStyle(el);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'&&r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth;}
 function nm(el){var t=el.getAttribute('aria-label')||el.getAttribute('alt')||el.getAttribute('title')||el.getAttribute('placeholder');
  if(!t&&el.id){var l=document.querySelector('label[for="'+el.id+'"]');if(l)t=l.innerText;}
  if(!t)t=(el.innerText||el.value||el.textContent||'');return String(t).replace(/\s+/g,' ').trim().slice(0,90);}
 function isPw(el){return el.tagName==='INPUT'&&el.type==='password';}
 function isCard(el){var a=(el.getAttribute('autocomplete')||'')+' '+(el.name||'')+' '+(el.id||'');return /cc-|card.?num|cvv|cvc|iban|security.?code/i.test(a);}
 var PAY=/(pay now|pay \$|place (your )?order|buy now|purchase|complete (order|purchase)|checkout|confirm (payment|order)|proceed to pay|subscribe|donate)/i;
 var DEL=/(delete|remove|erase|close account|deactivate|unsubscribe|cancel (my )?(subscription|account)|reset|clear all)/i;
 var MSG=/(\bsend\b|\bpost\b|publish|tweet|\breply\b|comment|share|submit review)/i;
 function risk(el){var n=nm(el);var isBtn=el.tagName==='BUTTON'||el.type==='submit'||el.getAttribute('role')==='button'||el.tagName==='A';
  var sub=isBtn&&(el.type==='submit'||(el.tagName==='BUTTON'&&el.form&&el.type!=='button'));
  var pg=/checkout|payment|\/pay\b|billing/i.test(location.pathname+location.search);
  return {host:location.hostname,label:n,password:isPw(el),payment:isCard(el)||(isBtn&&(PAY.test(n)||(pg&&sub))),submit:!!sub,destructive:isBtn&&DEL.test(n),message:isBtn&&MSG.test(n)};}
 function find(ref){return document.querySelector('[data-hy-ref="'+ref+'"]');}
 function snapshot(){
  document.querySelectorAll('[data-hy-ref]').forEach(function(e){e.removeAttribute('data-hy-ref');});seq=0;
  var sel='a[href],button,input,select,textarea,summary,[role=button],[role=link],[role=checkbox],[role=menuitem],[role=tab],[contenteditable=true],[onclick]';
  var out=[];var all=document.querySelectorAll(sel);
  for(var i=0;i<all.length&&out.length<150;i++){var el=all[i];if(!vis(el))continue;var ref='e'+(++seq);el.setAttribute('data-hy-ref',ref);
   var o={ref:ref,tag:el.tagName.toLowerCase(),role:el.getAttribute('role')||'',type:el.type||'',name:nm(el),disabled:!!el.disabled};
   if(el.href&&el.tagName==='A')o.href=String(el.href).slice(0,160);
   if(isPw(el)||isCard(el))o.protected=true;else if(el.tagName==='INPUT'||el.tagName==='TEXTAREA'||el.tagName==='SELECT'){o.value=String(el.value||'').slice(0,60);}
   if(el.type==='checkbox'||el.type==='radio')o.checked=!!el.checked;
   var r=risk(el);if(r.payment)o.payment=true;if(r.destructive)o.destructive=true;if(r.message)o.message=true;if(r.submit)o.submit=true;
   out.push(o);}
  return {url:location.href,title:document.title,scrollY:Math.round(scrollY),pageHeight:document.documentElement.scrollHeight,viewport:[innerWidth,innerHeight],elements:out,text:(document.body?document.body.innerText:'').slice(0,12000)};}
 function inspect(ref){var el=find(ref);if(!el)return {error:'ref '+ref+' not found. Call browser_read_page again.'};return risk(el);}
 function click(ref){var el=find(ref);if(!el)return {error:'ref '+ref+' not found. Call browser_read_page again.'};if(el.disabled)return {error:'element is disabled'};
  el.scrollIntoView({block:'center'});el.focus&&el.focus();el.click();return {ok:true,clicked:nm(el)};}
 function type(ref,text,submit){var el=find(ref);if(!el)return {error:'ref '+ref+' not found. Call browser_read_page again.'};
  if(isPw(el)||isCard(el))return {error:'password and card fields are protected'};
  el.scrollIntoView({block:'center'});el.focus();
  if(el.isContentEditable){el.textContent=text;}else{var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var d=Object.getOwnPropertyDescriptor(proto,'value');d.set.call(el,text);}
  el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));
  if(submit){if(el.form&&el.form.requestSubmit){el.form.requestSubmit();}else{['keydown','keypress','keyup'].forEach(function(t){el.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,bubbles:true}));});}}
  return {ok:true};}
 function select(ref,value){var el=find(ref);if(!el||el.tagName!=='SELECT')return {error:'not a select element'};
  var o=[].slice.call(el.options).filter(function(x){return x.value===value||x.text.trim()===value;})[0];if(!o)return {error:'option not found'};
  el.value=o.value;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return {ok:true};}
 function press(key){var el=document.activeElement||document.body;['keydown','keypress','keyup'].forEach(function(t){el.dispatchEvent(new KeyboardEvent(t,{key:key,bubbles:true}));});return {ok:true};}
 function scroll(dir){var h=innerHeight*0.85;if(dir==='down')scrollBy(0,h);else if(dir==='up')scrollBy(0,-h);else if(dir==='top')scrollTo(0,0);else scrollTo(0,document.documentElement.scrollHeight);return {ok:true,scrollY:Math.round(scrollY)};}
 return {snapshot:snapshot,inspect:inspect,click:click,type:type,select:select,press:press,scroll:scroll};
})();
"""

    /** Builds a one-shot script: installs the helper if missing, runs [fn] with JSON [args], returns a JSON string. */
    fun call(fn: String, vararg args: Any?): String {
        require(fn in setOf("snapshot", "inspect", "click", "type", "select", "press", "scroll")) { "unknown helper" }
        val a = args.joinToString(",") { v -> if (v == null) "null" else JSONObject.quote(v.toString()).let { q -> if (v is Boolean || v is Number) v.toString() else q } }
        return "(function(){try{if(!window.__hy){$RUNTIME}return JSON.stringify(window.__hy.$fn($a));}catch(e){return JSON.stringify({error:String(e)});}})()"
    }

    /** Formats a snapshot as compact text for a model. */
    fun format(json: String): String {
        val o = JSONObject(json)
        if (o.has("error")) return "Error: ${o.getString("error")}"
        val sb = StringBuilder("URL: ${o.optString("url")}\nTitle: ${o.optString("title")}\nScroll: ${o.optInt("scrollY")} / ${o.optInt("pageHeight")}\n\nInteractive elements:\n")
        val els = o.optJSONArray("elements")
        if (els != null) for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            sb.append("[${e.getString("ref")}] ${e.optString("role").ifBlank { e.getString("tag") }}")
            if (e.optString("type").isNotBlank() && e.getString("tag") == "input") sb.append("(${e.getString("type")})")
            if (e.optString("name").isNotBlank()) sb.append(" \"${e.getString("name")}\"")
            if (e.has("value") && e.optString("value").isNotBlank()) sb.append(" value=\"${e.getString("value")}\"")
            if (e.optBoolean("protected")) sb.append(" [protected: not readable or writable by agents]")
            if (e.has("checked")) sb.append(if (e.getBoolean("checked")) " [checked]" else " [unchecked]")
            if (e.optBoolean("disabled")) sb.append(" [disabled]")
            val flags = listOf("payment", "destructive", "message", "submit").filter { e.optBoolean(it) }
            if (flags.isNotEmpty()) sb.append(" [needs approval: ${flags.joinToString("/")}]")
            if (e.has("href")) sb.append(" → ${e.getString("href")}")
            sb.append('\n')
        }
        sb.append("\nVisible text:\n").append(o.optString("text"))
        return sb.toString()
    }

    fun risk(json: String): ToolRisk {
        val o = JSONObject(json)
        return ToolRisk(
            host = o.optString("host").ifBlank { null }, payment = o.optBoolean("payment"), password = o.optBoolean("password"),
            submit = o.optBoolean("submit"), destructive = o.optBoolean("destructive"), message = o.optBoolean("message"), label = o.optString("label"),
        )
    }
}
