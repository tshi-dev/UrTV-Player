package com.urtv.player.phone

/** The tiny bilingual (Arabic first) page served to the phone. No script, no external resources. */
object Page {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun wrap(body: String) = """<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>UrTV</title><style>
body{margin:0;background:#0b0d12;color:#f3f5fa;font:18px/1.6 system-ui,sans-serif;padding:20px}
main{max-width:520px;margin:auto}h1{font-size:26px;margin:0 0 4px}p.s{color:#98a2b3;margin:0 0 18px}
label{display:block;margin:14px 0 4px}small{color:#98a2b3}
input{width:100%;box-sizing:border-box;font:inherit;padding:12px;border-radius:12px;border:1px solid #2a3140;background:#151922;color:#f3f5fa;direction:ltr;text-align:left}
fieldset{border:1px solid #2a3140;border-radius:14px;margin:18px 0;padding:6px 14px 14px}legend{color:#7c6cff;padding:0 6px}
button{width:100%;margin-top:18px;font:inherit;font-weight:600;padding:14px;border:0;border-radius:14px;background:#7c6cff;color:#fff}
.e{background:#3a1d22;border-radius:12px;padding:10px 14px;margin:12px 0}
</style></head><body><main>$body</main></body></html>"""

    fun codeForm(error: Boolean) = wrap("""<h1>UrTV</h1><p class="s">أدخل الرمز الظاهر على التلفاز · Enter the code shown on the TV</p>
${if (error) "<div class=\"e\">الرمز غير صحيح · Wrong code</div>" else ""}
<form method="get" action="/"><input name="c" inputmode="numeric" maxlength="6" autocomplete="off" autofocus placeholder="000000"><button>متابعة · Continue</button></form>""")

    fun form(code: String, error: Boolean = false) = wrap("""<h1>إضافة قائمة · Add a playlist</h1>
<p class="s">املأ أحد القسمين · Fill one of the two sections</p>
${if (error) "<div class=\"e\">أكمل الرابط أو بيانات الخادم · Enter a URL or server details</div>" else ""}
<form method="post" action="/add"><input type="hidden" name="c" value="${esc(code)}">
<label>الاسم · Name <small>(اختياري · optional)</small></label><input name="name" maxlength="60">
<fieldset><legend>M3U</legend><label>الرابط · URL</label><input name="url" type="url" placeholder="https://"></fieldset>
<fieldset><legend>Xtream</legend><label>الخادم · Server</label><input name="server" placeholder="http://host:port">
<label>المستخدم · Username</label><input name="user" autocapitalize="none" autocomplete="off">
<label>كلمة المرور · Password</label><input name="pass" type="password" autocomplete="off"></fieldset>
<label>دليل البرامج XMLTV · Guide URL <small>(اختياري · optional)</small></label><input name="epg" type="url" placeholder="https://">
<button>إرسال إلى التلفاز · Send to TV</button></form>""")

    fun done() = wrap("""<h1>تم ✓ · Done</h1><p class="s">أُرسلت القائمة إلى التلفاز، ويمكنك إغلاق هذه الصفحة.<br>The playlist was sent to the TV. You can close this page.</p>""")
}
