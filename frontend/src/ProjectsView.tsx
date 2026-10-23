import {useEffect, useState, type FormEvent} from 'react';
import {api, post} from './api';
import {JsonField, Badge} from './components';

export type ProjectInstance = {id:string; project_id:string; name:string; status:string; state_fingerprint?:string};
export async function readyInstances():Promise<ProjectInstance[]> {
 const status = await api<{enabled:boolean}>('/projects/status');
 if (!status.enabled) return [];
 const projects = await api<{id:string}[]>('/projects');
 const rows = await Promise.all(projects.map(p => api<ProjectInstance[]>(`/projects/${p.id}/instances`)));
 return rows.flat().filter(i => i.status === 'READY');
}
export function InstancePicker({value, onChange}:{value:string; onChange:(v:string)=>void}) {
 const [instances, setInstances] = useState<ProjectInstance[]>([]);
 const [error, setError] = useState('');
 useEffect(() => {readyInstances().then(setInstances).catch(e => setError(e.message));}, []);
 return <label className="field">Project instance
  <select value={value} onChange={e => onChange(e.target.value)}>
   <option value="">Workflow default / no project</option>
   {instances.map(i => <option key={i.id} value={i.id}>{i.name} · {i.state_fingerprint?.slice(0,12)}</option>)}
  </select>
  {error && <span role="alert">{error}</span>}
 </label>;
}
export default function ProjectsView() {
 const [projects, setProjects] = useState<any[]>([]);
 const [instances, setInstances] = useState<ProjectInstance[]>([]);
 const [selected, setSelected] = useState('');
 const [enabled, setEnabled] = useState(false);
 const [error, setError] = useState('');
 const [commands, setCommands] = useState<unknown>([]);
 const [valid, setValid] = useState(true);
 const [busy, setBusy] = useState(false);
 async function load() {
  const state = await api<{enabled:boolean}>('/projects/status');
  setEnabled(state.enabled);
  if (state.enabled) setProjects(await api('/projects'));
 }
 useEffect(() => {load().catch(e => setError(e.message));}, []);
 useEffect(() => {
  if (selected) api<ProjectInstance[]>(`/projects/${selected}/instances`).then(setInstances).catch(e => setError(e.message));
 }, [selected]);
 async function action(task:()=>Promise<unknown>) {
  setBusy(true);
  setError('');
  try {
   await task();
   await load();
   if (selected) setInstances(await api(`/projects/${selected}/instances`));
  } catch(e) {setError((e as Error).message);}
  finally {setBusy(false);}
 }
 function create(e:FormEvent<HTMLFormElement>) {
  e.preventDefault();
  const body = Object.fromEntries(new FormData(e.currentTarget));
  void action(() => post('/projects', {...body, allowedCommands:commands}));
 }
 function instance(e:FormEvent<HTMLFormElement>) {
  e.preventDefault();
  const body = Object.fromEntries(new FormData(e.currentTarget));
  void action(() => post(`/projects/${selected}/instances`, body));
 }
 return <section>
  {error && <p role="alert" className="error-banner">{error}</p>}
  {!enabled ? <p>Projects are disabled. The operator must enable project execution and configure allowed roots.</p> : <>
   <form onSubmit={create} className="record-grid">
    <label className="field">Project name<input name="name" required maxLength={100}/></label>
    <label className="field">Source folder<input name="rootPath" required autoComplete="off"/></label>
    <label className="field">Kind<select name="kind"><option value="git">Git worktrees</option><option value="plain">Plain snapshots</option></select></label>
    <JsonField label="Allowed command argv arrays" value={commands} onChange={setCommands} onValidity={setValid}/>
    <button className="btn primary" disabled={busy || !valid}>Create project</button>
   </form>
   <label className="field">Project<select value={selected} onChange={e => setSelected(e.target.value)}><option value="">Select a project</option>{projects.filter(p => !p.archived).map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
   {selected && <>
    <form onSubmit={instance} className="workbench-toolbar">
     <label className="field">Instance name<input name="name" required maxLength={100}/></label>
     <label className="field">Ref<input name="ref" defaultValue="HEAD" required/></label>
     <button className="btn primary" disabled={busy}>Create instance</button>
    </form>
    {instances.map(i => <article key={i.id} className="data-table-card">
     <h3>{i.name}</h3><Badge status={i.status}/><p><code>{i.state_fingerprint}</code></p>
     <button className="btn" disabled={busy || i.status !== 'READY'} onClick={() => void action(() => post(`/projects/${selected}/instances/${i.id}/fork`, {name:`${i.name} fork`, ref:'HEAD'}))}>Fork</button>
     <button className="btn" disabled={busy || i.status !== 'READY'} onClick={() => {const ref = prompt('Reset instance to ref (discards changes)', 'HEAD'); if (ref) void action(() => post(`/projects/${selected}/instances/${i.id}/refresh`, {ref}));}}>Refresh</button>
     <button className="btn" disabled={busy || i.status !== 'READY'} onClick={() => void action(() => post(`/projects/${selected}/instances/${i.id}/archive`))}>Archive</button>
    </article>)}
   </>}
  </>}
 </section>;
}
