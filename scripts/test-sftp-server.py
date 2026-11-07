#!/usr/bin/env python3
"""Loopback-only native-test transport; actual OpenSSH SFTP server, synthetic files, no credentials."""
import os
import socketserver
import subprocess
import tempfile
import pathlib

root = pathlib.Path(tempfile.mkdtemp(prefix="pocketshell-native-files-"))
server_binary = "/usr/lib/openssh/sftp-server"
if not pathlib.Path(server_binary).is_file():
    raise SystemExit("Install openssh-sftp-server before running native file tests")

class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        # SFTP uses a duplex byte stream; the child owns no shared terminals.
        child = subprocess.Popen([server_binary, "-d", str(root)], stdin=self.request, stdout=self.request,
                                 stderr=subprocess.PIPE)
        _, stderr = child.communicate()
        if child.returncode:
            print("fixture SFTP exited", child.returncode, flush=True)

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

with Server(("127.0.0.1", 48372), Handler) as server:
    print("SFTP fixture ready", flush=True)
    server.serve_forever()
