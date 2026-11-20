"""Applies PlayerHost's rules to the real host smali (baksmali output) and reports counts.

usage: python3 verify_player_contract.py <smali-root-with-classesN-dirs> [res.txt]
"""
import re, sys
from pathlib import Path

ROOT = Path(sys.argv[1])
T = 'com/luna/biz/playing/playpage/track/'


def smali(name):
    hits = list(ROOT.glob(f'classes*/{name}.smali'))
    if len(hits) != 1:
        raise SystemExit(f'class {name}: {len(hits)} files')
    return hits[0].read_text()


def fields(text):
    return [(m.group(2), m.group(3)) for m in re.finditer(r'^\.field ([a-z ]*?)(\w+):(\S+)', text, re.M)
            if 'static' not in m.group(1).split()]


def implements(desc, iface, depth=0):
    """Whether class `desc` (L...;) implements `iface`, following superclasses inside the dex."""
    if depth > 8 or not desc.startswith('L'):
        return False
    try:
        text = smali(desc[1:-1])
    except SystemExit:
        return False
    if re.search(r'^\.implements ' + re.escape(iface) + r'$', text, re.M):
        return True
    sup = re.search(r'^\.super (\S+)', text, re.M)
    return bool(sup) and implements(sup.group(1), iface, depth + 1)


results = []
def expect(what, items, n):
    results.append((what, len(items), n, items))

base, complete, portrait = smali(T + 'BaseTrackCardViewDelegate'), smali(T + 'BaseCompleteTrackCardViewDelegate'), smali(T + 'PortraitTrackCardViewDelegate')
expect('Portrait extends BaseComplete', re.findall(r'^\.super L' + T + r'BaseCompleteTrackCardViewDelegate;', portrait, re.M), 1)
expect('BaseComplete extends BaseTrack', re.findall(r'^\.super L' + T + r'BaseTrackCardViewDelegate;', complete, re.M), 1)
expect('BaseTrack.onViewCreated(View, Bundle)', re.findall(r'^\.method public (?:final )?onViewCreated\(Landroid/view/View;Landroid/os/Bundle;\)V', base, re.M), 1)
provider = 'Lcom/luna/biz/playing/common/playpage/IPlayerControllerProvider;'
expect('BaseComplete.provider', [f for f in fields(complete) if f[1] == provider or implements(f[1], provider)], 1)
iface = smali('com/luna/biz/playing/common/playpage/IPlayerControllerProvider')
expect('IPlayerControllerProvider.getPlayerController', re.findall(r'^\.method public abstract getPlayerController\(\)Lcom/luna/common/player/queue/api/IPlayerController;', iface, re.M), 1)
meta = smali('com/luna/common/player/bach/IMetaPlayerController')
listener = 'Lcom/luna/common/player/queue/api/IPlayerListener;'
expect('add/removePlayerListener', re.findall(r'^\.method public abstract (?:add|remove)PlayerListener\(' + re.escape(listener) + r'\)V', meta, re.M), 2)
# Card layout names the skin looks up with getIdentifier (resource names survive resguard).
resources = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT.parent / 'res.txt'
resource_dump = resources.read_text(errors='replace')
for name in ['playing_track_title_artist_layout', 'playing_actions_container', 'playing_seek_container_view']:
    expect('id/' + name, re.findall(r'id/' + name + r'$', resource_dump, re.M), 1)
expect('CoverViewPager class', list(ROOT.glob('classes*/com/luna/biz/playing/community/viewpager/CoverViewPager.smali')), 1)

# Trims: delegates hooked by lifecycle name, root picked among exactly three View fields.
for cls, sig in [('com/luna/biz/playing/playpage/main/pendantview/CampaignPendantDelegate', r'onViewCreated\(Landroid/view/View;Landroid/os/Bundle;\)V'),
                 ('com/luna/biz/playing/playpage/title/main/treasure/TreasureEntranceDelegate', r'initViews\(Landroid/view/View;\)V')]:
    t = smali(cls)
    name = cls.rsplit('/', 1)[1]
    expect(name + ' lifecycle', re.findall(r'^\.method public (?:final )?' + sig, t, re.M), 1)
    expect(name + ' View fields', [f for f in fields(t) if f[1] == 'Landroid/view/View;'], 3)
# Card trims and the tab bar are found by class name.
for cls in ['com/luna/biz/playing/playpage/track/name/NameContainerView', 'com/luna/common/arch/widget/CustomMarqueeView',
            'com/luna/biz/playing/common/view/artist/ArtistsTruncatingLayout', 'com/luna/common/ui/viewpager/DotPagerIndicator',
            'com/luna/biz/main/main/bottombar/BottomNavigationView']:
    expect(cls.rsplit('/', 1)[1] + ' class', list(ROOT.glob('classes*/' + cls + '.smali')), 1)

