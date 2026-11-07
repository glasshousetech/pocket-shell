"""Bundle the reviewed media into one private, offline-capable HTML artifact."""
import base64,json,pathlib,sys
work=pathlib.Path(sys.argv[1]).resolve()
page=(work/'index.html').read_text(encoding='utf-8')
assets={key:{'mime':mime,'data':base64.b64encode((work/name).read_bytes()).decode('ascii')} for key,name,mime in [('video','PocketShell-demo.mp4','video/mp4'),('captions','captions.vtt','text/vtt'),('transcript','transcript.txt','text/plain;charset=utf-8')]}
page=page.replace('poster="poster.png"','poster="data:image/png;base64,'+base64.b64encode((work/'poster.png').read_bytes()).decode('ascii')+'"')
page=page.replace('<source src="PocketShell-demo.mp4" type="video/mp4">','').replace('src="captions.vtt"','id="captions-track"')
page=page.replace('href="PocketShell-demo.mp4" download','id="download-video" download="PocketShell-demo.mp4"').replace('href="captions.vtt" download','id="download-captions" download="captions.vtt"').replace('href="transcript.txt"','id="download-transcript" download="transcript.txt"')
script='''<script type="application/json" id="embedded-media">ASSETS_JSON</script><script>
const assets=JSON.parse(document.getElementById('embedded-media').textContent),urls={};
for(const [name,item]of Object.entries(assets)){const bin=atob(item.data),bytes=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)bytes[i]=bin.charCodeAt(i);urls[name]=URL.createObjectURL(new Blob([bytes],{type:item.mime}));}
document.getElementById('demo').src=urls.video;document.getElementById('captions-track').src=urls.captions;
document.getElementById('download-video').href=urls.video;document.getElementById('download-captions').href=urls.captions;document.getElementById('download-transcript').href=urls.transcript;window.reviewReady=true;
</script>'''.replace('ASSETS_JSON',json.dumps(assets))
(work/'PocketShell-review.html').write_text(page.replace('</html>',script+'</html>'),encoding='utf-8')
print(json.dumps({'review':'PocketShell-review.html','embeddedAssets':len(assets)}))
