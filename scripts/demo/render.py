"""Render a continuous native recording with timed narration and accessible review files.
No app UI is reconstructed. Fails if narration outlasts the observed on-screen beat.
"""
import hashlib, html, json, pathlib, subprocess, sys

work = pathlib.Path(sys.argv[1]).resolve()
revision = sys.argv[2]
story = json.loads((work / "script.json").read_text())
speech = json.loads((work / "narration.json").read_text())
events = [json.loads(line) for line in (work / "screenshots/workbench-demo-timeline.jsonl").read_text().splitlines() if line]
if [event["beat"] for event in events] != [beat["id"] for beat in story["beats"]]:
    raise SystemExit("Recorded beats do not exactly match the narration storyboard.")
boot_start = float((work / "capture-start.txt").read_text().split()[0]) * 1000
raw_times = [(event["uptimeMs"] - boot_start) / 1000 for event in events]
start = max(0, raw_times[0] - 0.35)
end = raw_times[-1] + 10
duration = end - start

def probe(file):
    return json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(file)]))

raw = probe(work / "raw.mp4")
video = next(stream for stream in raw["streams"] if stream["codec_type"] == "video")
if float(raw["format"]["duration"]) < end:
    raise SystemExit("The continuous recording ended before the final demonstrated result.")
width, height = int(video["width"]), int(video["height"])
if (width, height) != (720, 1280):
    raise SystemExit("Unexpected recording size; reframe before rendering.")

def clock(seconds, ass=False):
    centis = int(round(seconds * (100 if ass else 1000)))
    base = 100 if ass else 1000
    sec, frac = divmod(centis, base)
    mins, sec = divmod(sec, 60)
    hours, mins = divmod(mins, 60)
    return f"{hours}:{mins:02}:{sec:02}.{frac:02}" if ass else f"{hours:02}:{mins:02}:{sec:02}.{frac:03}"

chapters, cues, narration_inputs, audio_filters = [], [], [], []
for index, beat in enumerate(story["beats"]):
    source_time = raw_times[index]
    chapter_time = source_time - start
    audio_time = chapter_time + 0.25
    voiced = speech["beats"][beat["id"]]
    next_time = raw_times[index + 1] - start if index + 1 < len(raw_times) else duration
    if audio_time + voiced["duration"] > next_time - 0.1:
        raise SystemExit("Narration overlaps the next state: " + beat["id"])
    chapters.append({"id": beat["id"], "title": beat["title"], "time": chapter_time, "narrationStart": audio_time,
                     "narrationEnd": audio_time + voiced["duration"], "text": beat["narration"]})
    narration_inputs += ["-i", str(work / "narration" / (beat["id"] + ".mp3"))]
    audio_filters.append(f"[{index + 1}:a]adelay={int(audio_time * 1000)}:all=1[a{index}]")
    if not voiced["cues"]:
        raise SystemExit("Missing timed captions for " + beat["id"])
    for cue in voiced["cues"]:
        cues.append({"start": audio_time + cue["start"], "end": audio_time + cue["end"], "text": cue["text"]})

vtt = ["WEBVTT", ""]
for cue in cues:
    vtt += [f"{clock(cue['start'])} --> {clock(cue['end'])}", html.escape(cue["text"]), ""]
(work / "captions.vtt").write_text("\n".join(vtt), encoding="utf-8")
ass = ["[Script Info]", "ScriptType: v4.00+", "PlayResX: 720", "PlayResY: 1488",
       "[V4+ Styles]",
       "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
       "Style: Default,DejaVu Sans,30,&H00FFFFFF,&H00FFFFFF,&H00141C2A,&H00141C2A,0,0,0,0,100,100,0,0,1,1,0,2,30,30,24,1",
       "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"]
for cue in cues:
    text = cue["text"].replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("\n", "\\N")
    ass.append(f"Dialogue: 0,{clock(cue['start'], True)},{clock(cue['end'], True)},Default,,0,0,0,,{text}")
(work / "captions.ass").write_text("\n".join(ass), encoding="utf-8")
audio_filters.append("".join(f"[a{i}]" for i in range(len(chapters))) +
                     f"amix=inputs={len(chapters)}:normalize=0,apad=whole_dur={duration},loudnorm=I=-16:TP=-1.5:LRA=11[voice]")
filters = (f"trim=start={start}:end={end},setpts=PTS-STARTPTS,pad=iw:ih+208:0:80:color=0x0c1424,"
           "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='PocketShell — save, run, find':fontcolor=white:fontsize=28:x=24:y=18,"
           f"drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='Android emulator · candidate {revision[:8]}':fontcolor=0xa9bad1:fontsize=18:x=24:y=53,"
           "ass=captions.ass")
subprocess.run(["ffmpeg", "-y", "-v", "warning", "-i", "raw.mp4", *narration_inputs, "-filter_complex", ";".join(audio_filters),
                "-vf", filters, "-map", "0:v", "-map", "[voice]", "-t", str(duration),
                "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", "PocketShell-demo.mp4"], cwd=work, check=True)
subprocess.run(["ffmpeg", "-v", "error", "-i", str(work / "PocketShell-demo.mp4"), "-f", "null", "-"], check=True)
final = probe(work / "PocketShell-demo.mp4")
if not any(stream["codec_type"] == "audio" for stream in final["streams"]):
    raise SystemExit("Narration audio track missing.")
if abs(float(final["format"]["duration"]) - duration) > 0.3:
    raise SystemExit("Final media duration differs from the reviewed timeline.")
frames = work / "review-frames"
frames.mkdir(exist_ok=True)
for chapter in chapters:
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-ss", str(chapter["time"] + 1), "-i", str(work / "PocketShell-demo.mp4"),
                    "-frames:v", "1", str(frames / (chapter["id"] + ".png"))], check=True)
