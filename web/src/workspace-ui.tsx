import {useEffect,useRef,useState,type FormEvent} from 'react';
import {NavLink,useNavigate} from 'react-router-dom';
import {ArrowUpRight,Search,Users,BedDouble,ClipboardList,Check,CalendarDays,ArrowRight,HeartPulse} from 'lucide-react';
import {useCareData} from './care-ui';
import {Operations} from './operations';
import type {User} from './api';

export function PatientFinder(){
 const navigate=useNavigate(),input=useRef<HTMLInputElement>(null);const [query,setQuery]=useState('');
 useEffect(()=>{const key=(e:KeyboardEvent)=>{if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();input.current?.focus()}};window.addEventListener('keydown',key);return()=>window.removeEventListener('keydown',key)},[]);
 function search(e:FormEvent){e.preventDefault();navigate('/patients?q='+encodeURIComponent(query.trim()))}
 return <form className="global-search" onSubmit={search} role="search"><Search size={17}/><input ref={input} value={query} onChange={e=>setQuery(e.target.value)} aria-label="Знайти пацієнта у центрі" placeholder="ПІБ, номер або палата" maxLength={200}/><button type="submit" aria-label="Знайти пацієнта"><ArrowRight size={16}/></button><kbd>Ctrl K</kbd></form>
}
export function SidebarNavigation({user,items}:{user:User;items:any[]}){
 const groups=[{title:'РОБОТА З ПАЦІЄНТАМИ',paths:['/','/patients','/tasks','/pool','/handovers','/messages']},{title:'ЦЕНТР',paths:['/rooms','/cabinets','/schedule','/archive']},{title:'КЕРУВАННЯ',paths:['/users','/sessions','/audit']}];
 return <nav aria-label="Основна навігація">{groups.map(group=>{
 const visible=group.paths.map(path=>items.find(n=>n.to===path)).filter(n=>n&&user.permissions.includes(n.p)&&(n.to!=='/messages'||user.role==='ADMIN')&&(n.to!=='/schedule'||!user.permissions.includes('appointments.manage')));
 return visible.length?<div className="nav-group" key={group.title}><p className="nav-label">{group.title}</p>{visible.map(n=><NavLink key={n.to} to={n.to} end={n.to==='/'}><n.icon size={18}/><span>{n.to==='/'?'Робочий стіл':n.name}</span></NavLink>)}</div>:null
 })}</nav>
}
export function WorkspaceDashboard({user}:{user:User}){
 const {data:d,error}=useCareData('/dashboard');const worker=['NURSE','THERAPIST'].includes(user.role);
 const links=[{to:'/patients',p:'patients.read',label:'Картки пацієнтів',note:'Знайти, переглянути, призначити',Icon:Users},{to:worker?'/pool':'/tasks',p:worker?'tasks.work':'tasks.read',label:worker?'Завдання зміни':'Призначення',note:'План лікування та виконання',Icon:ClipboardList},{to:user.permissions.includes('appointments.manage')?'/cabinets':'/schedule',p:'appointments.read',label:'Розклад процедур',note:'Час, кабінети та спеціалісти',Icon:CalendarDays}].filter(l=>user.permissions.includes(l.p));
 const stats=worker?[{label:'Вільні завдання',value:d?.open,Icon:ClipboardList,to:'/pool',note:'Доступні для вашої ролі'},{label:'У моїй роботі',value:d?.mine,Icon:HeartPulse,to:'/pool',note:'Прийняті вами завдання'},{label:'Виконано сьогодні',value:d?.completed,Icon:Check,to:'/pool',note:'Ваші завершені завдання'}]:[{label:'Пацієнтів у центрі',value:d?.active,Icon:Users,to:'/patients',note:'Активні госпіталізації'},{label:'Зайняті ліжка',value:d?`${d.occupied} / ${d.beds}`:null,Icon:BedDouble,to:'/rooms',note:'Поточне розміщення'},{label:'Завдань у пулі',value:d?.open,Icon:ClipboardList,to:'/tasks',note:'Очікують виконання'},{label:'Виконано сьогодні',value:d?.completed,Icon:Check,to:'/tasks',note:'Результати роботи команди'}];
 return <>
 <div className="desk-heading"><div><p className="eyebrow">ВАШ ЦЕНТР · ВАША КОМАНДА</p><h1>Робочий стіл</h1><p className="muted">Вітаємо, {user.name}. Усе важливе для роботи — поруч.</p></div><div className="desk-date"><CalendarDays size={20}/><div><strong>{new Date().toLocaleDateString('uk-UA',{day:'numeric',month:'long'})}</strong><small>{new Date().toLocaleDateString('uk-UA',{weekday:'long'})}</small></div></div></div>
 <section className="desk-welcome"><div className="welcome-copy"><span className="welcome-label"><span/>REHAFLOW · ПРОСТІР ТУРБОТИ</span><h2>Більше уваги людині.<br/>Менше зайвих дій.</h2><p>Пацієнти, лікування та робота команди<br className="desktop-break"/> в одному зручному просторі.</p>{links[0]&&<NavLink to={links[0].to} className="welcome-action">{links[0].label}<ArrowUpRight size={18}/></NavLink>}</div><div className="welcome-art" aria-hidden="true"><div className="orbit orbit-one"/><div className="orbit orbit-two"/><div className="orbit-core"><HeartPulse size={70} strokeWidth={1.2}/></div><span className="art-label">ТУРБОТА В КОЖНІЙ ДІЇ</span></div><span className="hero-index" aria-hidden="true">01 / РОБОЧИЙ ДЕНЬ</span></section>
 {error&&<p className="error" role="alert">{error}</p>}
 <div className={'stats desk-stats '+(worker?'three':'')}>{stats.map(({label,value,Icon,to,note},i)=>{const allowed=user.permissions.includes(to==='/rooms'?'rooms.read':to==='/patients'?'patients.read':worker?'tasks.work':'tasks.read');const content=<><div><span>{label}</span><Icon size={19}/></div><strong>{value??'—'}</strong><small>{note}</small><span className="stat-index">0{i+1}</span></>;return allowed?<NavLink className="stat" key={label} to={to}>{content}</NavLink>:<div className="stat" key={label}>{content}</div>})}</div>
 <div className="section-heading"><div><p className="eyebrow">РОБОЧІ ПРОЦЕСИ</p><h2>Сьогодні у центрі</h2></div><span className="section-note">Дані оновлюються автоматично</span></div>
 <Operations user={user}/>
 <div className="quick-grid desk-shortcuts">{links.map(({to,label,note,Icon})=><NavLink to={to} className="quick" key={to}><Icon/><div><h3>{label}</h3><p>{note}</p></div><ArrowUpRight size={18}/></NavLink>)}</div>
 </>
}
