"""Generate once per request and dispatch actual Android verification."""
import hashlib
import json
import os
import pathlib
import re
import sys
from urllib.error import HTTPError
from urllib.parse import quote
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'server'/'build_orchestrator'))
import service

request_id=os.environ['REQUEST_ID']
if not re.fullmatch(r'[a-f0-9]{32}',request_id):raise ValueError('Invalid request ID')
spec=json.loads(os.environ['REQUEST_JSON'])
if not spec.get('requirements'):raise ValueError('requirements required')
branch='native/'+request_id
existing=None
try:existing=service.github('git/ref/heads/'+quote(branch,safe=''))['object']['sha']
except HTTPError as error:
    if error.code!=404:raise
if existing:
    runs=service.github('actions/runs?branch='+quote(branch,safe='')+'&event=workflow_dispatch&per_page=30')['workflow_runs']
    if not any(run['head_sha']==existing and run['path']=='.github/workflows/native-build.yml' for run in runs):
        service.github('actions/workflows/native-build.yml/dispatches','POST',{'ref':branch,'inputs':{'request_id':request_id,'channel':spec.get('channel','standalone')}})
    plan={'id':request_id,'summary':'Reuse committed source for this request','branch':branch,'commit':existing}
else:
    if not service.AI_KEY:raise ValueError('Configure LIFEATLAS_AI_KEY in repository Actions secrets')
    base=service.github('git/ref/heads/'+quote(service.BASE,safe=''))['object']['sha']
    tree_sha=service.github('git/commits/'+base)['tree']['sha']
    generated=service.generate(spec,service.github('git/trees/'+tree_sha+'?recursive=1'))
    entries=[{'path':c['path'],'mode':'100644','type':'blob','content':c['content']} for c in generated['changes']]
    plan={'id':request_id,'summary':generated.get('summary'),'branch':branch,'baselineCommit':base,'files':[c['path'] for c in entries],'requestHash':hashlib.sha256(json.dumps(spec,sort_keys=True).encode()).hexdigest()}
    entries.append({'path':'.atlas/request.json','mode':'100644','type':'blob','content':json.dumps(plan,ensure_ascii=False)})
    tree=service.github('git/trees','POST',{'base_tree':tree_sha,'tree':entries})['sha']
    commit=service.github('git/commits','POST',{'message':'Native feature: '+spec.get('title',request_id),'tree':tree,'parents':[base]})['sha']
    service.github('git/refs','POST',{'ref':'refs/heads/'+branch,'sha':commit})
    service.github('actions/workflows/native-build.yml/dispatches','POST',{'ref':branch,'inputs':{'request_id':request_id,'channel':spec.get('channel','standalone')}})
    plan['commit']=commit
pathlib.Path('feature-plan.json').write_text(json.dumps(plan,ensure_ascii=False),encoding='utf-8')
print('Source saved; Android workflow submitted: '+plan['commit'])
