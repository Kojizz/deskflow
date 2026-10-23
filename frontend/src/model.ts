import type { Node, Edge } from '@xyflow/react';
export type Kind = 'project.command'|'project.read'|'project.git'| 'manual'|'webhook'|'schedule'|'form'|'transform'|'condition'|'filter'|'switch'|'merge'|'format'|'collection'|'delay'|'approval'|'subworkflow'|'loop'|'table'|'http'|'email'|'ai'|'fail';
export type StepData = {kind:Kind;label:string;config:Record<string,any>;[key:string]:unknown};
export type FlowNode = Node<StepData,'step'>;
export type Definition = {nodes:FlowNode[];edges:Edge[];settings?:{effectLimit?:number;allowedHosts?:string[];instanceId?:string};metadata?:{folder?:string;tags?:string[]}};
export type Workflow = {id:string;name:string;description:string;active:boolean;version:number;definition:Definition;webhook_token:string;updated_at:string;node_count?:number;last_status?:string;archived?:boolean};
export type Execution = {instance_id?:string;state_fingerprint?:string;id:string;workflow_id:string;workflow_name:string;version:number;status:string;trigger_type:string;created_at:string;started_at?:string;finished_at?:string;error?:string;cancel_requested?:boolean;input?:any;steps?:Step[];parent_id?:string;root_id?:string;effect_count?:number;effect_limit?:number;approvals?:Approval[];children?:any[];effects?:any[]};
export type Step = {node_id:string;label:string;type:string;status:string;attempts:number;output:any;error?:string;started_at?:string;finished_at?:string};
export type Credential = {id:string;name:string;created_at:string};
export type Approval = {id:string;execution_id:string;node_id:string;title:string;status:string;workflow_name?:string;created_at:string};
export type DataTable = {id:string;name:string;record_count:number;created_at:string};
export type User = {id:string;name:string;email:string};
export const catalog:Record<Kind,{name:string;description:string;color:string;config:Record<string,any>}> = {
 'project.command':{name:'Project command',description:'Run approved argv inside an instance',color:'purple',config:{argv:['/absolute/path/to/executable','--version'],timeoutSeconds:30}},
 'project.read':{name:'Read project',description:'Read an instance file or directory',color:'blue',config:{path:'README.md'}},
 'project.git':{name:'Project Git',description:'Inspect or change the instance Git state',color:'green',config:{operation:'status'}},
 manual:{name:'Manual trigger',description:'Start with your own test data',color:'green',config:{}},
 webhook:{name:'Webhook',description:'Receive an event from another app',color:'green',config:{}},
 schedule:{name:'Schedule',description:'Run at a regular interval',color:'green',config:{intervalMinutes:60,input:{}}},
 transform:{name:'Transform JSON',description:'Map data into a new shape',color:'blue',config:{output:{message:'Hello {{input.name}}'}}},
 condition:{name:'Condition',description:'Choose a path with a rule',color:'amber',config:{field:'input.total',operator:'greaterThan',value:100}},
 http:{name:'HTTP request',description:'Connect to a public API',color:'purple',config:{url:'https://httpbin.org/get',method:'GET'}},
 email:{name:'Send email',description:'Deliver an email notification',color:'pink',config:{to:'demo@example.com',subject:'A little less busywork',body:'Your workflow ran successfully.'}},
 form:{name:'Form submission',description:'Collect input through a public form',color:'green',config:{title:'Get in touch',fields:[{name:'name',label:'Your name',type:'text',required:true},{name:'email',label:'Email address',type:'email',required:true}]}},
 filter:{name:'Filter',description:'Continue only when a rule matches',color:'amber',config:{field:'input.total',operator:'greaterThan',value:100}},
 switch:{name:'Switch paths',description:'Route data across multiple named paths',color:'amber',config:{field:'input.priority',cases:[{name:'urgent',value:'high'},{name:'standard',value:'normal'}]}},
 merge:{name:'Merge branches',description:'Collect the active upstream outputs',color:'blue',config:{}},
 format:{name:'Formatter',description:'Format text, numbers, dates and JSON',color:'blue',config:{operation:'uppercase',value:'{{input.name}}'}},
 collection:{name:'Collection',description:'Filter, sort, deduplicate or aggregate a list',color:'blue',config:{operation:'count',items:'{{input.items}}'}},
 delay:{name:'Delay',description:'Pause durably and continue later',color:'amber',config:{seconds:60}},
 approval:{name:'Human approval',description:'Wait for an approve or reject decision',color:'amber',config:{title:'Review this request'}},
 subworkflow:{name:'Subworkflow',description:'Call another reusable workflow',color:'purple',config:{workflowId:'',input:'{{input}}'}},
 loop:{name:'Loop over items',description:'Run a child workflow for each item',color:'purple',config:{workflowId:'',items:'{{input.items}}'}},
 table:{name:'Data table',description:'Read or write a workspace record',color:'blue',config:{tableId:'',operation:'upsert',key:'{{input.id}}',value:'{{input}}'}},
 ai:{name:'AI text',description:'Call a compatible chat completion API',color:'purple',config:{url:'https://api.openai.com/v1/chat/completions',model:'',credentialId:'',prompt:'Summarize this: {{input.text}}'}},
 fail:{name:'Stop with error',description:'Fail explicitly with a useful message',color:'pink',config:{message:'Request could not be processed'}}
};
export const triggerKinds:Kind[]=['manual','webhook','schedule','form'];
export const isTrigger=(kind:Kind)=>triggerKinds.includes(kind);
export function outputHandles(data:StepData){const list=data.kind==='condition'||data.kind==='approval'?['true','false']:data.kind==='switch'?[...(Array.isArray(data.config.cases)?data.config.cases:[]).map((c:any)=>String(c.name)),'default']:[''];return data.config.onError==='route'?[...list,'error']:list;}
export function node(id:string,kind:Kind,label:string,x:number,y:number,config?:Record<string,any>):FlowNode {
 return {id,type:'step',position:{x,y},data:{kind,label,config:structuredClone(config??catalog[kind].config)}};
}
function edge(source:string,target:string,sourceHandle?:string):Edge {return {id:`${source}-${target}`,source,target,...(sourceHandle?{sourceHandle}:{}),type:'smoothstep'};}
export const sampleInput={name:'Ada',orderId:'ORD-1042',total:149,customer:{name:'Ada Lovelace',email:'ada@example.com'}};
export const templates=[
 {name:'Order intake',description:'Route high-value orders and notify your team.',category:'Operations',definition:{nodes:[
  node('start','webhook','Order received',60,210),
  node('format','transform','Prepare order',370,210,{output:{orderId:'{{input.orderId}}',total:'{{input.total}}',customer:'{{input.customer.name}}'}}),
  node('check','condition','High-value order?',680,210,{field:'steps.format.total',operator:'greaterThan',value:100}),
  node('notify','email','Notify the team',1000,80,{to:'team@example.com',subject:'Priority order {{steps.format.orderId}}',body:'{{steps.format.customer}} placed an order for {{steps.format.total}}.'}),
  node('standard','transform','Standard processing',1000,350,{output:{orderId:'{{steps.format.orderId}}',priority:'normal'}})
 ],edges:[edge('start','format'),edge('format','check'),edge('check','notify','true'),edge('check','standard','false')]} as Definition},
 {name:'API health check',description:'Check a public endpoint on a schedule.',category:'Monitoring',definition:{nodes:[
  node('start','schedule','Every 60 minutes',60,180,{intervalMinutes:60,input:{}}),node('request','http','Fetch endpoint',370,180),
  node('format','transform','Keep the result',680,180,{output:{status:'{{steps.request.status}}',checked:true}})
 ],edges:[edge('start','request'),edge('request','format')]} as Definition},
 {name:'Welcome message',description:'Turn an incoming name into a personal greeting.',category:'Getting started',definition:{nodes:[
  node('start','manual','Run manually',80,180),node('format','transform','Create greeting',400,180)
 ],edges:[edge('start','format')]} as Definition},
 {name:'Request approval',description:'Review a request and follow the approved or rejected path.',category:'Operations',definition:{nodes:[
  node('start','manual','New request',60,210),node('review','approval','Review request',370,210,{title:'Review order {{input.orderId}}'}),
  node('approved','transform','Approved',680,80,{output:{orderId:'{{input.orderId}}',approved:true}}),node('rejected','transform','Rejected',680,350,{output:{orderId:'{{input.orderId}}',approved:false}})
 ],edges:[edge('start','review'),edge('review','approved','true'),edge('review','rejected','false')]} as Definition},
 {name:'Contact form',description:'Collect contact details, format the name, and prepare a record.',category:'Forms',definition:{nodes:[
  node('start','form','Contact submitted',60,180),node('name','format','Normalize name',370,180,{operation:'trim',value:'{{input.name}}'}),node('record','transform','Prepare contact',680,180,{output:{name:'{{steps.name.value}}',email:'{{input.email}}'}})
 ],edges:[edge('start','name'),edge('name','record')]} as Definition},
 {name:'Recovery path',description:'See how an error becomes a handled result.',category:'Reliability',definition:{nodes:[
  node('start','manual','Run scenario',60,180),node('parse','format','Parse incoming JSON',370,180,{operation:'jsonParse',value:'{{input.name}}',onError:'route'}),node('recover','transform','Handle parsing error',680,350,{output:{handled:true,reason:'{{steps.parse.error.message}}'}}),node('valid','transform','Valid JSON',680,80,{output:{value:'{{steps.parse.value}}'}})
 ],edges:[edge('start','parse'),edge('parse','recover','error'),edge('parse','valid')]} as Definition}
];
export function validateGraph(definition:Definition):string|null {
 if(!definition||!Array.isArray(definition.nodes)||!Array.isArray(definition.edges))return 'Workflow needs nodes and edges arrays.';
 const {nodes,edges}=definition;
 if(!nodes.length)return 'Add a trigger to get started.';
 if(nodes.length>60||edges.length>180)return 'Use at most 60 steps and 180 connections.';
 if(nodes.some(n=>!n?.data||!catalog[n.data.kind]||!n.data.config||Array.isArray(n.data.config)||typeof n.data.config!=='object'||!n.data.label))return 'A step has an unsupported type or invalid configuration.';
 if(nodes.some(n=>!n.id||!/^[A-Za-z0-9_-]{1,64}$/.test(n.id)))return 'Step IDs must be unique identifiers.';
 if(nodes.filter(n=>isTrigger(n.data.kind)).length!==1)return 'Use exactly one trigger.';
 const ids=new Set(nodes.map(n=>n.id));
 if(ids.size!==nodes.length)return 'Node IDs must be unique.';
 if(edges.some(e=>!ids.has(e.source)||!ids.has(e.target)))return 'A connection references a missing step.';
 if(edges.some(e=>!outputHandles(nodes.find(n=>n.id===e.source)!.data).includes(e.sourceHandle??'')))return 'A connection uses an output path that no longer exists.';
 const done=new Set<string>();
 while(done.size<nodes.length){const ready=nodes.filter(n=>!done.has(n.id)&&edges.filter(e=>e.target===n.id).every(e=>done.has(e.source)));if(!ready.length)return 'Connections must not form a cycle.';ready.forEach(n=>done.add(n.id));}
 const root=nodes.find(n=>isTrigger(n.data.kind))!;
 if(edges.some(e=>e.target===root.id))return 'Triggers cannot have incoming connections.';
 const reached=new Set([root.id]);for(let i=0;i<nodes.length;i++)edges.forEach(e=>{if(reached.has(e.source))reached.add(e.target);});
 return reached.size===nodes.length?null:'Connect every step to the trigger.';
}
export function relative(value:string) {const minutes=Math.max(0,Math.floor((Date.now()-new Date(value).getTime())/60000));return minutes<1?'Just now':minutes<60?`${minutes}m ago`:minutes<1440?`${Math.floor(minutes/60)}h ago`:new Date(value).toLocaleDateString();}
export function duration(run:Execution) {if(!run.started_at)return '—';const ms=new Date(run.finished_at??Date.now()).getTime()-new Date(run.started_at).getTime();return ms<1000?`${ms} ms`:`${(ms/1000).toFixed(1)} s`;}
