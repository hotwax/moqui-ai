#!/usr/bin/env python3
"""Start the local POC and wait for health. Never kills an existing process."""
import os,socket,subprocess,time,urllib.request,json
from pathlib import Path
root=Path(os.environ.get('KNOWLEDGE_POC_HOME','/Users/adityapatel/Documents/GitHub/moqui/maarg-oms/client-knowledge-poc'))
with socket.socket() as s:
    if s.connect_ex(('127.0.0.1',8097))==0: raise SystemExit('Port 8097 is occupied; inspect the existing process before starting.')
with (root/'server.log').open('a') as log:
    child=subprocess.Popen(['/opt/homebrew/bin/python3',str(Path(__file__).parent/'run.py'),'serve'],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
(root/'server.pid').write_text(str(child.pid))
for _ in range(60):
    if child.poll() is not None: raise SystemExit('Server exited; inspect local server.log.')
    try:
        with urllib.request.urlopen('http://127.0.0.1:8097/',timeout=1) as response:
            if response.status==200:
                print(json.dumps({'url':'http://127.0.0.1:8097','pid':child.pid}));break
    except OSError: time.sleep(1)
else: raise SystemExit('Startup not yet verified; inspect server.log. The process was left intact.')
