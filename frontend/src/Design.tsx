import {useEffect,useRef,useState,useId,type ReactNode,type CSSProperties} from 'react';
import {ArrowUp,ArrowUpRight,Workflow,Check,GitBranch,Play} from 'lucide-react';

export function Brand({onClick}:{onClick?:()=>void}){
 const content=<><span className="studio-brand-mark"><Workflow size={23} strokeWidth={1.5}/></span><span>deskflow<span className="brand-period">.</span></span></>;
 return onClick?<button className="brand studio-brand" onClick={onClick} aria-label="DeskFlow overview">{content}</button>:<a className="brand studio-brand" href="#home" aria-label="DeskFlow home">{content}</a>;
}

export function Reveal({children,className='',delay=0}:{children:ReactNode;className?:string;delay?:number}){
 const element=useRef<HTMLDivElement>(null);const [visible,setVisible]=useState(false);
 useEffect(()=>{
  if(!element.current)return;
  if(!('IntersectionObserver' in window)||window.matchMedia('(prefers-reduced-motion: reduce)').matches){setVisible(true);return;}
  const observer=new IntersectionObserver(entries=>{if(entries.some(e=>e.isIntersecting)){setVisible(true);observer.disconnect();}},{threshold:.08,rootMargin:'0px 0px -24px 0px'});
  observer.observe(element.current);return()=>observer.disconnect();
 },[]);
 return <div ref={element} className={`scroll-reveal ${visible?'is-visible':''} ${className}`} style={{'--reveal-delay':`${delay}ms`} as CSSProperties}>{children}</div>;
}

export function AnimatedNumber({value}:{value:number}){
 const element=useRef<HTMLSpanElement>(null);const previous=useRef(0);const [display,setDisplay]=useState(value);
 useEffect(()=>{
  if(!element.current||!('IntersectionObserver' in window)||window.matchMedia('(prefers-reduced-motion: reduce)').matches){setDisplay(value);previous.current=value;return;}
  let frame=0;let cancelled=false;const from=previous.current;
  const observer=new IntersectionObserver(entries=>{
   if(!entries.some(e=>e.isIntersecting))return;observer.disconnect();const start=performance.now();
   const tick=(now:number)=>{if(cancelled)return;const progress=Math.min(1,(now-start)/900);setDisplay(Math.round(from+(value-from)*(1-Math.pow(1-progress,3))));if(progress<1)frame=requestAnimationFrame(tick);else previous.current=value;};
   frame=requestAnimationFrame(tick);
  },{threshold:.4});observer.observe(element.current);
  return()=>{cancelled=true;cancelAnimationFrame(frame);observer.disconnect();};
 },[value]);
 return <span ref={element} aria-label={String(value)}>{display}</span>;
}

export function ScrollProgress(){
 const element=useRef<HTMLDivElement>(null);
 useEffect(()=>{
  let frame=0;const update=()=>{cancelAnimationFrame(frame);frame=requestAnimationFrame(()=>{const max=document.documentElement.scrollHeight-innerHeight;const progress=max>0?scrollY/max:0;if(element.current)element.current.style.transform=`scaleX(${Math.min(1,Math.max(0,progress))})`;});};
  update();window.addEventListener('scroll',update,{passive:true});window.addEventListener('resize',update);
  return()=>{cancelAnimationFrame(frame);window.removeEventListener('scroll',update);window.removeEventListener('resize',update);};
 },[]);
 return <div ref={element} className="page-scroll-progress" aria-hidden="true"/>;
}

