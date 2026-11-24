#!/usr/bin/env python3
"""Rebuild only PocketShell's x86_64 PRoot in an isolated server workspace."""
import argparse, hashlib, json, os, pathlib, shutil, subprocess, tarfile, urllib.request
PROOT = 'b40accf05dfddbaf44758aa666ade79c57255005'
BUILDER = '01f83b8841358450c78333d1b33ab30d4943bec4'
TALLOC_SHA = 'b185602756a628bac507fa8af8b9df92ace69d27c0add5dab93190ad7c3367ce'
PYPY_SHA = 'aa3bb92dbb529fa2d4920895b16d67a810b0c709207857d56cfe4a6e3b41e02a'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--work', type=pathlib.Path, required=True)
p.add_argument('--ndk', type=pathlib.Path, required=True)
p.add_argument('--jobs', type=int, default=2, help='parallel compiler jobs; 2 limits shared-worker load')
a = p.parse_args()
a.work = a.work.resolve(); a.ndk = a.ndk.resolve()
if a.jobs < 1: p.error('--jobs must be positive')
assert (a.ndk/'source.properties').is_file(), 'Provide Android NDK 27.2.12479018'
assert '27.2.12479018' in (a.ndk/'source.properties').read_text(), 'Pinned NDK revision differs'
a.work.mkdir(parents=True, exist_ok=True)
def run(args, **kw): subprocess.run(args, check=True, **kw)
def clone(url, dest, commit):
    if not dest.exists():
        run(['git','clone',url,str(dest)])
        run(['git','checkout','--detach',commit],cwd=dest)
    assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=dest,text=True).strip()==commit

def archive(url, filename, sha, dest, expected):
    path=a.work/filename
    if not path.exists(): urllib.request.urlretrieve(url,path)
    assert hashlib.sha256(path.read_bytes()).hexdigest()==sha, 'Archive checksum mismatch: '+filename
    if not expected.exists():
        with tarfile.open(path) as tar: tar.extractall(dest,filter='data')

builder=a.work/'builder'; source=a.work/'proot'
clone('https://github.com/green-green-avk/build-proot-android.git',builder,BUILDER)
clone('https://github.com/green-green-avk/proot.git',source,PROOT)
patch=pathlib.Path(__file__).with_name('android-x86-syscalls.patch').read_bytes()
check=subprocess.run(['git','apply','--reverse','--check'],cwd=source,input=patch,capture_output=True)
if check.returncode:
    run(['git','apply','--check'],cwd=source,input=patch)
    run(['git','apply'],cwd=source,input=patch)
archive('https://downloads.python.org/pypy/pypy2.7-v7.3.20-linux64.tar.bz2','pypy.tar.bz2',PYPY_SHA,a.work,a.work/'pypy2.7-v7.3.20-linux64/bin/pypy')
(builder/'build').mkdir(exist_ok=True)
archive('https://download.samba.org/pub/talloc/talloc-2.1.14.tar.gz','talloc.tar.gz',TALLOC_SHA,builder/'build',builder/'build/talloc-2.1.14')
original=subprocess.check_output(['git','show',BUILDER+':config'],cwd=builder,text=True)
original=original.replace("ARCHS='aarch64 armv7a armv7a-pre5 x86_64 i686 i686-pre5'","ARCHS='x86_64'").replace('$HOME/Android/Sdk/ndk/23.2.8568313',str(a.ndk)).replace('else API=21','else API=26')
(builder/'config').write_text(original)
make_talloc=subprocess.check_output(['git','show',BUILDER+':make-talloc-static.sh'],cwd=builder,text=True)
make_talloc=make_talloc.replace('Checking for HAVE_IFACE_IFCONF: FAIL','Checking for HAVE_IFACE_IFCONF: FAIL\nChecking for HAVE_IFACE_GETIFADDRS: OK')
(builder/'make-talloc-static.sh').write_text(make_talloc)
pybin=a.work/'python-bin';pybin.mkdir(exist_ok=True)
if not (pybin/'python').exists(): (pybin/'python').symlink_to(a.work/'pypy2.7-v7.3.20-linux64/bin/pypy')
env=os.environ.copy();env['PATH']=str(pybin)+':'+env['PATH'];env['PYTHON']=str(pybin/'python')
run(['bash','make-talloc-static.sh'],cwd=builder,env=env)
# Build the standard runtime. USERLAND is intentionally absent: it changes fake-root/statx behavior.
script='''set -euo pipefail
. ./config
ARCH=x86_64
set-arch "$ARCH"
export CFLAGS="-I$STATIC_ROOT/include -Werror=implicit-function-declaration"
export LDFLAGS="-L$STATIC_ROOT/lib -Wl,-z,max-page-size=16384"
export PROOT_UNBUNDLE_LOADER=.
export PROOT_UNBUNDLE_LOADER_NAME=libproot-loader.so
export PROOT_UNBUNDLE_LOADER_NAME_32=libproot-loader32.so
cd "$1/src"
make -j"$2" V=1 proot
"$STRIP" -o "$3/libproot.so" proot
'''
out=a.work/'output';out.mkdir(exist_ok=True)
run(['bash','-c',script,'build',str(source),str(a.jobs),str(out)],cwd=builder,env=env)
receipt={'proot':PROOT,'builder':BUILDER,'ndk':'27.2.12479018','api':26,'abi':'x86_64','patchSha256':hashlib.sha256(patch).hexdigest(),'binarySha256':hashlib.sha256((out/'libproot.so').read_bytes()).hexdigest(),'acceptance':'Build only; real Android app-context and upgrade tests are separate.'}
(out/'build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt))
