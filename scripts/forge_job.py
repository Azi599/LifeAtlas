"""Run native code generation in a GitHub Actions job, using repository secrets."""
import json
import os
import pathlib
import sys
import time
from urllib.parse import quote
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'server' / 'build_orchestrator'))
import service

request_id = os.environ['REQUEST_ID']
if not __import__('re').fullmatch(r'[a-f0-9]{32}', request_id):
    raise ValueError('Invalid request ID')
if not service.AI_KEY:
    raise ValueError('Configure LIFEATLAS_AI_KEY in repository Actions secrets')
spec = json.loads(os.environ['REQUEST_JSON'])
if not spec.get('requirements'):
    raise ValueError('requirements required')
base = service.github('git/ref/heads/' + quote(service.BASE, safe=''))['object']['sha']
commit = service.github('git/commits/' + base)
tree_sha = commit['tree']['sha']
generated = service.generate(spec, service.github('git/trees/' + tree_sha + '?recursive=1'))
entries = [{'path': change['path'], 'mode':'100644', 'type':'blob', 'content':change['content']} for change in generated['changes']]
new_tree = service.github('git/trees', 'POST', {'base_tree':tree_sha,'tree':entries})['sha']
new_commit = service.github('git/commits','POST',{'message':'Native feature: '+spec.get('title',request_id),'tree':new_tree,'parents':[base]})['sha']
branch = 'native/' + request_id
service.github('git/refs','POST',{'ref':'refs/heads/'+branch,'sha':new_commit})
service.github('actions/workflows/native-build.yml/dispatches','POST',{'ref':branch,'inputs':{'request_id':request_id}})
pathlib.Path('feature-plan.json').write_text(json.dumps({'id':request_id,'summary':generated.get('summary'),'branch':branch,'commit':new_commit,'files':[c['path'] for c in entries]},ensure_ascii=False),encoding='utf-8')
print('Source committed and native workflow dispatched: '+new_commit)
