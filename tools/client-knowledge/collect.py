#!/usr/bin/env python3
"""Read-only collectors. All writes are local snapshots; never posts comments/messages."""
import argparse,datetime,hashlib,json,os,re,subprocess,urllib.request
from pathlib import Path
BASE=Path(os.environ.get('KNOWLEDGE_POC_HOME','/Users/adityapatel/Documents/GitHub/moqui/maarg-oms/client-knowledge-poc'))
INBOX=BASE/'inbox'
def save(client,key,url,kind,title,body,date):
    if re.search(r'(?i)(-----BEGIN [A-Z ]*PRIVATE KEY|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{20,}|(?:authorization\s*:\s*bearer|api[_-]?key|password|access[_-]?token)\s*[:=]\s*["\']?[^\s"\']{8,})',body):
        raise ValueError('Possible credential in source; redact before ingestion')
    # Split large sources into stable section windows and retain their identity.
    parts=[body[i:i+12000] for i in range(0,len(body),12000)] or ['']
    for i,part in enumerate(parts):
        source_key=key+f'#part-{i+1}'
        doc=dict(clientId=client,sourceKey=source_key,sourceUrl=url,sourceType=kind,title=title+(f' (part {i+1})' if len(parts)>1 else ''),body=part,sourceDate=date)
        name=hashlib.sha256((client+source_key).encode()).hexdigest()+'.json'
        target=INBOX/name; tmp=target.with_suffix('.tmp'); tmp.write_text(json.dumps(doc)); tmp.chmod(0o600); tmp.replace(target)
    return len(parts)
def get(url):
    req=urllib.request.Request(url,headers={'User-Agent':'HotWax-local-knowledge-poc'})
    with urllib.request.urlopen(req,timeout=30) as r: return r.read(2_000_000).decode()
def github():
    # Explicit Rails-only repository. gh keeps the credential in its existing native credential store.
    rows=json.loads(subprocess.check_output(['gh','api','repos/hotwax/rails/issues?state=all&sort=updated&direction=desc&per_page=100']))
    state_file=BASE/'github-cursor.json'
    state=json.loads(state_file.read_text()) if state_file.exists() else {}
    count=0
    for row in rows:
        if state.get(str(row['number'])) == row['updated_at']:
            continue
        kind='PR' if 'pull_request' in row else 'Issue'
        text=f"{kind} #{row['number']}: {row['title']}\nGitHub state: {row['state']}\nUpdated: {row['updated_at']}\n\n{row.get('body') or ''}"
        if kind=='PR':
            pr=json.loads(subprocess.check_output(['gh','api',f"repos/hotwax/rails/pulls/{row['number']}"]))
            text+=f"\nMerged at: {pr.get('merged_at')}\nHead SHA: {pr['head']['sha']}\nMerge does not establish deployment or acceptance."
        if row.get('comments',0):
            comments=json.loads(subprocess.check_output(['gh','api',f"repos/hotwax/rails/issues/{row['number']}/comments?per_page=100"]))
            for comment in comments:
                text+=f"\n\nComment by {comment['user']['login']} at {comment['updated_at']} ({comment['html_url']}):\n{comment.get('body') or ''}"
            if row['comments']>100: text+='\n[POC limit: first 100 comments only.]'
        count+=save('rails',row['html_url'],row['html_url'],'github',f"Rails {kind} #{row['number']}: {row['title']}",text,row['updated_at'])
        state[str(row['number'])]=row['updated_at']
    state_file.write_text(json.dumps(state)); state_file.chmod(0o600)
    return count
def local():
    root=Path('/Users/adityapatel/Documents/Clients/Rails')
    p=root/'rails-routing-inventory-decision-record.md'
    return save('rails',str(p),p.as_uri(),'decision_record','Rails routing and inventory decision record — historical August 13 snapshot',p.read_text(),'2026-08-13T23:59:00Z')
def docs():
    index=get('https://docs.hotwax.co/documents/llms.txt')
    urls=re.findall(r'\]\((https://docs\.hotwax\.co/[^)]+)\)',index)
    # Small intentional first corpus; the full product corpus is a subsequent scaling step.
    selected=[u for u in dict.fromkeys(urls) if any(t in u for t in ['routing','safety-stock','available-to-promise','transfer-order','receiving','shopify-product','inventory-sync'])][:24]
    n=0; now=datetime.datetime.now(datetime.timezone.utc).isoformat()
    for url in selected:
        canonical=url.removesuffix('.md')
        body=get(canonical+'.md')
        n+=save('GLOBAL',canonical,canonical,'documentation',canonical.rsplit('/',1)[-1].replace('-',' '),body,now)
    return n
if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('source',choices=['github','local','docs']); args=p.parse_args()
    INBOX.mkdir(parents=True,exist_ok=True)
    print(json.dumps({'source':args.source,'snapshots':globals()[args.source]()}))
