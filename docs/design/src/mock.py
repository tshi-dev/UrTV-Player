#!/usr/bin/env python3
"""Generates the UrTV design mockups (HTML -> 1920x1080 PNG via Playwright). Placeholder content only."""
import json, os, random, sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.dirname(HERE)

T = {
 'ar': dict(dir='rtl', lang='ar',
  nav=['الرئيسية','القنوات','أفلام','مسلسلات','الدليل','الإعدادات'],
  cont='أكمل المشاهدة', favs='المفضّلة', recent='آخر القنوات', all='كل القنوات',
  groups=['أخبار','رياضة','أفلام','وثائقيات','أطفال','منوعات','موسيقى'],
  chans=['تجريبية · أخبار 1','تجريبية · أخبار 2','تجريبية · رياضة 1','تجريبية · رياضة 2','تجريبية · أفلام','تجريبية · وثائقيات','تجريبية · أطفال','تجريبية · منوعات','تجريبية · موسيقى','تجريبية · أخبار 3','تجريبية · رياضة 3','تجريبية · أفلام 2'],
  progs=['النشرة الرئيسية','مباراة مباشرة','فيلم السهرة','عالم الطبيعة','رسوم متحركة','حوار مفتوح','ركن الموسيقى','تقرير خاص'],
  left='د متبقية', now='الآن', next='التالي', live='مباشر', search='ابحث', play='تشغيل', resume='تابع من', fromstart='من البداية', season='الموسم',
  ep='ح', sn='م', remaining=lambda m: f'{m} د متبقية', desc='وصف قصير للبرنامج يظهر هنا من دليل البرامج الذي أضافه المستخدم.',
  series='مسلسل تجريبي', movie='فيلم تجريبي', sub='دراما · 2٠23 · ٤٫3 ★',
  empty_t='ما عندك قائمة بعد', empty_b='أضف قائمتك من جوالك أو اكتب الرابط هنا. التطبيق مشغّل فقط ولا يحتوي على أي قنوات.', add='إضافة قائمة', from_phone='من الجوال', type_url='كتابة الرابط',
  ph_t='امسح الرمز بجوالك', ph_b='أو افتح هذا العنوان في متصفح جوالك وأدخل الرمز. الجوال والتلفاز على نفس الشبكة.', code='الرمز', safe='الصفحة تعمل على شبكتك فقط وتُغلق بعد الإرسال أو بعد 1٠ دقائق.',
  set_menu=['عام','القوائم','التشغيل','المظهر','الرقابة الأبوية','عن التطبيق'],
  set_rows=[('اللغة','العربية'),('تحديث القائمة تلقائيًا','كل 2٤ ساعة'),('التخزين المؤقت','متوسط'),('متابعة آخر قناة عند الفتح','مفعّل'),('إزاحة وقت الدليل','+0.0 س'),('حجم الخط','عادي')],
  audio='الصوت', subs='الترجمة', aspect='الأبعاد', fit='ملائم', minutes=lambda n: f'{n} د', epcount='حلقات', del_row='إزالة من القائمة',
  next_ep='الحلقة التالية', next_in='تبدأ خلال', sec='ث', clock='2٠:٤٥', day='الاثنين'),
 'en': dict(dir='ltr', lang='en',
  nav=['Home','Channels','Movies','Series','Guide','Settings'],
  cont='Continue watching', favs='Favourites', recent='Recent channels', all='All channels',
  groups=['News','Sports','Movies','Documentary','Kids','Entertainment','Music'],
  chans=['Example News 1','Example News 2','Example Sports 1','Example Sports 2','Example Movies','Example Docs','Example Kids','Example Variety','Example Music','Example News 3','Example Sports 3','Example Movies 2'],
  progs=['Main Bulletin','Live Match','Late Movie','Nature World','Cartoon Hour','Open Talk','Music Corner','Special Report'],
  left='min left', now='Now', next='Next', live='LIVE', search='Search', play='Play', resume='Resume from', fromstart='From the start', season='Season',
  ep='E', sn='S', remaining=lambda m: f'{m} min left', desc='A short programme description appears here, taken from the guide the user added.',
  series='Example Series', movie='Example Movie', sub='Drama · 2023 · 4.3 ★',
  empty_t='No playlist yet', empty_b='Add yours from your phone or type the link here. The app is a player only and contains no channels.', add='Add playlist', from_phone='From phone', type_url='Type the link',
  ph_t='Scan the code with your phone', ph_b="Or open this address in your phone's browser and enter the code. Phone and TV must be on the same network.", code='Code', safe='The page works on your network only and closes after sending, or after 10 minutes.',
  set_menu=['General','Playlists','Playback','Appearance','Parental control','About'],
  set_rows=[('Language','English'),('Auto-refresh playlist','Every 24 h'),('Buffer','Normal'),('Resume last channel on start','On'),('Guide time offset','+0.0 h'),('Text size','Normal')],
  audio='Audio', subs='Subtitles', aspect='Aspect', fit='Fit', minutes=lambda n: f'{n} min', epcount='episodes', del_row='Remove from row',
  next_ep='Next episode', next_in='starts in', sec='s', clock='20:45', day='Mon'),
}

