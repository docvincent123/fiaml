let latestPatientId='';
const patientDetail=/\/api\/patients\/([0-9a-f-]{36})(?:\?.*)?$/i;
const originalFetch=window.fetch.bind(window);
window.fetch=async(input,init)=>{
  const response=await originalFetch(input,init);
  try{
    const raw=typeof input==='string'?input:input instanceof URL?input.href:input.url;
    const url=new URL(raw,location.href);
    const match=url.pathname.match(patientDetail);
    if(match&&response.ok&&(!init?.method||init.method.toUpperCase()==='GET'))latestPatientId=match[1];
  }catch{ /* tracking must never affect API traffic */ }
  return response;
};

const htmlChars={'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'};
const esc=value=>String(value??'—').replace(/[&<>"']/g,ch=>htmlChars[ch]||ch);
const multi=value=>esc(value).replace(/\r?\n/g,'<br>');
const dateOnly=value=>value?new Date(String(value)).toLocaleDateString('uk-UA'):'—';
const dateTime=value=>value?new Date(String(value)).toLocaleString('uk-UA'):'—';

function fallbackPatientId(){
  const resources=performance.getEntriesByType('resource');
  for(let i=resources.length-1;i>=0;i--){
    try{const match=new URL(resources[i].name,location.href).pathname.match(patientDetail);if(match)return match[1];}catch{}
  }
  return '';
}
function section(title,body){return `<section class="rf-ds-section"><h2>${esc(title)}</h2>${body}</section>`;}
function field(label,value){return `<div class="rf-ds-field"><b>${esc(label)}:</b> <span>${multi(value)}</span></div>`;}

function buildSheet(data){
  const p=data.patient||{},a=data.admission||{};
  const entries=Array.isArray(data.entries)?data.entries:[];
  const assessment=entries.find(e=>e.kind==='ASSESSMENT');
  const discharge=entries.find(e=>e.kind==='DISCHARGE');
  const rehab=entries.filter(e=>e.kind==='REHAB').slice().reverse();
  const performed=Array.isArray(data.performed)?data.performed:[];
  const appointments=Array.isArray(data.appointments)?data.appointments:[];
  const treatmentItems=performed.map(t=>`<li><b>${esc(t.task_type||'Призначення')}</b> · ${dateTime(t.completed_at)}${t.description?`<br>${multi(t.description)}`:''}${t.medication?`<br>Препарат: ${esc(t.medication)} ${esc(t.dose)} ${esc(t.dose_unit)} · ${esc(t.route)}`:''}${t.outcome?`<br>Результат: ${multi(t.outcome)}`:''}${t.executor?`<br><small>Виконав: ${esc(t.executor)}</small>`:''}</li>`).join('');
  const appointmentItems=appointments.map(item=>`<li><b>${esc(item.cabinet_name||'Процедура')}</b> · ${dateTime(item.starts_at)}${item.staff_name?` · ${esc(item.staff_name)}`:''}</li>`).join('');
  const rehabItems=rehab.map(e=>`<article class="rf-ds-event"><b>${dateTime(e.created_at)} · ${esc(e.author||'Спеціаліст')}</b>${e.data?.goals?`<p><b>Цілі:</b> ${multi(e.data.goals)}</p>`:''}${e.data?.assessment?`<p><b>Оцінювання:</b> ${multi(e.data.assessment)}</p>`:''}${e.data?.result?`<p><b>Результат:</b> ${multi(e.data.result)}</p>`:''}${e.data?.next_plan?`<p><b>Подальший план:</b> ${multi(e.data.next_plan)}</p>`:''}${e.body?`<p>${multi(e.body)}</p>`:''}</article>`).join('');
  const period=`${dateTime(a.admitted_at)} — ${a.discharged_at?dateTime(a.discharged_at):'триває'}`;
  const title=a.discharged_at?'Виписка з медичної картки':'Проєкт виписки з медичної картки';
  return `<section class="rehaflow-discharge-sheet" aria-label="${esc(title)}">
    <header class="rf-ds-brand"><span class="rf-ds-logo">Q</span><div><strong>RehaFlow</strong><small>QUREMED INDUSTRIES</small></div><div class="rf-ds-docno">Сформовано ${dateTime(data.generated_at)}</div></header>
    <div class="rf-ds-title"><p>РЕАБІЛІТАЦІЙНИЙ ЦЕНТР</p><h1>${esc(title)}</h1></div>
    ${section('Пацієнт',`${field('ПІБ',p.name)}${field('Дата народження',dateOnly(p.birth_date))}${field('Телефон',p.phone||'—')}${field('Адреса',p.address||'—')}${field('Контакт близької людини',p.emergency_contact||'—')}`)}
    ${section('Госпіталізація',`${field('Період',period)}${field('Вид лікування',a.care_type==='OUTPATIENT'?'Амбулаторно':'Стаціонар')}${field('Палата / ліжко',a.care_type==='OUTPATIENT'?'Без ліжка':`${a.room_number||'—'} / ${a.bed_number||'—'}`)}${field('Лікуючий лікар',a.doctor_name?`${a.doctor_name}${a.doctor_specialty?' · '+a.doctor_specialty:''}`:'Не призначено')}${field('Направлення / звідки надійшов',a.referral||'—')}`)}
    ${section('Клінічний підсумок',`${field('Діагноз',assessment?.data?.diagnosis||'Не вказано у поточній госпіталізації')}${field('Алергії',assessment?.data?.allergies||'Не вказано')}${field('План лікування',assessment?.data?.plan||'—')}`)}
    ${section('Проведене лікування, процедури та виконані призначення',treatmentItems?`<ol class="rf-ds-list">${treatmentItems}</ol>`:'<p>Виконаних призначень у системі не зафіксовано.</p>')}
    ${appointments.length?section('Завершені процедури / кабінети',`<ul class="rf-ds-list">${appointmentItems}</ul>`):''}
    ${rehab.length?section('Реабілітація та динаміка',rehabItems):''}
    ${section('Стан при виписці та підсумок лікування',discharge?.body?`<p>${multi(discharge.body)}</p>`:'<p class="rf-ds-warning">Виписний підсумок лікарем ще не заповнений.</p>')}
    ${section('Рекомендації після виписки',`${field('Рекомендації',discharge?.data?.recommendations||'Не вказано')}${field('Подальше спостереження',discharge?.data?.follow_up||'Не вказано')}`)}
    <div class="rf-ds-sign"><div>Лікуючий лікар: <b>${esc(a.doctor_name||'________________')}</b></div><div>Підпис: ____________________</div></div>
    <footer>RehaFlow · QureMed Industries · документ сформовано з даних поточної госпіталізації</footer>
  </section>`;
}

function installStyles(){
  if(document.getElementById('rehaflow-discharge-style'))return;
  const style=document.createElement('style');style.id='rehaflow-discharge-style';style.textContent=`
    .rehaflow-discharge-sheet{display:none}
    .discharge-print-button{white-space:nowrap}
    @media print{
      @page{size:A4;margin:13mm}
      body.printing-discharge{background:#fff!important;color:#111!important}
      body.printing-discharge>*:not(.rehaflow-discharge-sheet){display:none!important}
      body.printing-discharge>.rehaflow-discharge-sheet{display:block!important;position:static!important;width:auto!important;max-width:none!important;margin:0!important;padding:0!important;background:#fff!important;color:#111!important;font:11.5pt/1.42 "Segoe UI",Arial,sans-serif}
      .rehaflow-discharge-sheet *{box-sizing:border-box;color:#111!important}
      .rf-ds-brand{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:10px;border-bottom:2px solid #111;padding-bottom:9px;margin-bottom:18px}
      .rf-ds-brand strong{display:block;font-size:17pt}.rf-ds-brand small{display:block;font-size:7.5pt;letter-spacing:1.4px}.rf-ds-docno{font-size:8.5pt;text-align:right}
      .rf-ds-logo{display:grid;place-items:center;width:36px;height:36px;border:2px solid #111;border-radius:10px;font-size:20pt;font-weight:800}
      .rf-ds-title{text-align:center;margin:8px 0 18px}.rf-ds-title p{font-size:8pt;letter-spacing:1.6px;margin:0}.rf-ds-title h1{font-size:19pt;margin:4px 0}
      .rf-ds-section{border:1px solid #9a9a9a;border-radius:7px;padding:9px 11px;margin:0 0 10px;break-inside:avoid}.rf-ds-section h2{font-size:11.5pt;margin:0 0 7px}.rf-ds-field{margin:3px 0}.rf-ds-list{margin:4px 0 0;padding-left:20px}.rf-ds-list li{margin:0 0 7px}.rf-ds-event{border-top:1px solid #ccc;padding:7px 0}.rf-ds-event:first-child{border-top:0}.rf-ds-event p{margin:3px 0}.rf-ds-warning{font-weight:700;border:1px dashed #555;padding:8px}.rf-ds-sign{display:flex;justify-content:space-between;gap:20px;margin-top:24px;padding-top:12px;border-top:1px solid #555}.rehaflow-discharge-sheet footer{margin-top:18px;text-align:center;font-size:8pt;border-top:1px solid #aaa;padding-top:7px}
    }
  `;document.head.appendChild(style);
}

async function printDischarge(button){
  const patientId=latestPatientId||fallbackPatientId();
  if(!patientId){alert('Не вдалося визначити картку пацієнта. Закрийте її та відкрийте ще раз.');return;}
  const token=sessionStorage.getItem('quremed-token')||'';
  button.disabled=true;const old=button.textContent;button.textContent='Формуємо виписку…';
  try{
    const response=await originalFetch(`/api/patients/${patientId}/discharge-sheet`,{headers:{Authorization:`Bearer ${token}`},cache:'no-store'});
    const data=await response.json();if(!response.ok)throw new Error(data.message||'Не вдалося сформувати виписку');
    document.querySelector('.rehaflow-discharge-sheet')?.remove();
    const host=document.createElement('div');host.innerHTML=buildSheet(data);const sheet=host.firstElementChild;if(!sheet)throw new Error('Не вдалося підготувати документ');document.body.appendChild(sheet);
    document.body.classList.add('printing-discharge');
    const cleanup=()=>{document.body.classList.remove('printing-discharge');sheet.remove();};
    window.addEventListener('afterprint',cleanup,{once:true});
    requestAnimationFrame(()=>window.print());
    window.setTimeout(()=>{if(document.body.classList.contains('printing-discharge'))cleanup()},60000);
  }catch(error){alert(error instanceof Error?error.message:'Не вдалося сформувати виписку');}
  finally{button.disabled=false;button.textContent=old;}
}

function enhance(){
  installStyles();
  document.querySelectorAll('dialog.care-dialog[open]').forEach(dialog=>{
    const identity=dialog.querySelector('.patient-identity');const tabs=dialog.querySelector('.care-tabs');if(!identity||!tabs)return;
    if(!tabs.querySelector('.discharge-print-button')){
      const button=document.createElement('button');button.type='button';button.className='ghost no-print discharge-print-button';button.textContent='Друк виписки';button.addEventListener('click',()=>void printDischarge(button));tabs.appendChild(button);
    }
    dialog.querySelectorAll('section.panel.padded h3').forEach(title=>{
      if(title.textContent?.trim()!=='Виписний підсумок')return;
      title.closest('section')?.querySelectorAll('.field > span').forEach(label=>{
        if(label.textContent?.trim()==='Підсумок / примітка')label.textContent='Проведене лікування, процедури, реабілітація, динаміка та стан при виписці';
      });
    });
  });
}

const observer=new MutationObserver(()=>enhance());
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',()=>{enhance();observer.observe(document.body,{subtree:true,childList:true});},{once:true});
else{enhance();observer.observe(document.body,{subtree:true,childList:true});}
