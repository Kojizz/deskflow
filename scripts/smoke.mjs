// Runs against a running DeskFlow instance; creates a disposable test workspace.
// Usage: node scripts/smoke.mjs http://localhost:8088
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
const base=process.argv[2]??'http://localhost:8088';
let cookie='';
async function request(path,{method='GET',body,key,expected=200}={}){
 const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json','X-Deskflow-Request':'1',...(cookie?{Cookie:cookie}:{}),...(key?{'Idempotency-Key':key}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{})});
 const value=await response.json();assert.equal(response.status,expected,`${method} ${path}: ${JSON.stringify(value)}`);
 if(response.headers.has('set-cookie'))cookie=response.headers.get('set-cookie').split(';')[0];
 return value;
}
const email=`smoke-${randomUUID()}@example.com`,password=randomUUID()+'Aa1!';
const user=await request('/api/auth/register',{method:'POST',body:{name:'Smoke Test',email,password}});
assert.equal((await request('/api/auth/me')).id,user.id);
await request('/api/auth/logout',{method:'POST',body:{}});
await request('/api/auth/me',{expected:401});
await request('/api/auth/login',{method:'POST',body:{email,password}});
const n=(id,kind,config)=>({id,type:'step',position:{x:0,y:0},data:{kind,label:id,config}});
const definition={nodes:[n('start','webhook',{}),n('format','transform',{output:{total:'{{input.total}}'}}),n('check','condition',{field:'steps.format.total',operator:'greaterThan',value:100}),n('yes','transform',{output:{priority:'high'}}),n('no','transform',{output:{priority:'normal'}})],edges:[{id:'a',source:'start',target:'format'},{id:'b',source:'format',target:'check'},{id:'c',source:'check',target:'yes',sourceHandle:'true'},{id:'d',source:'check',target:'no',sourceHandle:'false'}]};
let wf=await request('/api/workflows',{method:'POST',body:{name:'Smoke: branching',description:'Created by the smoke test',active:true,definition}});
const path=`/hooks/${wf.id}/${wf.webhook_token}`,payload={total:150};
const first=await request(path,{method:'POST',body:payload,key:'event-1',expected:202});
const duplicate=await request(path,{method:'POST',body:payload,key:'event-1',expected:202});
assert.equal(first.id,duplicate.id);
await request(path,{method:'POST',body:{total:99},key:'event-1',expected:409});
// Save a new version after queuing: the existing run must keep its original snapshot.
wf=await request(`/api/workflows/${wf.id}`,{method:'PUT',body:{...wf,name:'Smoke: edited',definition}});
async function completed(id){for(let i=0;i<100;i++){const run=await request(`/api/executions/${id}`);if(!['RUNNING','QUEUED','RETRYING'].includes(run.status))return run;await new Promise(r=>setTimeout(r,500));}throw new Error('Execution timed out');}
const run=await completed(first.id);assert.equal(run.status,'SUCCEEDED');assert.equal(run.version,1);assert.equal(run.steps.find(s=>s.node_id==='yes').status,'SUCCEEDED');assert.equal(run.steps.find(s=>s.node_id==='no').status,'SKIPPED');
const next=await request(`/api/workflows/${wf.id}/execute`,{method:'POST',body:{total:25}});
const low=await completed(next.id);assert.equal(low.status,'SUCCEEDED');assert.equal(low.steps.find(s=>s.node_id==='yes').status,'SKIPPED');assert.equal(low.steps.find(s=>s.node_id==='no').status,'SUCCEEDED');
const secret=randomUUID();await request('/api/credentials',{method:'POST',body:{name:'Smoke bearer',value:secret}});
assert.ok(!JSON.stringify(await request('/api/credentials')).includes(secret));
const replay=await request(`/api/executions/${first.id}/replay`,{method:'POST',body:{}});assert.equal((await completed(replay.id)).status,'SUCCEEDED');
const stats=await request('/api/stats');assert.equal(stats.succeeded,3);
await request('/api/auth/logout',{method:'POST',body:{}});
console.log('PASS: register/login/logout, workflow saves, webhooks, deduplication/conflict, version snapshot, true/false branching, credentials, replay, persisted outputs and stats.');
