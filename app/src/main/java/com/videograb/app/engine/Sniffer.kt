package com.videograb.app.engine

import android.net.Uri

data class Media(val url: String, val label: String, val kind: String, val quality: String?)

/** تشخیص لینک ویدیو از ترافیک WebView + اسکریپت استخراج از صفحه. */
object Sniffer {
    private val mediaExt = Regex("""\.(mp4|m4v|webm|mov|mkv|m3u8)(?=$|[?#])""", RegexOption.IGNORE_CASE)

    /** HLS | HTTP | null */
    fun classify(url: String): String? {
        if (!url.startsWith("http")) return null
        val m = mediaExt.find(url) ?: return null
        return if (m.groupValues[1].equals("m3u8", true)) "HLS" else "HTTP"
    }

    fun extOf(url: String): String {
        val e = mediaExt.find(url)?.groupValues?.get(1)?.lowercase() ?: return "mp4"
        return if (e == "m3u8") "ts" else e
    }

    fun labelOf(url: String): String =
        url.substringBefore('?').substringAfterLast('/').ifBlank { "video" }.take(40)

    /** پارامترهای bytestart/byteend (تکه‌های DASH) را حذف می‌کند تا کل فایل دانلود شود. */
    fun clean(url: String): String {
        if (!url.contains("bytestart") && !url.contains("byteend")) return url
        val u = Uri.parse(url)
        val b = u.buildUpon().clearQuery()
        u.queryParameterNames.filter { it != "bytestart" && it != "byteend" }
            .forEach { n -> u.getQueryParameters(n).forEach { b.appendQueryParameter(n, it) } }
        return b.build().toString()
    }

    /** به صفحه تزریق می‌شود: <video>، <source>، og:video و JSON اینستاگرام (video_versions). */
    val JS = """
(function(){
 if(window.__vgTimer) return;
 window.__vgSeen = {};
 function dec(s){ try{ return JSON.parse('"'+s+'"'); }catch(e){ return s.replace(/\\u0026/g,'&').replace(/\\\//g,'/'); } }
 function scan(){
  try{
   var out=[];
   document.querySelectorAll('video').forEach(function(v){
     var s=v.currentSrc||v.src;
     if(s&&s.indexOf('blob:')!==0) out.push({u:s,q:v.videoHeight?(v.videoHeight+'p'):''});
     v.querySelectorAll('source').forEach(function(x){ if(x.src) out.push({u:x.src,q:x.getAttribute('label')||x.getAttribute('title')||''}); });
   });
   document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[name="twitter:player:stream"]').forEach(function(m){ if(m.content) out.push({u:m.content,q:''}); });
   document.querySelectorAll('script').forEach(function(sc){
     var t=sc.textContent; if(!t||t.length>5000000) return;
     if(t.indexOf('video_versions')>=0){
       var re=/"video_versions":\[(.*?)\]/g, m;
       while((m=re.exec(t))!==null){
         m[1].split('},{').forEach(function(part){
           var u=/"url":"([^"]+)"/.exec(part), h=/"height":(\d+)/.exec(part);
           if(u) out.push({u:dec(u[1]),q:h?(h[1]+'p'):''});
         });
       }
     }
     var re2=/"video_url":"([^"]+)"/g, m2;
     while((m2=re2.exec(t))!==null) out.push({u:dec(m2[1]),q:''});
   });
   var fresh=out.filter(function(o){ if(window.__vgSeen[o.u]) return false; window.__vgSeen[o.u]=1; return true; });
   if(fresh.length && window.VG) window.VG.onMedia(JSON.stringify(fresh));
  }catch(e){}
 }
 scan(); window.__vgTimer=setInterval(scan,2500);
})();
"""
}
