#!/usr/bin/env python3
"""Prepare, or explicitly run, a fresh Codex acceptance actor with only document MCP.

Default mode prepares a reviewable command/prompt; --run is a separate trusted operator
action after the isolated loopback server is ready. Existing CODEX_HOME auth is reused.
No auth files or credentials are read/copied by this harness.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

from document_mcp_bridge import ACTOR_TOOLS, ATTACHMENT_TOOL, load_attachments

DISABLED_FEATURES = ('shell_tool', 'unified_exec', 'shell_snapshot', 'apps', 'plugins', 'remote_plugin',
                     'hooks', 'browser_use', 'browser_use_external', 'browser_use_full_cdp_access',
                     'computer_use', 'in_app_browser', 'image_generation', 'view_image', 'code_mode',
                     'code_mode_host', 'multi_agent', 'multi_agent_v2', 'goals', 'memories', 'chronicle',
                     'workspace_dependencies', 'tool_suggest')


def build_command(bridge, login_env, manifest, audit_log, actor_cwd, model=None):
    command = ['codex', '--ask-for-approval', 'never', 'exec', '--ignore-user-config', '--ephemeral', '--json',
               '--skip-git-repo-check', '--sandbox', 'read-only', '--cd', str(actor_cwd)]
    values = [f'features.{feature}=false' for feature in DISABLED_FEATURES]
    values += ['web_search="disabled"', 'tools.web_search=false', 'tools.view_image=false', 'project_doc_max_bytes=0',
               'mcp_servers.document_local.command=' + json.dumps(sys.executable),
               'mcp_servers.document_local.args=' + json.dumps([str(bridge), '--login-env', str(login_env),
                   '--attachment-manifest', str(manifest), '--audit-log', str(audit_log)]),
               'mcp_servers.document_local.required=true', 'mcp_servers.document_local.startup_timeout_sec=30',
               'mcp_servers.document_local.tool_timeout_sec=180',
               'mcp_servers.document_local.default_tools_approval_mode="approve"',
               'mcp_servers.document_local.enabled_tools=' + json.dumps(sorted(ACTOR_TOOLS | {ATTACHMENT_TOOL}))]
    for value in values:
        command += ['-c', value]
    if model:
        command += ['--model', model]
    return command


def check_events(events):
    violations, calls = [], []
    allowed_items = {'agent_message', 'reasoning', 'plan', 'todo_list', 'context_compaction'}
    complete = False
    for event in events:
        if event.get('type') in {'invalid_json_event', 'error', 'turn.failed'}:
            violations.append({'type': event.get('type')})
        if event.get('type') == 'turn.completed':
            complete = True
        item = event.get('item')
        if not isinstance(item, dict) or event.get('type') not in {'item.started', 'item.completed', 'item.updated'}:
            continue
        kind = item.get('type')
        if kind == 'mcp_tool_call':
            server, tool = item.get('server'), item.get('tool')
            if server != 'document_local' or tool not in ACTOR_TOOLS | {ATTACHMENT_TOOL}:
                violations.append({'type': kind, 'server': server, 'tool': tool})
            if event['type'] == 'item.completed':
                calls.append({'server': server, 'tool': tool})
        elif kind not in allowed_items:
            violations.append({'type': kind})
    return {'passed': complete and bool(calls) and not violations, 'turnCompleted': complete,
            'mcpCalls': calls, 'violations': violations}


def private_write(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as target:
        target.write(value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--login-env', type=Path, required=True)
    parser.add_argument('--attachment-manifest', type=Path, required=True)
    parser.add_argument('--request-file', type=Path, required=True, help='Trusted business request; no credentials or local paths.')
    parser.add_argument('--output-dir', type=Path, required=True, help='Private output outside the source checkout.')
    parser.add_argument('--reference-image', type=Path, help='Optional trusted, non-PII reference PNG supplied to the initial prompt.')
    parser.add_argument('--model', help='Optional operator-selected model; otherwise use CLI default.')
    parser.add_argument('--run', action='store_true', help='Execute only after the operator confirms the local server is ready.')
    args = parser.parse_args()
    try:
        catalog = load_attachments(args.attachment_manifest)
        args.output_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        visible = [{'id': key, 'description': value['displayName'], 'kind': value['kind']} for key, value in catalog.items()]
        prompt = args.request_file.read_text() + '\n\nSupplied attachments: ' + json.dumps(visible) + (
            '\nUse only the document_local MCP tools/resources. Forward supplied bytes only with '
            'forward_document_attachment; never generate base64 or request a path/URL. Do not use shell, '
            'filesystem, SQL, generic service/entity execution, browsing, other agents, source edits or restarts. '
            'Report a concrete tool capability gap if blocked. Keep private record content out of the final summary.\n')
        bridge = Path(__file__).with_name('document_mcp_bridge.py').resolve()
        audit_path = args.output_dir.resolve() / 'bridge-audit.jsonl'
        # Scratch has no repository/project configuration or files for the actor to inspect.
        with tempfile.TemporaryDirectory(prefix='document-mcp-actor-') as scratch:
            command = build_command(bridge, args.login_env.resolve(), args.attachment_manifest.resolve(),
                                    audit_path, Path(scratch), args.model)
            if args.reference_image:
                command += ['--image', str(args.reference_image.resolve())]
            private_write(args.output_dir / 'actor-prompt.txt', prompt)
            private_write(args.output_dir / 'launch-plan.json', json.dumps({
                'command': command, 'run': args.run, 'authHome': 'existing CODEX_HOME; not copied',
                'cwd': 'fresh temporary directory created for this invocation',
                'note': 'Prepared commands contain a transient cwd; invoke this harness with --run when ready.'}, indent=2))
            if not args.run:
                print('Prepared the restricted document MCP acceptance run; no model or server connection was started.')
                return 0
            events_path = args.output_dir / 'codex-events.jsonl'
            error_path = args.output_dir / 'codex-stderr.log'
            with os.fdopen(os.open(events_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as events, \
                    os.fdopen(os.open(error_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as errors:
                proc = subprocess.run(command, input=prompt, text=True, stdout=events, stderr=errors)
            parsed = []
            for line in events_path.read_text().splitlines():
                try:
                    parsed.append(json.loads(line))
                except ValueError:
                    parsed.append({'type': 'invalid_json_event'})
            evidence = check_events(parsed)
            evidence['exitCode'] = proc.returncode
            evidence['passed'] = evidence['passed'] and proc.returncode == 0
            private_write(args.output_dir / 'tool-audit.json', json.dumps(evidence, indent=2))
            print('Document MCP actor finished; inspect the private transcript and tool-audit.json.')
            return 0 if evidence['passed'] else 1
    except Exception:
        sys.stderr.write('Acceptance harness failed; inspect the trusted inputs and choose a fresh private output directory.\n')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
