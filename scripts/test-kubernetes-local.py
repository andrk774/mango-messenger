"""Explicit local-cluster acceptance. Creates labelled fixtures, retains their data.
Restarts only mango-dev backend/PostgreSQL; never targets another context/namespace.
No passwords, mail codes, access tokens or signed URLs are written to reports.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time
import urllib.request
import urllib.error
import urllib.parse
import uuid

ROOT=Path(__file__).resolve().parent.parent
KUBECTL=shutil.which('kubectl') or str(Path(os.environ['LOCALAPPDATA'])/'Programs/DockerDesktop/resources/bin/kubectl.exe')
BASE='http://127.0.0.1:28081'
MAIL='http://127.0.0.1:28026'
FLAGS=[KUBECTL,'--context','docker-desktop','--namespace','mango-dev','--request-timeout=15s']
processes=[]

def kube(*args):
    result=subprocess.run(FLAGS+list(args),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=55)
    if result.returncode: raise RuntimeError('Kubernetes operation failed')
    return result.stdout

def http(path,data=None,token=None,method=None,base=BASE,raw=False):
    url=urllib.parse.urljoin(base,path)
    if urllib.parse.urlsplit(url).netloc != urllib.parse.urlsplit(base).netloc:
        raise ValueError('Unexpected URL origin')
    headers={}
    if token: headers['Authorization']='Bearer '+token
    if data is not None:
        if isinstance(data,bytes): headers['Content-Type']='application/octet-stream'
        else: headers['Content-Type']='application/json';data=json.dumps(data).encode()
    req=urllib.request.Request(url,data=data,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=15) as response:
            body=response.read()
            return body if raw else json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        raise RuntimeError('HTTP status '+str(error.code)+' for '+urllib.parse.urlsplit(path).path) from None

def forward(service,local,remote):
    process=subprocess.Popen(FLAGS+['port-forward','service/'+service,f'{local}:{remote}','--address','127.0.0.1'],
        stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    processes.append(process)
    for _ in range(40):
        if process.poll() is not None: raise RuntimeError('Port-forward exited')
        try:
            http('/v1/health/ready' if service=='backend' else '/api/v1/messages',base=f'http://127.0.0.1:{local}')
            return process
        except Exception: time.sleep(.5)
    raise RuntimeError('Service did not become ready')

def new_user(label):
    account={'email':label+'@example.test','username':label,'displayName':'Kubernetes acceptance '+label,
             'password':secrets.token_urlsafe(24)}
    http('/v1/auth/register',account)
    code=None
    for _ in range(20):
        messages=http('/api/v1/messages',base=MAIL)['messages']
        for message in messages:
            if any(x['Address']==account['email'] for x in message['To']):
                letter=http('/api/v1/message/'+message['ID'],base=MAIL)
                code=letter['Text'].strip().rsplit(': ',1)[-1]
                break
        if code:break
        time.sleep(.5)
    if not code:raise RuntimeError('Verification email not received')
    http('/v1/auth/verify-email',{'email':account['email'],'token':code})
    token=login(account)
    return account,token,http('/v1/users/me',token=token)['id']

def login(account):
    return http('/v1/auth/login',{'email':account['email'],'password':account['password'],
        'device':{'platform':'WEB','model':'Kubernetes acceptance','appVersion':'test'}})['accessToken']

def run():
    forward('backend',28081,8080);forward('mailpit',28026,8025)
    print('PASS readiness and Mailpit',flush=True)
    label='kube_'+uuid.uuid4().hex[:10]
    account,one,uid=new_user(label+'a')
    other,two,peer=new_user(label+'b')
    print('PASS registration, real SMTP capture, email confirmation and login',flush=True)
    chat=http('/v1/conversations',{'type':'DIRECT','memberIds':[peer]},one)['id']
    text='Kubernetes persistence fixture '+label
    request={'clientMsgId':str(uuid.uuid4()),'body':text,'type':'TEXT'}
    first=http('/v1/conversations/'+chat+'/messages',request,one)
    again=http('/v1/conversations/'+chat+'/messages',request,one)
    assert first['id']==again['id'],'Duplicate retry'
    content=b'Mango Kubernetes attachment persistence fixture\n'
    attachment=str(uuid.uuid4())
    ticket=http('/v1/attachments/upload-ticket',{'attachmentId':attachment,'conversationId':chat,
        'mimeType':'text/plain','sizeBytes':len(content),'checksumSha256':hashlib.sha256(content).hexdigest(),
        'fileName':'kubernetes-acceptance.txt'},one)
    http(ticket['uploadUrl'],content,method='PUT')
    http('/v1/conversations/'+chat+'/messages',{'clientMsgId':str(uuid.uuid4()),'type':'FILE','attachmentIds':[attachment]},one)
    task=str(uuid.uuid4())
    http('/v1/tasks',{'taskId':task,'title':'Kubernetes acceptance task','conversationId':chat,'assigneeIds':[peer]},one)
    def verify_history(token):
        items=http('/v1/conversations/'+chat+'/messages',token=token)['items']
        assert sum(m.get('body')==text for m in items)==1
        url=http('/v1/attachments/'+attachment+'/url',token=token)['url']
        assert http(url,raw=True)==content
        assert http('/v1/tasks/'+task,token=token)['id']==task
    verify_history(two)
    print('PASS messages, idempotent retry, byte-exact attachment download and assigned task',flush=True)
    before=json.loads(kube('get','pvc','-o','json'))
    volumes={x['metadata']['name']:x['spec']['volumeName'] for x in before['items']}
    # Only these isolated test resources, not Compose or any external context.
    for resource in ('statefulset/postgres','deployment/backend'):
        kube('rollout','restart',resource)
        kube('rollout','status',resource,'--timeout=45s')
    processes[0].terminate();processes[0].wait(timeout=5)
    forward('backend',28081,8080)
    verify_history(login(other))
    after=json.loads(kube('get','pvc','-o','json'))
    assert volumes=={x['metadata']['name']:x['spec']['volumeName'] for x in after['items']}
    print('PASS database and attachment persistence after PostgreSQL/backend restarts',flush=True)
    report={'passed':True,'context':'docker-desktop','namespace':'mango-dev','fixtureLabel':label,
        'checks':['readiness','SMTP via Mailpit','email confirmation','login','messages','idempotency',
                  'attachments byte comparison','assigned tasks','PostgreSQL/backend restart','PVC identity preserved'],
        'notVerified':['external cluster','external SMTP','FCM','Galaxy against Kubernetes','public HTTPS','WebSocket reconnect'],
        'deletedSourceData':False}
    out=ROOT/'artifacts/kubernetes-acceptance'
    out.mkdir(parents=True,exist_ok=True)
    (out/(label+'.json')).write_text(json.dumps(report,indent=2),encoding='utf-8')
    print('Acceptance report:',out/(label+'.json'))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute-local',action='store_true',required=True)
    parser.parse_args()
    try: run()
    except Exception as error: raise SystemExit('Acceptance failed: '+type(error).__name__+'; fixture data retained. '+str(error))
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=5)
