"""Pure client-transport tests; no Moqui, sockets, credentials, or model execution."""
import base64
import hashlib
import io
import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from document_mcp_bridge import (
    ACTOR_TOOLS, DocumentBridge, NativeHttpClient, load_attachments, load_login, serve,
)
from run_document_mcp_acceptance import build_command, check_events, main as prepare_main


class FakeNative:
    def __init__(self):
        self.calls = []
        self.data = bytearray()
        self.upload_id = 'U' + 'a' * 64

    def request(self, method, params):
        self.calls.append((method, params))
        if method == 'tools/list':
            return {'tools': [{'name': name, 'inputSchema': {'type': 'object'}} for name in
                              ['get_document_template', 'append_document_upload', 'run_sql', 'exec_service']]}
        if method == 'initialize':
            return {'protocolVersion': params['protocolVersion'], 'capabilities': {'tools': {}, 'resources': {}},
                    'serverInfo': {'name': 'test', 'version': '1'}}
        name = params.get('name')
        if name == 'begin_document_upload':
            body = {'ok': True, 'uploadId': self.upload_id, 'chunkLimit': 65536, 'nextOffset': 0}
        elif name == 'append_document_upload':
            args = params['arguments']
            assert args['offset'] == len(self.data)
            self.data.extend(base64.b64decode(args['dataBase64'], validate=True))
            body = {'ok': True, 'uploadId': self.upload_id, 'nextOffset': len(self.data)}
        elif name == 'finish_document_upload':
            body = {'ok': True, 'uploadId': self.upload_id, 'finalizedUploadId': self.upload_id,
                    'totalBytes': len(self.data), 'sha256': hashlib.sha256(self.data).hexdigest(),
                    'inspection': {'privateProfile': 'not returned'}}
        else:
            body = {'ok': True}
        return {'content': [{'type': 'text', 'text': json.dumps(body)}], 'structuredContent': body, 'isError': False}


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.file = self.root / 'private-source.ttf'
        self.payload = b'attachment transport fixture' * 5000
        self.file.write_bytes(self.payload)
        self.manifest = self.root / 'attachments.json'
        self.manifest.write_text(json.dumps({'attachments': [{
            'id': 'sample-font', 'kind': 'font', 'displayName': 'Supplied font',
            'localPath': str(self.file), 'filename': 'supplied.ttf', 'mimeType': 'font/ttf',
        }]}))
        self.native = FakeNative()
        self.audit = []
        self.bridge = DocumentBridge(self.native, load_attachments(self.manifest), self.audit.append)

    def call(self, method, params=None, ident=1):
        return self.bridge.handle({'jsonrpc': '2.0', 'id': ident, 'method': method, 'params': params or {}})

    def test_catalog_is_closed_and_paths_never_enter_helper_definition(self):
        out = self.call('tools/list')['result']
        self.assertEqual((out['ttlMs'], out['cacheScope']), (0, 'private'))
        self.assertEqual([t['name'] for t in out['tools']], ['get_document_template', 'forward_document_attachment'])
        helper = out['tools'][-1]
        self.assertEqual(helper['inputSchema']['properties']['attachmentId']['enum'], ['sample-font'])
        self.assertIn('Supplied font', helper['description'])
        self.assertNotIn(str(self.root), json.dumps(out))
        self.assertNotIn('private-source.ttf', json.dumps(out))

    def test_attachment_bytes_are_chunked_into_native_mcp_calls_without_actor_base64(self):
        result = self.call('tools/call', {'name': 'forward_document_attachment', 'arguments': {'attachmentId': 'sample-font'}})['result']
        self.assertFalse(result['isError'])
        self.assertEqual(result['structuredContent']['finalizedUploadId'], self.native.upload_id)
        self.assertEqual(bytes(self.native.data), self.payload)
        begin = self.native.calls[0][1]['arguments']
        self.assertEqual(begin['sha256'], hashlib.sha256(self.payload).hexdigest())
        self.assertEqual(begin['totalBytes'], len(self.payload))
        append = [p['arguments'] for m, p in self.native.calls if p.get('name') == 'append_document_upload']
        self.assertEqual(len(append), 3)
        self.assertTrue(all(len(base64.b64decode(p['dataBase64'])) <= 65536 for p in append))
        self.assertEqual(self.native.calls[-1][1]['name'], 'finish_document_upload')
        self.assertNotIn('privateProfile', json.dumps(result))
        self.assertNotIn(str(self.file), json.dumps(result))
        self.assertNotIn(base64.b64encode(self.payload).decode(), json.dumps(self.audit))
        self.assertNotIn('supplied.ttf', json.dumps(self.audit))

    def test_actor_cannot_supply_bytes_paths_urls_or_raw_upload_tool_calls(self):
        for name, args in [
            ('append_document_upload', {'uploadId': 'x', 'dataBase64': 'YQ=='}),
            ('run_sql', {'query': 'select 1'}),
            ('forward_document_attachment', {'attachmentId': str(self.file)}),
            ('forward_document_attachment', {'attachmentId': 'sample-font', 'dataBase64': 'YQ=='}),
            ('forward_document_attachment', {'attachmentId': 'sample-font', 'url': 'https://example.test/a'}),
        ]:
            out = self.call('tools/call', {'name': name, 'arguments': args})
            self.assertEqual(out['error']['code'], -32602)
        self.assertEqual(self.native.calls, [])

    def test_repeat_attachment_forward_uses_cached_finalized_handle(self):
        params = {'name': 'forward_document_attachment', 'arguments': {'attachmentId': 'sample-font'}}
        first = self.call('tools/call', params)['result']
        count = len(self.native.calls)
        self.assertEqual(self.call('tools/call', params)['result'], first)
        self.assertEqual(len(self.native.calls), count)

    def test_reconnect_to_finalized_upload_does_not_append_more_chunks(self):
        original = self.native.request
        def finalized(method, params):
            if params.get('name') == 'begin_document_upload':
                self.native.calls.append((method, params))
                return {'structuredContent': {'ok': True, 'uploadId': self.native.upload_id, 'status': 'finalized',
                                             'finalizedUploadId': self.native.upload_id, 'chunkLimit': 65536}, 'isError': False}
            return original(method, params)
        self.native.request = finalized
        result = self.call('tools/call', {'name': 'forward_document_attachment', 'arguments': {'attachmentId': 'sample-font'}})
        self.assertFalse(result['result']['isError'])
        self.assertEqual([p['name'] for m, p in self.native.calls], ['begin_document_upload', 'finish_document_upload'])

    def test_upload_domain_failure_is_a_tool_error_with_safe_code(self):
        def denied(method, params):
            return {'structuredContent': {'ok': False, 'code': 'UPLOAD_EXPIRED', 'message': 'private profile'}, 'isError': True}
        self.native.request = denied
        result = self.call('tools/call', {'name': 'forward_document_attachment', 'arguments': {'attachmentId': 'sample-font'}})
        self.assertTrue(result['result']['isError'])
        self.assertEqual(result['result']['structuredContent']['code'], 'UPLOAD_EXPIRED')
        self.assertNotIn('private profile', json.dumps(result))

    def test_normal_initialization_ping_resources_and_notifications(self):
        out = self.call('initialize', {'protocolVersion': '2025-11-25', 'capabilities': {}})
        self.assertEqual(out['result']['protocolVersion'], '2025-11-25')
        self.assertEqual(self.call('ping')['result'], {})
        self.assertIsNone(self.bridge.handle({'jsonrpc': '2.0', 'method': 'notifications/initialized'}))
        uri = 'hotwax-document://preview/123/pdf'
        self.call('resources/read', {'uri': uri})
        self.assertEqual(self.native.calls[-1], ('resources/read', {'uri': uri}))
        self.assertEqual(self.call('resources/read', {'uri': 'file:///etc/passwd'})['error']['code'], -32602)
        self.assertEqual(self.call('service/call', {})['error']['code'], -32601)

    def test_errors_do_not_expose_local_paths_or_exception_text(self):
        self.file.unlink()
        result = self.call('tools/call', {'name': 'forward_document_attachment', 'arguments': {'attachmentId': 'sample-font'}})
        self.assertTrue(result['result']['isError'])
        self.assertNotIn(str(self.root), json.dumps(result))
        self.assertNotIn('private-source', json.dumps(result))

    def test_stdio_is_json_lines_and_parse_errors_are_protocol_errors(self):
        output = io.StringIO()
        serve(self.bridge, io.StringIO('bad-json\n{"jsonrpc":"2.0","id":2,"method":"ping"}\n'), output)
        lines = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(lines[0]['error']['code'], -32700)
        self.assertEqual(lines[1], {'jsonrpc': '2.0', 'id': 2, 'result': {}})

    def test_manifest_rejects_relative_paths_duplicate_ids_and_invalid_media(self):
        data = json.loads(self.manifest.read_text())
        for updates in [{'localPath': 'relative.ttf'}, {'mimeType': 'text/html'}, {'id': '../path'}]:
            variant = json.loads(json.dumps(data))
            variant['attachments'][0].update(updates)
            self.manifest.write_text(json.dumps(variant))
            with self.assertRaises(ValueError):
                load_attachments(self.manifest)
        data['attachments'] *= 2
        self.manifest.write_text(json.dumps(data))
        with self.assertRaises(ValueError):
            load_attachments(self.manifest)

    def test_login_parser_does_not_source_shell_and_requires_loopback(self):
        login = self.root / 'login.env'
        login.write_text("OMS_TEST_URL='http://127.0.0.1:8090'\nOMS_TEST_USER='test'\nOMS_TEST_PASSWORD='fake-test-value'\n")
        values = load_login(login)
        self.assertEqual(values, ('test', 'fake-test-value'))
        login.write_text("OMS_TEST_URL=https://production.example.test\nOMS_TEST_USER=test\nOMS_TEST_PASSWORD=fake\n")
        with self.assertRaises(ValueError):
            load_login(login)

    def test_native_http_emits_modern_headers_and_auth_only_in_memory(self):
        class Reply(io.BytesIO):
            def __enter__(self): return self
            def __exit__(self, *args): self.close()
        class Opener:
            request = None
            def open(self, request, timeout):
                self.request = request
                return Reply(b'{"jsonrpc":"2.0","id":1,"result":{"contents":[]}}')
        opener = Opener()
        client = NativeHttpClient('test', 'fake-test-value', opener=opener)
        uri = 'hotwax-document://preview/123/pdf'
        client.request('resources/read', {'uri': uri})
        headers = dict((k.lower(), v) for k, v in opener.request.header_items())
        self.assertEqual(opener.request.full_url, 'http://127.0.0.1:8093/mcp/json')
        self.assertEqual(headers['mcp-name'], uri)
        self.assertEqual(headers['mcp-protocol-version'], '2026-07-28')
        self.assertNotIn('fake-test-value', opener.request.data.decode())
        body = json.loads(opener.request.data)
        self.assertEqual(body['params']['_meta']['io.modelcontextprotocol/protocolVersion'], '2026-07-28')

    def test_harness_disables_other_capabilities_and_reuses_auth_home(self):
        command = build_command(Path('/trusted/bridge.py'), Path('/private/login.env'), self.manifest,
                                Path('/private/audit.jsonl'), Path('/fresh/cwd'))
        joined = '\n'.join(command)
        for value in ['--ignore-user-config', '--ephemeral', '--json', 'features.shell_tool=false',
                      'features.unified_exec=false', 'features.plugins=false', 'features.apps=false',
                      'web_search="disabled"', 'tools.view_image=false', 'features.code_mode_host=true']:
            self.assertIn(value, command)
        self.assertNotIn('CODEX_HOME=', joined)
        self.assertNotIn('auth.json', joined)
        self.assertEqual(command[command.index('--sandbox') + 1], 'read-only')
        self.assertLess(command.index('--ask-for-approval'), command.index('exec'))

    def test_prepare_mode_writes_private_review_files_without_starting_codex(self):
        business = self.root / 'request.txt'
        business.write_text('Create the requested document layout using the supplied attachments.')
        output = self.root / 'prepared'
        argv = ['prepare', '--login-env', str(self.root / 'unread-login.env'), '--attachment-manifest', str(self.manifest),
                '--request-file', str(business), '--output-dir', str(output)]
        with patch('sys.argv', argv), patch('subprocess.run') as execution, patch('sys.stdout', io.StringIO()):
            self.assertEqual(prepare_main(), 0)
        execution.assert_not_called()
        self.assertFalse(json.loads((output / 'launch-plan.json').read_text())['run'])
        self.assertEqual((output / 'actor-prompt.txt').stat().st_mode & 0o777, 0o600)
        self.assertNotIn(str(self.file), (output / 'actor-prompt.txt').read_text())

    def test_transcript_check_rejects_shell_and_foreign_mcp(self):
        def event(kind, **fields): return {'type': 'item.completed', 'item': {'type': kind, **fields}}
        good = [event('mcp_tool_call', server='document_local', tool='get_document_template'), {'type': 'turn.completed'}]
        self.assertTrue(check_events(good)['passed'])
        for bad in [event('command_execution', command='echo no'), event('file_change', changes=[]),
                    event('mcp_tool_call', server='other', tool='get_document_template')]:
            self.assertFalse(check_events(good + [bad])['passed'])
        self.assertFalse(check_events(good + [{'type': 'invalid_json_event'}])['passed'])

    def test_actual_cli_nested_mcp_event_shape_keeps_strict_tool_audit(self):
        event = {'type': 'item.completed', 'item': {
            'id': 'item_1', 'type': 'mcp_tool_call', 'server': 'document_local',
            'tool': 'list_document_templates', 'arguments': {}, 'status': 'completed', 'error': None,
            'result': {'content': [{'type': 'text', 'text': '{"ok":true,"templates":[]}'}],
                       'structured_content': {'ok': True, 'templates': []}, '_meta': {}}}}
        audit = check_events([event, {'type': 'turn.completed'}])
        self.assertTrue(audit['passed'])
        self.assertEqual(audit['mcpCalls'], [{'server': 'document_local', 'tool': 'list_document_templates'}])
        unknown_exec = {'type': 'item.completed', 'item': {'type': 'code_mode_execution', 'code': 'arbitrary()'}}
        self.assertFalse(check_events([event, unknown_exec, {'type': 'turn.completed'}])['passed'])


if __name__ == '__main__':
    unittest.main()