# Short lyrics: line and time entries on the container, the sentence on the base line data.
t = smali('com/luna/biz/playing/lyric/shortlyrics/ShortLyricsContainerView')
expect('ShortLyricsContainerView.update', re.findall(r'^\.method public (?:final )?\w+\(Lcom/luna/biz/playing/lyric/shortlyrics/data/ShortLyricViewData;Lcom/luna/biz/playing/lyric/shortlyrics/data/UpdateLyricsViewData;\)V', t, re.M), 1)
expect('ShortLyricsContainerView.time', re.findall(r'^\.method public (?:final )?\w+\(J\)V', t, re.M), 1)
t = smali('com/luna/biz/playing/lyric/BaseLyricViewData')
expect('BaseLyricViewData.sentence', [f for f in fields(t) if f[1] == 'Lcom/luna/common/arch/db/entity/lyrics/Sentence;'], 1)
expect('BaseLyricViewData.time', [f for f in fields(t) if f[1] == 'J'], 1)
t = smali('com/luna/biz/playing/lyric/shortlyrics/data/ShortLyricViewData')
expect('ShortLyricViewData extends BaseLyricViewData', re.findall(r'^\.super Lcom/luna/biz/playing/lyric/BaseLyricViewData;', t, re.M), 1)

# Floating bar (TabHook): selected id, the id -> tab map, and the setBackgroundColor recolouring.
t = smali('com/luna/biz/main/main/bottombar/BottomNavigationView')
expect('BottomNavigationView.getSelectedItemId', re.findall(r'^\.method public (?:final )?getSelectedItemId\(\)Ljava/lang/Integer;', t, re.M), 1)
expect('BottomNavigationView id map', [f for f in fields(t) if f[1] == 'Ljava/util/Map;'], 1)
# 0.5.5: taps are forwarded to the tab wrappers' click listener (the bar itself), the attribution
# line is read from the bar's only String field and hidden through its only TextPaint field.
expect('BottomNavigationView.onClick(View)', re.findall(r'^\.method public onClick\(Landroid/view/View;\)V', t, re.M), 1)
expect('BottomNavigationView TextPaint field', [f for f in fields(t) if f[1] == 'Landroid/text/TextPaint;'], 1)
expect('BottomNavigationView String field', [f for f in fields(t) if f[1] == 'Ljava/lang/String;'], 1)
expect('BottomNavigationView wrapper click listener', re.findall(r'invoke-virtual \{\w+, \w+\}, Lcom/luna/common/ui/[\w/]*LunaFrameLayout;->setOnClickListener|setOnClickListener\(Landroid/view/View\$OnClickListener;\)V', t), 1)
# Tabs are named by the host's enum: nav id -> constant name.
e = smali('com/luna/biz/main/BottomTab')
expect('BottomTab is an enum', re.findall(r'^\.super Ljava/lang/Enum;', e, re.M), 1)
expect('BottomTab.getNavId()I', re.findall(r'^\.method public final getNavId\(\)I', e, re.M), 1)
for name in ['Play', 'Discovery', 'Home', 'ListenVideo', 'CommerceCoin']:
    expect('BottomTab.' + name, re.findall(r'^\.field public static final enum ' + name + r':Lcom/luna/biz/main/BottomTab;', e, re.M), 1)
# Smoothness (SmoothHook): the blur's per-frame copy lives in its pre-draw listener.
t = smali('com/github/mmin18/widget/RealtimeBlurView$1')
expect('RealtimeBlurView$1 is the pre-draw listener', re.findall(r'^\.implements Landroid/view/ViewTreeObserver\$OnPreDrawListener;', t, re.M), 1)
expect('RealtimeBlurView$1.onPreDraw', re.findall(r'^\.method public onPreDraw\(\)Z', t, re.M), 1)
expect('RealtimeBlurView$1 outer view', [f for f in fields(t) if f[1] == 'Lcom/github/mmin18/widget/RealtimeBlurView;'], 1)

bad = 0
for what, got, want, names in results:
    ok = got == want
    bad += not ok
    print(('OK  ' if ok else 'FAIL'), f'{what}: {got} (want {want}) {[n[0] if isinstance(n, tuple) else str(n)[-60:] for n in names]}')
print('contract', 'PASS' if bad == 0 else f'FAIL {bad}')
sys.exit(1 if bad else 0)
