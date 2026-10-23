import {JsonField} from './components';
export default function ProjectInspector({kind, config, update, validity}:{kind:string; config:Record<string,any>; update:(k:string,v:any)=>void; validity:(v:boolean,key?:string)=>void}) {
 if (kind === 'project.command') return <>
  <JsonField label="Argument array (one value per argument)" value={config.argv} onChange={v => update('argv',v)} onValidity={validity}/>
  <label className="field">Timeout seconds<input type="number" min={1} max={60} value={config.timeoutSeconds} onChange={e => update('timeoutSeconds',Number(e.target.value))}/></label>
 </>;
 if (kind === 'project.read') return <label className="field">Relative file or folder<input value={config.path} onChange={e => update('path',e.target.value)}/></label>;
 return <>
  <label className="field">Git operation<select value={config.operation} onChange={e => update('operation',e.target.value)}>{['status','diff','log','checkout','commit'].map(o => <option key={o}>{o}</option>)}</select></label>
  {config.operation === 'checkout' && <label className="field">Ref<input value={config.ref ?? 'HEAD'} onChange={e => update('ref',e.target.value)}/></label>}
  {config.operation === 'commit' && <label className="field">Commit message<input value={config.message ?? ''} maxLength={200} onChange={e => update('message',e.target.value)}/></label>}
 </>;
}
