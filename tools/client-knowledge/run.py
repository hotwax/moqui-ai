#!/usr/bin/env python3
"""Run a POC Groovy program using the isolated Moqui runtime, never the operational DB."""
import os,sys,subprocess
from pathlib import Path
root=Path(os.environ.get('KNOWLEDGE_POC_HOME','/Users/adityapatel/Documents/GitHub/moqui/maarg-oms/client-knowledge-poc'))
java=os.environ.get('KNOWLEDGE_JAVA','/opt/homebrew/opt/openjdk@21/bin/java')
script=Path(__file__).parent/(sys.argv[1]+'.groovy')
cmd=[java,'-Xmx1536m',f'-Dmoqui.runtime={root}/runtime','-Dmoqui.conf=conf/KnowledgeConf.xml',f'-Dai_client_inbox={root}/inbox','-Dmoqui.init.static=true','-Dai_client_collectors_enabled=Y','-Dai_client_run_job_now=Y','-Dai_client_collector_user=knowledge-local',f'-Dai_client_collector_script={Path(__file__).parent}/collect.py','-cp',(root/'classpath.txt').read_text(),'groovy.ui.GroovyMain',str(script),*sys.argv[2:]]
env={k:v for k,v in os.environ.items() if k not in ['ai_openai_key','ai_anthropic_key']}
os.chdir(root)
os.execve(java,cmd,env)