CSS = """
@font-face{font-family:'Plex';src:url('fonts/IBMPlexSansArabic_400Regular.ttf');font-weight:400}
@font-face{font-family:'Plex';src:url('fonts/IBMPlexSansArabic_500Medium.ttf');font-weight:500}
@font-face{font-family:'Plex';src:url('fonts/IBMPlexSansArabic_600SemiBold.ttf');font-weight:600}
:root{--bg:#0E0F11;--s1:#16181B;--s2:#1E2125;--t1:#F2F2F2;--t2:rgba(242,242,242,.70);--t3:rgba(242,242,242,.45);--acc:#0A84FF;--glass:rgba(255,255,255,.07);--glass2:rgba(255,255,255,.13)}
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:1920px;height:1080px;overflow:hidden;background:var(--bg);color:var(--t1);font-family:'Plex',sans-serif;font-weight:400;font-size:24px;font-variant-numeric:tabular-nums}
body{position:relative}
.glow{position:absolute;inset:0;background:radial-gradient(900px 600px at 12% -10%,rgba(38,64,110,.30),transparent 70%),radial-gradient(900px 600px at 95% 110%,rgba(92,59,31,.22),transparent 70%)}
.wrap{position:absolute;inset:0;padding:40px 72px}
.top{height:72px;display:flex;align-items:center;justify-content:space-between}
.brand{display:flex;align-items:center;gap:14px;font-weight:600;font-size:28px;letter-spacing:.3px;width:300px}
.mark{width:40px;height:40px;border-radius:12px;background:var(--t1);display:grid;place-items:center}
.mark:after{content:'';border-style:solid;border-width:9px 0 9px 15px;border-color:transparent transparent transparent #0E0F11;margin-inline-start:3px}
[dir=rtl] .mark:after{border-width:9px 15px 9px 0;border-color:transparent #0E0F11 transparent transparent;margin-inline-start:0;margin-inline-end:3px}
.nav{display:flex;gap:4px;background:var(--glass);border-radius:999px;padding:6px;border:1px solid rgba(255,255,255,.06)}
.nav span{padding:10px 26px;border-radius:999px;font-size:22px;color:var(--t2)}
.nav span.on{background:var(--glass2);color:var(--t1);font-weight:500}
.nav span.f{background:var(--t1);color:#111;font-weight:500}
.clock{width:300px;text-align:end;color:var(--t2);font-size:22px;display:flex;justify-content:flex-end;gap:14px;align-items:center}
.clock b{color:var(--t1);font-weight:500;font-size:26px}
.dot{width:10px;height:10px;border-radius:50%;background:var(--acc);display:inline-block}
.dim{color:var(--t2)} .dim3{color:var(--t3)}
.bar{height:3px;border-radius:2px;background:rgba(255,255,255,.16);overflow:hidden}.bar i{display:block;height:100%;background:var(--acc)}
.tile{border-radius:10px;display:grid;place-items:center;font-weight:600;color:#fff}
.art{position:absolute;inset:0}
.row-title{font-size:26px;font-weight:600;margin:0 0 16px}
.btn{display:inline-flex;align-items:center;height:60px;padding:0 34px;border-radius:14px;font-size:24px;font-weight:500;background:var(--s2);color:var(--t1)}
.btn.f{background:var(--t1);color:#111}
.pill{display:inline-block;padding:4px 14px;border-radius:999px;background:var(--glass2);font-size:18px}
"""