subprocess.run(["ffmpeg", "-y", "-v", "error", "-ss", str(chapters[3]["time"] + 1), "-i", str(work / "PocketShell-demo.mp4"),
                "-frames:v", "1", str(work / "poster.png")], check=True)
transcript = ["PocketShell: save, run, find", f"Candidate {revision}; isolated Android emulator; controlled command fixture.",
              "Narrator: " + story["narrator"], ""]
for chapter in chapters:
    transcript += [clock(chapter["time"]) + " — " + chapter["title"], chapter["text"], ""]
(work / "transcript.txt").write_text("\n".join(transcript), encoding="utf-8")
manifest = {"revision": revision, "surface": "isolated Android API 34 emulator, 720x1280", "fixture": "harmless literal shell message",
            "narrator": story["narrator"], "voiceId": story["voice"], "duration": duration, "chapters": chapters,
            "continuousCapture": True, "speed": 1, "sourceStart": start, "sourceEnd": end,
            "preferredVoice": "Mark — Natural Conversations", "preferredVoiceAvailable": story["voice"] == "UgBBYS2sOqTuMpoF3BR0",
            "verification": {"nativeTests": "OK (2 tests)", "fullDecode": True, "player": "pending", "listening": "pending"}}
manifest["files"] = {name: hashlib.sha256((work / name).read_bytes()).hexdigest()
                     for name in ["raw.mp4", "PocketShell-demo.mp4", "captions.vtt", "transcript.txt"]}
(work / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
buttons = "".join(f'<button type="button" data-time="{c["time"]}">{i+1}. {html.escape(c["title"])}</button>' for i,c in enumerate(chapters))
transcript_html = "".join(f'<section><h3>{html.escape(c["title"])}</h3><p>{html.escape(c["text"])}</p></section>' for c in chapters)
page = """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PocketShell — narrated native demo</title><style>
:root{color-scheme:dark;font:18px/1.55 system-ui;background:#0c1424;color:#edf4ff}*{box-sizing:border-box}
body{margin:0}main{max-width:1120px;margin:auto;padding:28px}h1{font-size:clamp(26px,4vw,42px);line-height:1.15}
.lead{color:#afc3de;max-width:70ch}.layout{display:grid;grid-template-columns:minmax(0,600px) minmax(240px,1fr);gap:28px;align-items:start}
video{width:100%;max-height:82vh;background:#0c1424;border-radius:12px;display:block}button,select{font:inherit;color:inherit;background:#18283f;border:1px solid #426084;border-radius:8px;padding:12px;cursor:pointer}
button:focus-visible,select:focus-visible,a:focus-visible{outline:3px solid #6bd4ff;outline-offset:3px}nav{display:grid;gap:10px}nav button{text-align:left}
.controls{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0}a{color:#8edaff}section{border-top:1px solid #2d405a;margin-top:18px;padding-top:12px}
small{color:#afc3de}#status{min-height:1.5em}summary{cursor:pointer}p{margin:12px 0}@media(max-width:760px){main{padding:18px}.layout{grid-template-columns:1fr}video{max-height:75vh}}</style>
<main><h1>Save it. Run it. Find the result.</h1><p class="lead">A real PocketShell command workflow, recorded continuously on an isolated Android emulator. This candidate is not a claim about the version installed on your Fold.</p>
<div class="layout"><div><video id="demo" controls playsinline preload="metadata" poster="poster.png" aria-label="Narrated PocketShell command workflow"><source src="PocketShell-demo.mp4" type="video/mp4"><track src="captions.vtt" kind="captions" srclang="en" label="English captions"></video>
<div class="controls"><button id="play">Play demo</button><label>Speed <select id="speed"><option value=".75">0.75×</option><option value="1" selected>1×</option><option value="1.25">1.25×</option><option value="1.5">1.5×</option></select></label></div>
<p id="status" role="status"></p></div><aside><h2>Jump to a step</h2><nav aria-label="Demo chapters">__BUTTONS__</nav>
<p><small>Narrator: __NARRATOR__.<br>Candidate: __REVISION__.<br>Captions are included below the app image.</small></p>
<p><a href="PocketShell-demo.mp4" download>Download video</a> · <a href="captions.vtt" download>Captions</a> · <a href="transcript.txt">Transcript</a></p></aside></div>
<details><summary>Read the narrated walkthrough</summary>__TRANSCRIPT__</details></main>
<script>const v=document.getElementById('demo'),status=document.getElementById('status'),play=document.getElementById('play');
play.onclick=()=>{if(v.paused)v.play().catch(e=>status.textContent='Playback needs attention: '+e.message);else v.pause()};
v.onplay=()=>{play.textContent='Pause demo';status.textContent='Playing'};v.onpause=()=>{play.textContent='Play demo';status.textContent=v.ended?'Finished':'Paused'};
v.onended=()=>status.textContent='Finished';v.onerror=()=>status.textContent='The video could not load. Open the review folder with its media files.';
document.getElementById('speed').onchange=e=>v.playbackRate=Number(e.target.value);
document.querySelectorAll('[data-time]').forEach(b=>b.onclick=()=>{v.currentTime=Number(b.dataset.time);v.play().catch(e=>status.textContent=e.message)});
</script></html>"""
page = page.replace("__BUTTONS__", buttons).replace("__NARRATOR__", html.escape(story["narrator"])).replace("__REVISION__", revision[:8]).replace("__TRANSCRIPT__", transcript_html)
(work / "index.html").write_text(page, encoding="utf-8")
print(json.dumps({"duration": duration, "chapters": len(chapters), "revision": revision, "fullDecode": True, "playerReview": "pending", "listeningReview": "pending"}))
