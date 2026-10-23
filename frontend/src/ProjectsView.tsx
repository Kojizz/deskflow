import {useEffect, useState, type FormEvent} from 'react';
import {api, post} from './api';
import {JsonField, Badge, Modal, Spinner} from './components';
import {instanceActions, type Project, type ProjectInstance, type InstanceAction} from './projectModel';
import './projects.css';

export type {ProjectInstance} from './projectModel';
export async function readyInstances():Promise<ProjectInstance[]> {
 const status = await api<{enabled:boolean}>('/projects/status');
 if (!status.enabled) return [];
 const projects = await api<Project[]>('/projects');
 const rows = await Promise.all(projects.filter(p => !p.archived).map(p => api<ProjectInstance[]>(`/projects/${p.id}/instances`)));
 return rows.flat().filter(i => i.status === 'READY' && !i.leased);
}
export function InstancePicker({value, onChange}:{value:string; onChange:(v:string)=>void}) {
 const [instances, setInstances] = useState<ProjectInstance[]>([]);
 const [error, setError] = useState('');
 useEffect(() => {
  let active = true;
  readyInstances().then(rows => {if (active) setInstances(rows);}).catch(e => {if (active) setError(e.message);});
  return () => {active = false;};
 }, []);
 return <label className="field">Project instance
  <select value={value} onChange={e => onChange(e.target.value)}>
   <option value="">Workflow default / no project</option>
   {instances.map(i => <option key={i.id} value={i.id}>{i.name} · {i.state_fingerprint?.slice(0,12)}</option>)}
  </select>
  {error && <span role="alert">{error}</span>}
 </label>;
}