def hue(s):
    h = 0
    for ch in s: h = (h*31 + ord(ch)) % 360
    return h
def tile(name, size=44, fs=20):
    h = hue(name)
    return f'<div class="tile" style="width:{size}px;height:{size}px;font-size:{fs}px;background:linear-gradient(135deg,hsl({h} 38% 34%),hsl({(h+30)%360} 42% 22%))">{name.strip()[-1] if name.strip()[-1].isdigit() else name.strip()[0]}</div>'
def art(seed, extra=''):
    h = hue(seed); rnd = random.Random(h)
    blobs = ''.join(f'<div style="position:absolute;border-radius:50%;filter:blur(40px);opacity:.55;width:{rnd.randint(180,420)}px;height:{rnd.randint(180,420)}px;left:{rnd.randint(-5,80)}%;top:{rnd.randint(-10,70)}%;background:hsl({(h+rnd.randint(0,90))%360} 45% {rnd.randint(30,46)}%)"></div>' for _ in range(3))
    return f'<div class="art" style="background:linear-gradient(160deg,hsl({h} 35% 20%),hsl({(h+40)%360} 40% 12%));overflow:hidden">{blobs}{extra}</div>'

def page(t, body, nav_on=1, focus=None, extra_css=''):
    nav = ''.join(f'<span class="{"f" if focus==i else ("on" if nav_on==i else "")}">{n}</span>' for i, n in enumerate(t['nav']))
    return f"""<!doctype html><html dir="{t['dir']}" lang="{t['lang']}"><meta charset="utf-8"><style>{CSS}{extra_css}</style><body><div class="glow"></div><div class="wrap">
<div class="top"><div class="brand"><div class="mark"></div>UrTV</div><div class="nav">{nav}</div><div class="clock"><span>{t['day']}</span><b>{t['clock']}</b></div></div>{body}</div></body></html>"""

def prog(t, i, mins=None):
    return t['progs'][i % len(t['progs'])]

# ---------------- screens
def s_home(t):
    cont = [('series',0.62,t['sn']+'2·'+t['ep']+'4',31),('movie',0.35,'',58),('series',0.18,t['sn']+'1·'+t['ep']+'7',40),('series',0.80,t['sn']+'3·'+t['ep']+'2',12)]
    cards = ''
    for i,(k,p,se,m) in enumerate(cont):
        name = t['series'] if k=='series' else t['movie']; name += f' {i+1}'
        f = ' f' if i==0 else ''
        cards += f"""<div class="cc{f}"><div class="im">{art(name)}<div class="bar" style="position:absolute;inset-inline:14px;bottom:14px"><i style="width:{int(p*100)}%"></i></div></div>
<div class="tx"><div class="n">{name}</div><div class="m">{(se+' · ') if se else ''}{t['remaining'](m)}</div></div></div>"""
    favs = ''.join(f'<div class="fc">{tile(c,64,28)}<div><div class="n">{c}</div><div class="m">{prog(t,i)}</div></div></div>' for i,c in enumerate(t['chans'][:6]))
    rec = ''.join(f'<div class="rc">{tile(c,56,24)}<div class="n">{c}</div></div>' for c in t['chans'][6:12])
    css = """.rc{display:flex;flex-direction:column;align-items:center;gap:10px;width:200px;font-size:19px;color:var(--t2);text-align:center}.recs{display:flex;gap:28px;margin-top:0}.rows{margin-top:30px}.cont{display:flex;gap:28px;margin-bottom:46px}
.cc{width:400px}.cc .im{position:relative;height:225px;border-radius:16px;overflow:hidden}.cc .tx{padding:14px 4px}
.cc .n{font-size:24px;font-weight:500}.cc .m{font-size:19px;color:var(--t2);margin-top:4px}
.cc.f{transform:scale(1.04);transform-origin:center top}.cc.f .im{box-shadow:0 18px 40px rgba(0,0,0,.55),0 0 0 3px var(--t1)}
.cc.f .n{color:#fff}
.favs{display:grid;grid-template-columns:repeat(3,1fr);gap:16px 28px}.fc{display:flex;gap:18px;align-items:center;background:var(--s1);border-radius:14px;padding:14px 18px}
.fc .n{font-size:23px;font-weight:500}.fc .m{font-size:18px;color:var(--t2)}"""
    body = f"""<div class="rows"><div class="row-title">{t['cont']}</div><div class="cont">{cards}</div>
<div class="row-title">{t['favs']}</div><div class="favs" style="margin-bottom:40px">{favs}</div><div class="row-title">{t['recent']}</div><div class="recs">{rec}</div></div>"""
    return page(t, body, nav_on=0, extra_css=css)

