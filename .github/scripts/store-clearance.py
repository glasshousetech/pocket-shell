#!/usr/bin/env python3
"""Verify a short-lived, exact-run store clearance before an automatic upload.

Standalone so public app repositories need not access the private release engine.
The certificate contains release identity only, never credentials or review files.
"""
import argparse
import base64
import datetime as dt
import glob
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile


def credential_identity(store, environment):
    """Read only public principal identifiers from the exact uploader credentials.

    Credentials stay in the CI environment and never enter the clearance/output.
    The operator separately proves this principal's store-account association.
    """
    if store=='google':
        credential=json.loads(environment.get('GHT_UPLOAD_GOOGLE_JSON',''))
        email=credential.get('client_email','')
        if credential.get('type')!='service_account' or not re.fullmatch(r'[A-Za-z0-9._-]+@[A-Za-z0-9.-]+\.gserviceaccount\.com',email):
            raise ValueError('Google uploader needs its actual service-account credential')
        return 'google:'+email
    if store=='apple':
        issuer=environment.get('GHT_UPLOAD_APPLE_ISSUER','')
        if not re.fullmatch(r'[0-9a-fA-F-]{36}',issuer):raise ValueError('Apple uploader needs its actual App Store Connect issuer')
        return 'apple:'+issuer.lower()
    if store=='microsoft':
        credential=json.loads(environment.get('GHT_UPLOAD_MICROSOFT_JSON',''))
        fields=[credential.get(key,'') for key in ('tenantId','clientId')]
        if any(not re.fullmatch(r'[0-9a-fA-F-]{36}',value) for value in fields):
            raise ValueError('Microsoft uploader needs tenantId and clientId from its actual credential')
        return 'microsoft:'+':'.join(value.lower() for value in fields)
    raise ValueError('unsupported store credential')


def check(certificate, trust_file, artifact_pattern, source_sha, store, package_id, channel, repository, run_id, run_attempt, account, release_mode):
    envelope = json.loads(certificate)
    if set(envelope) != {"payload", "signature"}:
        raise ValueError("invalid clearance envelope")
    payload = base64.b64decode(envelope["payload"], validate=True)
    signature = base64.b64decode(envelope["signature"], validate=True)
    with tempfile.TemporaryDirectory(prefix="store-clearance-") as temp:
        sig = Path(temp) / "clearance.sig"
        sig.write_bytes(signature)
        verify = subprocess.run(["ssh-keygen", "-Y", "verify", "-f", str(trust_file), "-I", "ght-store-reviewer", "-n", "ght-store-clearance", "-s", str(sig)], input=payload, capture_output=True)
        if verify.returncode:
            raise ValueError("clearance signature is invalid")
    data = json.loads(payload)
    expected = {"schema", "candidate_sha256", "artifact_sha256", "source_sha", "store", "package_id", "channel", "account", "release_mode", "repository", "run_id", "run_attempt", "issued_at", "expires_at", "batch_id", "reservation"}
    if set(data) != expected or data["schema"] != 1:
        raise ValueError("unsupported clearance format")
    values = {"source_sha":source_sha,"store":store,"repository":repository,"run_id":str(run_id),"run_attempt":str(run_attempt),"package_id":package_id,"channel":channel,"account":account,"release_mode":release_mode}
    if any(not isinstance(v,str) or not v.strip() for v in values.values()) or release_mode not in ("manual","automatic"):
        raise ValueError("actual package, account, channel and release mode are required")
    for k, value in values.items():
        if data[k] != value:
            raise ValueError("clearance differs from this upload: " + k)
    now=dt.datetime.now(dt.timezone.utc)
    issued=dt.datetime.fromisoformat(data["issued_at"].replace("Z","+00:00"))
    expires=dt.datetime.fromisoformat(data["expires_at"].replace("Z","+00:00"))
    if not issued <= now < expires or (expires-issued).total_seconds() > 900:
        raise ValueError("clearance expired or invalid; refresh console state and request a new authorized attempt")
    matches=glob.glob(artifact_pattern,recursive=True)
    if len(matches)!=1:
        raise ValueError("upload must resolve to one approved artifact")
    digest=hashlib.sha256()
    with open(matches[0],"rb") as stream:
        for block in iter(lambda:stream.read(1024*1024),b""):digest.update(block)
    if digest.hexdigest()!=data["artifact_sha256"]:
        raise ValueError("actual upload bytes differ from clearance; use the frozen approved build")
    return data


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ("trust","artifact","source-sha","store","package-id","channel","account","release-mode"):
        p.add_argument("--"+name,required=True)
    args=p.parse_args()
    try:
        certificate=os.environ.get("GHT_STORE_CLEARANCE_JSON","")
        if not certificate:
            raise ValueError("no controller-issued batch clearance; prepare evidence and obtain Connor's approval before uploading")
        actual_account=credential_identity(args.store,os.environ)
        if actual_account!=args.account:
            raise ValueError('actual uploader credential principal differs from approved account scope')
        result=check(certificate,args.trust,args.artifact,args.source_sha,args.store,args.package_id,args.channel,
                     os.environ.get("GITHUB_REPOSITORY",""),os.environ.get("GITHUB_RUN_ID",""),os.environ.get("GITHUB_RUN_ATTEMPT",""),actual_account,args.release_mode)
        print(json.dumps({"state":"AUTHORIZED","candidate_sha256":result["candidate_sha256"],"batch_id":result["batch_id"]}))
        return 0
    except (ValueError,KeyError,OSError,TypeError) as exc:
        print(json.dumps({"state":"BLOCKED","error":str(exc)}),file=sys.stderr)
        return 2


if __name__=="__main__":
    sys.exit(main())