type Dialog = {kind:'create'} | {kind:InstanceAction; instance:ProjectInstance} | {kind:'project-archive'} | {kind:'project-delete'};
export default function ProjectsView() {
 const [projects, setProjects] = useState<Project[]>([]);
 const [instances, setInstances] = useState<ProjectInstance[]>([]);
 const [selected, setSelected] = useState('');
 const [enabled, setEnabled] = useState<boolean|null>(null);
 const [error, setError] = useState('');
 const [commands, setCommands] = useState<unknown>([]);
 const [valid, setValid] = useState(true);
 const [busy, setBusy] = useState(false);
 const [dialog, setDialog] = useState<Dialog|null>(null);
 const selectedProject = projects.find(p => p.id === selected);
 useEffect(() => {
  if (busy) return;
  let active = true;
  let loading = false;
  async function load() {
   if (loading) return;
   loading = true;
   try {
    const state = await api<{enabled:boolean}>('/projects/status');
    const rows = state.enabled ? await api<Project[]>('/projects') : [];
    const current = rows.find(p => p.id === selected);
    const entries = current ? await api<ProjectInstance[]>(`/projects/${current.id}/instances`) : [];
    if (active) {
     setEnabled(state.enabled);
     setProjects(rows);
     setInstances(entries);
     if (selected && !current) setSelected('');
    }
   } catch(e) {
    if (active) setError((e as Error).message);
   } finally {loading = false;}
  }
  void load();
  const timer = setInterval(() => void load(), 5000);
  return () => {
   active = false;
   clearInterval(timer);
  };
 }, [selected, busy]);
 async function action(task:()=>Promise<unknown>) {
  setBusy(true);
  setError('');
  try {
   await task();
   setDialog(null);
  } catch(e) {setError((e as Error).message);}
  finally {setBusy(false);}
 }
 function createProject(e:FormEvent<HTMLFormElement>) {
  e.preventDefault();
  const body = Object.fromEntries(new FormData(e.currentTarget));
  void action(async () => {
   const project = await post<Project>('/projects', {...body, allowedCommands:commands});
   setSelected(project.id);
  });
 }
 function confirmDialog(e:FormEvent<HTMLFormElement>) {
  e.preventDefault();
  if (!dialog || !selectedProject) return;
  const body = Object.fromEntries(new FormData(e.currentTarget));
  const current = dialog;
  const base = `/projects/${selected}`;
  void action(async () => {
   if (current.kind === 'project-archive') {
    await api(base, {method:'PUT', body:JSON.stringify({name:selectedProject.name, archived:!selectedProject.archived})});
   } else if (current.kind === 'project-delete') {
    await api(base, {method:'DELETE'});
    setSelected('');
   } else if (current.kind === 'create') {
    await post(`${base}/instances`, body);
   } else {
    const endpoint = `${base}/instances/${current.instance.id}`;
    if (current.kind === 'delete') await api(endpoint, {method:'DELETE'});
    else await post(`${endpoint}/${current.kind}`, body);
   }
  });
 }
 const dialogInstance = dialog && 'instance' in dialog ? dialog.instance : null;
 const names = {create:'Create instance', fork:'Fork instance', refresh:'Refresh instance', archive:'Archive instance', delete:'Delete instance', 'project-archive':selectedProject?.archived?'Restore project':'Archive project', 'project-delete':'Delete project'};
 return <section className="projects-view" aria-label="Projects">
  {error && !dialog && <p role="alert" className="error-banner">{error}</p>}
  {enabled === null ? <p role="status"><Spinner/>Loading projects…</p> : !enabled ? <p>Projects are disabled. The operator must enable project execution and configure allowed roots.</p> : <>
   <details className="input-details" open={!projects.length}>
    <summary>Register a project</summary>
    <form onSubmit={createProject} className="project-create">
     <label className="field">Project name<input name="name" required maxLength={100}/></label>
     <label className="field">Source folder<input name="rootPath" required autoComplete="off"/></label>
     <label className="field">Kind<select name="kind"><option value="git">Git worktrees</option><option value="plain">Plain snapshots</option></select></label>
     <JsonField label="Allowed command argv arrays (exact arguments)" value={commands} onChange={setCommands} onValidity={setValid}/>
     <button className="btn primary" disabled={busy || !valid}>Create project</button>
    </form>
   </details>
   <label className="field">Project<select value={selected} disabled={busy} onChange={e => {
    setSelected(e.target.value);
    setInstances([]);
    setError('');
   }}><option value="">Select a project</option>{projects.map(p => <option key={p.id} value={p.id}>{p.name}{p.archived?' (archived)':''}</option>)}</select></label>
   {selectedProject && <>
    <div className="workbench-toolbar">
     <p>{selectedProject.name} · {selectedProject.kind === 'git'?'Git worktrees':'Plain snapshots'}</p>
     <div className="project-actions">
      <button className="btn primary" disabled={busy || selectedProject.archived} onClick={() => setDialog({kind:'create'})}>Create instance</button>
      <button className="btn" disabled={busy || instances.some(i => i.leased)} onClick={() => setDialog({kind:'project-archive'})}>{selectedProject.archived?'Restore project':'Archive project'}</button>
      <button className="btn" disabled={busy || instances.length > 0} onClick={() => setDialog({kind:'project-delete'})}>Delete project</button>
     </div>
    </div>
    {!instances.length && <p>No instances yet. Create an isolated workspace to test your workflows.</p>}
    <div className="table-cards">{instances.map(i => {
     const available = instanceActions(i);
     return <article key={i.id} className="project-instance-card">
      <header><h3>{i.name}</h3><Badge status={i.status}/></header>
      <p className="help">{i.ref || 'Plain snapshot'}</p>
      <code className="project-fingerprint">{i.state_fingerprint || 'Fingerprint unavailable'}</code>
      {i.status === 'STALE' && <p className="help">Inspect the previous run before refreshing this instance.</p>}
      {i.leased && <p role="status">An execution holds this instance.</p>}
      <div className="project-actions">{available.map(kind => <button key={kind} className="btn" disabled={busy || (selectedProject.archived && kind !== 'delete' && kind !== 'archive')} onClick={() => setDialog({kind, instance:i})}>{kind === 'refresh' && i.status === 'ARCHIVED'?'Restore':kind[0].toUpperCase()+kind.slice(1)}</button>)}</div>
     </article>;
    })}</div>
   </>}
  </>}
  {dialog && selectedProject && <Modal title={names[dialog.kind]} onClose={() => {if (!busy) setDialog(null);}}>
   <form onSubmit={confirmDialog}>
    {error && <p role="alert" className="error-banner">{error}</p>}
    {dialog.kind === 'create' || dialog.kind === 'fork' ? <label className="field">Instance name<input name="name" required maxLength={100} defaultValue={dialogInstance?`${dialogInstance.name} fork`:''}/></label> : null}
    {selectedProject.kind === 'git' && ['create','fork','refresh'].includes(dialog.kind) && <label className="field">Ref<input name="ref" required defaultValue="HEAD"/></label>}
    {dialog.kind === 'refresh' && <p className="modal-intro">Refresh discards changes in this instance. {selectedProject.kind === 'git'?'It resets to the selected ref.':'It replaces the snapshot with a fresh copy of the source folder.'}</p>}
    {dialog.kind === 'archive' && <p className="modal-intro">The instance will stop accepting runs. Its files are kept and continue to count toward your quotas.</p>}
    {dialog.kind === 'delete' && <p className="modal-intro">Delete this instance’s workspace permanently. Previous execution history will be kept.</p>}
    {dialog.kind === 'project-delete' && <p className="modal-intro">Remove this project from your workspace. Previous execution history will be kept.</p>}
    {dialog.kind === 'project-archive' && <p className="modal-intro">{selectedProject.archived?'Restore this project to create or refresh instances.':'Stop this project from accepting new runs and instances.'}</p>}
    <div className="modal-actions"><button type="button" className="btn" disabled={busy} onClick={() => setDialog(null)}>Cancel</button><button className="btn primary" disabled={busy}>{busy?<Spinner/>:names[dialog.kind]}</button></div>
   </form>
  </Modal>}
 </section>;
}
