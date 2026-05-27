// Exercises v0.2 against a running local instance. Uses a separate test workspace.
// No external API calls, paid AI calls, or real email delivery.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
const base=process.argv[2]??'http://localhost:8088';let cookie='';
async function request(path,{method='GET',body,expected=200}={}){
 const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json','X-Deskflow-Request':'1',...(cookie?{Cookie:cookie}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{})});
 const value=await response.json();assert.equal(response.status,expected,`${method} ${path}: ${JSON.stringify(value)}`);
 if(response.headers.has('set-cookie'))cookie=response.headers.get('set-cookie').split(';')[0];return value;
}
const n=(id,kind,config={})=>({id,type:'step',position:{x:80+(id==='start'?0:320),y:180},data:{kind,label:id,config}});
const edge=(source,target,sourceHandle)=>({id:`${source}-${target}`,source,target,...(sourceHandle?{sourceHandle}:{})});
const chain=(...steps)=>({nodes:[n('start','manual'),...steps],edges:[edge('start',steps[0].id),...steps.slice(1).map((s,i)=>edge(steps[i].id,s.id))]});
const create=(name,definition,active=false)=>request('/api/workflows',{method:'POST',body:{name:`Core smoke: ${name}`,description:'v0.2 verification fixture',definition,active}});
const execute=(wf,input={})=>request(`/api/workflows/${wf.id}/execute`,{method:'POST',body:input});
async function until(id,predicate){for(let i=0;i<120;i++){const run=await request(`/api/executions/${id}`);if(predicate(run))return run;await new Promise(r=>setTimeout(r,500));}throw new Error('Run timed out: '+id);}
const finished=id=>until(id,r=>['SUCCEEDED','FAILED','CANCELLED'].includes(r.status));
const step=(run,id)=>run.steps.find(s=>s.node_id===id);
await request('/api/auth/register',{method:'POST',body:{name:'Core Smoke',email:`core-${randomUUID()}@example.com`,password:randomUUID()+'Aa1!'}});
const table=await request('/api/tables',{method:'POST',body:{name:'Core smoke records'}});
await request(`/api/tables/${table.id}/records/manual`,{method:'PUT',body:{value:1}});
assert.equal((await request(`/api/tables/${table.id}/records`)).length,1);
await request(`/api/tables/${table.id}/records/manual`,{method:'DELETE'});
let wf=await create('preview + compare',chain(n('map','transform',{output:{total:'{{input.total}}'}}),n('save','table',{tableId:table.id,operation:'upsert',key:'preview',value:'{{steps.map}}'})));
const preview=await request(`/api/workflows/${wf.id}/preview`,{method:'POST',body:{input:{total:12},mocks:{}}});
assert.equal(preview.status,'SUCCEEDED');assert.equal(preview.effectCount,1);assert.equal(preview.sideEffectsDispatched,0);assert.equal((await request(`/api/tables/${table.id}/records`)).length,0);
const edited=structuredClone(wf.definition);edited.nodes[1].data.config.output={total:99};
wf=await request(`/api/workflows/${wf.id}`,{method:'PUT',body:{...wf,definition:edited}});
const diff=await request(`/api/workflows/${wf.id}/compare`,{method:'POST',body:{baselineVersion:1,input:{total:12},mocks:{}}});assert.ok(diff.changes.some(c=>c.node==='map'));assert.equal(diff.effectsChanged,true);
await request(`/api/workflows/${wf.id}/restore/1`,{method:'POST'});
const live=await finished((await execute(wf,{total:12})).id);assert.equal(live.status,'SUCCEEDED');assert.equal(step(live,'save').output.value.total,12);assert.equal(live.effect_count,1);
const approvalDef={nodes:[n('start','manual'),n('review','approval',{title:'Review smoke request'}),n('yes','transform',{output:{approved:true}}),n('no','transform',{output:{approved:false}})],edges:[edge('start','review'),edge('review','yes','true'),edge('review','no','false')]};
const approvalRun=await execute(await create('approval',approvalDef));const waiting=await until(approvalRun.id,r=>r.status==='WAITING');
assert.equal(waiting.approvals[0].status,'PENDING');await request(`/api/approvals/${waiting.approvals[0].id}/decision`,{method:'POST',body:{approved:false}});
const rejected=await finished(waiting.id);assert.equal(rejected.status,'SUCCEEDED');assert.equal(step(rejected,'no').status,'SUCCEEDED');assert.equal(step(rejected,'yes').status,'SKIPPED');
const delay=await execute(await create('delay',chain(n('wait','delay',{seconds:1}),n('done','transform',{output:{done:true}}))));assert.equal((await finished(delay.id)).status,'SUCCEEDED');
const child=await create('loop child',chain(n('write','table',{tableId:table.id,operation:'upsert',key:'{{input.item.id}}',value:'{{input.item}}'})));
const loopDef=chain(n('loop','loop',{workflowId:child.id,items:'{{input.items}}'}));loopDef.settings={effectLimit:1};
const nested=await finished((await execute(await create('shared budget',loopDef),{items:[{id:'a'},{id:'b'}]})).id);assert.equal(nested.status,'FAILED');assert.equal(nested.effect_count,1);assert.equal(nested.children.length,2);assert.equal((await request(`/api/tables/${table.id}/records`)).length,2);
const formDef={nodes:[n('start','form',{title:'Smoke form',fields:[{name:'amount',label:'Amount',type:'number',required:true}]}),n('result','transform',{output:{amount:'{{input.amount}}'}})],edges:[edge('start','result')]};
const form=await create('form',formDef,true);const formResponse=await fetch(`${base}/forms/${form.id}/${form.webhook_token}`,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'amount=12.5'});assert.equal(formResponse.status,200);assert.ok((await formResponse.text()).includes('Thank you.'));
const formRuns=await request(`/api/executions?workflowId=${form.id}`);const submitted=await finished(formRuns[0].id);assert.equal(step(submitted,'result').output.amount,12.5);
const recoveryDef={nodes:[n('start','manual'),n('fail','fail',{message:'Expected test error',onError:'route'}),n('handled','transform',{output:{message:'{{steps.fail.error.message}}'}})],edges:[edge('start','fail'),edge('fail','handled','error')]};
const recovered=await finished((await execute(await create('error recovery',recoveryDef))).id);assert.equal(recovered.status,'SUCCEEDED');assert.equal(step(recovered,'fail').status,'RECOVERED');
const duplicate=await request(`/api/workflows/${wf.id}/duplicate`,{method:'POST'});assert.equal(duplicate.version,1);
await request(`/api/workflows/${duplicate.id}/archive`,{method:'POST',body:{archived:true}});await request(`/api/workflows/${duplicate.id}/execute`,{method:'POST',body:{},expected:400});assert.ok((await request('/api/workflows?archived=true')).some(w=>w.id===duplicate.id));
assert.ok((await request('/api/executions?status=FAILED&search=shared')).some(r=>r.id===nested.id));
await request('/api/auth/logout',{method:'POST'});
console.log('PASS: tables CRUD, preview isolation, same-input version diff, restore, live writes, rejected approval routing, durable delay, child loops, root budget, typed public forms, error recovery, duplicate/archive and execution filters.');