def s_live(t):
    rail = ''.join(f'<div class="g{" on" if i==0 else ""}"><span>{g}</span><span class="dim3">{c}</span></div>' for i,(g,c) in enumerate(zip([t['all']]+t['groups'], [412,64,38,57,22,31,45,19])))
    rows = ''
    pcts = [62,35,80,12,50,70,25,90,44,58]
    for i in range(10):
        c = t['chans'][i % len(t['chans'])]
        f = ' f' if i==2 else ''
        rows += f"""<div class="r{f}"><div class="num">{101+i}</div>{tile(c,48,22)}<div class="mid"><div class="nm">{c}</div><div class="np"><span>{prog(t,i+2)}</span><span class="rm">{t['remaining'](10+i*7%50)}</span></div><div class="bar"><i style="width:{pcts[i]}%"></i></div></div></div>"""
    sel = t['chans'][2]
    css = """.main{display:grid;grid-template-columns:270px 1fr 600px;gap:36px;margin-top:22px;height:900px}
.rail{padding-top:6px}.g{display:flex;justify-content:space-between;padding:13px 18px;border-radius:12px;font-size:23px;color:var(--t2)}.g.on{background:var(--glass2);color:var(--t1);font-weight:500}.g .dim3{font-size:18px}
.srch{display:flex;align-items:center;gap:12px;height:52px;border-radius:12px;background:var(--s1);padding:0 18px;color:var(--t3);font-size:21px;margin-bottom:12px}
.list{display:flex;flex-direction:column;gap:4px}
.r{display:flex;align-items:center;gap:16px;height:76px;padding:0 18px;border-radius:12px}
.r .num{width:48px;color:var(--t3);font-size:20px}.r .mid{flex:1;min-width:0}.nm{font-size:24px;font-weight:500;white-space:nowrap}
.np{display:flex;justify-content:space-between;font-size:18px;color:var(--t2);margin:1px 0 5px}.np .rm{color:var(--t3)}
.r.f{background:var(--t1);color:#111;transform:scale(1.02);box-shadow:0 10px 30px rgba(0,0,0,.45)}.r.f .num,.r.f .np,.r.f .np .rm{color:#555}.r.f .bar{background:rgba(0,0,0,.15)}
.pv{background:var(--s1);border-radius:18px;padding:18px;align-self:start}
.vid{position:relative;height:315px;border-radius:12px;overflow:hidden}
.vid .lv{position:absolute;top:14px;inset-inline-start:14px;display:flex;gap:8px;align-items:center;font-size:16px;font-weight:600;letter-spacing:.5px;background:rgba(0,0,0,.5);padding:5px 12px;border-radius:999px}
.pv h2{font-size:32px;font-weight:600;margin:18px 4px 6px}.pv .t{font-size:22px;font-weight:500;margin:0 4px}.pv .tm{font-size:18px;color:var(--t2);margin:4px 4px 10px}
.pv p{font-size:19px;color:var(--t2);margin:12px 4px;line-height:1.6}.pv .nx{margin:16px 4px 4px;padding-top:14px;border-top:1px solid rgba(255,255,255,.08);font-size:19px;color:var(--t2)}"""
    body = f"""<div class="main"><div class="rail">{rail}</div><div><div class="srch">⌕ &nbsp;{t['search']}</div><div class="list">{rows}</div></div>
<div class="pv"><div class="vid">{art('video')}<div class="lv"><span class="dot" style="background:#fff"></span>{t['live']}</div></div>
<h2>{sel}</h2><div class="t">{prog(t,4)}</div><div class="tm">20:00 – 21:00 · {t['remaining'](15)}</div><div class="bar"><i style="width:75%"></i></div>
<p>{t['desc']}</p><div class="nx"><b style="color:var(--t1);font-weight:500">{t['next']}</b> &nbsp; 21:00 &nbsp; {prog(t,5)}</div></div></div>"""
    return page(t, body, nav_on=1, extra_css=css)

