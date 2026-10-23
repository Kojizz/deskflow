import {useState,type FormEvent} from 'react';
import {ArrowLeft,ArrowRight,CheckCircle2,Mail} from 'lucide-react';
import {post} from './api';
import {Spinner} from './components';
import type {User} from './model';

export type RecoveryMode='forgot'|'reset'|'confirm-email';
export function recoveryMode(search:string):RecoveryMode|null{
 const mode=new URLSearchParams(search).get('auth');
 return mode==='forgot'||mode==='reset'||mode==='confirm-email'?mode:null;
}
export default function AccountRecovery({mode,token,onBack,onLogin,onReset}:{mode:RecoveryMode;token:string;onBack:()=>void;onLogin:(u:User)=>void;onReset:()=>void}){
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[done,setDone]=useState(false);
 const hasToken=/^[A-Za-z0-9_-]{43}$/.test(token);
 async function submit(e:FormEvent<HTMLFormElement>){
  e.preventDefault();setError('');
  const fields=Object.fromEntries(new FormData(e.currentTarget));
  if(mode==='reset'&&fields.password!==fields.confirmPassword){setError('Your passwords do not match.');return;}
  setBusy(true);
  try{
   if(mode==='forgot'){await post('/auth/forgot-password',{email:fields.email});setDone(true);}
   else if(mode==='reset'){await post('/auth/reset-password',{token,password:fields.password});setDone(true);window.history.replaceState(null,'','/?auth=signin#get-started');}
   else {const user=await post<User>('/auth/oauth2/confirm-email',{token});window.history.replaceState(null,'','/');onLogin(user);}
  }catch(e){setError((e as Error).message);}finally{setBusy(false);}
 }
 if(done)return <div id="workspace-form" className="recovery-result"><CheckCircle2 size={28} aria-hidden="true"/><span className="eyebrow">{mode==='forgot'?'CHECK YOUR INBOX':'READY WHEN YOU ARE'}</span><h2>{mode==='forgot'?'A fresh start.':'Password updated.'}</h2><p role="status">{mode==='forgot'?'If an account exists for this email, a reset link will be sent. Check your inbox and spam folder.':'Your old sessions have been signed out. Sign in with your new password, or continue with Google.'}</p>{mode==='forgot'&&<p className="help">The link expires in 30 minutes. You can request another after a minute.</p>}<button type="button" className="btn primary full" onClick={mode==='reset'?onReset:onBack}>Back to sign in<ArrowRight size={17}/></button></div>;
 return <form id="workspace-form" onSubmit={submit}>
  <span className="eyebrow">{mode==='confirm-email'?'YOUR WORKSPACE, CONNECTED':'A LITTLE HELP GETTING BACK'}</span>
  <h2>{mode==='forgot'?'Forgot your password?':mode==='reset'?'Choose a new password.':'Confirm your sign-in.'}</h2>
  <p>{mode==='forgot'?'Enter your account email. We’ll send you a link to choose a new password.':mode==='reset'?'Use at least 10 characters for your new DeskFlow password.':'Confirm your email to finish Google sign-in. No DeskFlow password is needed.'}</p>
  {mode==='forgot'?<label className="field">Email address<input name="email" type="email" placeholder="you@example.com" autoComplete="email" required maxLength={254}/></label>:!hasToken?<p role="alert" className="error-banner">This link is invalid or expired. Open the full link from your email or request a new one.</p>:mode==='reset'?<><label className="field">New password<input name="password" type="password" autoComplete="new-password" minLength={10} maxLength={72} required/></label><label className="field">Confirm new password<input name="confirmPassword" type="password" autoComplete="new-password" minLength={10} maxLength={72} required/></label></>:null}
  {error&&<p role="alert" className="error-banner">{error}</p>}
  {(mode==='forgot'||hasToken)&&<button className="btn primary full" disabled={busy}>{busy?<Spinner/>:<>{mode==='forgot'?<><Mail size={17}/>Send reset link</>:mode==='reset'?'Save new password':'Confirm and sign in'}<ArrowRight size={17}/></>}</button>}
  <button type="button" className="btn subtle full recovery-back" disabled={busy} onClick={onBack}><ArrowLeft size={15}/>Back to sign in</button>
 </form>;
}
