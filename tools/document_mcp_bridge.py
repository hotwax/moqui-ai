#!/usr/bin/env python3
"""Restricted stdio MCP client bridge to the isolated loopback document server.

Credentials and attachment paths come only from trusted CLI inputs. Neither is exposed
in tool schemas/results/logs. The actor cannot name a URL, filesystem path, service,
entity, or base64 upload chunk. This script uses the Python standard library only.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import shlex
import sys
from pathlib import Path
from urllib import error, request
from urllib.parse import urlsplit

ENDPOINT = 'http://127.0.0.1:8093/mcp/json'
PROTOCOL = '2026-07-28'
MAX_JSON = 32 * 1024 * 1024
CHUNK_BYTES = 64 * 1024
ATTACHMENT_TOOL = 'forward_document_attachment'
ACTOR_TOOLS = frozenset({
    'list_document_templates', 'get_document_template', 'save_document_revision',
    'create_document_preview', 'get_document_preview', 'list_document_template_history',
    'activate_document_revision', 'rollback_document_template', 'inspect_document_design',
    'find_document_examples', 'configure_document_data', 'list_document_fonts',
    'register_document_font', 'store_document_asset', 'list_document_assets',
})
UPLOAD_TOOLS = frozenset({'begin_document_upload', 'append_document_upload', 'finish_document_upload'})
URI = re.compile(r'hotwax-document://preview/[A-Za-z0-9_-]{1,128}/(?:pdf|page/(?:[1-9]|1[0-9]|20))\Z')
IDENTIFIER = re.compile(r'[A-Za-z0-9_-]{1,80}\Z')


class BridgeFailure(Exception):
    """Only fixed, public messages/codes belong in client-visible failures."""


class RpcFailure(Exception):
    def __init__(self, rpc_error):
        self.rpc_error = rpc_error


class UploadFailure(Exception):
    def __init__(self, code):
        self.code = code


def load_login(path):
    """Parse dotenv data; never execute, expand, source, print or export it."""
    values = {}
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:]
        key, sep, raw = line.partition('=')
        if sep and key.strip() in {'OMS_TEST_URL', 'OMS_TEST_USER', 'OMS_TEST_PASSWORD'}:
            parts = shlex.split(raw, comments=True, posix=True)
            if len(parts) != 1:
                raise ValueError('Invalid local login configuration')
            values[key.strip()] = parts[0]
    url = urlsplit(values.get('OMS_TEST_URL', ''))
    if url.scheme != 'http' or url.hostname not in {'127.0.0.1', 'localhost', '::1'} or url.username or url.password:
        raise ValueError('Local loopback login configuration is required')
    if not values.get('OMS_TEST_USER') or not values.get('OMS_TEST_PASSWORD'):
        raise ValueError('Local login configuration is incomplete')
    return values['OMS_TEST_USER'], values['OMS_TEST_PASSWORD']


def load_attachments(path):
    source = Path(path)
    if source.stat().st_size > 64 * 1024:
        raise ValueError('Attachment catalog exceeds limit')
    data = json.loads(source.read_text())
    entries = data.get('attachments') if isinstance(data, dict) else None
    if not isinstance(entries, list) or len(entries) > 16:
        raise ValueError('Invalid trusted attachment catalog')
    catalog = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError('Invalid attachment descriptor')
        ident = entry.get('id')
        kind = entry.get('kind')
        mime = entry.get('mimeType')
        display = entry.get('displayName')
        local = entry.get('localPath')
        filename = entry.get('filename')
        if not isinstance(ident, str) or not IDENTIFIER.fullmatch(ident) or ident in catalog:
            raise ValueError('Invalid or duplicate attachment ID')
        if kind not in {'font', 'image'} or mime not in ({'font/ttf'} if kind == 'font' else {'image/png', 'image/jpeg'}):
            raise ValueError('Unsupported attachment kind or media')
        if not isinstance(display, str) or not 1 <= len(display) <= 120 or any(ord(c) < 32 for c in display):
            raise ValueError('Invalid attachment description')
        if not isinstance(local, str) or not Path(local).is_absolute():
            raise ValueError('Trusted attachment paths must be absolute')
        if not isinstance(filename, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,119}', filename):
            raise ValueError('Invalid upload filename')
        catalog[ident] = dict(entry)
    return catalog


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise BridgeFailure('Upstream redirects are disabled')


class NativeHttpClient:
    def __init__(self, username, password, opener=None):
        self._authorization = 'Basic ' + base64.b64encode((username + ':' + password).encode()).decode('ascii')
        self._opener = opener or request.build_opener(request.ProxyHandler({}), NoRedirect())
        self._request_id = 0

    def request(self, method, params):
        self._request_id += 1
        native_params = dict(params)
        native_params['_meta'] = {
            'io.modelcontextprotocol/protocolVersion': PROTOCOL,
            'io.modelcontextprotocol/clientCapabilities': {},
        }
        body = json.dumps({'jsonrpc': '2.0', 'id': self._request_id, 'method': method, 'params': native_params}).encode()
        headers = {'Content-Type': 'application/json', 'Accept': 'application/json',
                   'MCP-Protocol-Version': PROTOCOL, 'Mcp-Method': method, 'Authorization': self._authorization}
        if method in {'tools/call', 'resources/read'}:
            headers['Mcp-Name'] = params['name' if method == 'tools/call' else 'uri']
        req = request.Request(ENDPOINT, body, headers, method='POST')
        try:
            response = self._opener.open(req, timeout=180)
        except error.HTTPError as failure:
            response = failure
        except Exception:
            raise BridgeFailure('The local document MCP server is unavailable') from None
        try:
            with response:
                raw = response.read(MAX_JSON + 1)
            if len(raw) > MAX_JSON:
                raise ValueError()
            out = json.loads(raw)
            if not isinstance(out, dict) or out.get('jsonrpc') != '2.0' or out.get('id') != self._request_id:
                raise ValueError()
            if isinstance(out.get('error'), dict):
                raise RpcFailure(out['error'])
            if not isinstance(out.get('result'), dict):
                raise ValueError()
            return out['result']
        except RpcFailure:
            raise
        except Exception:
            raise BridgeFailure('The local document MCP server returned an invalid response') from None


def tool_result(body):
    return {'content': [{'type': 'text', 'text': json.dumps(body)}],
            'structuredContent': body, 'isError': not body.get('ok', False)}


class DocumentBridge:
    def __init__(self, native, attachments, audit_sink=None):
        self.native = native
        self.attachments = attachments
        self.audit_sink = audit_sink or (lambda event: None)
        self.forwarded = {}

    def _native(self, method, params):
        out = self.native.request(method, params)
        event = {'method': method, 'success': not out.get('isError', False)}
        if method == 'tools/call':
            event['tool'] = params['name']
            if params['name'] == 'append_document_upload':
                event['bytes'] = len(base64.b64decode(params['arguments']['dataBase64']))
        self.audit_sink(event)
        return out

    def _upload_call(self, name, arguments):
        out = self._native('tools/call', {'name': name, 'arguments': arguments})
        body = out.get('structuredContent')
        if not isinstance(body, dict) or not isinstance(body.get('ok'), bool):
            raise BridgeFailure('The upload service returned an invalid response')
        if not body['ok']:
            code = body.get('code', '')
            safe_code = code if isinstance(code, str) and re.fullmatch(r'[A-Z][A-Z0-9_]{0,63}', code) else 'ATTACHMENT_UPLOAD_FAILED'
            raise UploadFailure(safe_code)
        return body

    def _forward(self, ident):
        if ident in self.forwarded:
            return self.forwarded[ident]
        item = self.attachments[ident]
        maximum = 8 * 1024 * 1024 if item['kind'] == 'font' else 5 * 1024 * 1024
        try:
            with Path(item['localPath']).open('rb') as source:
                data = source.read(maximum + 1)
        except Exception:
            raise BridgeFailure('The supplied attachment is unavailable') from None
        if not data or len(data) > maximum:
            raise BridgeFailure('The supplied attachment exceeds its byte limit')
        digest = hashlib.sha256(data).hexdigest()
        request_key = 'attachment-' + hashlib.sha256((ident + ':' + digest).encode()).hexdigest()
        begun = self._upload_call('begin_document_upload', {
            'kind': item['kind'], 'filename': item['filename'], 'mimeType': item['mimeType'],
            'totalBytes': len(data), 'sha256': digest, 'requestKey': request_key,
        })
        upload_id = begun.get('uploadId')
        if not isinstance(upload_id, str) or not re.fullmatch(r'U[a-f0-9]{64}', upload_id):
            raise BridgeFailure('The upload service returned an invalid handle')
        chunk_limit = begun.get('chunkLimit')
        if not isinstance(chunk_limit, int) or not 1 <= chunk_limit <= CHUNK_BYTES:
            raise BridgeFailure('The upload service returned an invalid chunk limit')
        # Replaying chunks from zero is intentional: the native service accepts identical
        # immutable retries after a lost response, including a reconnected client process.
        if begun.get('status') != 'finalized':
            for offset in range(0, len(data), chunk_limit):
                chunk = data[offset:offset + chunk_limit]
                self._upload_call('append_document_upload', {'uploadId': upload_id, 'offset': offset,
                                                           'dataBase64': base64.b64encode(chunk).decode('ascii')})
        finished = self._upload_call('finish_document_upload', {'uploadId': upload_id})
        if finished.get('finalizedUploadId') != upload_id:
            raise BridgeFailure('The upload service did not finalize the attachment')
        safe = {'ok': True, 'attachmentId': ident, 'finalizedUploadId': upload_id,
                'totalBytes': len(data), 'sha256': digest, 'kind': item['kind'], 'mimeType': item['mimeType']}
        self.forwarded[ident] = tool_result(safe)
        return self.forwarded[ident]

    def _helper(self):
        visible = [{'id': k, 'description': v['displayName'], 'kind': v['kind'], 'mimeType': v['mimeType']}
                   for k, v in self.attachments.items()]
        return {'name': ATTACHMENT_TOOL, 'title': 'Forward Supplied Document Attachment',
                'description': 'Transfer one supplied attachment through native document MCP upload tools. '
                               'Use the returned finalizedUploadId to register a font or asset. '
                               'No path, URL, or base64 input is accepted. Available attachments: ' + json.dumps(visible),
                'inputSchema': {'type': 'object', 'properties': {'attachmentId': {'type': 'string', 'enum': list(self.attachments)}},
                                'required': ['attachmentId'], 'additionalProperties': False},
                'annotations': {'readOnlyHint': False, 'destructiveHint': False, 'openWorldHint': False}}

    def handle(self, message):
        if not isinstance(message, dict):
            return {'jsonrpc': '2.0', 'id': None, 'error': {'code': -32600, 'message': 'Invalid Request'}}
        ident = message.get('id')
        if ident is None:
            return None
        def failure(code, text):
            return {'jsonrpc': '2.0', 'id': ident, 'error': {'code': code, 'message': text}}
        if message.get('jsonrpc') != '2.0' or not isinstance(message.get('method'), str):
            return failure(-32600, 'Invalid Request')
        method, params = message['method'], message.get('params', {})
        if not isinstance(params, dict):
            return failure(-32602, 'Invalid params')
        try:
            if method == 'ping':
                result = {}
            elif method == 'initialize':
                result = self._native(method, params)
            elif method == 'tools/list':
                upstream = self._native(method, {k: v for k, v in params.items() if k == 'cursor'})
                result = {'tools': [t for t in upstream.get('tools', []) if t.get('name') in ACTOR_TOOLS] + [self._helper()],
                          'ttlMs': 0, 'cacheScope': 'private'}
                if upstream.get('nextCursor'):
                    result['nextCursor'] = upstream['nextCursor']
            elif method == 'tools/call':
                name, args = params.get('name'), params.get('arguments', {})
                if not isinstance(args, dict):
                    return failure(-32602, 'Invalid tool arguments')
                if name == ATTACHMENT_TOOL:
                    if set(args) != {'attachmentId'} or args.get('attachmentId') not in self.attachments:
                        return failure(-32602, 'Choose one supplied attachment ID')
                    try:
                        result = self._forward(args['attachmentId'])
                    except UploadFailure as denied:
                        result = tool_result({'ok': False, 'code': denied.code,
                                              'message': 'Attachment upload could not be completed.'})
                    except BridgeFailure:
                        result = tool_result({'ok': False, 'code': 'ATTACHMENT_FORWARD_FAILED',
                                              'message': 'The supplied attachment could not be transferred.'})
                elif name in ACTOR_TOOLS:
                    result = self._native(method, {'name': name, 'arguments': args})
                else:
                    return failure(-32602, 'Unknown document tool')
            elif method in {'resources/read', 'resources/list', 'resources/templates/list'}:
                if method == 'resources/read':
                    if not isinstance(params.get('uri'), str) or not URI.fullmatch(params['uri']):
                        return failure(-32602, 'Invalid document resource URI')
                    selected = {'uri': params['uri']}
                else:
                    selected = {k: v for k, v in params.items() if k == 'cursor'}
                result = self._native(method, selected)
            else:
                return failure(-32601, 'Method not found')
            return {'jsonrpc': '2.0', 'id': ident, 'result': result}
        except RpcFailure as upstream:
            return {'jsonrpc': '2.0', 'id': ident, 'error': upstream.rpc_error}
        except Exception:
            return failure(-32603, 'The local document MCP request failed')


def serve(bridge, source, output):
    while True:
        line = source.readline(MAX_JSON + 1)
        if not line:
            return
        try:
            if len(line) > MAX_JSON:
                raise ValueError()
            response = bridge.handle(json.loads(line))
        except Exception:
            response = {'jsonrpc': '2.0', 'id': None, 'error': {'code': -32700, 'message': 'Parse error'}}
        if response is not None:
            output.write(json.dumps(response, separators=(',', ':')) + '\n')
            output.flush()
        if len(line) > MAX_JSON:
            return


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--login-env', type=Path, required=True)
    parser.add_argument('--attachment-manifest', type=Path, required=True)
    parser.add_argument('--audit-log', type=Path)
    args = parser.parse_args()
    try:
        login = load_login(args.login_env)
        attachments = load_attachments(args.attachment_manifest)
        log = os.fdopen(os.open(args.audit_log, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600), 'a') if args.audit_log else None
        def audit(event):
            if log:
                log.write(json.dumps(event, separators=(',', ':')) + '\n')
                log.flush()
        bridge = DocumentBridge(NativeHttpClient(*login), attachments, audit)
        serve(bridge, sys.stdin, sys.stdout)
        if log:
            log.close()
        return 0
    except Exception:
        sys.stderr.write('Document MCP bridge setup failed; check the trusted local configuration.\n')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
