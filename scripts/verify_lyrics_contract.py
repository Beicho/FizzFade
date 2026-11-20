"""Applies LyricsHost's shape rules to the real host smali (baksmali output) and reports counts.

usage: python3 verify_lyrics_contract.py <smali-root-with-classesN-dirs>
"""
import re, sys
from pathlib import Path

ROOT = Path(sys.argv[1])
P = 'com/luna/biz/playing/lyric/longlyrics/indicatorlyrics/'
E = 'com/luna/common/arch/db/entity/lyrics/'


def smali(name):
    hits = list(ROOT.glob(f'classes*/{name}.smali'))
    if len(hits) != 1:
        raise SystemExit(f'class {name}: {len(hits)} files')
    return hits[0].read_text()


def fields(text):
    out = []
    for m in re.finditer(r'^\.field ([a-z ]*?)(\w+):(\S+)', text, re.M):
        flags, name, typ = m.groups()
        if 'static' not in flags.split():
            out.append((name, typ))
    return out


def methods(text):
    out = []
    for m in re.finditer(r'^\.method ([a-z ]*?)([\w<>$]+)\((.*?)\)(\S+)$', text, re.M):
        flags, name, params, ret = m.groups()
        out.append((set(flags.split()), name, params, ret))
    return out


def is_enum(desc):
    if not desc.startswith('L'):
        return False
    try:
        return re.search(r'^\.class [^\n]*\benum\b', smali(desc[1:-1]), re.M) is not None
    except SystemExit:
        return False


results = []
def expect(what, items, n):
    results.append((what, len(items), n, [i[0] if isinstance(i, tuple) else i for i in items]))

upd = 'L' + P + 'lyrics/data/UpdateLyricsViewData;'
t = smali(P + 'lyrics/data/UpdateLyricsViewData')
expect('UpdateLyricsViewData.list', [f for f in fields(t) if f[1] == 'Ljava/util/List;'], 1)
expect('UpdateLyricsViewData.index', [f for f in fields(t) if f[1] == 'Ljava/lang/Integer;'], 1)

t = smali(P + 'lyrics/LyricsViewController')
expect('LyricsViewController.recycler', [f for f in fields(t) if f[1].endswith('/LongLyricsRecyclerView;') or 'RecyclerView' in f[1]], 1)
expect('LyricsViewController.update', [(m[1],) for m in methods(t) if 'public' in m[0] and 'static' not in m[0]
                                        and not m[0] & {'synthetic', 'bridge'} and m[3] == 'V' and m[2] == upd], 1)

live = 'L' + P + 'lyrics/LyricsLiveDataController;'
t = smali(P + 'lyrics/LyricsLiveDataController')
expect('LyricsLiveDataController.player', [f for f in fields(t) if f[1] == 'Lcom/luna/common/player/queue/api/IPlayerController;'], 1)
expect('LyricsLiveDataController.list', [f for f in fields(t) if f[1] == 'Ljava/util/concurrent/CopyOnWriteArrayList;'], 1)

t = smali(P + 'IndicatorAndLyricsViewModel')
expect('IndicatorAndLyricsViewModel.controller', [f for f in fields(t) if f[1] == live], 1)
expect('IndicatorAndLyricsViewModel.seek', [(m[1],) for m in methods(t) if {'public', 'final'} <= m[0] and 'static' not in m[0]
                                            and not m[0] & {'synthetic', 'bridge'} and m[3] == 'V' and m[2] == 'I'], 1)

sentence = 'L' + E + 'Sentence;'
t = smali(P + 'lyrics/data/LyricViewData')
expect('LyricViewData.sentences', [f for f in fields(t) if f[1] == sentence], 2)
expect('LyricViewData.time', [f for f in fields(t) if f[1] == 'J'], 1)

t = smali(E + 'Sentence')
expect('Sentence.type', [f for f in fields(t) if is_enum(f[1])], 1)
expect('Sentence.content', [f for f in fields(t) if f[1] == 'Ljava/lang/CharSequence;'], 1)
expect('Sentence.times', [f for f in fields(t) if f[1] == 'J'], 2)
expect('Sentence.words', [f for f in fields(t) if f[1] == 'Ljava/util/List;'], 1)
expect('Sentence.translations', [f for f in fields(t) if f[1] == 'Ljava/util/Map;'], 1)
t = smali(E + 'Word')
expect('Word.content', [f for f in fields(t) if f[1] == 'Ljava/lang/CharSequence;'], 1)
expect('Word.times', [f for f in fields(t) if f[1] == 'J'], 2)

listener = 'Lcom/luna/common/player/queue/api/IPlayerListener;'
t = smali('com/luna/common/player/bach/IMetaPlayerController')
expect('IPlayerController.add/removePlayerListener', [(m[1],) for m in methods(t) if m[1] in ('addPlayerListener', 'removePlayerListener') and m[2] == listener], 2)
t = smali('com/luna/common/player/queue/api/IPlayerController')
expect('IPlayerController extends IMetaPlayerController', re.findall(r'^\.implements Lcom/luna/common/player/bach/IMetaPlayerController;', t, re.M), 1)

bad = 0
for what, got, want, names in results:
    ok = got == want
    bad += not ok
    print(('OK  ' if ok else 'FAIL'), f'{what}: {got} (want {want}) {names}')
print('contract', 'PASS' if bad == 0 else f'FAIL {bad}')
sys.exit(1 if bad else 0)