def s_guide(t):
    rtl = t['dir']=='rtl'
    slots = ['20:00','20:30','21:00','21:30','22:00','22:30','23:00']
    hdr = ''.join(f'<div class="ts" style="inset-inline-start:{i*220}px">{s}</div>' for i,s in enumerate(slots))
    lens = [[3,2,4],[2,2,3,2],[4,3,2],[2,3,3,2],[2,4,3],[3,3,3],[2,2,2,3]]
    rows = ''
    for r in range(8):
        c = t['chans'][r]
        x = 0; cells = ''
        for j,l in enumerate(lens[r % len(lens)]):
            w = l*110
            f = ' f' if (r==2 and j==1) else ''
            cells += f'<div class="pc{f}" style="inset-inline-start:{x}px;width:{w}px"><div class="pt">{t["progs"][(r+j)%8]}</div><div class="ptm">{slots[min(x//220,6)]}</div></div>'
            x += w
        rows += f'<div class="gr"><div class="gc"><span class="num">{101+r}</span>{tile(c,40,18)}<span class="gn">{c}</span></div><div class="gp">{cells}</div></div>'
    css = """.g0{margin-top:18px}.dtl{height:120px}.dtl h2{font-size:34px;font-weight:600}.dtl .tm{font-size:20px;color:var(--t2);margin:4px 0}.dtl p{font-size:20px;color:var(--t3)}
.gh{display:flex;height:40px}.gh .sp{width:330px}.gh .tr{position:relative;flex:1}.ts{position:absolute;top:6px;font-size:19px;color:var(--t3);padding-inline-start:8px}
.gr{display:flex;height:84px;border-top:1px solid rgba(255,255,255,.06)}.gc{width:330px;display:flex;align-items:center;gap:14px}.gc .num{width:44px;color:var(--t3);font-size:20px}.gn{font-size:21px;color:var(--t2);white-space:nowrap}
.gp{position:relative;flex:1}.pc{position:absolute;top:8px;bottom:8px;padding:6px 14px;border-inline-start:1px solid rgba(255,255,255,.10);overflow:hidden}
.pt{font-size:21px;font-weight:500;white-space:nowrap}.ptm{font-size:16px;color:var(--t3)}
.pc.f{background:var(--t1);color:#111;border-radius:10px;border:0}.pc.f .ptm{color:#555}
.nowl{position:absolute;top:0;bottom:0;inset-inline-start:330px;width:0;border-inline-start:2px solid var(--acc)}"""
    body = f"""<div class="g0"><div class="dtl"><h2>{t['progs'][3]}</h2><div class="tm">20:30 – 21:30 · {t['chans'][2]}</div><p>{t['desc']}</p></div>
<div class="gh"><div class="sp"></div><div class="tr">{hdr}</div></div><div style="position:relative">{rows}<div class="nowl" style="inset-inline-start:{330+150}px"></div></div></div>"""
    return page(t, body, nav_on=4, extra_css=css)

