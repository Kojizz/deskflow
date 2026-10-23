import {useEffect,useState} from 'react';
import {Github,CheckCircle2} from 'lucide-react';
import {api} from './api';

export function oauthMessage(){
 const params=new URLSearchParams(window.location.search);
 const messages:Record<string,string>={account_exists:'Continue with your provider again to connect it to your existing workspace.',identity_in_use:'This provider account is already connected to another workspace.',provider_already_linked:'A different account from this provider is already connected.',verified_email_required:'This provider must supply a verified email address.',link_expired:'Your connection request expired. Continue with your provider again.',confirmation_account_mismatch:'That provider account belongs to a different workspace. Try again with your existing sign-in account.',unavailable:'This sign-in option is temporarily unavailable.',failed:'Sign-in did not complete. Please try again.'};
 return params.has('oauth_error')?(messages[params.get('oauth_error')!]??messages.failed):'';
}
export default function SocialAuth({link=false,intent='login'}:{link?:boolean;intent?:'login'|'signup'}){
 const [providers,setProviders]=useState<Record<string,boolean>|null>(null);
 const [connections,setConnections]=useState<string[]>([]);
 const [error,setError]=useState('');
 useEffect(()=>{let active=true;Promise.all([api<Record<string,boolean>>('/auth/oauth2/providers'),link?api<string[]>('/auth/oauth2/connections'):Promise.resolve([])]).then(([p,c])=>{if(active){setProviders(p);setConnections(c);}}).catch(()=>{if(active)setError('Sign-in connections could not be loaded. Please try again.');});return()=>{active=false;};},[link]);
 return <div className="social-auth">{link&&<p className="help">Connect a provider to sign in to this same private workspace.</p>}{['google','github'].map(provider=>{
  const name=provider==='google'?'Google':'GitHub',connected=connections.includes(provider),ready=providers?.[provider];
  return <button key={provider} className="btn full social-auth-button" type="button" disabled={!ready||connected} title={providers&&!ready?`${name} sign-in is temporarily unavailable`:undefined} onClick={()=>{window.location.assign(`/api/auth/oauth2/start/${provider}?intent=${intent}${link?'&link=true':''}`);}}>{provider==='github'?<Github size={18}/>:<span className="google-letter" aria-hidden="true">G</span>}{connected?`${name} connected`:`${link?'Connect':'Continue with'} ${name}`}{connected&&<CheckCircle2 size={16}/>}</button>;
 })}{error&&<p className="error-banner" role="alert">{error}</p>}{!link&&<div className="social-auth-divider"><span>or use your email</span></div>}</div>;
}
