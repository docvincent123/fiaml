// Synthetic data only: renders the production bundle without accessing a clinic server.
const {chromium}=require('playwright');
const {spawn,execFileSync}=require('node:child_process');
const fs=require('node:fs');
const assert=require('node:assert/strict');
const id='00000000-0000-4000-8000-000000000001';
const permissions=['dashboard','patients.read','patients.manage','rooms.read','rooms.manage','archive.read','appointments.read','appointments.manage','cabinets.manage','tasks.read','tasks.create','clinical.write','observations.write','rehab.write','documents.manage','messages.use','users.manage','sessions.manage','audit.read','handover.manage'];
const user={id:'demo-user',name:'Олександр Мельник',role:'ADMIN',permissions,onShift:true};
const now=new Date(),at=new Date(now.getFullYear(),now.getMonth(),now.getDate(),10).toISOString();
const patient={id,patient_number:1042,name:'Коваль Олена Іванівна',phone:'+380 00 000 00 00',birth_date:'1967-04-12',address:'Демонстраційна адреса',status:'ACTIVE',room_number:'201',bed_number:'2',doctor_name:'Мельник Олександр Петрович',admissions:[{id:'admission-demo',admitted_at:at,doctor_since:at,doctor_name:'Мельник Олександр Петрович',room_number:'201',bed_number:'2',care_type:'INPATIENT',complaints:'Демонстраційний запис'}],entries:[],timelineTasks:[],notes:[],documents:[],handovers:[]};
const people=[patient,...['Бондар Василь Миколайович','Шевченко Марія Петрівна','Кравчук Ігор Степанович','Романюк Галина Василівна','Ткачук Андрій Олегович'].map((name,i)=>({...patient,id:`00000000-0000-4000-8000-${String(i+2).padStart(12,'0')}`,name,patient_number:1043+i,room_number:String(202+i),bed_number:'1'}))];
let prescriptionBody;
const server=spawn(process.execPath,['scripts/desktop-preview.mjs'],{stdio:'ignore'});
(async()=>{
 try{
 for(let i=0;i<30;i++){try{if((await fetch('http://127.0.0.1:8787')).ok)break}catch{}await new Promise(r=>setTimeout(r,200))}
 const browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1440,height:1000},locale:'uk-UA',timezoneId:'Europe/Kyiv'});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{sessionStorage.setItem('quremed-token','synthetic-review');localStorage.setItem('quremed.appearance.v1','eco')});
 await page.route('**/api/**',async route=>{const url=new URL(route.request().url()),p=url.pathname.replace('/api','');let data=[];
 if(p==='/auth/me')data=user;
 else if(p==='/dashboard')data={active:34,occupied:28,beds:40,open:18,completed:47};
 else if(p==='/operations')data={version:'3.0.0',metrics:[{label:'Вільних ліжок',value:12,path:'/rooms'},{label:'Надходжень сьогодні',value:3,path:'/patients'},{label:'Прострочених завдань',value:2,path:'/tasks'},{label:'В роботі',value:8,path:'/tasks'}],staff:[{id:'d1',name:'Мельник Олександр',role:'DOCTOR'},{id:'n1',name:'Петренко Наталія',role:'NURSE'},{id:'t1',name:'Кравчук Андрій',role:'THERAPIST'}],appointments:[]};
 else if(p==='/patients')data=people.filter(x=>!url.searchParams.get('q')||x.name.toLowerCase().includes(url.searchParams.get('q').toLowerCase()));
 else if(p.startsWith('/patients/'))data=patient;
 else if(p==='/appointments')data=[{id:'ap1',patient_id:id,patient_name:patient.name,cabinet_name:'Зал реабілітації',staff_name:'Кравчук Андрій',starts_at:at,ends_at:new Date(Date.parse(at)+1800000).toISOString(),status:'BOOKED'}];
 else if(p==='/cabinets')data=[{id:'c1',name:'Зал реабілітації'},{id:'c2',name:'Масаж'},{id:'c3',name:'Фізіотерапія'}];
 else if(p==='/care/alerts')data={unread:0,due:0};
 else if(p==='/care/notification-feed')data={active:true,events:[]};
 else if(p==='/staff')data=[{id:'doctor',name:'Мельник Олександр',role:'DOCTOR'}];
 else if(p==='/tasks'&&route.request().method()==='POST'){prescriptionBody=route.request().postDataJSON();data={id:'saved-demo'}}
 await route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
 });
 fs.mkdirSync('ui-review',{recursive:true});
 async function shot(name){await page.waitForTimeout(250);await page.evaluate(()=>{document.querySelector('#fixture-label')?.remove();const el=document.createElement('div');el.id='fixture-label';el.textContent='Демонстраційні дані · перевірка інтерфейсу';Object.assign(el.style,{position:'fixed',bottom:'5px',right:'8px',zIndex:10000,font:'10px Segoe UI',color:'#6c7c8b',background:'#ffffffdd',padding:'4px 8px',borderRadius:'5px',pointerEvents:'none'});document.body.append(el)});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'Page overflow: '+name);await page.screenshot({path:'ui-review/'+name+'.png',fullPage:true})}
 await page.goto('http://127.0.0.1:8787/');await page.getByRole('heading',{name:'Робочий стіл'}).waitFor();await shot('desktop-dashboard');
 await page.setViewportSize({width:1366,height:768});await shot('laptop-dashboard');
 await page.getByRole('textbox',{name:'Знайти пацієнта у центрі'}).fill('Коваль');await page.getByRole('button',{name:'Знайти пацієнта',exact:true}).click();await page.getByRole('heading',{name:'Пацієнти центру'}).waitFor();assert.equal(await page.getByRole('textbox',{name:'Пошук пацієнта',exact:true}).inputValue(),'Коваль');
 await page.getByRole('textbox',{name:'Пошук пацієнта',exact:true}).fill('');await page.waitForTimeout(200);await shot('desktop-patients');
 await page.getByRole('button',{name:/Коваль Олена/}).click();await page.getByRole('button',{name:'Призначити лікування',exact:true}).waitFor();await shot('desktop-patient-card');
 await page.getByRole('button',{name:'Призначити лікування',exact:true}).click();const prescription=page.locator('dialog').filter({has:page.getByRole('heading',{name:'Нове призначення'})});await prescription.waitFor();assert.ok(await prescription.getByText(patient.name,{exact:true}).isVisible());await prescription.getByLabel('Назва процедури',{exact:true}).fill('Демонстраційна процедура');await prescription.getByRole('button',{name:'Підписати призначення'}).click();await page.waitForTimeout(300);assert.equal(prescriptionBody?.patient_id,id);await page.keyboard.press('Escape');
 await page.goto('http://127.0.0.1:8787/cabinets');await page.getByLabel('День процедур',{exact:true}).waitFor();await shot('desktop-schedule');await page.getByRole('button',{name:patient.name,exact:true}).click();await page.getByRole('heading',{name:patient.name,exact:true}).waitFor();await page.keyboard.press('Escape');
 await page.setViewportSize({width:390,height:844});await page.goto('http://127.0.0.1:8787/');await page.getByRole('heading',{name:'Робочий стіл'}).waitFor();await shot('responsive-dashboard');await page.getByRole('button',{name:'Меню',exact:true}).click();await shot('responsive-menu');
 assert.deepEqual(errors,[],'Browser errors');await browser.close();console.log('UI_REVIEW_OK: 1440 desktop, 1366 laptop, 390 mobile; search, patient card, prescription identity, schedule navigation.');
 if(process.env.GH_TOKEN){for(const file of fs.readdirSync('ui-review').filter(f=>f.endsWith('.png'))){const payload=JSON.stringify({encoding:'base64',content:fs.readFileSync('ui-review/'+file).toString('base64')});const result=JSON.parse(execFileSync('gh',['api','--method','POST',`repos/${process.env.GITHUB_REPOSITORY}/git/blobs`,'--input','-'],{input:payload,encoding:'utf8'}));console.log('UI_IMAGE '+JSON.stringify({file,sha:result.sha}));}}
 }finally{server.kill()}
})().catch(e=>{console.error(e);process.exit(1)});
