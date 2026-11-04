#!/usr/bin/env python3
"""Build and publish a signed GouXiang Android release. Never prints credentials."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path('/home/neethu/projects/GouXiang')
REPO = 'hufeiya/GouXiang'
EXPECTED_CERT = 'fc01bf770afe15cd449d2c4891509c64b4535e1b58974771adf58bf7d1c51beb'
SIGN_DIR = Path.home() / '.config/gouxiang/release-signing'
KEYSTORE = SIGN_DIR / 'gouxiang-release.jks'
PASSFILE = SIGN_DIR / 'keystore-password.txt'
SERVICE_KEYS = ('VOLCANO_ARK_API_KEY', 'SILICONFLOW_API_KEY', 'VOLCANO_TTS_API_KEY',
                'OPENROUTER_API_KEY', 'DEEPSEEK_OFFICIAL_API_KEY', 'ALIYUNBAILIAN_API_KEY', 'GROK_API_KEY')
ENV = dict(os.environ, GIT_TERMINAL_PROMPT='0')

class Stop(Exception):
    pass

class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        old, new = urllib.parse.urlsplit(req.full_url), urllib.parse.urlsplit(newurl)
        if new.scheme.lower() != 'https' or not new.hostname:
            raise Stop('GitHub 重定向目标不是有效的 HTTPS 地址。')
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None and (old.scheme.lower(), old.hostname, old.port or 443) != (new.scheme.lower(), new.hostname, new.port or 443):
            redirected.remove_header('Authorization')
        return redirected

def run(args, *, capture=False):
    proc = subprocess.run([str(a) for a in args], cwd=ROOT, env=ENV,
                          stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.STDOUT if capture else None, text=True)
    if proc.returncode:
        raise Stop(f'命令失败（退出码 {proc.returncode}）：{args[0]}；请检查上方输出。')
    return proc.stdout.strip() if capture else ''

def properties(path):
    result = {}
    if path.exists():
        for line in path.read_text(encoding='utf-8').splitlines():
            match = re.match(r'^\s*([A-Za-z0-9_]+)\s*[=:]\s*(.*?)\s*$', line)
            if match:
                result[match.group(1)] = match.group(2)
    return result

def github(method, path, token, payload=None, accept='application/vnd.github+json', binary=False):
    url = path if path.startswith('https://uploads.github.com/') else 'https://api.github.com' + path
    headers = {'Authorization': 'Bearer ' + token, 'Accept': accept,
               'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'GouXiang-release'}
    if payload is not None and not binary:
        payload = json.dumps(payload, ensure_ascii=False).encode()
        headers['Content-Type'] = 'application/json'
    elif binary:
        headers['Content-Type'] = 'application/vnd.android.package-archive'
    req = urllib.request.Request(url, data=payload, headers=headers, method=method)
    try:
        with urllib.request.build_opener(SafeRedirect()).open(req, timeout=120) as response:
            return response.read(), response.headers.get_content_type()
    except urllib.error.HTTPError as exc:
        raise Stop(f'GitHub API 返回 HTTP {exc.code}（{method} {urllib.parse.urlsplit(url).path}）。') from None
    except (urllib.error.URLError, TimeoutError) as exc:
        raise Stop(f'GitHub API 连接失败（{type(exc).__name__}）。') from None

def json_api(method, path, token, payload=None):
    data, _ = github(method, path, token, payload)
    try:
        return json.loads(data)
    except (ValueError, UnicodeDecodeError):
        raise Stop('GitHub API 响应不是有效 JSON。') from None

def optional_api(path, token):
    try:
        return json_api('GET', path, token)
    except Stop as exc:
        if 'HTTP 404（' in str(exc):
            return None
        raise

def versions(source, requested):
    codes = re.findall(r'(?m)^(\s*versionCode\s*=\s*)(\d+)(\s*)$', source)
    names = re.findall(r'(?m)^(\s*versionName\s*=\s*")(\d+(?:\.\d+)+)("\s*)$', source)
    if len(codes) != 1 or len(names) != 1:
        raise Stop('无法唯一定位 versionCode/versionName。')
    old = names[0][1]
    if requested is None:
        parts = old.split('.')
        parts[-1] = str(int(parts[-1]) + 1)
        requested = '.'.join(parts)
    if not re.fullmatch(r'\d+(?:\.\d+)+', requested):
        raise Stop('版本号须为数字点分形式，例如 1.1。')
    if tuple(map(int, requested.split('.'))) <= tuple(map(int, old.split('.'))):
        raise Stop(f'目标版本必须高于当前版本 {old}。')
    code = int(codes[0][1]) + 1
    if code > 2100000000:
        raise Stop('versionCode 已超出 Android 支持范围。')
    result = re.sub(r'(?m)^(\s*versionCode\s*=\s*)\d+(\s*)$', lambda m: m[1]+str(code)+m[2], source)
    result = re.sub(r'(?m)^(\s*versionName\s*=\s*")\d+(?:\.\d+)+("\s*)$', lambda m: m[1]+requested+m[2], result)
    return requested, code, result

def build_tools():
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or (Path.home() / 'Android/Sdk'))
    dirs = sorted((sdk / 'build-tools').glob('*'), key=lambda x: tuple(int(p) if p.isdigit() else 0 for p in x.name.split('.')), reverse=True)
    for d in dirs:
        if all((d/name).is_file() for name in ('apksigner', 'zipalign', 'aapt')):
            return d/'apksigner', d/'zipalign', d/'aapt'
    raise Stop('找不到 Android SDK build-tools 中的 apksigner、zipalign 和 aapt。')

def setup_git_auth(token):
    directory = tempfile.TemporaryDirectory(prefix='gouxiang-git-auth-')
    helper = Path(directory.name)/'askpass'
    helper.write_text('#!/bin/sh\ncase "$1" in *sername*) printf "%s\\n" x-access-token;; *) printf "%s\\n" "$GOUXIANG_GIT_TOKEN";; esac\n')
    helper.chmod(0o700)
    ENV.update(GIT_ASKPASS=str(helper), GOUXIANG_GIT_TOKEN=token,
               GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='credential.helper', GIT_CONFIG_VALUE_0='')
    return directory

def verify_metadata(aapt, apk, version, code):
    out = run([aapt, 'dump', 'badging', apk], capture=True)
    package = re.search(r"(?m)^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", out)
    sdk = re.search(r"(?m)^sdkVersion:'([^']+)'", out)
    if not package or package.groups() != ('com.neethu.gouxiang', str(code), version) or not sdk or sdk.group(1) != '29':
        raise Stop('APK 包名、版本或最低 Android SDK 与预期不一致。')

def verify_signature(apksigner, apk):
    out = run([apksigner, 'verify', '--verbose', '--print-certs', apk], capture=True)
    certs = re.findall(r'(?im)^.*signer(?: #\d+)?: certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$', out)
    if not certs or any(c.replace(':', '').lower() != EXPECTED_CERT for c in certs):
        raise Stop('APK 签名证书与 v1.0 不一致，已停止。')

def check_key_store():
    if not KEYSTORE.is_file() or not PASSFILE.is_file():
        raise Stop('找不到现有签名密钥或密码文件；不得生成新密钥。')
    out = run(['keytool', '-list', '-v', '-alias', 'gouxiang-release', '-keystore', KEYSTORE,
               '-storepass:file', PASSFILE], capture=True)
    fingerprints = re.findall(r'(?im)^\s*SHA256:\s*([0-9a-f:]+)\s*$', out)
    if len(fingerprints) != 1 or fingerprints[0].replace(':', '').lower() != EXPECTED_CERT:
        raise Stop('现有密钥证书与 v1.0 不一致，已停止。')

def check_release_secrets(apk, secrets):
    generated = ROOT/'app/build/generated/source/buildConfig/release/com/neethu/gouxiang/BuildConfig.java'
    if not generated.is_file():
        raise Stop('未找到 release BuildConfig，无法验证密钥为空。')
    text = generated.read_text(encoding='utf-8')
    fields = set(re.findall(r'\bString\s+([A-Z][A-Z0-9_]*API_KEY)\s*=', text))
    if not set(SERVICE_KEYS).issubset(fields):
        raise Stop('release BuildConfig 缺少预期的服务密钥字段。')
    for key in fields:
        if not re.search(r'\bString\s+'+re.escape(key)+r'\s*=\s*""\s*;', text):
            raise Stop(f'release BuildConfig 中 {key} 不为空或不存在。')
    needles = [(key, value.encode('utf-8')) for key, value in secrets.items() if len(value.encode('utf-8')) >= 8]
    with zipfile.ZipFile(apk) as z:
        for entry in z.infolist():
            if entry.is_dir():
                continue
            data = z.read(entry)
            for key, value in needles:
                if value in data or value.decode('utf-8').encode('utf-16le') in data:
                    raise Stop(f'APK 内检测到本机 {key} 的值，已停止。')

def main():
    parser = argparse.ArgumentParser(description='构建、签名并发布 GouXiang APK')
    parser.add_argument('version', nargs='?', help='目标版本号，省略则末位加 1')
    parser.add_argument('--dry-run', action='store_true', help='只做前置检查和展示计划，不修改源码或远端')
    args = parser.parse_args()
    if run(['git', 'rev-parse', '--show-toplevel'], capture=True) != str(ROOT):
        raise Stop('仓库路径错误。')
    if run(['git', 'status', '--porcelain', '--untracked-files=all'], capture=True):
        raise Stop('工作区或暂存区不干净；请先处理已有改动。')
    build_file = ROOT/'app/build.gradle.kts'
    source = build_file.read_text(encoding='utf-8')
    version, code, updated = versions(source, args.version)
    tag = 'v' + version
    note_path = os.environ.get('NOTES_FILE', '')
    if not args.dry_run and (not note_path or not Path(note_path).is_file()):
        raise Stop('NOTES_FILE 必须指向非空发布说明文件。')
    if note_path and not Path(note_path).is_file():
        raise Stop('NOTES_FILE 指向的文件不存在。')
    notes = Path(note_path).read_text(encoding='utf-8') if note_path else ''
    if not args.dry_run and not notes.strip():
        raise Stop('发布说明不能为空。')
    secrets = properties(ROOT/'secrets.properties')
    token = secrets.get('GITHUB_TOKEN', '')
    if not token:
        raise Stop('secrets.properties 中缺少 GITHUB_TOKEN。')
    if any(value and len(value) >= 8 and value in notes for value in secrets.values()):
        raise Stop('发布说明包含本机密钥，已停止。')
    askpass_dir = setup_git_auth(token)  # 生命周期覆盖所有 git 操作；仅临时文件，无 token 内容
    origin = run(['git', 'remote', 'get-url', 'origin'], capture=True).removesuffix('.git')
    if origin not in ('https://github.com/'+REPO, 'git@github.com:'+REPO, 'ssh://git@github.com/'+REPO):
        raise Stop('origin 不是预期的 hufeiya/GouXiang。')
    push_origins = run(['git', 'remote', 'get-url', '--push', '--all', 'origin'], capture=True).splitlines()
    if len(push_origins) != 1 or push_origins[0].removesuffix('.git') != origin:
        raise Stop('origin 的 push URL 不唯一或与 fetch URL 不一致。')
    repo = json_api('GET', '/repos/'+REPO, token)
    if repo.get('full_name', '').lower() != REPO.lower() or not repo.get('permissions', {}).get('push'):
        raise Stop('GitHub token 无法确认目标仓库的写权限。')
    branch = repo['default_branch']
    head = run(['git', 'rev-parse', 'HEAD'], capture=True)
    remote = run(['git', 'ls-remote', '--heads', 'origin', 'refs/heads/'+branch], capture=True)
    fields = remote.split()
    if len(fields) != 2 or fields[1] != 'refs/heads/'+branch or not re.fullmatch(r'[0-9a-f]{40,64}', fields[0]):
        raise Stop('无法唯一确定远端默认分支提交。')
    ancestor = subprocess.run(['git', 'merge-base', '--is-ancestor', fields[0], head], cwd=ROOT, env=ENV,
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if ancestor.returncode != 0:
        raise Stop('远端提交在本地不可用，或远端领先/已分叉；请先 fetch 并检查同步状态。')
    if run(['git', 'tag', '--list', tag], capture=True) or run(['git', 'ls-remote', '--tags', 'origin', 'refs/tags/'+tag], capture=True):
        raise Stop(f'标签 {tag} 已存在；不会覆盖。请按恢复提示检查。')
    if optional_api('/repos/'+REPO+'/releases/tags/'+urllib.parse.quote(tag, safe=''), token) is not None:
        raise Stop(f'Release {tag} 已存在；不会覆盖。请按恢复提示检查。')
    apksigner, zipalign, aapt = build_tools()
    check_key_store()
    print(f'计划：{tag}（versionCode {code}）→ {REPO}:{branch}，草稿校验后发布。', flush=True)
    if args.dry_run:
        print('演练完成：未修改源码、构建产物、Git 或远端。')
        return
    build_file.write_text(updated, encoding='utf-8')
    run(['./gradlew', ':app:clean', ':app:testDebugUnitTest', ':app:assembleRelease'])
    unsigned = ROOT/'app/build/outputs/apk/release/app-release-unsigned.apk'
    if not unsigned.is_file():
        raise Stop('未生成 unsigned release APK。')
    output = unsigned.parent / f'GouXiang-{tag}-release.apk'
    with tempfile.TemporaryDirectory(prefix='gouxiang-release-') as tmp:
        aligned = Path(tmp)/'aligned.apk'
        run([zipalign, '-f', '-P', '16', '4', unsigned, aligned])
        run([apksigner, 'sign', '--ks', KEYSTORE, '--ks-key-alias', 'gouxiang-release',
             '--ks-pass', 'file:'+str(PASSFILE), '--out', output, aligned])
    verify_signature(apksigner, output)
    run([zipalign, '-c', '-P', '16', '4', output])
    verify_metadata(aapt, output, version, code)
    check_release_secrets(output, secrets)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    print(f'APK SHA-256: {digest}', flush=True)
    changed = run(['git', 'status', '--porcelain', '--untracked-files=all'], capture=True).splitlines()
    if changed != ['M app/build.gradle.kts']:
        raise Stop('构建期间工作区出现非预期更改，已停止提交。')
    if build_file.read_text(encoding='utf-8') != updated:
        raise Stop('构建期间版本文件被修改，已停止提交。')
    run(['git', 'add', '--', str(build_file.relative_to(ROOT))])
    run(['git', 'commit', '-m', f'chore: 发布 {tag}'])
    commit = run(['git', 'rev-parse', 'HEAD'], capture=True)
    run(['git', 'tag', '-a', tag, '-m', f'GouXiang {tag}', commit])
    run(['git', 'push', '--atomic', 'origin', f'{commit}:refs/heads/{branch}', f'refs/tags/{tag}:refs/tags/{tag}'])
    release = json_api('POST', '/repos/'+REPO+'/releases', token,
                       {'tag_name': tag, 'target_commitish': commit, 'name': '狗箱 GouXiang '+tag, 'body': notes, 'draft': True, 'prerelease': False})
    release_id = release['id']
    upload_url = release['upload_url'].split('{', 1)[0] + '?name=' + urllib.parse.quote(output.name)
    uploaded, _ = github('POST', upload_url, token, output.read_bytes(), binary=True)
    asset = json.loads(uploaded)
    if asset.get('state') != 'uploaded' or asset.get('name') != output.name or asset.get('size') != output.stat().st_size:
        raise Stop('附件上传状态、名称或大小校验失败；草稿尚未公开。')
    asset_data, mime = github('GET', f'/repos/{REPO}/releases/assets/{asset["id"]}', token, accept='application/octet-stream')
    if mime == 'application/json' or hashlib.sha256(asset_data).hexdigest() != digest:
        raise Stop('下载的附件 SHA-256 与本地不一致；草稿尚未公开。')
    published = json_api('PATCH', f'/repos/{REPO}/releases/{release_id}', token, {'draft': False})
    if published.get('draft') is not False:
        raise Stop('无法确认 Release 已公开；请检查 GitHub。')
    print(f'发布成功：{published["html_url"]}\nAPK：{output}\nSHA-256：{digest}')

if __name__ == '__main__':
    try:
        main()
    except (Stop, OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        print(f'发布已停止：{exc}', file=sys.stderr)
        print('恢复：先检查 git status、HEAD、远端标签及 GitHub 草稿。构建前失败可修复后重试；'
              '若已产生提交、标签或草稿，不要直接重跑或删除/覆盖：核对版本、签名及 APK 哈希后手动完成推送或在草稿补传并核对附件，再公开。', file=sys.stderr)
        sys.exit(1)
