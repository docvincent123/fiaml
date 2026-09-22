import 'reflect-metadata';
import {test,before,after} from 'node:test';
import assert from 'node:assert/strict';
import {PGlite} from '@electric-sql/pglite';
import {Database} from '../src/db';
import {Care} from '../src/care';
import {permissions,type Actor} from '../src/access';
let db:Database,c:Care,admin:Actor,doctor:Actor,nurse:Actor,close:()=>Promise<void>;
before(async()=>{
 if(process.env.TEST_DATABASE_URL){const control=new Database(process.env.TEST_DATABASE_URL),schema='reliability_'+crypto.randomUUID().replaceAll('-','');await control.query('CREATE SCHEMA '+schema);const u=new URL(process.env.TEST_DATABASE_URL);u.searchParams.set('options','-c search_path='+schema);db=new Database(u.toString());close=async()=>{await db.pool.end();await control.query('DROP SCHEMA '+schema+' CASCADE');await control.pool.end()};}
 else{const pg=new PGlite();const query=async(q:string,v:any[]=[])=>q.includes(';')&&!v.length?(await pg.exec(q)).at(-1):pg.query(q,v);db=new Database('postgres://unused:unused@localhost/unused');await db.pool.end();let queue=Promise.resolve();db.pool={query,connect:async()=>{let release!:()=>void;const prior=queue;queue=new Promise<void>(r=>release=r);await prior;return {query,release}},end:()=>pg.close()} as any;close=()=>pg.close();}
 await db.migrate();c=new Care(db,'test-secret-with-at-least-thirty-two-characters');
 async function actor(role:string):Promise<Actor>{const u=(await db.query('INSERT INTO users(name,login,password_hash,role,specialty) VALUES($1,$1,$2,$3,$4) RETURNING *',[role,'not-a-login-hash',role,'test'])).rows[0];return {...u,permissions:permissions(u),sid:crypto.randomUUID()}}
 admin=await actor('ADMIN');doctor=await actor('DOCTOR');nurse=await actor('NURSE');
});
after(async()=>close?.());
const registration=()=>({name:'V4 '+crypto.randomUUID(),birth_date:'1990-01-01',complaints:'Тест',doctor_id:doctor.id});
test('Registration receipts survive retries, concurrent calls, reordered keys and service restart',async()=>{
 const b={...registration(),request_id:crypto.randomUUID()};const [a,d]=await Promise.all([c.register(admin,b),c.register(admin,b)]);assert.equal(a.id,d.id);
 const restarted=new Care(db,'test-secret-with-at-least-thirty-two-characters');assert.equal((await restarted.register(admin,Object.fromEntries(Object.entries(b).reverse()))).id,a.id);
 assert.equal(Number((await db.query('SELECT count(*) n FROM patients WHERE name=$1',[b.name])).rows[0].n),1);
 await assert.rejects(()=>c.register(admin,{...b,name:'changed'}));await assert.rejects(()=>c.register(nurse,b));
});
test('Failed registration rolls back its receipt and may be corrected with the same operation id',async()=>{
 const b={...registration(),request_id:crypto.randomUUID(),doctor_id:crypto.randomUUID()};await assert.rejects(()=>c.register(admin,b));assert.equal((await db.query('SELECT 1 FROM request_receipts WHERE request_id=$1',[b.request_id])).rows.length,0);
 const p=await c.register(admin,{...b,doctor_id:doctor.id});assert.ok(p.id);
});
test('Clinical retries create one entry and old-admission drafts cannot enter a new admission',async()=>{
 const p=await c.register(admin,registration()),ad=(await c.patient(doctor,p.id)).admissions[0].id;
 const b={request_id:crypto.randomUUID(),expected_admission_id:ad,kind:'ASSESSMENT',body:'Огляд',data:{diagnosis:'Тест',allergies:'Не уточнено',plan:'Тест'}};
 const [a,d]=await Promise.all([c.entry(doctor,p.id,b),c.entry(doctor,p.id,b)]);assert.equal(a.id,d.id);
 await assert.rejects(()=>c.entry(doctor,p.id,{...b,body:'changed'}));await c.discharge(admin,p.id);await c.readmit(admin,p.id,{doctor_id:doctor.id,complaints:'Повторне'});
 await assert.rejects(()=>c.entry(doctor,p.id,{...b,request_id:crypto.randomUUID()}));assert.equal((await c.entry(doctor,p.id,b)).id,a.id);
});
test('Appointment retries return original booking rather than conflict or duplicate',async()=>{
 const p=await c.register(admin,registration()),cab=await c.cabinet(admin,{name:'V4 test',type:'gym'});
 const b={request_id:crypto.randomUUID(),patient_id:p.id,cabinet_id:cab.id,starts_at:'2032-01-01T10:00:00Z',ends_at:'2032-01-01T10:30:00Z'};
 const [a,d]=await Promise.all([c.appointment(admin,b),c.appointment(admin,b)]);assert.equal(a.id,d.id);assert.equal(Number((await db.query('SELECT count(*) n FROM appointments WHERE patient_id=$1',[p.id])).rows[0].n),1);
});
test('24 hour shift fits bounded 30 hour session; feed heartbeat persists and excessive duration rejected',async()=>{
 const u=await c.saveUser(admin,{name:'Shift worker',login:'shift-v4',password:'1',role:'NURSE'});const r=await c.login({login:'shift-v4',password:'1'},'127.0.0.1');const actor=await c.authenticate(r.token);const pending=(await c.shiftRequests(admin)).find(x=>x.user_id===u.id)!;
 await assert.rejects(()=>c.approveShift(admin,pending.id,true,25));await c.approveShift(admin,pending.id,true,24);const me=await c.me(actor);assert.ok(me.onShift);assert.ok(Date.parse(me.shiftEndsAt)-Date.now()>23.9*3600000);assert.ok(Date.parse(me.sessionExpiresAt)-Date.now()>29.9*3600000);
 await c.notificationFeed(actor);assert.ok((await db.query('SELECT alerts_seen_at FROM sessions WHERE id=$1',[actor.sid])).rows[0].alerts_seen_at);
 const data=(await c.users(admin)).find(x=>x.id===u.id)!;await assert.rejects(()=>c.saveUser(admin,{...data,role:'ADMIN'},u.id));
});