export function SignalArt({compact=false}:{compact?:boolean}){
 const id=useId().replaceAll(':','');const element=useRef<HTMLDivElement>(null);
 useEffect(()=>{
  if(window.matchMedia('(prefers-reduced-motion: reduce)').matches)return;
  let frame=0;const update=()=>{cancelAnimationFrame(frame);frame=requestAnimationFrame(()=>{if(!element.current)return;const rect=element.current.getBoundingClientRect();element.current.style.setProperty('--art-shift',`${Math.max(-22,Math.min(22,(innerHeight/2-rect.top-rect.height/2)*.045))}px`);});};
  update();window.addEventListener('scroll',update,{passive:true});return()=>{cancelAnimationFrame(frame);window.removeEventListener('scroll',update);};
 },[]);
 return <div ref={element} className={`signal-art ${compact?'compact':''}`}>
  <div className="signal-grain"/><div className="signal-bloom"/>
  <svg className="signal-lines" viewBox="0 0 1200 440" fill="none" aria-hidden="true">
   <defs>
    <linearGradient id={`${id}-trail`} x1="80" y1="220" x2="1120" y2="220" gradientUnits="userSpaceOnUse"><stop stopColor="#c6e5a1" stopOpacity="0"/><stop offset=".28" stopColor="#c6e5a1"/><stop offset=".57" stopColor="#edf6d9"/><stop offset=".8" stopColor="#82bdb0"/><stop offset="1" stopColor="#82bdb0" stopOpacity="0"/></linearGradient>
    <linearGradient id={`${id}-blue`} x1="260" y1="100" x2="1000" y2="340" gradientUnits="userSpaceOnUse"><stop stopColor="#7ea9b9" stopOpacity="0"/><stop offset=".5" stopColor="#99bdd6"/><stop offset="1" stopColor="#c6e5a1" stopOpacity="0"/></linearGradient>
    <filter id={`${id}-blur`} x="-50%" y="-100%" width="200%" height="300%"><feGaussianBlur stdDeviation="10"/></filter>
   </defs>
   <g stroke={`url(#${id}-trail)`} filter={`url(#${id}-blur)`} strokeWidth="12" opacity=".4"><path d="M-50 340 C180 340 230 100 420 100 S560 350 680 250 S840 80 1250 160"/><path d="M-50 170 C200 170 260 350 430 350 S560 90 690 175 S920 350 1250 270"/></g>
   <g className="signal-strands" stroke={`url(#${id}-trail)`}>
    {Array.from({length:13},(_,i)=><path key={i} d={`M-50 ${335+i*2} C180 ${335-i*3} 235 ${90+i*4} 420 ${95+i*3} S560 ${355-i*3} 680 ${245+i*2} S850 ${70+i*5} 1250 ${155+i*3}`} strokeWidth={i===6?1.7:.6} opacity={.15+(i%4)*.1}/>)}
    {Array.from({length:10},(_,i)=><path key={i} d={`M-50 ${165+i*3} C190 ${165-i*2} 270 ${345-i*4} 430 ${345-i*3} S560 ${85+i*3} 690 ${170+i*2} S940 ${345-i*3} 1250 ${265+i*3}`} strokeWidth=".7" opacity={.13+(i%3)*.1}/>)}
   </g>
   <ellipse cx="600" cy="225" rx="92" ry="136" stroke={`url(#${id}-blue)`} strokeWidth=".8" transform="rotate(26 600 225)"/>
   <ellipse cx="600" cy="225" rx="68" ry="118" stroke={`url(#${id}-trail)`} strokeWidth=".6" transform="rotate(-28 600 225)"/>
   <path className="signal-travel" d="M-50 347 C180 317 235 114 420 113 S560 337 680 257 S850 100 1250 173" stroke="#edf6d9" strokeWidth="2" strokeDasharray="12 1050"/>
   <path className="signal-travel second" d="M-50 180 C190 155 270 325 430 330 S560 100 690 185 S940 330 1250 280" stroke="#acd8d0" strokeWidth="2" strokeDasharray="9 1200"/>
  </svg>
  <div className="signal-caption"><span className="signal-status-dot"/>CONNECTED BY DESIGN<span>01 — 04</span></div>
  <div className="signal-node event"><span className="signal-node-icon"><Play size={15}/></span><div><small>01 / TRIGGER</small><b>Something happens</b></div></div>
  <div className="signal-center"><Workflow size={26} strokeWidth={1.2}/><span>deskflow</span></div>
  <div className="signal-node action"><span className="signal-node-icon"><Check size={15}/></span><div><small>04 / ACTION</small><b>The work moves forward</b></div></div>
  <div className="signal-foot"><span><GitBranch size={13}/>Your logic. A clear path.</span><span>ILLUSTRATIVE WORKFLOW</span></div>
 </div>;
}

type FooterDestination='overview'|'workflows'|'executions'|'credentials'|'integrations'|'tables'|'forms'|'approvals'|'impact';
const footerGroups:{title:string;links:{label:string;view:FooterDestination}[]}[]=[
 {title:'Workspace',links:[{label:'Overview',view:'overview'},{label:'Workflows',view:'workflows'},{label:'Executions',view:'executions'},{label:'Forms',view:'forms'}]},
 {title:'Connections',links:[{label:'Integrations',view:'integrations'},{label:'Credentials',view:'credentials'},{label:'Data tables',view:'tables'}]},
 {title:'Tools',links:[{label:'Approvals',view:'approvals'},{label:'Impact Lab',view:'impact'}]}
];

export function StudioFooter({onGuide,onNavigate,activeView}:{onGuide:()=>void;onNavigate:(view:FooterDestination)=>void;activeView:FooterDestination}){
 return <Reveal className="footer-reveal">
  <footer className="studio-footer workspace-footer">
    <nav className="footer-navigation" aria-label="Footer navigation">
     {footerGroups.map(group=><div className="footer-nav-group" key={group.title}>
      <h3>{group.title}</h3>
      {group.links.map(link=><button key={link.view} onClick={()=>onNavigate(link.view)} aria-current={activeView===link.view?'page':undefined}>{link.label}</button>)}
      {group.title==='Tools'&&<button onClick={onGuide}>Quick guide<ArrowUpRight size={12}/></button>}
     </div>)}
    </nav>
   <div className="footer-utility">
    <span>DESKFLOW <i/> PERSONAL WORKSPACE</span>
    <div><a href="/swagger-ui/index.html" target="_blank" rel="noreferrer">API documentation<ArrowUpRight size={13}/></a><a href="#top">Back to top<ArrowUp size={13}/></a></div>
   </div>
  </footer>
 </Reveal>;
}
