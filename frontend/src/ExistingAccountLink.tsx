import {useState,type FormEvent} from 'react';
import {ArrowRight,ShieldCheck} from 'lucide-react';
import {api,post} from './api';
import {Spinner} from './components';
import type {User} from './model';

export type PendingLink={active:boolean;provider:string;email:string;hasPassword:boolean;alreadySignedIn:boolean;existingProviders:string[]};
export default function ExistingAccountLink({pending,onLogin,onCancel}:{pending:PendingLink;onLogin:(user:User)=>void;onCancel:()=>void}){
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[sent,setSent]=useState(false);
 const name=pending.provider==='github'?'GitHub':'Google';
 const emailConfirmation=pending.provider==='google'&&!pending.alreadySignedIn;
 async function confirm(e:FormEvent<HTMLFormElement>){
  e.preventDefault();setBusy(true);setError('');
  const password=new FormData(e.currentTarget).get('password')??'';
  try{if(emailConfirmation){await post('/auth/oauth2/email-link');setSent(true);return;}const user=await post<User>('/auth/oauth2/confirm-link',{password});const url=new URL(window.location.href);url.searchParams.delete('oauth_link');url.searchParams.delete('oauth_error');window.history.replaceState(null,'',url.pathname+url.search+url.hash);onLogin(user);}
  catch(e){setError((e as Error).message);}finally{setBusy(false);}
 }
 async function cancel(){setBusy(true);setError('');try{await post('/auth/oauth2/cancel-link');onCancel();}catch(e){setError((e as Error).message);}finally{setBusy(false);}}
 return <form id="workspace-form" onSubmit={confirm}>
  <span className="eyebrow">ONE LAST STEP</span><h2>Connect {name}.</h2>
  <p>You already have a DeskFlow workspace for <strong>{pending.email}</strong>.</p>
  <div className="social-link-note"><ShieldCheck size={18} aria-hidden="true"/><p>{pending.alreadySignedIn?'Confirm to use this provider with your current workspace.':emailConfirmation?'Google uses an external email address for this account. Confirm your email once to connect it. No DeskFlow password is needed.':pending.hasPassword?`Enter your DeskFlow password once to connect ${name}. Next time, just choose Continue with ${name}.`:'Sign in with a provider already connected to this workspace to confirm the connection.'}</p></div>
  {pending.hasPassword&&!pending.alreadySignedIn&&!emailConfirmation&&<label className="field">Your DeskFlow password<input name="password" type="password" minLength={10} maxLength={72} autoComplete="current-password" required autoFocus/></label>}
  {sent&&<p role="status" className="recovery-notice">Check your email for a confirmation link. It expires in 10 minutes.</p>}
  {error&&<p className="error-banner" role="alert">{error}</p>}
  {(pending.hasPassword||pending.alreadySignedIn||emailConfirmation)&&<button className="btn primary full" disabled={busy||sent}>{busy?<Spinner/>:<>{emailConfirmation?(sent?'Confirmation link requested':'Send confirmation link'):`Connect ${name} and sign in`}<ArrowRight size={17}/></>}</button>}
  {pending.existingProviders.map(provider=><a className="btn full social-auth-button" key={provider} href={`/api/auth/oauth2/start/${provider}?confirm=true`}>Confirm with {provider==='github'?'GitHub':'Google'}</a>)}
  <p className="help">Your existing workflows and data stay in the same workspace.</p>
  <button className="btn full" type="button" disabled={busy} onClick={()=>void cancel()}>Use another sign-in method</button>
 </form>;
}

export const loadPendingLink=()=>api<PendingLink>('/auth/oauth2/pending-link');