def s_player(t, mini=False):
    c = t['chans'][2]
    css = """.pl{position:absolute;inset:0}.scrim{position:absolute;inset:0;background:linear-gradient(to top,rgba(0,0,0,.88) 0%,rgba(0,0,0,.55) 22%,transparent 46%),linear-gradient(to bottom,rgba(0,0,0,.45),transparent 18%)}
.osd{position:absolute;inset-inline:84px;bottom:64px;display:flex;gap:34px;align-items:flex-end}
.osd .info{flex:1}.osd h1{font-size:44px;font-weight:600;margin:10px 0 6px}.osd .meta{font-size:22px;color:var(--t2);display:flex;gap:18px;align-items:center}
.osd .bar{margin:18px 0 12px;height:4px}.osd .nx{font-size:21px;color:var(--t2)}
.top2{position:absolute;inset-inline:84px;top:48px;display:flex;justify-content:space-between;font-size:22px;color:var(--t2)}
.chips{display:flex;gap:12px}.chip{background:var(--glass2);padding:8px 18px;border-radius:999px;font-size:19px;backdrop-filter:blur(8px)}
.mg{position:absolute;inset-block:0;inset-inline-start:0;width:640px;background:linear-gradient(to right,rgba(8,9,10,.94),rgba(8,9,10,.86) 75%,transparent);padding:56px 40px}
[dir=rtl] .mg{background:linear-gradient(to left,rgba(8,9,10,.94),rgba(8,9,10,.86) 75%,transparent)}
.mg .r{display:flex;align-items:center;gap:16px;height:80px;padding:0 18px;border-radius:12px}.mg .num{width:46px;color:var(--t3);font-size:20px}.mg .mid{flex:1}.mg .nm{font-size:23px;font-weight:500}.mg .np{font-size:18px;color:var(--t2)}
.mg .r.f{background:var(--t1);color:#111}.mg .r.f .num,.mg .r.f .np{color:#555}"""
    mg = ''
    if mini:
        for i in range(9):
            cc = t['chans'][i%12]
            mg += f'<div class="r{" f" if i==2 else ""}"><div class="num">{101+i}</div>{tile(cc,46,20)}<div class="mid"><div class="nm">{cc}</div><div class="np">{prog(t,i+2)}</div></div></div>'
        mg = f'<div class="mg">{mg}</div>'
    osd = '' if mini else f"""<div class="osd">{tile(c,96,40)}<div class="info"><div class="meta"><b style="color:var(--t1);font-weight:600">103</b><span>{c}</span><span class="pill"><span class="dot" style="background:#fff;width:8px;height:8px"></span> {t['live']}</span></div>
<h1>{t['progs'][2]}</h1><div class="meta"><span>20:00 – 21:30</span><span>{t['remaining'](34)}</span></div><div class="bar"><i style="width:62%"></i></div><div class="nx"><b style="color:var(--t1);font-weight:500">{t['next']}</b> &nbsp; 21:30 &nbsp; {t['progs'][3]}</div></div></div>
<div class="top2"><div class="chips"><span class="chip">{t['audio']} · 5.1</span><span class="chip">{t['subs']} · —</span><span class="chip">{t['aspect']} · {t['fit']}</span></div><span>{t['clock']}</span></div>"""
    return f"""<!doctype html><html dir="{t['dir']}" lang="{t['lang']}"><meta charset="utf-8"><style>{CSS}{css}</style><body><div class="pl">{art('player-frame')}</div><div class="scrim"></div>{osd}{mg}</body></html>"""

def qr_svg():
    rnd = random.Random(7); n = 29; cells = []
    def finder(x,y):
        for i in range(7):
            for j in range(7):
                if i in (0,6) or j in (0,6) or (2<=i<=4 and 2<=j<=4): cells.append((x+i,y+j))
    finder(0,0); finder(n-7,0); finder(0,n-7)
    for i in range(n):
        for j in range(n):
            if (i<8 and j<8) or (i>n-9 and j<8) or (i<8 and j>n-9): continue
            if rnd.random()<.48: cells.append((i,j))
    r = ''.join(f'<rect x="{x}" y="{y}" width="1.02" height="1.02"/>' for x,y in cells)
    return f'<svg viewBox="-2 -2 {n+4} {n+4}" width="360" height="360" style="background:#fff;border-radius:18px"><g fill="#111">{r}</g></svg>'

