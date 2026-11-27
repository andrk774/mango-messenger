"""Local acceptance using fresh test accounts and a synthetic Russian recording."""
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request
import uuid
import secrets
import time

ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'artifacts/voice-acceptance'
BASE='http://127.0.0.1:8081'

def load(name): return json.loads((ROOT/'artifacts'/name).read_text(encoding='utf-8-sig'))
def api(path,body=None,token=None,method=None):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    request=urllib.request.Request(BASE+path,None if body is None else json.dumps(body).encode(),headers,method=method)
    with urllib.request.urlopen(request,timeout=190) as response:
        data=response.read()
        return json.loads(data) if data else None
def login(account):
    return api('/v1/auth/login',{'email':account['email'],'password':account['password'],'device':{'platform':'WEB','model':'Voice acceptance','appVersion':'1'}})['accessToken']

def fresh_account():
    name='voice_'+uuid.uuid4().hex[:12]
    account={'email':name+'@example.test','password':secrets.token_urlsafe(24)}
    api('/v1/auth/register',{**account,'username':name,'displayName':'Voice acceptance'})
    for _ in range(30):
        with urllib.request.urlopen('http://127.0.0.1:8026/api/v1/messages',timeout=10) as response:
            letters=json.load(response)
        letter=next((m for m in letters.get('messages',[]) if any(t.get('Address')==account['email'] for t in m.get('To',[]))),None)
        if letter:
            with urllib.request.urlopen('http://127.0.0.1:8026/api/v1/message/'+letter['ID'],timeout=10) as response:
                token=json.load(response)['Text'].strip().split(': ')[-1]
            api('/v1/auth/verify-email',{'email':account['email'],'token':token})
            return account
        time.sleep(.5)
    raise AssertionError('Verification email not received')

owner=fresh_account();peer=fresh_account()
one=login(owner);two=login(peer)
try:
    me=api('/v1/users/me',token=one)
    chat=api('/v1/conversations',{'type':'GROUP','title':'Голосовые — приёмка','memberIds':[me['id']]},two)
    audio=(OUT/'reference.m4a').read_bytes();attachment=str(uuid.uuid4())
    ticket=api('/v1/attachments/upload-ticket',{'attachmentId':attachment,'conversationId':chat['id'],'mimeType':'audio/mp4','sizeBytes':len(audio),'checksumSha256':hashlib.sha256(audio).hexdigest(),'fileName':'Проверка речи.m4a','durationMs':9200},two)
    with urllib.request.urlopen(urllib.request.Request(BASE+ticket['uploadUrl'],audio,{'Content-Type':'audio/mp4'},method='PUT')) as response:
        assert response.status==204
    message=api('/v1/conversations/'+chat['id']+'/messages',{'clientMsgId':str(uuid.uuid4()),'type':'VOICE','attachmentIds':[attachment]},two)
    transcript=api('/v1/attachments/'+attachment+'/transcript',{},one)
    assert 'голосового' in transcript['text'].lower() and 'задачи' in transcript['text'].lower()
    assert api('/v1/attachments/'+attachment+'/transcript',{},two)==transcript
    # Test account only: a labelled, once-only balance entry makes Android history testable.
    user_id=str(uuid.UUID(me['id']));operation=str(uuid.uuid4())
    query=f"""BEGIN;
INSERT INTO account_balances(user_id) VALUES ('{user_id}') ON CONFLICT DO NOTHING;
WITH previous AS (SELECT * FROM account_balances WHERE user_id='{user_id}' FOR UPDATE),
added AS (
INSERT INTO balance_ledger(request_id,actor_id,user_id,request_json,old_points,new_points,old_subscription,new_subscription,reason)
SELECT '{operation}','{user_id}','{user_id}','{{"fixture":true}}',points,points+25,subscription_until,now()+interval '7 days','Acceptance subscription fixture 2026-11-19'
FROM previous WHERE NOT EXISTS (SELECT 1 FROM balance_ledger WHERE user_id='{user_id}' AND reason='Acceptance subscription fixture 2026-11-19')
RETURNING new_points,new_subscription)
UPDATE account_balances SET points=added.new_points,subscription_until=added.new_subscription FROM added WHERE user_id='{user_id}';
COMMIT;"""
    result=subprocess.run(['docker','exec','-i','mango-postgres-1','psql','-X','-v','ON_ERROR_STOP=1','-U','mango','-d','mango'],input=query.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    assert result.returncode==0,'Test balance fixture failed'
    history=api('/v1/users/me/account/history',token=one)
    assert any(x['reason']=='Acceptance subscription fixture 2026-11-19' for x in history)
    (OUT/'live-result.json').write_text(json.dumps({'ok':True,'conversationId':chat['id'],'attachmentId':attachment,'messageId':message['id'],'transcript':transcript,'syntheticAudio':True},ensure_ascii=False,indent=2),encoding='utf-8')
    print('PASS: Russian voice transcription through backend, cached access for both members, subscription history fixture ready')
finally:
    api('/v1/auth/logout',{},one)
    api('/v1/auth/logout',{},two)
