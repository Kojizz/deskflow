import {useState,useEffect,type ReactNode} from 'react';
import {Zap,Webhook,Clock,Braces,GitBranch,Globe,Mail,X,Check,Copy,LoaderCircle,FileInput,ListFilter,Route,Merge,CaseSensitive,List,Timer,UserCheck,Workflow,Repeat2,Database,Sparkles,OctagonX} from 'lucide-react';
import type {Kind} from './model';
export const icons={manual:Zap,webhook:Webhook,schedule:Clock,transform:Braces,condition:GitBranch,http:Globe,email:Mail,form:FileInput,filter:ListFilter,switch:Route,merge:Merge,format:CaseSensitive,collection:List,delay:Timer,approval:UserCheck,subworkflow:Workflow,loop:Repeat2,table:Database,ai:Sparkles,fail:OctagonX};
export function StepIcon({kind,size=18}:{kind:Kind;size?:number}){const Icon=icons[kind];return <Icon size={size}/>;}
export function Badge({status}:{status:string}) {return <span className={`badge ${status.toLowerCase()}`}><i/>{status.toLowerCase().replaceAll('_',' ')}</span>;}
export function Empty({icon,title,children}:{icon:ReactNode;title:string;children:ReactNode}) {return <div className="empty"><span className="empty-icon">{icon}</span><h3>{title}</h3><div>{children}</div></div>;}
export function Modal({title,children,onClose,wide=false}:{title:string;children:ReactNode;onClose:()=>void;wide?:boolean}) {return <div className="modal-backdrop" onMouseDown={e=>{if(e.target===e.currentTarget)onClose();}}><section role="dialog" aria-modal="true" aria-label={title} className={`modal ${wide?'wide':''}`}><header><h2>{title}</h2><button className="icon-btn" aria-label="Close dialog" onClick={onClose}><X size={20}/></button></header>{children}</section></div>;}
export function CopyButton({value}:{value:string}) {const [copied,setCopied]=useState(false);return <button className="icon-btn" aria-label="Copy to clipboard" onClick={async()=>{try{await navigator.clipboard.writeText(value);setCopied(true);setTimeout(()=>setCopied(false),1800);}catch{window.prompt('Copy this value',value);}}}>{copied?<Check size={16}/>:<Copy size={16}/>}</button>;}
export function Spinner(){return <LoaderCircle size={18} className="spin"/>;}
export function JsonField({value,onChange,onValidity,label='JSON'}:{value:unknown;onChange:(v:any)=>void;onValidity?:(v:boolean,key?:string)=>void;label?:string}){
 const [text,setText]=useState(()=>JSON.stringify(value,null,2));const [error,setError]=useState('');
 useEffect(()=>()=>onValidity?.(true,label),[]);
 return <label className="field">{label}<textarea aria-label={label} className={`code-input ${error?'invalid':''}`} spellCheck={false} value={text} onChange={e=>{setText(e.target.value);try{const parsed=JSON.parse(e.target.value);onChange(parsed);setError('');onValidity?.(true,label);}catch{setError('Enter valid JSON before saving.');onValidity?.(false,label);}}}/>{error&&<small className="error-text">{error}</small>}</label>;
}
