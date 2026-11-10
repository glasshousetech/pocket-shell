"""Generate narration audio per beat with edge-tts (neural voice), with word-timed captions.

Usage:  python narrate.py <workdir>
Reads:  <workdir>/script.json   {"voice": "...", "rate": "-8%", "beats": [{"id","title","narration"}]}
Writes: <workdir>/narration/<id>.mp3, <id>.vtt, <workdir>/narration.json (durations, cues)
Re-runs only regenerate beats whose text changed (hash cached in narration.json).
"""
import hashlib, json, os, re, subprocess, sys

work = sys.argv[1]
script = json.load(open(os.path.join(work, 'script.json'), encoding='utf-8'))
voice = script.get('voice', 'en-US-AndrewMultilingualNeural')
rate = script.get('rate', '-8%')
outdir = os.path.join(work, 'narration'); os.makedirs(outdir, exist_ok=True)
prev_path = os.path.join(work, 'narration.json')
prev = json.load(open(prev_path, encoding='utf-8')) if os.path.exists(prev_path) else {'beats': {}}

def duration(path):
    r = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', path], capture_output=True, text=True)
    return float(r.stdout.strip())

def parse_vtt(path):
    cues, txt = [], open(path, encoding='utf-8').read()
    for m in re.finditer(r'(\d+):(\d+):(\d+)[.,](\d+) --> (\d+):(\d+):(\d+)[.,](\d+)\n(.+?)(?:\n\n|\Z)', txt, re.S):
        g = list(map(int, m.groups()[:8]))
        a = g[0]*3600 + g[1]*60 + g[2] + g[3]/1000; b = g[4]*3600 + g[5]*60 + g[6] + g[7]/1000
        cues.append({'start': a, 'end': b, 'text': ' '.join(m.group(9).split())})
    return cues

out = {'voice': voice, 'rate': rate, 'beats': {}}
words_total = 0
for b in script['beats']:
    bid, text = b['id'], b['narration'].strip()
    words_total += len(text.split())
    key = hashlib.sha1(f'{voice}|{rate}|{text}'.encode()).hexdigest()[:12]
    mp3 = os.path.join(outdir, f'{bid}.mp3'); vtt = os.path.join(outdir, f'{bid}.vtt')
    cached = prev['beats'].get(bid)
    if not (cached and cached.get('hash') == key and os.path.exists(mp3) and os.path.exists(vtt)):
        cmd = [sys.executable, '-m', 'edge_tts', '--voice', voice, f'--rate={rate}', '--text', text,
               '--write-media', mp3, '--write-subtitles', vtt]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0 or not os.path.exists(mp3):
            sys.exit(f'edge-tts failed for {bid}: {r.stderr[-400:]}')
    d = duration(mp3)
    out['beats'][bid] = {'title': b.get('title', bid), 'text': text, 'hash': key, 'duration': d,
                         'words': len(text.split()), 'wpm': round(len(text.split()) / d * 60) if d else 0,
                         'cues': parse_vtt(vtt), 'file': mp3}
    print(f'{bid:14s} {d:5.1f}s  {out["beats"][bid]["wpm"]} wpm  {b.get("title","")}')
total = sum(x['duration'] for x in out['beats'].values())
out['totalNarration'] = total
json.dump(out, open(prev_path, 'w', encoding='utf-8'), indent=2)
print(f'narration total {total:.1f}s, {words_total} words, avg {round(words_total/total*60) if total else 0} wpm')