def s_add(t):
    css = """.ad{display:flex;gap:80px;align-items:center;height:860px;padding:0 40px}.ad h1{font-size:44px;font-weight:600;margin-bottom:14px}.ad p{font-size:23px;color:var(--t2);line-height:1.7;max-width:760px}
.addr{font-size:34px;font-weight:500;margin:26px 0 8px;direction:ltr;text-align:start}.cd{font-size:70px;font-weight:600;letter-spacing:10px;direction:ltr;text-align:start;margin:6px 0 22px}
.tabs2{display:flex;gap:10px;margin-bottom:30px}.tabs2 span{padding:10px 24px;border-radius:999px;font-size:22px;color:var(--t2)}.tabs2 .f{background:var(--t1);color:#111;font-weight:500}.safe{font-size:19px;color:var(--t3);max-width:700px}"""
    body = f"""<div class="ad"><div>{qr_svg()}</div><div><div class="tabs2"><span class="f">{t['from_phone']}</span><span>M3U</span><span>Xtream</span></div><h1>{t['ph_t']}</h1><p>{t['ph_b']}</p>
<div class="addr">192.0.2.10:8765</div><div class="dim3" style="font-size:20px">{t['code']}</div><div class="cd">482 915</div><div class="safe">{t['safe']}</div></div></div>"""
    return page(t, body, nav_on=5, extra_css=css)

def s_settings(t):
    menu = ''.join(f'<div class="mi{" on" if i==0 else ""}">{m}</div>' for i,m in enumerate(t['set_menu']))
    rows = ''.join(f'<div class="sr{" f" if i==1 else ""}"><span>{a}</span><span class="v">{b}</span></div>' for i,(a,b) in enumerate(t['set_rows']))
    css = """.st{display:grid;grid-template-columns:360px 1fr;gap:56px;margin-top:30px}.mi{padding:16px 22px;border-radius:12px;font-size:24px;color:var(--t2)}.mi.on{background:var(--glass2);color:var(--t1);font-weight:500}
.sr{display:flex;justify-content:space-between;align-items:center;height:84px;padding:0 26px;border-radius:14px;font-size:24px;border-bottom:1px solid rgba(255,255,255,.05)}.sr .v{color:var(--t2)}
.sr.f{background:var(--t1);color:#111;transform:scale(1.015);border-bottom:0;box-shadow:0 10px 30px rgba(0,0,0,.4)}.sr.f .v{color:#444}"""
    return page(t, f'<div class="st"><div>{menu}</div><div>{rows}</div></div>', nav_on=5, extra_css=css)

def s_empty(t):
    css = """.em{height:860px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center}.em h1{font-size:52px;font-weight:600;margin:28px 0 14px}.em p{font-size:24px;color:var(--t2);max-width:760px;line-height:1.7;margin-bottom:40px}
.em .mark{width:88px;height:88px;border-radius:26px}.em .mark:after{border-width:19px 0 19px 31px}[dir=rtl] .em .mark:after{border-width:19px 31px 19px 0}.bt{display:flex;gap:16px}"""
    body = f"""<div class="em"><div class="mark"></div><h1>{t['empty_t']}</h1><p>{t['empty_b']}</p><div class="bt"><span class="btn f">{t['add']}</span><span class="btn">{t['type_url']}</span></div></div>"""
    return page(t, body, nav_on=1, extra_css=css)

def s_series(t):
    eps = ''
    for i in range(6):
        p = [100,100,62,0,0,0][i]
        f = ' f' if i==2 else ''
        eps += f"""<div class="ep{f}"><div class="th">{art('ep'+str(i))}<div class="bar" style="position:absolute;inset-inline:10px;bottom:10px;{'display:none' if p==0 else ''}"><i style="width:{p}%"></i></div></div>
<div class="et"><div class="en"><span class="dim3">{i+1}</span> &nbsp;{t['series']} · {t['ep']}{i+1}</div><div class="ed">{t['desc'][:70]}…</div></div><div class="er">{('✓' if p==100 else (t['remaining'](17) if p else t['minutes'](44)))}</div></div>"""
    css = """.hero{position:absolute;inset:0 0 auto 0;height:560px;overflow:hidden}.hero .art{filter:saturate(.8)}.hv{position:absolute;inset:0;background:linear-gradient(to top,var(--bg) 4%,rgba(14,15,17,.55) 55%,rgba(14,15,17,.2)),linear-gradient(to right,rgba(14,15,17,.85),transparent 60%)}
[dir=rtl] .hv{background:linear-gradient(to top,var(--bg) 4%,rgba(14,15,17,.55) 55%,rgba(14,15,17,.2)),linear-gradient(to left,rgba(14,15,17,.85),transparent 60%)}
.sd{position:relative;margin-top:50px}.sd h1{font-size:62px;font-weight:600}.sd .mt{font-size:23px;color:var(--t2);margin:8px 0 14px}.sd p{font-size:22px;color:var(--t2);max-width:760px;line-height:1.6;margin-bottom:26px}
.seas{display:flex;gap:28px;font-size:24px;color:var(--t3);margin:48px 0 18px}.seas .on{color:var(--t1);font-weight:600;border-bottom:3px solid var(--t1);padding-bottom:6px}
.ep{display:flex;align-items:center;gap:22px;height:96px;padding:0 14px;border-radius:12px;margin-bottom:4px}.th{position:relative;width:150px;height:84px;border-radius:8px;overflow:hidden;flex:none}
.et{flex:1}.en{font-size:23px;font-weight:500}.ed{font-size:18px;color:var(--t2);margin-top:2px}.er{font-size:19px;color:var(--t2);width:150px;text-align:end}
.ep.f{background:var(--t1);color:#111;transform:scale(1.015);box-shadow:0 10px 30px rgba(0,0,0,.45)}.ep.f .ed,.ep.f .er,.ep.f .dim3{color:#555}.ep.f .bar{background:rgba(0,0,0,.25)}"""
    body = f"""<div class="hero">{art('series-hero')}<div class="hv"></div></div><div class="sd"><h1>{t['series']}</h1><div class="mt">{t['sub']}</div><p>{t['desc']}</p>
<span class="btn f">{t['resume']} {t['season']} 1 · {t['ep']}3</span> &nbsp; <span class="btn">{t['fromstart']}</span>
<div class="seas"><span class="on">{t['season']} 1</span><span>{t['season']} 2</span><span>{t['season']} 3</span></div>{eps}</div>"""
    return page(t, body, nav_on=3, extra_css=css)

def s_next(t):
    css = """.pl{position:absolute;inset:0}.scrim{position:absolute;inset:0;background:rgba(0,0,0,.35)}.nc{position:absolute;inset-inline-end:84px;bottom:84px;width:620px;background:rgba(20,22,25,.86);backdrop-filter:blur(14px);border-radius:20px;padding:22px;display:flex;gap:20px;align-items:center}
.nc .th{position:relative;width:200px;height:112px;border-radius:10px;overflow:hidden;flex:none}.nc .l{font-size:18px;color:var(--t2)}.nc .n{font-size:26px;font-weight:600;margin:4px 0 12px}.nc .btns{display:flex;gap:10px}.nc .btn{height:46px;padding:0 22px;font-size:20px;border-radius:12px}"""
    return f"""<!doctype html><html dir="{t['dir']}" lang="{t['lang']}"><meta charset="utf-8"><style>{CSS}{css}</style><body><div class="pl">{art('player-frame')}</div><div class="scrim"></div>
<div class="nc"><div class="th">{art('ep3')}</div><div><div class="l">{t['next_ep']} · {t['next_in']} 8 {t['sec']}</div><div class="n">{t['series']} · {t['sn']}1·{t['ep']}4</div><div class="btns"><span class="btn f">{t['play']}</span><span class="btn">✕</span></div></div></div></body></html>"""

SCREENS = [('01-home-continue',s_home),('02-live-preview',s_live),('03-guide',s_guide),('04-player-osd',s_player),('05-player-miniguide',lambda t:s_player(t,True)),
           ('06-series',s_series),('07-next-episode',s_next),('08-add-from-phone',s_add),('09-settings',s_settings),('10-empty',s_empty)]

if __name__=='__main__':
    os.makedirs(os.path.join(HERE,'html'),exist_ok=True); jobs=[]
    for name,fn in SCREENS:
        for lang in ('ar','en'):
            p=os.path.join(HERE,'html',f'{name}-{lang}.html'); open(p,'w').write(fn(T[lang])); jobs.append((p,os.path.join(OUT,f'{name}-{lang}.png')))
    json.dump(jobs,open(os.path.join(HERE,'html','jobs.json'),'w'))
    print(len(jobs),'pages')
